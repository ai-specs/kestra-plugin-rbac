package io.kestra.extensions.rbac;
import io.micronaut.context.ApplicationContext;
import io.kestra.webserver.rbac.*;
import io.kestra.webserver.oidc.*;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationController;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.extensions.rbac.management.ApplicationGroupDirectoryManagement;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BeanDiscoveryTest {
    @Test void productionRegistersOnlyTrustedV2Evaluation(){
        var properties=Map.<String,Object>of("kestra.rbac.enabled",true,"kestra.rbac.typed.enabled",true,
            "kestra.rbac.evaluation.enabled",true,"kestra.rbac.application-groups.enabled",true,
            "kestra.rbac.management.enabled",true,"kestra.oidc.issuer","https://issuer.example",
            "micronaut.security.oauth2.clients.kestra.client-id","ui");
        try(var context=ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(mock(ApplicationGroupDirectoryStore.class),mock(OidcProtocolStateV1.class),mock(TrustedIdentityV1.class)).start()){
            assertNotNull(context.getBean(AuthorizationEvaluationProviderV2.class));
            assertFalse(context.containsBean(AuthorizationEvaluationProviderV1.class));
            assertFalse(context.containsBean(PolicyEngine.class));
            assertNotNull(context.getBean(RbacAuthorizer.class));
            assertNotNull(context.getBean(McpAuthorizationResolver.class));
            assertNotNull(context.getBean(ApplicationScopeDeclarationsV1.class));
            assertFalse(context.containsBean(ApplicationScopeDeclarationController.class));
            assertNotNull(context.getBean(io.kestra.extensions.rbac.management.ApplicationPermissionController.class));
            assertNotNull(context.getBean(ApplicationGroupProtocolProjectionV1.class));
            assertSame(context.getBean(ApplicationGroupProtocolProjectionV1.class),context.getBean(ApplicationGroupProtocolProjectionV2.class));
            assertEquals(1,context.getBeansOfType(IdentityLifecycleParticipantV1.class).size());
            assertNotNull(context.getBean(io.kestra.extensions.rbac.management.ClientCredentialController.class));
            assertNotNull(context.getBean(io.kestra.extensions.rbac.management.ManagementAuditController.class));
            assertNotNull(context.getBean(IdentityLifecycleGuardV1.class));
            assertNotNull(context.getBean(ApplicationGroupDirectoryService.class));
            assertNotNull(context.getBean(ApplicationGroupDirectoryManagement.class));
            assertFalse(context.containsBean(ApplicationGroupPolicyStore.class));
            assertFalse(context.containsBean(ApplicationScopeDeclarationStore.class));
        }
    }
    @Test void legacyAdministratorConfigurationFailsStartup(){
        var properties=Map.<String,Object>of("kestra.rbac.enabled",true,"kestra.rbac.typed.enabled",true,
            "kestra.rbac.evaluation.enabled",true,"kestra.rbac.application-groups.enabled",true,
            "kestra.rbac.management-admin-subjects",List.of("owner"));
        try(var context=ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties).build()){
            assertThrows(io.micronaut.context.exceptions.BeanInstantiationException.class,context::start);
        }
    }
    @Test void disabledDoesNotRegisterAuthorizationBeans(){
        try(var context=ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(Map.of("kestra.rbac.enabled",false)).start()){
            assertFalse(context.containsBean(AuthorizationEvaluationProviderV2.class));
            assertFalse(context.containsBean(PolicyEngine.class));
            assertFalse(context.containsBean(SafeFlowDependencies.class));
        }
    }
}
