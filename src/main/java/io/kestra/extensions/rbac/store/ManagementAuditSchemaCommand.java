package io.kestra.extensions.rbac.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ManagementAuditSchemaCommand {
    private ManagementAuditSchemaCommand() {}
    public static void main(String[] arguments) {
        try {run(arguments);}
        catch(Exception failure) {System.err.println("Audit schema refused: "+failure.getClass().getSimpleName());System.exit(1);}
    }
    static void run(String[] arguments)throws Exception {
        if(arguments.length<1 || !("preview".equals(arguments[0]) && arguments.length==1 || "apply".equals(arguments[0]) && arguments.length==2))
            throw new IllegalArgumentException("preview; apply <approvalHash>");
        var protocol=ApplicationGroupDirectoryCommand.protocol();
        var directory=new ApplicationGroupDirectoryStore(ApplicationGroupPolicyCommand.dataSource(),protocol::lockSecurityState);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var result=directory.transaction(connection->{
            if(!directory.schemaPresent(connection) || directory.builtinGroupId(connection)==null)throw new IllegalStateException("Installed directory required");
            String hash=approval(connection,directory);
            if("apply".equals(arguments[0])) {
                apply(connection,directory,arguments[1]);
            }
            return java.util.Map.of("approvalHash",hash,"table","rbac_management_audit","applied","apply".equals(arguments[0]));
        });
        System.out.println(mapper.writeValueAsString(result));
    }
    static String approval(java.sql.Connection connection,ApplicationGroupDirectoryStore directory)throws java.sql.SQLException {
        if(!directory.schemaPresent(connection) || directory.builtinGroupId(connection)==null)throw new IllegalStateException("Installed directory required");
        String canonical;
        canonical="management-audit-v1\n"+directory.builtinGroupId(connection)+"\n"+directory.catalogRevision(connection)+"\n"+canonical(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(directory.readAll(connection)));
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
    static void apply(java.sql.Connection connection,ApplicationGroupDirectoryStore directory,String hash)throws java.sql.SQLException {
        if(!approval(connection,directory).equals(hash))throw new IllegalArgumentException("Reviewed audit schema hash required");
        ManagementAuditStore.initializeSchema(connection);
    }
    private static String canonical(com.fasterxml.jackson.databind.JsonNode node) {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        if(node.isObject()) {
            var names=new java.util.TreeSet<String>();node.fieldNames().forEachRemaining(names::add);
            var result=mapper.createObjectNode();for(String name:names) {
                try {result.set(name,mapper.readTree(canonical(node.get(name))));}
                catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("Audit schema source unavailable");}
            }
            return result.toString();
        }
        if(node.isArray()) {
            var children=new java.util.ArrayList<String>();node.forEach(child->children.add(canonical(child)));java.util.Collections.sort(children);
            return "["+String.join(",",children)+"]";
        }
        return node.toString();
    }
}
