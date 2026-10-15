package io.kestra.extensions.rbac.management;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.RbacConfiguration;
import io.kestra.extensions.rbac.RuntimePolicies;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicyDocument;
import io.kestra.extensions.rbac.store.PolicyDocumentValidator;
import io.kestra.webserver.rbac.RbacAuthorizer;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;

@Controller("/api/v1/{tenant}/extensions/rbac")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.typed.enabled", value = "true")
@Requires(property = "kestra.rbac.management.enabled", value = "true")
public final class ManagementController {
    private final RuntimePolicies policies;
    private final RbacConfiguration configuration;
    private final BeanProvider<UserDirectoryAccess> directories;
    private final PolicyDocumentValidator validator = new PolicyDocumentValidator();
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        private Failure(int status, String code) { this.status = status; this.code = code; }
    }
    public ManagementController(RuntimePolicies policies, RbacConfiguration configuration, BeanProvider<UserDirectoryAccess> directories) {
        this.policies = policies; this.configuration = configuration; this.directories = directories;
    }
    private RbacAuthorizer.Principal principal(Authentication identity) {
        if (identity == null) throw new Failure(401, "authentication_required");
        return new RbacAuthorizer.Principal(identity.getName(), List.copyOf(identity.getRoles()), null, null, List.of());
    }
    private HttpResponse<?> guard(String tenant, Authentication identity, Supplier<HttpResponse<?>> action) {
        try {
            if (!"main".equals(tenant)) throw new Failure(404, "not_found");
            if (!policies.isAdministrator(principal(identity))) throw new Failure(403, "access_denied");
            policies.snapshot();
            return action.get();
        } catch (Failure failure) { return error(failure.status, failure.code); }
        catch (JdbcPolicyStore.RevisionConflictException failure) { return error(412, "revision_conflict"); }
        catch (PolicyManagementService.Conflict failure) { return error(409, "role_conflict"); }
        catch (PolicyManagementService.Missing failure) { return error(404, "not_found"); }
        catch (UserDirectoryAccess.DirectoryFailure failure) {
            return switch (failure.failure()) {
                case INVALID -> error(400, "invalid_request");
                case NOT_FOUND -> error(404, "not_found");
                case CONFLICT -> error(409, "directory_conflict");
                case UNAVAILABLE -> error(503, "directory_unavailable");
            };
        } catch (IllegalArgumentException failure) { return error(400, "invalid_request"); }
        catch (JdbcPolicyStore.StorageException failure) { return error(503, "policy_unavailable"); }
        catch (Exception failure) { return error(500, "management_failed"); }
    }
    private HttpResponse<?> error(int status, String code) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code", code));
    }
    private void writable() { if (!policies.database()) throw new Failure(409, "policy_read_only"); }
    private long revision(String match) {
        if (match == null) throw new Failure(428, "revision_required");
        if (!match.matches("\"(?:0|[1-9][0-9]*)\"")) throw new IllegalArgumentException("Strong revision required");
        return Long.parseLong(match.substring(1, match.length() - 1));
    }
    private JsonNode body(byte[] bytes, Set<String> allowed) {
        if (bytes == null || bytes.length > PolicyDocumentValidator.MAX_BYTES) throw new IllegalArgumentException("Body limit");
        try (var parser = mapper.createParser(bytes)) {
            JsonNode value = mapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) throw new IllegalArgumentException("One JSON object required");
            value.fieldNames().forEachRemaining(name -> {if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown field");});
            return value;
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid JSON"); }
    }
    private String text(JsonNode node, String key) {
        if (!node.path(key).isTextual()) throw new IllegalArgumentException("Text field required");
        return node.get(key).textValue();
    }
    private List<String> identifiers(JsonNode node) {
        if (!node.path("roles").isArray() || node.path("roles").size() > 1000) throw new IllegalArgumentException("Role IDs required");
        var result = new java.util.ArrayList<String>();
        for (JsonNode value : node.path("roles")) {
            if (!value.isTextual()) throw new IllegalArgumentException("Role IDs must be text");
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }
    private PolicyDocument.Role role(byte[] bytes) {
        var value = body(bytes, Set.of("id", "name", "description", "grants"));
        var document = mapper.createObjectNode().put("schemaVersion", 1);
        document.putArray("roles").add(value);
        document.putObject("subjects"); document.putObject("roleMapping");
        return validator.parse(document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).document().roles().getFirst();
    }
    private PolicyManagementService service() {
        writable();
        return new PolicyManagementService(policies.store(), validator, policies.protectedSubjects());
    }
    private HttpResponse<?> view() {
        try {
            var result = new LinkedHashMap<String, Object>();
            long current;
            if (policies.database()) {
                var snapshot = policies.store().readCurrent(); current = snapshot.revision();
                result.put("policy", mapper.readTree(snapshot.canonicalJson()));
            } else {
                current = 0;
                result.put("policy", Map.of("schemaVersion", 1, "roles", configuration.getRoles().entrySet().stream()
                    .map(entry -> Map.of("id", entry.getKey(), "name", entry.getKey(), "description", "", "grants", entry.getValue())).toList(),
                    "subjects", configuration.getSubjects(), "roleMapping", configuration.getRoleMapping()));
            }
            result.put("revision", current); result.put("mode", policies.database() ? "database" : "configuration");
            result.put("readOnly", !policies.database()); result.put("protectedSubjects", policies.protectedSubjects());
            return HttpResponse.ok(result).header("ETag", "\"" + current + "\"").header("Cache-Control", "no-store");
        } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid stored policy"); }
    }
    @Get("/capabilities")
    public HttpResponse<?> capabilities(String tenant, Authentication identity) {
        if (identity != null && "main".equals(tenant) && !policies.isAdministrator(principal(identity))) return HttpResponse.ok(Map.of("allowed", false)).header("Cache-Control", "no-store");
        return guard(tenant, identity, () -> HttpResponse.ok(Map.of("allowed", true, "subject", identity.getName(),
            "mode", policies.database() ? "database" : "configuration", "readOnly", !policies.database(), "userDirectory", directories.isUnique()))
            .header("Cache-Control", "no-store"));
    }
    @Get("/policy")
    public HttpResponse<?> policy(String tenant, Authentication identity) { return guard(tenant, identity, this::view); }
    @Post("/roles") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createRole(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {service().createRole(principal(identity), revision(match), role(bytes)); return view();});
    }
    @Put("/roles/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateRole(String tenant, String id, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {service().replaceRole(principal(identity), revision(match), id, role(bytes)); return view();});
    }
    @Delete("/roles/{id}")
    public HttpResponse<?> deleteRole(String tenant, String id, Authentication identity, @Header("If-Match") @Nullable String match) {
        return guard(tenant, identity, () -> {service().deleteRole(principal(identity), revision(match), id); return view();});
    }
    @Put("/bindings") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> binding(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("subject", "roles"));
            var subject = text(node, "subject");
            if (directory().find(subject).isEmpty()) throw new Failure(404, "subject_not_found");
            service().bindSubject(principal(identity), revision(match), subject, identifiers(node)); return view();
        });
    }
    @Put("/role-mappings") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> mapping(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("trustedRole", "roles"));
            service().mapTrustedRole(principal(identity), revision(match), text(node, "trustedRole"), identifiers(node)); return view();
        });
    }
    private UserDirectoryAccess directory() {
        if (!directories.isUnique()) throw new Failure(503, "directory_unavailable");
        return directories.get();
    }
    @Get("/users{?page,size}")
    public HttpResponse<?> users(String tenant, Authentication identity, @QueryValue(defaultValue = "1") int page, @QueryValue(defaultValue = "20") int size) {
        return guard(tenant, identity, () -> HttpResponse.ok(directory().list(page, size)).header("Cache-Control", "no-store"));
    }
    @Get("/users/detail{?subject}")
    public HttpResponse<?> user(String tenant, Authentication identity, @QueryValue String subject) {
        return guard(tenant, identity, () -> HttpResponse.ok(directory().find(subject).orElseThrow(() -> new Failure(404, "not_found"))).header("Cache-Control", "no-store"));
    }
    @Post("/users") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createUser(String tenant, Authentication identity, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("username", "name", "email", "password"));
            var username = text(node, "username");
            if (policies.protectedSubjects().contains(username)) throw new Failure(403, "protected_subject");
            if (!policies.store().readCurrent().document().subjects().getOrDefault(username, List.of()).isEmpty()) throw new Failure(409, "binding_conflict");
            return HttpResponse.created(directory().create(username, text(node, "name"), text(node, "email"), text(node, "password")));
        });
    }
    private HttpResponse<?> accountUpdate(Supplier<UserDirectoryAccess.User> operation) {
        try { return HttpResponse.ok(operation.get()); }
        catch (UserDirectoryAccess.DirectoryFailure failure) {
            if (failure.failure() == UserDirectoryAccess.Failure.CONFLICT) throw new Failure(412, "revision_conflict");
            throw failure;
        }
    }
    @Put("/users/profile") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> profile(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("subject", "name", "email"));
            return accountUpdate(() -> directory().update(text(node, "subject"), revision(match), text(node, "name"), text(node, "email")));
        });
    }
    @Put("/users/state") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> state(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("subject", "active")); var subject = text(node, "subject");
            if (policies.protectedSubjects().contains(subject)) throw new Failure(403, "protected_subject");
            if (!node.path("active").isBoolean()) throw new IllegalArgumentException("Boolean state required");
            return accountUpdate(() -> directory().setActive(subject, revision(match), node.get("active").booleanValue()));
        });
    }
    @Put("/users/password") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> password(String tenant, Authentication identity, @Header("If-Match") @Nullable String match, @Body byte[] bytes) {
        return guard(tenant, identity, () -> {
            writable(); var node = body(bytes, Set.of("subject", "password")); var subject = text(node, "subject");
            if (policies.protectedSubjects().contains(subject)) throw new Failure(403, "protected_subject");
            return accountUpdate(() -> directory().resetPassword(subject, revision(match), text(node, "password")));
        });
    }
}
