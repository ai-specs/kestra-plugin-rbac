package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.extensions.rbac.*;
import io.kestra.extensions.rbac.store.*;
import io.kestra.webserver.oidc.*;
import io.kestra.webserver.rbac.*;
import io.kestra.webserver.errors.*;
import io.micronaut.http.*;
import io.micronaut.security.authentication.Authentication;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Published HTTP filter and real PG; signed test JWT, not a production login/browser test. */
@Tag("postgres-lifecycle")
class AuthorizationDependencyPostgresTest {
    @TempDir Path temporary;
    AccountLifecyclePostgresTest f;
    Authentication signed;
    @BeforeEach void initialize()throws Exception {
        f=new AccountLifecyclePostgresTest();f.temporary=temporary;f.initialize();
        var authentication=f.authentication("first");
        byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("first").issuer(AccountLifecyclePostgresTest.ISSUER)
            .audience("ui").expirationTime(new Date(System.currentTimeMillis()+60000));
        authentication.getAttributes().forEach(claims::claim);
        var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),claims.build());
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(key));
        var parsed=com.nimbusds.jwt.SignedJWT.parse(jwt.serialize());
        assertTrue(parsed.verify(new com.nimbusds.jose.crypto.MACVerifier(key)));
        signed=Authentication.build(parsed.getJWTClaimsSet().getSubject(),List.of(),parsed.getJWTClaimsSet().getClaims());
    }
    <T> T actor(java.util.function.Supplier<T> work) {
        var request=HttpRequest.GET("/api/v1/main/extensions/rbac").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,signed);
        try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {return work.get();}
    }
    String state()throws Exception {
        var rows=new ArrayList<String>();
        try(var connection=f.source.getConnection();var statement=connection.createStatement()) {
            for(String table:List.of("oidc_user","rbac_application_group_state","rbac_application_group_invalidation","rbac_management_audit"))
                try(var records=statement.executeQuery("SELECT row_to_json(record)::text FROM "+table+" record ORDER BY row_to_json(record)::text")) {
                    while(records.next())rows.add(table+":"+records.getString(1));
                }
        }
        return String.join("\n",rows);
    }
    void table(boolean damage)throws Exception {
        try(var connection=f.source.getConnection();var statement=connection.createStatement()) {
            statement.execute(damage?"ALTER TABLE rbac_application_group_state RENAME TO dependency_probe_saved":"ALTER TABLE dependency_probe_saved RENAME TO rbac_application_group_state");
        }
    }
    int http(ApplicationGroupRuntimePolicies runtime,TrustedIdentityV1 identities,Executor executor,AtomicInteger effects) {
        var provider=new ConfiguredEvaluationProvider(runtime,executor);
        var configuration=new io.kestra.webserver.rbac.RbacConfiguration(true,true);
        var evaluations=new AuthorizationEvaluations(true,configuration,true,AccountLifecyclePostgresTest.provider(()->null),
            AccountLifecyclePostgresTest.provider(()->provider),AccountLifecyclePostgresTest.provider(()->identities),true);
        var gate=new TypedAuthorizationGate(AccountLifecyclePostgresTest.provider(()->null),evaluations);
        var adapter=mock(OssRestOperationAdapter.class);
        when(adapter.resolve(any())).thenReturn(List.of(new TypedAuthorization.Operation(1,TypedAuthorization.Source.REST,TypedAuthorization.Resource.EXTENSION,
            TypedAuthorization.Action.READ,"main",null,"rbac",TypedAuthorization.QueryDomain.unrestricted(),TypedAuthorization.Coverage.SINGLE,
            TypedAuthorization.Aggregate.NONE,TypedAuthorization.SideEffect.READ_ONLY)));
        var problems=new ProblemFactory(new ProblemMapperRegistry(List.of()),new io.kestra.core.utils.EditionProvider());
        var filter=new TypedRestRbacFilter(configuration,adapter,gate,problems);
        var route=mock(io.micronaut.web.router.MethodBasedRouteMatch.class);
        when(route.getVariableValues()).thenReturn(Map.of("tenant","main"));
        doReturn(AuthorizationDependencyPostgresTest.class).when(route).getDeclaringType();
        when(route.getMethodName()).thenReturn("dependencyProbe");
        when(route.getArgumentTypes()).thenReturn(new Class<?>[0]);
        var request=HttpRequest.GET("/api/v1/main/extensions/rbac").setAttribute(io.micronaut.security.filters.SecurityFilter.AUTHENTICATION,signed);
        request.setAttribute(HttpAttributes.ROUTE_MATCH,route);
        try(var scope=io.micronaut.core.propagation.PropagatedContext.getOrEmpty().plus(new io.kestra.core.repositories.AuthorizationInvocationContext(request,false)).propagate()) {
            var response=reactor.core.publisher.Mono.from(filter.doFilter(request,ignored->{effects.incrementAndGet();return reactor.core.publisher.Mono.just(HttpResponse.ok());})).block(java.time.Duration.ofSeconds(15));
            assertNotNull(response);
            if(response.code()==503) {
                var body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response.body());
                assertFalse(body.contains("private"));assertFalse(body.contains("SQLException"));
            }
            return response.code();
        }catch(java.io.IOException failure){throw new AssertionError(failure);}
    }
    @Test void signedContextRealDirectorySqlFailureIs503AndRestoresWithoutSideEffects()throws Exception {
        var before=state();var identity=actor(()->f.trusted.currentForAuthorization());
        var identities=mock(TrustedIdentityV2.class);when(identities.currentForAuthorization()).thenReturn(identity);
        var runtime=new ApplicationGroupRuntimePolicies(f.service,f.trusted);var effects=new AtomicInteger();
        table(true);
        try {
            assertThrows(AuthorizationUnavailableException.class,()->actor(()->runtime.open(signed,"main")));
            assertEquals(503,http(runtime,identities,Runnable::run,effects));assertEquals(0,effects.get());
        }finally {table(false);}
        assertEquals(before,state());assertEquals(200,http(runtime,f.trusted,Runnable::run,effects));assertEquals(1,effects.get());
    }
    @Test void signedContextPolicySqlFailureIs503ThroughSynchronousAndAsynchronousBegin()throws Exception {
        var before=state();var service=spy(f.service);
        doThrow(new java.sql.SQLException("private policy query")).when(service).runtimePolicy(any(java.sql.Connection.class));
        var runtime=new ApplicationGroupRuntimePolicies(service,f.trusted);var effects=new AtomicInteger();
        for(boolean async:new boolean[]{false,true})try(var executor=Executors.newSingleThreadExecutor()) {
            assertEquals(503,http(runtime,f.trusted,async?executor:Runnable::run,effects));
        }
        assertEquals(0,effects.get());assertEquals(before,state());
    }
    @Test void signedContextAuthorizeExplicitFailuresAre503SecurityAndUnknownRemain403()throws Exception {
        var before=state();var runtime=mock(ApplicationGroupRuntimePolicies.class);var context=mock(ApplicationGroupRuntimePolicies.Context.class);
        when(runtime.open(any(TrustedIdentityV1.Identity.class),eq("main"))).thenReturn(context);var effects=new AtomicInteger();
        for(var reason:ApplicationGroupRuntimePolicies.Failure.values()) {
            doThrow(new ApplicationGroupRuntimePolicies.Rejected(reason)).when(context).authorize(any());
            int expected=reason==ApplicationGroupRuntimePolicies.Failure.DIRECTORY_UNAVAILABLE||reason==ApplicationGroupRuntimePolicies.Failure.POLICY_UNAVAILABLE?503:403;
            assertEquals(expected,http(runtime,f.trusted,Runnable::run,effects),reason.name());
        }
        doThrow(new IllegalStateException("unknown private")).when(context).authorize(any());
        assertEquals(403,http(runtime,f.trusted,Runnable::run,effects));
        assertEquals(0,effects.get());assertEquals(before,state());
    }
}
