package io.kestra.extensions.rbac.store;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ManagementAuditStore {
    public enum Operation { ACCOUNT_DELETE,ACCOUNT_ACTIVATE,ACCOUNT_DEACTIVATE,PASSWORD_RESET,CLIENT_SECRET_ROTATE }
    public record Event(String id,Instant time,String issuer,String actorSubject,Operation operation,
        String targetSubject,String clientId,String groupId) {}
    public record Page(List<Event> items,long total,int page,int size) {
        public Page {items=List.copyOf(items);}
    }

    public static void initializeSchema(Connection connection)throws SQLException {
        transaction(connection);
        try(var statement=connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("CREATE TABLE IF NOT EXISTS rbac_management_audit (event_id VARCHAR(36) PRIMARY KEY, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, issuer VARCHAR(2048) NOT NULL, actor_subject VARCHAR(1024) NOT NULL, operation VARCHAR(32) NOT NULL, target_subject VARCHAR(1024), client_id VARCHAR(2048), group_id VARCHAR(128) NOT NULL, CHECK ((operation='CLIENT_SECRET_ROTATE' AND target_subject IS NULL AND client_id IS NOT NULL) OR (operation<>'CLIENT_SECRET_ROTATE' AND target_subject IS NOT NULL AND client_id IS NULL)))");
            statement.execute("CREATE INDEX IF NOT EXISTS rbac_management_audit_scope ON rbac_management_audit (issuer,group_id,occurred_at,event_id)");
        }
    }

    public void append(Connection connection,String issuer,String actor,Operation operation,String target,String client,Set<String> groups)throws SQLException {
        transaction(connection);
        if(issuer==null || issuer.isBlank() || actor==null || actor.isBlank() || operation==null || groups==null || groups.isEmpty())throw new IllegalArgumentException("Audit identity required");
        if(operation==Operation.CLIENT_SECRET_ROTATE?(client==null || client.isBlank() || target!=null):(target==null || target.isBlank() || client!=null))throw new IllegalArgumentException("Audit target required");
        for(String group:groups.stream().sorted().toList()) {
            if(group==null || !group.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new IllegalArgumentException("Audit group required");
            try(var statement=connection.prepareStatement("INSERT INTO rbac_management_audit(event_id,issuer,actor_subject,operation,target_subject,client_id,group_id) VALUES(?,?,?,?,?,?,?)")) {
                statement.setQueryTimeout(5);statement.setString(1,UUID.randomUUID().toString());statement.setString(2,issuer);
                statement.setString(3,actor);statement.setString(4,operation.name());statement.setString(5,target);statement.setString(6,client);statement.setString(7,group);
                if(statement.executeUpdate()!=1)throw new SQLException("Audit append failed");
            }
        }
    }

    public Page read(Connection connection,String issuer,Set<String> groups,int page,int size)throws SQLException {
        return readFiltered(connection,issuer,groups,page,size);
    }

    public Page readIssuer(Connection connection,String issuer,int page,int size)throws SQLException {
        return readFiltered(connection,issuer,null,page,size);
    }

    private Page readFiltered(Connection connection,String issuer,Set<String> groups,int page,int size)throws SQLException {
        transaction(connection);
        if(issuer==null || issuer.isBlank() || page<1 || size<1 || size>100)throw new IllegalArgumentException("Invalid audit query");
        if(groups!=null && groups.isEmpty())return new Page(List.of(),0,page,size);
        String where=" WHERE issuer=?"+(groups==null?"":" AND group_id IN ("+String.join(",",java.util.Collections.nCopies(groups.size(),"?"))+")");
        var ordered=groups==null?List.<String>of():groups.stream().sorted().toList();long total;
        try(var statement=connection.prepareStatement("SELECT count(*) FROM rbac_management_audit"+where)) {
            statement.setQueryTimeout(5);statement.setString(1,issuer);for(int i=0;i<ordered.size();i++)statement.setString(i+2,ordered.get(i));
            try(var rows=statement.executeQuery()) {if(!rows.next())throw new SQLException("Audit count unavailable");total=rows.getLong(1);}
        }
        var items=new ArrayList<Event>();
        try(var statement=connection.prepareStatement("SELECT event_id,occurred_at,issuer,actor_subject,operation,target_subject,client_id,group_id FROM rbac_management_audit"+where+" ORDER BY occurred_at DESC,event_id DESC LIMIT ? OFFSET ?")) {
            statement.setQueryTimeout(5);statement.setString(1,issuer);for(int i=0;i<ordered.size();i++)statement.setString(i+2,ordered.get(i));
            statement.setInt(ordered.size()+2,size);statement.setLong(ordered.size()+3,Math.multiplyExact((long)page-1,size));
            try(var rows=statement.executeQuery()) {
                while(rows.next())items.add(new Event(rows.getString(1),rows.getTimestamp(2).toInstant(),rows.getString(3),rows.getString(4),Operation.valueOf(rows.getString(5)),rows.getString(6),rows.getString(7),rows.getString(8)));
            }
        }
        return new Page(items,total,page,size);
    }

    private static void transaction(Connection connection)throws SQLException {
        if(connection==null || connection.getAutoCommit() || connection.isReadOnly() || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException("Borrowed writable READ_COMMITTED transaction required");
    }
}
