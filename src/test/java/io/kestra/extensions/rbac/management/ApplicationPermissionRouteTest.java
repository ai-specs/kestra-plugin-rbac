package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.AnnotatedMethodRouteBuilder;
import io.micronaut.web.router.DefaultRouteBuilder;
import io.micronaut.web.router.DefaultRouter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationPermissionRouteTest {
    @Test void validMutationMetadataKeyResolvesToPermissionReadNotCollectionManagement() {
        var properties=Map.<String,Object>of("kestra.rbac.enabled",true,"kestra.rbac.typed.enabled",true,"kestra.rbac.evaluation.enabled",true,
            "kestra.rbac.application-groups.enabled",true,"kestra.rbac.management.enabled",true,
            "kestra.oidc.issuer","https://issuer.example","micronaut.security.oauth2.clients.kestra.client-id","ui");
        try(var context=ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(mock(ApplicationGroupDirectoryStore.class),mock(OidcProtocolStateV1.class),mock(TrustedIdentityV1.class)).start()) {
            var builder=new AnnotatedMethodRouteBuilder(context,DefaultRouteBuilder.CAMEL_CASE_NAMING_STRATEGY,ConversionService.SHARED);
            var definition=context.getBeanDefinition(ApplicationPermissionController.class);
            for(var method:definition.getExecutableMethods())builder.process(definition,method);
            var router=new DefaultRouter(builder);
            String root="/api/v1/main/extensions/rbac/application-groups/group/applications/~dWk/permissions";
            var permission=router.findClosest(HttpRequest.GET(root+"/mutation-metadata"));
            assertNotNull(permission);
            System.out.println("Actual GET permission key mutation-metadata dispatch: "+permission.getExecutableMethod().getMethodName());
            assertEquals("find",permission.getExecutableMethod().getMethodName());
            assertEquals("mutation-metadata",permission.getVariableValues().get("key"));
            var collection=router.findClosest(HttpRequest.GET(root+"/-/mutation-metadata"));
            assertNotNull(collection);assertEquals("applicationMetadata",collection.getExecutableMethod().getMethodName());
            var item=router.findClosest(HttpRequest.GET(root+"/mutation-metadata/mutation-metadata"));
            assertNotNull(item);assertEquals("metadata",item.getExecutableMethod().getMethodName());
            for(var request:java.util.List.of(HttpRequest.PUT(root+"/mutation-metadata","{}"),HttpRequest.DELETE(root+"/mutation-metadata"))) {
                var matched=router.findClosest(request);assertNotNull(matched);
                assertEquals("mutation-metadata",matched.getVariableValues().get("key"));
            }
        }
    }
}
