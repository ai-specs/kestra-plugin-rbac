package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Controller("/api/v1/{tenant}/extensions/rbac/application-groups")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property = "kestra.rbac.application-groups.enabled", value = "true")
public final class ApplicationScopeDeclarationController {
    private final ApplicationGroupRuntimePolicies policies;
    private final ApplicationScopeDeclarationStore store;
    private final ApplicationGroupDirectoryService directory;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public ApplicationScopeDeclarationController(ApplicationGroupRuntimePolicies policies,
                                                 ApplicationScopeDeclarationStore store,
                                                 ApplicationGroupDirectoryService directory) {
        this.policies = policies;
        this.store = store;
        this.directory = directory;
    }

    @Get("/{group}/applications/{client}/scope-declarations")
    public HttpResponse<?> list(String tenant, String group, String client, Authentication authentication) {
        return request(tenant, group, client, authentication, ApplicationGroupPolicy.ManagementAction.READ,
            (connection,target) -> ok(store.list(connection,group,target)));
    }

    @Get("/{group}/applications/{client}/scope-declarations/{scope}")
    public HttpResponse<?> detail(String tenant, String group, String client, String scope, Authentication authentication) {
        return request(tenant, group, client, authentication, ApplicationGroupPolicy.ManagementAction.READ, (connection,target) -> {
            var found = store.list(connection,group,target).stream().filter(value -> value.scopeKey().equals(scope)).findFirst()
                .orElseThrow(() -> new ApiFailure(404, "not_found"));
            return ok(found).header("ETag", "\"" + found.revision() + "\"");
        });
    }

    @Post("/{group}/applications/{client}/scope-declarations/{scope}")
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> create(String tenant, String group, String client, String scope,
                                  Authentication authentication, @Body byte[] body) {
        return request(tenant, group, client, authentication, ApplicationGroupPolicy.ManagementAction.MANAGE, (connection,target) -> {
            requireSupported(connection,group,target,scope);
            ApplicationScopeDeclarationStore.StoredScope created;
            try { created = store.create(connection,group,target,scope, permissions(body)); }
            catch (ApplicationScopeDeclarationStore.Conflict failure) { throw new ApiFailure(409, "already_exists"); }
            return HttpResponse.created(created).header("Cache-Control", "no-store")
                .header("ETag", "\"" + created.revision() + "\"");
        });
    }

    @Put("/{group}/applications/{client}/scope-declarations/{scope}")
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> update(String tenant, String group, String client, String scope,
                                  Authentication authentication, @Header("If-Match") @Nullable String match,
                                  @Body byte[] body) {
        return request(tenant, group, client, authentication, ApplicationGroupPolicy.ManagementAction.MANAGE, (connection,target) -> {
            requireSupported(connection,group,target,scope);
            var saved = store.replace(connection,group,target,scope, revision(match), permissions(body));
            return ok(saved).header("ETag", "\"" + saved.revision() + "\"");
        });
    }

    @Delete("/{group}/applications/{client}/scope-declarations/{scope}")
    public HttpResponse<?> delete(String tenant, String group, String client, String scope,
                                  Authentication authentication, @Header("If-Match") @Nullable String match) {
        return request(tenant, group, client, authentication, ApplicationGroupPolicy.ManagementAction.MANAGE, (connection,target) -> {
            store.delete(connection,group,target,scope, revision(match));
            return ok(Map.of("deleted", true));
        });
    }

    private HttpResponse<?> request(String tenant, String group, String encodedClient, Authentication authentication,
                                    ApplicationGroupPolicy.ManagementAction action, Operation operation) {
        try {
            if (!"main".equals(tenant) || group == null || !group.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
                throw new ApiFailure(404, "not_found");
            if (authentication == null) throw new ApiFailure(401, "authentication_required");
            String client = client(encodedClient);
            try (var admitted = policies.open(authentication,tenant)) {
                return directory.transaction(connection->{
                    try(var context=policies.open(connection,admitted.identity(),tenant)) {
                        if(!context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,action,group))throw new ApiFailure(403,"access_denied");
                        member(connection,group,client);
                        return operation.apply(connection,client);
                    }
                });
            }
        } catch (ApiFailure failure) { return error(failure.status, failure.code); }
        catch (ApplicationGroupRuntimePolicies.Rejected failure) {
            return switch (failure.failure()) {
                case DIRECTORY_UNAVAILABLE, INVALID_DIRECTORY -> error(503, "directory_unavailable");
                case POLICY_UNAVAILABLE -> error(503, "policy_unavailable");
                case INVALID_IDENTITY, FORBIDDEN, INVALID_CANDIDATE, LAST_ADMIN, CLOSED -> error(403, "access_denied");
            };
        }
        catch (ApplicationGroupChanges.NotFound failure){return error(404,"not_found");}
        catch (IdentityLifecycleGuardV1.Rejected failure){return error(failure.failure()==IdentityLifecycleGuardV1.Failure.FORBIDDEN?403:503,"access_denied");}
        catch (ApplicationScopeDeclarationStore.Conflict failure) { return error(412, "revision_conflict"); }
        catch (IllegalArgumentException failure) { return error(400, "invalid_request"); }
        catch (Exception failure) { return error(503, "declaration_unavailable"); }
    }

