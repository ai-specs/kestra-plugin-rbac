package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

/** Schema 2 has one runtime source. Schema 1 is read only inside an explicitly approved migration. */
public final class ApplicationGroupPolicyStore {
    private final DataSource source;
    private final ApplicationGroupPolicyParser parser;
    private final ApplicationGroupDirectoryStore.ConnectionGuard connectionGuard;
    public record Snapshot(long revision, long previousRevision, String actor, Instant createdAt,
                           ApplicationGroupPolicy policy, String canonicalJson) {}
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("Policy revision changed or initialization conflicts"); }
    }
    public static final class Unavailable extends RuntimeException {
        public Unavailable(Throwable cause) { super("Project policy storage unavailable", cause); }
    }
    public ApplicationGroupPolicyStore(DataSource source, ApplicationGroupPolicyParser parser) {
        this(source,parser,connection->{});
    }
    public ApplicationGroupPolicyStore(DataSource source,ApplicationGroupPolicyParser parser,ApplicationGroupDirectoryStore.ConnectionGuard connectionGuard) {
        this.source=io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(Objects.requireNonNull(source));
        this.parser=Objects.requireNonNull(parser);
        this.connectionGuard=Objects.requireNonNull(connectionGuard);
    }
    public void initializeSchema() {
        transaction(connection -> {
            initializeSchema(connection);
            return null;
        });
    }
    private void initializeSchema(Connection connection) throws SQLException {
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                try (var statement=prepare(connection,"SELECT pg_advisory_xact_lock(1380073796)")) { statement.execute(); }
            try (var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_policy_head (policy_id INTEGER PRIMARY KEY CHECK (policy_id = 1), schema_version INTEGER NOT NULL CHECK (schema_version = 2), revision BIGINT NOT NULL, source_revision BIGINT, approval_hash VARCHAR(64))");
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_policy_snapshot (revision BIGINT PRIMARY KEY, previous_revision BIGINT NOT NULL, actor_subject VARCHAR(1024) NOT NULL, created_at TIMESTAMP NOT NULL, document_json TEXT NOT NULL)");
            }
            try (var statement=prepare(connection,"INSERT INTO rbac_application_group_policy_head(policy_id,schema_version,revision) VALUES(1,2,0) ON CONFLICT DO NOTHING")) { statement.executeUpdate(); }
    }
    public Snapshot readCurrent() {
        return transaction(this::readCurrent);
    }
    public Snapshot readCurrent(Connection connection)throws SQLException {
            long revision=head(connection,false);
            if (revision<=0) throw new IllegalStateException("Schema 2 policy is not initialized");
            return load(connection,revision);
    }
    public Snapshot initialize(byte[] json,String actor) {
        ApplicationGroupPolicy policy=parser.parse(json);
        return transaction(connection -> {
            lockLegacyInitialization(connection);
            long current=head(connection,true);
            String canonical=parser.canonicalJson(policy);
            if (current>0) {
                var existing=load(connection,current);
                if (!existing.canonicalJson().equals(canonical)) throw new Conflict();
                return existing;
            }
            if (legacyRevision(connection,true)>0) throw new IllegalStateException("Legacy policy requires an approved migration");
            return insert(connection,current,policy,actor,null,null);
        });
    }
    public Snapshot replace(long expected,byte[] json,String actor) {
        return replace(expected,json,actor,(connection,previous,candidate)->{});
    }
    public interface Validation {
        void validate(Connection connection,Snapshot previous,ApplicationGroupPolicy candidate)throws SQLException;
    }
    public Snapshot replace(long expected,byte[] json,String actor,Validation validation) {
        if (expected<=0) throw new IllegalArgumentException("Positive revision required");
        ApplicationGroupPolicy policy=parser.parse(json);
        return transaction(connection -> {
            long current=head(connection,true);
            if (current!=expected) throw new Conflict();
            validation.validate(connection,load(connection,current),policy);
            return insert(connection,current,policy,actor,null,null);
        });
    }
    public Snapshot migrateApproved(ProjectMigrationReview.Review review,String approvedHash,byte[] target,String actor) {
        ApplicationGroupPolicy policy=parser.parse(target);
        return transaction(connection -> {
            lockLegacyInitialization(connection);
            long old=legacyRevision(connection,true);
            if (old<=0) throw new IllegalStateException("No initialized legacy policy");
            byte[] oldJson;
            try (var statement=prepare(connection,"SELECT document_json FROM rbac_policy_snapshot WHERE revision=? AND schema_version=1")) {
                statement.setLong(1,old);
                try (var result=statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("Missing legacy snapshot");
                    oldJson=result.getString(1).getBytes(StandardCharsets.UTF_8);
                }
            }
            new ProjectMigrationReview().verifyApproval(review,approvedHash,old,oldJson);
            // The approved target is part of the review, rather than an independently replaceable argument.
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            try {
                var approved=mapper.readTree(review.reportJson()).get("targetPolicy");
                if (!mapper.readTree(parser.canonicalJson(policy)).equals(approved)) throw new Conflict();
            } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid approved review",failure); }
            if (!review.operator().equals(actor)) throw new IllegalArgumentException("Migration operator changed");
            initializeSchema(connection);
            long current=head(connection,true);
            if (current!=0) throw new Conflict();
            return insert(connection,current,policy,actor,old,approvedHash);
        });
    }
    private Snapshot insert(Connection connection,long current,ApplicationGroupPolicy policy,String actor,Long old,String approval) throws SQLException {
        if (actor==null || actor.isBlank() || actor.length()>1024) throw new IllegalArgumentException("Operator required");
        long next=Math.addExact(current,1); Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String canonical=parser.canonicalJson(policy);
        try (var statement=prepare(connection,"INSERT INTO rbac_application_group_policy_snapshot(revision,previous_revision,actor_subject,created_at,document_json) VALUES(?,?,?,?,?)")) {
            statement.setLong(1,next);statement.setLong(2,current);statement.setString(3,actor);
            statement.setTimestamp(4,Timestamp.from(at));statement.setString(5,canonical);statement.executeUpdate();
        }
        try (var statement=prepare(connection,"UPDATE rbac_application_group_policy_head SET revision=?,source_revision=COALESCE(?,source_revision),approval_hash=COALESCE(?,approval_hash) WHERE policy_id=1 AND revision=? AND schema_version=2")) {
            statement.setLong(1,next); if(old==null)statement.setNull(2,java.sql.Types.BIGINT);else statement.setLong(2,old);
            statement.setString(3,approval);statement.setLong(4,current);
            if(statement.executeUpdate()!=1)throw new Conflict();
        }
        return new Snapshot(next,current,actor,at,policy,canonical);
    }
    private Snapshot load(Connection connection,long revision) throws SQLException {
        try(var statement=prepare(connection,"SELECT previous_revision,actor_subject,created_at,document_json FROM rbac_application_group_policy_snapshot WHERE revision=?")) {
            statement.setLong(1,revision);
            try(var result=statement.executeQuery()) {
                if(!result.next())throw new IllegalStateException("Missing schema 2 snapshot");
                String json=result.getString(4);ApplicationGroupPolicy policy=parser.parse(json.getBytes(StandardCharsets.UTF_8));
                if(!parser.canonicalJson(policy).equals(json))throw new IllegalStateException("Corrupt project policy snapshot");
                return new Snapshot(revision,result.getLong(1),result.getString(2),result.getTimestamp(3).toInstant(),policy,json);
            }
        }
    }
    private long head(Connection connection,boolean lock)throws SQLException {
        try(var statement=prepare(connection,"SELECT schema_version,revision FROM rbac_application_group_policy_head WHERE policy_id=1"+(lock?" FOR UPDATE":""));var result=statement.executeQuery()) {
            if(!result.next()||result.getInt(1)!=2||result.getLong(2)<0)throw new IllegalStateException("Incompatible schema 2 head");
            return result.getLong(2);
        }
    }
    private static void lockLegacyInitialization(Connection connection)throws SQLException {
        if("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
            try(var statement=prepare(connection,"SELECT pg_advisory_xact_lock(1380073795)")){statement.execute();}
    }
    private long legacyRevision(Connection connection,boolean lock)throws SQLException {
        boolean exists=false;
        try(var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next())if("rbac_policy_head".equalsIgnoreCase(tables.getString("TABLE_NAME")))exists=true;
        }
        if(!exists)return 0;
        try(var statement=prepare(connection,"SELECT schema_version,revision FROM rbac_policy_head WHERE policy_id=1"+(lock?" FOR UPDATE":""));var result=statement.executeQuery()) {
            if(!result.next()||result.getInt(1)!=1)throw new IllegalStateException("Invalid legacy policy head");return result.getLong(2);
        }
    }
    private static PreparedStatement prepare(Connection connection,String sql)throws SQLException {
        var statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);return statement;
    }
    private interface Work<T>{T run(Connection connection)throws SQLException;}
    private <T>T transaction(Work<T> work) {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try{connectionGuard.lock(connection);T result=work.run(connection);connection.commit();return result;}
            catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }catch(SQLException failure){throw new Unavailable(failure);}
    }
}
