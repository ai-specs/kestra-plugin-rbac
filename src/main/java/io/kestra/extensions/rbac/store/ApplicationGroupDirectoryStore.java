package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.Group;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.Snapshot;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;

public final class ApplicationGroupDirectoryStore {
    public static final class Conflict extends IllegalStateException {}
    public enum DeletionFailure { PROTECTED_GROUP, GROUP_NOT_EMPTY }
    /** Valid deletion request blocked by current business state; distinct from revision CAS. */
    public static final class DeletionRejected extends IllegalStateException {
        private final DeletionFailure failure;
        public DeletionRejected(DeletionFailure failure) { super(Objects.requireNonNull(failure).name()); this.failure=failure; }
        public DeletionFailure failure() { return failure; }
    }
    public static class Unavailable extends IllegalStateException {
        public Unavailable(SQLException cause) { super("Application group storage unavailable",cause); }
        Unavailable(String message,SQLException cause) { super(message,cause); }
    }
    public static final class NotInitialized extends Unavailable {
        public NotInitialized(SQLException cause) { super("Application group directory is not initialized; run ApplicationGroupDirectoryCommand preview/apply against this database before enabling kestra.rbac.application-groups",cause); }
    }

    public static RuntimeException reclassified(SQLException failure) {
        var state=failure.getSQLState();
        return "42P01".equals(state)||"42S04".equals(state)?new NotInitialized(failure):new Unavailable(failure);
    }

    private final DataSource source;
    private final ConnectionGuard connectionGuard;
    private final ObjectMapper mapper=new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ApplicationGroupDirectoryStore(DataSource source) { this(source,connection->{}); }

    public ApplicationGroupDirectoryStore(DataSource source,ConnectionGuard connectionGuard) {
        this.source=Objects.requireNonNull(source);this.connectionGuard=Objects.requireNonNull(connectionGuard);
    }

    public void initializeSchema() {
        transaction(connection->{initializeSchema(connection);return null;});
    }

