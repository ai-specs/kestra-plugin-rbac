package io.kestra.extensions.rbac;
import io.micronaut.context.ApplicationContext;
import io.kestra.webserver.rbac.*;
import io.kestra.webserver.oidc.*;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BeanDiscoveryTest {
    @Test void productionRegistersOnlyTrustedV2Evaluation(){
        var properties=Map.<String,Object>of("kestra.rbac.enabled",true,"kestra.rbac.typed.enabled",true,
            "kestra.rbac.evaluation.enabled",true,"kestra.rbac.application-projects.enabled",true);
        try(var context=ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(mock(ProjectPolicyStore.class),mock(ApplicationDirectoryV1.class),mock(TrustedIdentityV1.class)).start()){
            assertNotNull(context.getBean(AuthorizationEvaluationProviderV2.class));
            assertFalse(context.containsBean(AuthorizationEvaluationProviderV1.class));
            assertFalse(context.containsBean(PolicyEngine.class));
            assertNotNull(context.getBean(RbacAuthorizer.class));
            assertNotNull(context.getBean(McpAuthorizationResolver.class));
        }
    }
    @Test void legacyAdministratorConfigurationFailsStartup(){
        var properties=Map.<String,Object>of("kestra.rbac.enabled",true,"kestra.rbac.typed.enabled",true,
            "kestra.rbac.evaluation.enabled",true,"kestra.rbac.application-projects.enabled",true,
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
