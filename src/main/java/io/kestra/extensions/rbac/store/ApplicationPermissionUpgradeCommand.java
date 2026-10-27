package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplicationPermissionUpgradeCommand {
    private ApplicationPermissionUpgradeCommand() {}
    public static void main(String[] arguments) {
        try {run(arguments);}
        catch(Exception failure) {
            System.err.println("Declaration upgrade refused: "+failure.getClass().getSimpleName());System.exit(1);
        }
    }
    static void run(String[] arguments)throws Exception {
        if(arguments.length==0 || !(arguments[0].equals("source") && arguments.length==1
            || arguments[0].equals("preview") && arguments.length==2 || arguments[0].equals("apply") && arguments.length==3))
            throw new IllegalArgumentException("source; preview <mapping.json>; apply <mapping.json> <approvalHash>");
        var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        ApplicationPermissionUpgrade.Plan plan=null;
        if(arguments.length>1) {
            var path=Path.of(arguments[1]);if(Files.size(path)>1024*1024)throw new IllegalArgumentException("Upgrade plan too large");
            try(var input=Files.newInputStream(path)){plan=mapper.readValue(input.readNBytes(1024*1024+1),ApplicationPermissionUpgrade.Plan.class);}
        }
        var protocol=ApplicationGroupDirectoryCommand.protocol();var source=ApplicationGroupPolicyCommand.dataSource();
        String configuration=System.getenv("KESTRA_CONFIGURATION");
        var config=new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(configuration);
        String issuer=config.path("kestra").path("oidc").path("issuer").asText();
        String client=config.path("micronaut").path("security").path("oauth2").path("clients").path("kestra").path("client-id").asText();
        if(issuer.isBlank() || client.isBlank())throw new IllegalArgumentException("Formal issuer and configured Kestra client required");
        var directory=new ApplicationGroupDirectoryStore(source,protocol::lockSecurityState);
        var policies=new ApplicationGroupPolicyStore(source,new ApplicationGroupPolicyParser(),protocol::lockSecurityState);
        var service=new ApplicationGroupDirectoryService(directory,policies,protocol,issuer,client);
        var upgrade=new ApplicationPermissionUpgrade(directory,policies,new ApplicationScopeDeclarationStore(source,mapper),service);
        if(arguments[0].equals("source"))System.out.println(mapper.writeValueAsString(upgrade.source()));
        else if(arguments[0].equals("preview"))System.out.println(mapper.writeValueAsString(upgrade.preview(plan)));
        else System.out.println(mapper.writeValueAsString(java.util.Map.of("applied",upgrade.apply(plan,arguments[2]))));
    }
}
