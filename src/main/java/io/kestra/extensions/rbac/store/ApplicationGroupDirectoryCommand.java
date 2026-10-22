package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupInstallationPlan;
import io.kestra.webserver.oidc.OidcProtocolState;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.kestra.webserver.oidc.services.OidcInstallationReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

public final class ApplicationGroupDirectoryCommand {
    private ApplicationGroupDirectoryCommand() {}

    public static void main(String[] arguments) {
        try { run(arguments); }
        catch(Exception failure) {
            System.err.println("Application group installation refused: "+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void run(String[] arguments)throws Exception {
        if(arguments.length<2 || !("preview".equals(arguments[0])&&arguments.length==2
            || "apply".equals(arguments[0])&&arguments.length==3))
            throw new IllegalArgumentException("preview <plan>; apply <plan> <reviewed-hash>");
        var file=Path.of(arguments[1]);
        if(Files.size(file)>1024*1024)throw new IllegalArgumentException("Installation plan too large");
        ApplicationGroupInstallationPlan.Review review;
        try(var input=Files.newInputStream(file)){review=ApplicationGroupInstallationPlan.review(input.readNBytes(1024*1024+1));}
        if("apply".equals(arguments[0]) && !review.approvalHash().equals(arguments[2]))
            throw new IllegalArgumentException("Exact reviewed installation approval required");
        var protocol=protocol();var source=ApplicationGroupPolicyCommand.dataSource();
        var store=new ApplicationGroupDirectoryStore(source,protocol::lockSecurityState);
        boolean apply="apply".equals(arguments[0]);
        store.transaction(connection->{
            validate(connection,protocol,review.plan());
            if(store.schemaPresent(connection) && (store.builtinGroupId(connection)!=null
                || store.catalogRevision(connection)!=0 || !store.readAll(connection).isEmpty()))
                throw new ApplicationGroupDirectoryStore.Conflict();
            if(apply) {
                store.initializeSchema(connection);
                store.initializeBuiltin(connection,review.plan().applicationGroups().getFirst().snapshot());
            }
            return null;
        });
        if(apply)System.out.println("Initialized builtin application group="+review.plan().builtInApplicationGroupId());
        else {
            var mapper=new ObjectMapper();var result=mapper.createObjectNode();
            result.set("plan",mapper.readTree(review.canonicalJson()));result.putArray("source");
            result.put("approvalHash",review.approvalHash());System.out.println(result);
        }
    }

    static void validate(Connection connection,OidcProtocolStateV1 protocol,ApplicationGroupInstallationPlan plan)throws SQLException {
        var installation=protocol.verifyInstallation(connection,plan.issuer(),plan.installationId());
        if(!installation.configuredLoginClientId().equals(plan.uiClientId()))throw new IllegalArgumentException("Configured login application mismatch");
        for(var group:plan.applicationGroups()) {
            for(var application:group.applications()) {
                var client=protocol.client(connection,plan.issuer(),application.clientId());
                if((client.isEmpty() || !client.get().active())
                    && protocol.pendingClient(connection,plan.issuer(),application.clientId()).isEmpty())
                    throw new IllegalArgumentException("Protocol registration required");
            }
            for(var grant:group.userGrants()) {
                var account=protocol.account(connection,plan.issuer(),grant.subject());
                if(account.isEmpty() || !account.get().active() || !account.get().canLogin())
                    throw new IllegalArgumentException("Initial user must be able to log in");
            }
        }
    }

    static OidcProtocolStateV1 protocol()throws Exception {
        String configuration=System.getenv("KESTRA_CONFIGURATION");
        if(configuration==null || configuration.isBlank())throw new IllegalArgumentException("KESTRA_CONFIGURATION required");
        var config=new ObjectMapper(new YAMLFactory()).readTree(configuration);
        String issuer=config.path("kestra").path("oidc").path("issuer").asText();
        String name=config.path("kestra").path("oidc").path("bootstrap").path("client-name").asText("kestra");
        String clientId=config.path("micronaut").path("security").path("oauth2").path("clients").path(name).path("client-id").asText();
        String storage=config.path("kestra").path("storage").path("local").path("base-path").asText("/app/storage");
        String receipt=config.path("kestra").path("oidc").path("bootstrap").path("receipt-path").asText(storage+"/.oidc-installation");
        return new OidcProtocolState(issuer,clientId,new OidcInstallationReceipt(Path.of(receipt)),new ObjectMapper());
    }
}