    private interface Operation { HttpResponse<?> apply(java.sql.Connection connection,String client)throws java.sql.SQLException; }

    private void requireSupported(java.sql.Connection connection,String group,String client,String scope)throws java.sql.SQLException {
        var application=member(connection,group,client);
        if(!application.supportedScopeKeys().contains(scope))throw new ApiFailure(409,"scope_not_registered");
        if(directory.find(connection,group).scopes().stream().noneMatch(value->value.key().equals(scope)))throw new ApiFailure(409,"scope_unavailable");
    }

    private io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.Application member(java.sql.Connection connection,String group,String client)throws java.sql.SQLException {
        return directory.find(connection,group).applications().stream().filter(value->value.clientId().equals(client)).findFirst()
            .orElseThrow(()->new ApiFailure(404,"not_found"));
    }

    private List<ApplicationScopeDeclarationsV1.Permission> permissions(byte[] body) {
        if (body == null || body.length == 0 || body.length > 65536) throw new ApiFailure(400, "invalid_request");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            JsonNode root;
            try (var parser = mapper.createParser(json)) {
                root = mapper.readTree(parser);
                if (parser.nextToken() != null) throw new ApiFailure(400, "invalid_request");
            }
            if (!root.isObject() || root.size() != 1 || !root.path("permissions").isArray()
                || root.path("permissions").isEmpty() || root.path("permissions").size() > 100)
                throw new ApiFailure(400, "invalid_request");
            var result = new ArrayList<ApplicationScopeDeclarationsV1.Permission>();
            for (var permission : root.path("permissions")) {
                if (!permission.isObject() || permission.size() != 2 || !permission.path("key").isTextual()
                    || !permission.path("name").isTextual()) throw new ApiFailure(400, "invalid_request");
                result.add(new ApplicationScopeDeclarationsV1.Permission(permission.path("key").textValue(),
                    permission.path("name").textValue()));
            }
            new ApplicationScopeDeclarationsV1.Scope("validation", result);
            return List.copyOf(result);
        } catch (ApiFailure failure) { throw failure; }
        catch (Exception failure) { throw new ApiFailure(400, "invalid_request"); }
    }

    private static long revision(String value) {
        if (value == null) throw new ApiFailure(428, "revision_required");
        if (!value.matches("\"[1-9][0-9]*\"")) throw new ApiFailure(400, "invalid_revision");
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException failure) { throw new ApiFailure(400, "invalid_revision"); }
    }

    private static String client(String value) {
        if (value == null || !value.startsWith("~")) throw new ApiFailure(400, "invalid_client_key");
        try {
            var decoded = Base64.getUrlDecoder().decode(value.substring(1));
            String client = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(decoded)).toString();
            if (client.isBlank() || client.length() > 512
                || !value.equals("~" + Base64.getUrlEncoder().withoutPadding().encodeToString(client.getBytes(StandardCharsets.UTF_8))))
                throw new ApiFailure(400, "invalid_client_key");
            return client;
        } catch (ApiFailure failure) { throw failure; }
        catch (Exception failure) { throw new ApiFailure(400, "invalid_client_key"); }
    }

    private static io.micronaut.http.MutableHttpResponse<?> ok(Object value) {
        return HttpResponse.ok(value).header("Cache-Control", "no-store");
    }

    private static HttpResponse<?> error(int status, String code) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code", code))
            .header("Cache-Control", "no-store");
    }

    private static final class ApiFailure extends RuntimeException {
        private final int status;
        private final String code;
        private ApiFailure(int status, String code) { this.status = status; this.code = code; }
    }
}
