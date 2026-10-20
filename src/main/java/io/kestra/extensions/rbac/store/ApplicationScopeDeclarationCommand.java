package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ApplicationScopeDeclarationCommand {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ApplicationScopeDeclarationCommand() {}

    private record Plan(String applicationGroupId, String clientId, List<ApplicationScopeDeclarationsV1.Scope> scopes) {}

    public static void main(String[] arguments) {
        try { run(arguments); }
        catch (Exception failure) {
            System.err.println("Application declaration command refused: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static void run(String[] arguments) throws Exception {
        if (arguments.length != 2 || !Set.of("validate", "initialize").contains(arguments[0]))
            throw new IllegalArgumentException("Usage: validate|initialize <declarations.json>");
        var plan = parse(Path.of(arguments[1]));
        if ("validate".equals(arguments[0])) {
            System.out.println("Valid application scope declarations=" + plan.scopes().size());
            return;
        }
        var store = new ApplicationScopeDeclarationStore(ProjectPolicyCommand.dataSource(), MAPPER);
        store.initializeSchema();
        store.initialize(plan.applicationGroupId(), plan.clientId(), plan.scopes());
        System.out.println("Initialized application scope declarations=" + plan.scopes().size());
    }

    private static Plan parse(Path file) throws Exception {
        if (Files.size(file) > 1024 * 1024) throw new IllegalArgumentException("Declaration file too large");
        byte[] bytes = Files.readAllBytes(file);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JsonNode root;
        try (var parser = MAPPER.createParser(text)) {
            root = MAPPER.readTree(parser);
            if (parser.nextToken() != null) throw new IllegalArgumentException("Trailing declaration data");
        }
        fields(root, Set.of("applicationGroupId", "clientId", "scopes"));
        if (!root.path("scopes").isArray() || root.path("scopes").isEmpty() || root.path("scopes").size() > 1000)
            throw new IllegalArgumentException("Application scopes required");
        var scopes = new ArrayList<ApplicationScopeDeclarationsV1.Scope>();
        for (var item : root.path("scopes")) {
            fields(item, Set.of("key", "permissions"));
            if (!item.path("permissions").isArray() || item.path("permissions").isEmpty()
                || item.path("permissions").size() > 100) throw new IllegalArgumentException("Scope permissions required");
            var permissions = new ArrayList<ApplicationScopeDeclarationsV1.Permission>();
            for (var permission : item.path("permissions")) {
                fields(permission, Set.of("key", "name"));
                permissions.add(new ApplicationScopeDeclarationsV1.Permission(text(permission, "key"), text(permission, "name")));
            }
            scopes.add(new ApplicationScopeDeclarationsV1.Scope(text(item, "key"), permissions));
        }
        var groupId = text(root, "applicationGroupId");
        var clientId = text(root, "clientId");
        new ApplicationScopeDeclarationsV1.Projection(groupId, clientId, scopes);
        return new Plan(groupId, clientId, List.copyOf(scopes));
    }

    private static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()) throw new IllegalArgumentException("Invalid declaration fields");
        for (var name : names) if (!node.hasNonNull(name)) throw new IllegalArgumentException("Missing declaration field");
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("Declaration text required");
        return node.path(field).textValue();
    }
}
