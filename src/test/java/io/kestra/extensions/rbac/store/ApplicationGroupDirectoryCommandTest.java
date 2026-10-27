package io.kestra.extensions.rbac.store;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupInstallationPlan;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationGroupDirectoryCommandTest {
    private static final String ISSUER="https://issuer.example";
    private final Connection connection=mock(Connection.class);
    private final OidcProtocolStateV1 protocol=mock(OidcProtocolStateV1.class);
    private ApplicationGroupInstallationPlan plan(boolean external) {
        var apps=new java.util.ArrayList<Application>();apps.add(io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("ui",true,Set.of("ui"),Set.of("manager"),1));
        var manager=new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ScopeDefinition("",true,List.of(),List.of(
            new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.IDENTITY,Set.of(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE)),
            new io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementGrant(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,Set.of(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE))));
        apps.set(0,io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.withPolicy(apps.getFirst(),java.util.Map.of("manager",manager)));
        if(external)apps.add(io.kestra.extensions.rbac.applicationgroup.TestApplicationFactory.application("external",true,Set.of("api"),Set.of("manager"),1));
        return new ApplicationGroupInstallationPlan(2,"28e9352d-ea66-4a24-888f-e76fda93fef0",ISSUER,"ui","group",List.of(
            new ApplicationGroupInstallationPlan.Entry(new Group("group","Group",true,1,1),List.of(new Scope("manager","Manager","",1)),apps,
                List.of(new UserGrant(ISSUER,"admin",Set.of("manager"),1)))));
    }
    private void standard()throws Exception {
        when(protocol.verifyInstallation(connection,ISSUER,plan(false).installationId())).thenReturn(new OidcProtocolStateV1.Installation(ISSUER,plan(false).installationId(),"ui"));
        when(protocol.client(connection,ISSUER,"ui")).thenReturn(Optional.of(new OidcProtocolStateV1.Client(ISSUER,"ui",true,Set.of("openid"),true)));
        when(protocol.account(connection,ISSUER,"admin")).thenReturn(Optional.of(new OidcProtocolStateV1.Account(ISSUER,"admin",true,true,0)));
    }
    @Test void validatesRealInstallationClientAndLoginAccountUsingCallerConnection()throws Exception {
        standard();ApplicationGroupDirectoryCommand.validate(connection,protocol,plan(false));
        verify(protocol).verifyInstallation(connection,ISSUER,plan(false).installationId());
        verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).close();
    }
    @Test void wrongInstallationOrConfiguredClientCannotInitialize()throws Exception {
        standard();when(protocol.verifyInstallation(connection,ISSUER,plan(false).installationId())).thenReturn(new OidcProtocolStateV1.Installation(ISSUER,plan(false).installationId(),"other"));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.validate(connection,protocol,plan(false)));
        verify(protocol,never()).account(connection,ISSUER,"admin");
    }
    @Test void inactiveAndUnknownAccountsCannotBecomeInstallationAdministrators()throws Exception {
        standard();for(var account:List.of(Optional.<OidcProtocolStateV1.Account>empty(),Optional.of(new OidcProtocolStateV1.Account(ISSUER,"admin",false,false,1)))) {
            when(protocol.account(connection,ISSUER,"admin")).thenReturn(account);
            assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.validate(connection,protocol,plan(false)));
        }
    }
    @Test void externalClientRequiresActiveOrValidPendingProtocolRegistration()throws Exception {
        standard();when(protocol.client(connection,ISSUER,"external")).thenReturn(Optional.empty());
        when(protocol.pendingClient(connection,ISSUER,"external")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.validate(connection,protocol,plan(true)));
        when(protocol.pendingClient(connection,ISSUER,"external")).thenReturn(Optional.of(new OidcProtocolStateV1.PendingClient(ISSUER,"external",Set.of("openid"))));
        ApplicationGroupDirectoryCommand.validate(connection,protocol,plan(true));
    }
    @Test void malformedArgumentsAndWrongApprovalFailBeforeDatabaseAccess()throws Exception {
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.run(new String[]{}));
        assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.run(new String[]{"restore","missing"}));
        var file=java.nio.file.Files.createTempFile("group-command-",".json");
        try {
            java.nio.file.Files.write(file,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(plan(false)));
            assertThrows(IllegalArgumentException.class,()->ApplicationGroupDirectoryCommand.run(new String[]{"apply",file.toString(),"incorrect"}));
        }finally{java.nio.file.Files.delete(file);}
        verifyNoInteractions(protocol,connection);
    }
}