    public void initializeSchema(Connection connection)throws SQLException {
            requireTransaction(connection);
            if("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                try(var statement=prepare(connection,"SELECT pg_advisory_xact_lock(1380073796)")){statement.execute();}
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_state (group_id VARCHAR(128) PRIMARY KEY, revision BIGINT NOT NULL CHECK(revision>0), authorization_version BIGINT NOT NULL CHECK(authorization_version>0), document_json TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_bootstrap (singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), builtin_group_id VARCHAR(128) REFERENCES rbac_application_group_state(group_id), catalog_revision BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_invalidation (group_id VARCHAR(128) PRIMARY KEY, authorization_version BIGINT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS rbac_application_group_retired (group_id VARCHAR(128) PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO rbac_application_group_bootstrap(singleton_id,builtin_group_id,catalog_revision) VALUES(1,NULL,0) ON CONFLICT DO NOTHING");
            }
            ManagementAuditStore.initializeSchema(connection);
    }

    public Snapshot initializeBuiltin(Snapshot initial) {
        return transaction(connection->initializeBuiltin(connection,initial));
    }

    public Snapshot initializeBuiltin(Connection connection,Snapshot initial)throws SQLException {
            lock(connection);
            if(builtin(connection)!=null || !readAll(connection).isEmpty())throw new Conflict();
            requireInitial(initial);
            if(!initial.group().active())throw new IllegalArgumentException("Builtin application group must be active at installation");
            insert(connection,initial);
            try(var statement=prepare(connection,"UPDATE rbac_application_group_bootstrap SET builtin_group_id=? WHERE singleton_id=1 AND builtin_group_id IS NULL")) {
                statement.setString(1,initial.group().id());
                if(statement.executeUpdate()!=1)throw new Conflict();
            }
            advanceCatalog(connection);
            return initial;
    }

    public Snapshot create(Snapshot initial) {
        return transaction(connection->create(connection,initial));
    }

    public Snapshot create(Connection connection,Snapshot initial)throws SQLException {
            lock(connection);
            if(builtin(connection)==null)throw new IllegalStateException("Application group installation required");
            requireInitial(initial);
            insert(connection,initial);
            advanceCatalog(connection);
            return initial;
    }

    public List<Snapshot> readAll() { return transaction(this::readAll); }

    public String builtinGroupId() { return transaction(ApplicationGroupDirectoryStore::builtin); }

    public String builtinGroupId(Connection connection)throws SQLException { requireTransaction(connection);return builtin(connection); }

    public long catalogRevision(Connection connection)throws SQLException {
        requireTransaction(connection);
        try(var statement=prepare(connection,"SELECT catalog_revision FROM rbac_application_group_bootstrap WHERE singleton_id=1");var result=statement.executeQuery()) {
            if(!result.next() || result.getLong(1)<0)throw new IllegalStateException("Invalid group catalog revision");
            return result.getLong(1);
        }
    }

    public boolean schemaPresent(Connection connection)throws SQLException {
        requireTransaction(connection);var names=new java.util.HashSet<String>();
        try(var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next())names.add(tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        var required=java.util.Set.of("rbac_application_group_state","rbac_application_group_bootstrap","rbac_application_group_invalidation","rbac_application_group_retired");
        long count=required.stream().filter(names::contains).count();
        if(count!=0 && count!=required.size())throw new IllegalStateException("Incomplete group schema");
        return count==required.size();
    }

    public Snapshot update(String groupId,long expectedRevision,UnaryOperator<Snapshot> change) {
        return transaction(connection->update(connection,groupId,expectedRevision,change));
    }

    public Snapshot update(Connection connection,String groupId,long expectedRevision,UnaryOperator<Snapshot> change)throws SQLException {
        Objects.requireNonNull(change);
            lock(connection);
            var current=find(connection,groupId);
            if(current.group().revision()!=expectedRevision)throw new Conflict();
            var proposed=Objects.requireNonNull(change.apply(current));
            if(!proposed.group().id().equals(groupId) || proposed.group().revision()!=current.group().revision()
                || proposed.group().authorizationVersion()!=current.group().authorizationVersion())
                throw new IllegalArgumentException("Directory controls identity and versions");
            var group=proposed.group();
            var next=new Snapshot(new Group(group.id(),group.displayName(),group.description(),group.active(),
                Math.addExact(expectedRevision,1),authorizationChanged(current,proposed)?Math.addExact(group.authorizationVersion(),1):group.authorizationVersion()),
                proposed.scopes(),proposed.applications(),proposed.userGrants());
            requireUniqueClients(connection,next);
            try(var statement=prepare(connection,"UPDATE rbac_application_group_state SET revision=?,authorization_version=?,document_json=? WHERE group_id=? AND revision=?")) {
                statement.setLong(1,next.group().revision());statement.setLong(2,next.group().authorizationVersion());
                statement.setString(3,encode(next));statement.setString(4,groupId);statement.setLong(5,expectedRevision);
                if(statement.executeUpdate()!=1)throw new Conflict();
            }
            invalidate(connection,next);
            advanceCatalog(connection);
            return next;
    }

    private static boolean authorizationChanged(Snapshot previous,Snapshot next) {
        if(previous.group().active()!=next.group().active())return true;
        var previousClients=previous.applications().stream().map(value->value.clientId()).collect(java.util.stream.Collectors.toSet());
        var nextClients=next.applications().stream().map(value->value.clientId()).collect(java.util.stream.Collectors.toSet());
        if(!previousClients.equals(nextClients))return true;
        var before=new java.util.TreeMap<String,java.util.Set<String>>();
        var after=new java.util.TreeMap<String,java.util.Set<String>>();
        previous.applications().stream().filter(value->!value.supportedScopeKeys().isEmpty()).forEach(value->before.put(value.clientId(),value.supportedScopeKeys()));
        next.applications().stream().filter(value->!value.supportedScopeKeys().isEmpty()).forEach(value->after.put(value.clientId(),value.supportedScopeKeys()));
        if(!before.equals(after))return true;
        var oldAudiences=new java.util.TreeMap<String,java.util.Set<String>>();
        var newAudiences=new java.util.TreeMap<String,java.util.Set<String>>();
        previous.applications().stream().filter(value->!value.supportedScopeKeys().isEmpty()).forEach(value->oldAudiences.put(value.clientId(),value.audiences()));
        next.applications().stream().filter(value->!value.supportedScopeKeys().isEmpty()).forEach(value->newAudiences.put(value.clientId(),value.audiences()));
        if(!oldAudiences.equals(newAudiences))return true;
        var oldGrants=new java.util.HashMap<java.util.List<String>,java.util.Set<String>>();
        var newGrants=new java.util.HashMap<java.util.List<String>,java.util.Set<String>>();
        previous.userGrants().stream().filter(value->!value.scopeKeys().isEmpty()).forEach(value->oldGrants.put(java.util.List.of(value.issuer(),value.subject()),value.scopeKeys()));
        next.userGrants().stream().filter(value->!value.scopeKeys().isEmpty()).forEach(value->newGrants.put(java.util.List.of(value.issuer(),value.subject()),value.scopeKeys()));
        return !oldGrants.equals(newGrants);
    }

    public int removeUserGrants(Connection connection,String issuer,String subject)throws SQLException {
        Objects.requireNonNull(issuer);Objects.requireNonNull(subject);
        if(issuer.isBlank() || subject.isBlank())throw new IllegalArgumentException("Account identity required");
        lock(connection);
        int affected=0;
        for(var snapshot:readAll(connection)) {
            var remaining=snapshot.userGrants().stream()
                .filter(grant->!issuer.equals(grant.issuer()) || !subject.equals(grant.subject())).toList();
            if(remaining.size()==snapshot.userGrants().size())continue;
            update(connection,snapshot.group().id(),snapshot.group().revision(),current->
                new Snapshot(current.group(),current.scopes(),current.applications(),remaining));
            affected++;
        }
        return affected;
    }

    public void delete(String groupId,long expectedRevision) {
        transaction(connection->{delete(connection,groupId,expectedRevision);return null;});
    }

    public void delete(Connection connection,String groupId,long expectedRevision)throws SQLException {
            lock(connection);
            var current=find(connection,groupId);
            if(current.group().revision()!=expectedRevision)throw new Conflict();
            if(groupId.equals(builtin(connection)))throw new DeletionRejected(DeletionFailure.PROTECTED_GROUP);
            if(!current.scopes().isEmpty() || !current.applications().isEmpty() || !current.userGrants().isEmpty())
                throw new DeletionRejected(DeletionFailure.GROUP_NOT_EMPTY);
            try(var statement=prepare(connection,"INSERT INTO rbac_application_group_retired(group_id) VALUES(?)")) {
                statement.setString(1,groupId);statement.executeUpdate();
            }
            var group=current.group();
            invalidate(connection,new Snapshot(new Group(group.id(),group.displayName(),false,group.revision(),
                Math.addExact(group.authorizationVersion(),1)),List.of(),List.of(),List.of()));
            try(var statement=prepare(connection,"DELETE FROM rbac_application_group_state WHERE group_id=? AND revision=?")) {
                statement.setString(1,groupId);statement.setLong(2,expectedRevision);
                if(statement.executeUpdate()!=1)throw new Conflict();
            }
            advanceCatalog(connection);
    }

    private void insert(Connection connection,Snapshot initial)throws SQLException {
        try(var statement=prepare(connection,"SELECT group_id FROM rbac_application_group_retired WHERE group_id=?")) {
            statement.setString(1,initial.group().id());
            try(var result=statement.executeQuery()){if(result.next())throw new Conflict();}
        }
        if(readAll(connection).stream().anyMatch(value->value.group().id().equals(initial.group().id())))throw new Conflict();
        requireUniqueClients(connection,initial);
        try(var statement=prepare(connection,"INSERT INTO rbac_application_group_state(group_id,revision,authorization_version,document_json) VALUES(?,?,?,?)")) {
            statement.setString(1,initial.group().id());statement.setLong(2,initial.group().revision());
            statement.setLong(3,initial.group().authorizationVersion());statement.setString(4,encode(initial));statement.executeUpdate();
        }
        try(var statement=prepare(connection,"INSERT INTO rbac_application_group_invalidation(group_id,authorization_version) VALUES(?,?)")) {
            statement.setString(1,initial.group().id());statement.setLong(2,initial.group().authorizationVersion());statement.executeUpdate();
        }
    }

    private void requireUniqueClients(Connection connection,Snapshot proposed)throws SQLException {
        var clients=proposed.applications().stream().map(value->value.clientId()).toList();
        for(var existing:readAll(connection))if(!existing.group().id().equals(proposed.group().id())
            && existing.applications().stream().anyMatch(value->clients.contains(value.clientId())))
            throw new IllegalArgumentException("Application already belongs to another group");
    }

    private static void requireInitial(Snapshot initial) {
        if(initial.group().revision()!=1 || initial.group().authorizationVersion()!=1)
            throw new IllegalArgumentException("Initial group versions must equal one");
    }

    private Snapshot find(Connection connection,String groupId)throws SQLException {
        return readAll(connection).stream().filter(value->value.group().id().equals(groupId)).findFirst()
            .orElseThrow(Conflict::new);
    }

    public List<Snapshot> readAll(Connection connection)throws SQLException {
        requireTransaction(connection);
        var snapshots=new ArrayList<Snapshot>();
        try(var statement=prepare(connection,"SELECT state.group_id,state.revision,state.authorization_version,state.document_json,invalidation.authorization_version FROM rbac_application_group_state state LEFT JOIN rbac_application_group_invalidation invalidation ON state.group_id=invalidation.group_id ORDER BY state.group_id");var result=statement.executeQuery()) {
            while(result.next()) {
                Snapshot snapshot;
                try{snapshot=mapper.readValue(result.getString(4),Snapshot.class);}
                catch(java.io.IOException failure){throw new IllegalStateException("Invalid stored application group",failure);}
                if(!snapshot.group().id().equals(result.getString(1)) || snapshot.group().revision()!=result.getLong(2)
                    || snapshot.group().authorizationVersion()!=result.getLong(3)
                    || snapshot.group().authorizationVersion()!=result.getLong(5) || result.wasNull())
                    throw new IllegalStateException("Inconsistent application group versions");
                snapshots.add(snapshot);
            }
        }
        return List.copyOf(snapshots);
    }

    private String encode(Snapshot snapshot) {
        try{return mapper.writeValueAsString(snapshot);}
        catch(java.io.IOException failure){throw new IllegalArgumentException("Invalid application group document",failure);}
    }

    private static String builtin(Connection connection)throws SQLException {
        try(var statement=prepare(connection,"SELECT builtin_group_id FROM rbac_application_group_bootstrap WHERE singleton_id=1");var result=statement.executeQuery()) {
            if(!result.next())throw new IllegalStateException("Missing application group catalog");
            return result.getString(1);
        }
    }

    private static void lock(Connection connection)throws SQLException {
        requireTransaction(connection);
        try(var statement=prepare(connection,"SELECT catalog_revision FROM rbac_application_group_bootstrap WHERE singleton_id=1 FOR UPDATE");var result=statement.executeQuery()) {
            if(!result.next() || result.getLong(1)<0)throw new IllegalStateException("Invalid application group catalog");
        }
    }

    private static void advanceCatalog(Connection connection)throws SQLException {
        try(var statement=prepare(connection,"UPDATE rbac_application_group_bootstrap SET catalog_revision=catalog_revision+1 WHERE singleton_id=1 AND catalog_revision<9223372036854775807")) {
            if(statement.executeUpdate()!=1)throw new IllegalStateException("Application group catalog version exhausted");
        }
    }

    private static void invalidate(Connection connection,Snapshot snapshot)throws SQLException {
        try(var statement=prepare(connection,"UPDATE rbac_application_group_invalidation SET authorization_version=? WHERE group_id=?")) {
            statement.setLong(1,snapshot.group().authorizationVersion());statement.setString(2,snapshot.group().id());
            if(statement.executeUpdate()!=1)throw new IllegalStateException("Missing group invalidation state");
        }
    }

    private static PreparedStatement prepare(Connection connection,String sql)throws SQLException {
        var statement=connection.prepareStatement(sql);statement.setQueryTimeout(5);return statement;
    }

    public interface Work<T> { T run(Connection connection)throws SQLException; }

    public interface ConnectionGuard { void lock(Connection connection)throws SQLException; }

    private static void requireTransaction(Connection connection)throws SQLException {
        if(connection.getAutoCommit() || connection.isReadOnly()
            || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException("Writable READ_COMMITTED transaction required");
    }

    public <T>T transaction(Work<T> work) {
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try{connectionGuard.lock(connection);var result=work.run(connection);connection.commit();return result;}
            catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }catch(SQLException failure){throw reclassified(failure);}
    }
}
