package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.extensions.rbac.store.*;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.rbac.*;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationDependencyTest {
    RuntimePoliciesTest.Fixture fixture() {return new RuntimePoliciesTest.Fixture(Map.of("read",RuntimePoliciesTest.scope("a","x","READ")));}
    void unavailable(Throwable failure) {
        assertEquals(AuthorizationUnavailableException.class,failure.getClass());
        assertEquals("Authorization dependency is unavailable",failure.getMessage());
        assertNull(failure.getCause());
    }
    @Test void ordinaryAuthenticationUsesV2AuthorizationEntryAndRetainsV1Fallback() {
        var f=fixture();var identities=mock(TrustedIdentityV2.class);
        when(identities.currentForAuthorization()).thenReturn(f.identity("read"));
        var runtime=new ApplicationGroupRuntimePolicies(f.directory,identities);
        try(var context=runtime.open(Authentication.build("s"),"main")){assertEquals("s",context.identity().subject());}
        verify(identities).currentForAuthorization();verify(identities,never()).current();
        when(f.identities.current()).thenReturn(f.identity("read"));
        try(var context=f.runtime.open(Authentication.build("s"),"main")){assertEquals("s",context.identity().subject());}
        verify(f.identities).current();
    }
    @Test void ordinaryOpenClassifiesOnlyExplicitDirectoryAndPolicyFailures() {
        var f=fixture();doThrow(new ApplicationGroupDirectoryStore.Unavailable(new SQLException("private sql"))).when(f.directory).boundaries();
        unavailable(assertThrows(RuntimeException.class,()->f.runtime.open(f.identity("read"),"main")));
        reset(f.directory);
        doThrow(new IllegalStateException("unknown")).when(f.directory).boundaries();
        assertEquals(IllegalStateException.class,assertThrows(RuntimeException.class,()->f.runtime.open(f.identity("read"),"main")).getClass());
        var policyFixture=fixture();
        doThrow(new ApplicationGroupPolicyStore.Unavailable(new SQLException("private policy"))).when(policyFixture.directory).runtimePolicy();
        unavailable(assertThrows(RuntimeException.class,()->policyFixture.runtime.open(policyFixture.identity("read"),"main")));
    }
    @Test void beginPreservesExplicitDependencyForDirectAndAsyncExecutors() throws Exception {
        for(boolean async:new boolean[]{false,true})try(var executor=Executors.newSingleThreadExecutor()) {
            var f=fixture();doThrow(new ApplicationGroupDirectoryStore.Unavailable(new SQLException("private"))).when(f.directory).boundaries();
            var provider=new ConfiguredEvaluationProvider(f.runtime,async?executor:Runnable::run);
            unavailable(assertThrows(CompletionException.class,()->provider.begin(f.identity("read"),"main").toCompletableFuture().join()).getCause());
            verify(f.directory,never()).runtimePolicy();
        }
    }
    @Test void authorizePreservesOnlyExplicitDependencyAndDeniesSecurityUnknownAndClosed() {
        var f=fixture();var runtime=mock(ApplicationGroupRuntimePolicies.class);var context=mock(ApplicationGroupRuntimePolicies.Context.class);
        when(runtime.open(f.identity("read"),"main")).thenReturn(context);
        var evaluation=new ConfiguredEvaluationProvider(runtime,Runnable::run).begin(f.identity("read"),"main").toCompletableFuture().join();
        var operation=RuntimePoliciesTest.op(TypedAuthorization.Resource.FLOW,TypedAuthorization.Action.READ,"a","x",false);
        for(var reason:new ApplicationGroupRuntimePolicies.Failure[]{ApplicationGroupRuntimePolicies.Failure.DIRECTORY_UNAVAILABLE,ApplicationGroupRuntimePolicies.Failure.POLICY_UNAVAILABLE}) {
            doThrow(new ApplicationGroupRuntimePolicies.Rejected(reason)).when(context).authorize(operation);
            unavailable(assertThrows(CompletionException.class,()->evaluation.authorize(operation).toCompletableFuture().join()).getCause());
        }
        doThrow(new AuthorizationUnavailableException()).when(context).authorize(operation);
        unavailable(assertThrows(CompletionException.class,()->evaluation.authorize(operation).toCompletableFuture().join()).getCause());
        for(var reason:ApplicationGroupRuntimePolicies.Failure.values()) {
            if(reason==ApplicationGroupRuntimePolicies.Failure.DIRECTORY_UNAVAILABLE || reason==ApplicationGroupRuntimePolicies.Failure.POLICY_UNAVAILABLE)continue;
            doThrow(new ApplicationGroupRuntimePolicies.Rejected(reason)).when(context).authorize(operation);
            assertFalse(evaluation.authorize(operation).toCompletableFuture().join().allowed());
        }
        doThrow(new IllegalStateException("unclassified")).when(context).authorize(operation);
        assertFalse(evaluation.authorize(operation).toCompletableFuture().join().allowed());
        evaluation.close().toCompletableFuture().join();assertFalse(evaluation.authorize(operation).toCompletableFuture().join().allowed());
    }
    @Test void missingAndDuplicateEvaluationProvidersRetainOriginalDenial() {
        var configuration=new io.kestra.webserver.rbac.RbacConfiguration(true,true);
        for(int count:new int[]{0,2}) {
            io.micronaut.context.BeanProvider<AuthorizationEvaluationProviderV2> providers=mock(io.micronaut.context.BeanProvider.class);
            when(providers.isUnique()).thenReturn(false);when(providers.isPresent()).thenReturn(count>0);
            var identities=mock(TrustedIdentityV2.class);
            io.micronaut.context.BeanProvider<TrustedIdentityV1> identityProviders=mock(io.micronaut.context.BeanProvider.class);
            when(identityProviders.isUnique()).thenReturn(true);when(identityProviders.get()).thenReturn(identities);
            var evaluations=new AuthorizationEvaluations(true,configuration,true,mock(io.micronaut.context.BeanProvider.class),providers,identityProviders,true);
            var principal=new RbacAuthorizer.Principal("s",java.util.List.of(),"ui","ui",java.util.List.of());
            var effects=new java.util.concurrent.atomic.AtomicInteger();
            var failure=assertThrows(IllegalStateException.class,()->evaluations.boundary(principal,"main",()->{
                effects.incrementAndGet();return reactor.core.publisher.Mono.just("not reached");
            }).blockLast());
            assertNotEquals(AuthorizationUnavailableException.class,failure.getClass());
            assertEquals(0,effects.get());verifyNoInteractions(identities);verify(providers,never()).get();
        }
    }

}
