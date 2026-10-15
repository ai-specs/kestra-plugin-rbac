package io.kestra.extensions.rbac.store;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import io.kestra.extensions.rbac.PolicyEngine;

public final class JdbcPolicyStore {
    public static final int HISTORY_LIMIT = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private final DataSource dataSource;
    private final PolicyDocumentValidator validator;
    private final Map<Long, Snapshot> cache = new LinkedHashMap<>();

    public record Snapshot(long revision, long previousRevision, String actorSubject, Instant createdAt,
                           PolicyDocument document, String canonicalJson, PolicyEngine engine) {}

    public static final class RevisionConflictException extends RuntimeException {
        public RevisionConflictException() { super("策略版本不匹配或已用不同策略初始化。"); }
    }

    public static final class StorageException extends RuntimeException {
        StorageException(Throwable cause) { super("策略存储不可用。", cause); }
    }

    public JdbcPolicyStore(DataSource dataSource, PolicyDocumentValidator validator) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.validator = Objects.requireNonNull(validator);
    }

    public void initializeSchema() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                    try (var lock = prepare(connection, "SELECT pg_advisory_xact_lock(1380073795)")) { lock.execute(); }
                }
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.execute("CREATE TABLE IF NOT EXISTS rbac_policy_head (policy_id INTEGER PRIMARY KEY CHECK (policy_id = 1), schema_version INTEGER NOT NULL, revision BIGINT NOT NULL)");
                    statement.execute("CREATE TABLE IF NOT EXISTS rbac_policy_snapshot (revision BIGINT PRIMARY KEY, previous_revision BIGINT NOT NULL, schema_version INTEGER NOT NULL, actor_subject VARCHAR(1024) NOT NULL, created_at TIMESTAMP NOT NULL, document_json TEXT NOT NULL)");
                }
                try (var statement = prepare(connection, "INSERT INTO rbac_policy_head (policy_id, schema_version, revision) VALUES (1, 1, 0) ON CONFLICT DO NOTHING")) {
                    statement.executeUpdate();
                }
                lockHead(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) { throw new StorageException(failure); }
    }

    public Snapshot importInitial(byte[] json, String actorSubject) {
        return write(null, json, actorSubject);
    }

    public Snapshot replace(long expectedRevision, byte[] json, String actorSubject) {
        PolicyDocumentValidator.require(expectedRevision > 0, "必须提供正数 expectedRevision。");
        return write(expectedRevision, json, actorSubject);
    }

    private Snapshot write(Long expectedRevision, byte[] json, String actorSubject) {
        PolicyDocumentValidator.concrete(actorSubject);
        PolicyDocumentValidator.require(actorSubject.length() <= 1024, "操作者 subject 超过限额。");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                long current = lockHead(connection);
                var candidate = validator.parse(json);
                if (expectedRevision == null && current != 0) {
                    Snapshot existing = load(connection, current);
                    if (!existing.canonicalJson().equals(candidate.canonicalJson())) throw new RevisionConflictException();
                    connection.commit();
                    return existing;
                }
                if (expectedRevision != null && current != expectedRevision) throw new RevisionConflictException();
                long revision = Math.addExact(current, 1);
                Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                try (var statement = prepare(connection, "INSERT INTO rbac_policy_snapshot (revision, previous_revision, schema_version, actor_subject, created_at, document_json) VALUES (?, ?, 1, ?, ?, ?)")) {
                    statement.setLong(1, revision);
                    statement.setLong(2, current);
                    statement.setString(3, actorSubject);
                    statement.setTimestamp(4, Timestamp.from(createdAt));
                    statement.setString(5, candidate.canonicalJson());
                    statement.executeUpdate();
                }
                try (var statement = prepare(connection, "UPDATE rbac_policy_head SET revision = ? WHERE policy_id = 1 AND revision = ? AND schema_version = 1")) {
                    statement.setLong(1, revision);
                    statement.setLong(2, current);
                    if (statement.executeUpdate() != 1) throw new RevisionConflictException();
                }
                try (var statement = prepare(connection, "DELETE FROM rbac_policy_snapshot WHERE revision < ?")) {
                    statement.setLong(1, revision - HISTORY_LIMIT);
                    statement.executeUpdate();
                }
                var snapshot = new Snapshot(revision, current, actorSubject, createdAt, candidate.document(), candidate.canonicalJson(), candidate.engine());
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) { throw new StorageException(failure); }
    }

    public Snapshot readCurrent() {
        try (var connection = dataSource.getConnection()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                long revision = currentRevision(connection);
                synchronized (cache) {
                    Snapshot snapshot = cache.get(revision);
                    if (snapshot == null) {
                        try {
                            snapshot = load(connection, revision);
                        } catch (IllegalStateException missingSnapshot) {
                            if (attempt == 0) continue;
                            throw missingSnapshot;
                        }
                        cache.put(revision, snapshot);
                        while (cache.size() > HISTORY_LIMIT + 1) cache.remove(cache.keySet().iterator().next());
                    }
                    return snapshot;
                }
            }
            throw new IllegalStateException("策略头在读取期间持续变化。");
        } catch (SQLException failure) { throw new StorageException(failure); }
    }

    private long currentRevision(Connection connection) throws SQLException {
        try (var statement = prepare(connection, "SELECT schema_version, revision FROM rbac_policy_head WHERE policy_id = 1");
             var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1 || result.getLong(2) <= 0) throw new IllegalStateException("策略未初始化或 schema 不兼容。");
            return result.getLong(2);
        }
    }

    public List<Snapshot> history() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                var revisions = new ArrayList<Long>();
                try (var statement = prepare(connection, "SELECT revision FROM rbac_policy_snapshot ORDER BY revision DESC");
                     var result = statement.executeQuery()) {
                    while (result.next()) revisions.add(result.getLong(1));
                }
                var snapshots = new ArrayList<Snapshot>();
                for (long revision : revisions) snapshots.add(load(connection, revision));
                connection.commit();
                return List.copyOf(snapshots);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) { throw new StorageException(failure); }
    }

    private long lockHead(Connection connection) throws SQLException {
        try (var statement = prepare(connection, "SELECT schema_version, revision FROM rbac_policy_head WHERE policy_id = 1 FOR UPDATE");
             var result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1 || result.getLong(2) < 0) throw new IllegalStateException("策略头缺失或 schema 不兼容。");
            return result.getLong(2);
        }
    }

    private Snapshot load(Connection connection, long revision) throws SQLException {
        try (var statement = prepare(connection, "SELECT previous_revision, schema_version, actor_subject, created_at, document_json FROM rbac_policy_snapshot WHERE revision = ?")) {
            statement.setLong(1, revision);
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(2) != 1) throw new IllegalStateException("策略快照缺失或 schema 不兼容。");
                String json = result.getString(5);
                var policy = validator.parse(json.getBytes(StandardCharsets.UTF_8));
                if (!policy.canonicalJson().equals(json)) throw new IllegalStateException("策略快照不是规范 JSON。");
                return new Snapshot(revision, result.getLong(1), result.getString(3), result.getTimestamp(4).toInstant(),
                    policy.document(), policy.canonicalJson(), policy.engine());
            }
        }
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    private void rollback(Connection connection, Throwable original) {
        try { connection.rollback(); }
        catch (SQLException failure) { original.addSuppressed(failure); }
    }
}
