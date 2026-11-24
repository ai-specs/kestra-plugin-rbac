package io.kestra.extensions.rbac.applicationgroup;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NamespaceExpressionTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ApplicationGroupPolicyParser parser = new ApplicationGroupPolicyParser();
    private static final List<String> NAMES = List.of("company", "company.sub", "company.sub.deep", "company2", "other");

    private String grant(String expression, String legacy) {
        return "{\"scope\":\"NAMESPACE\",\"tenant\":\"main\",\"namespace\":\"" + expression + "\"," + legacy
            + "\"resource\":\"FLOW\",\"actions\":[\"READ\",\"LIST\"],\"ids\":[],\"allIds\":true}";
    }

    private String policy(String grant) {
        return "{\"schemaVersion\":2,\"applicationGroups\":{\"group\":{\"issuer\":\"https://issuer.example\","
            + "\"audiences\":[\"ui\"],\"enabled\":true,\"scopes\":{\"reader\":{\"description\":\"\","
            + "\"enabled\":true,\"grants\":[" + grant + "],\"managementGrants\":[]}}}}}";
    }

    private ApplicationGroupPolicy parse(String json) { return parser.parse(json.getBytes(StandardCharsets.UTF_8)); }

    @Test void parsesCanonicalExpressions() {
        for (String name : List.of("company", "!company", "company.sub", "!company.sub", "a-B_1")) {
            assertEquals(new NamespaceExpression(name, false), NamespaceExpression.parse(name));
            assertEquals(new NamespaceExpression(name, true), NamespaceExpression.parse(name + ".*"));
            assertEquals(name + ".*", NamespaceExpression.parse(name + ".*").render());
        }
    }

    @Test void rejectsInvalidExpressions() {
        for (String value : List.of("", "!", "*", "!*", "company*", "company.*.child", "!!company", "company!",
            " company", "company ", "company.", ".company", "company..sub", "company.**", "company.*.*", "company\n", "公司")) {
            assertThrows(IllegalArgumentException.class, () -> NamespaceExpression.parse(value), value);
            assertThrows(IllegalArgumentException.class, () -> parse(policy(grant(value, ""))), value);
        }
        assertThrows(IllegalArgumentException.class, () -> NamespaceExpression.parse(null));
    }

    @Test void legacyAndExpressionHaveIdenticalCanonicalPoliciesAndRuntimeDomains() {
        for (String name : List.of("company", "!company")) for (boolean descendants : List.of(false, true)) {
            String expression = name + (descendants ? ".*" : "");
            var current = parse(policy(grant(expression, "")));
            var legacy = parse(policy(grant(name, "\"descendants\":" + descendants + ",")));
            assertEquals(current, legacy);
            String canonical = parser.canonicalJson(current);
            assertEquals(canonical, parser.canonicalJson(legacy));
            assertFalse(canonical.contains("descendants"));
            assertEquals(current, parse(canonical));
            var identity = new ApplicationGroupAuthorization.Identity("user", "https://issuer.example", "ui", "group", 1, Set.of("reader"), Set.of());
            var upper = current.applicationGroups().get("group");
            var directory = new ApplicationGroupAuthorization.Directory(DirectoryCatalogTestData.catalog(upper, 1), "group", Set.of());
            var modernAuth = new ApplicationGroupAuthorization(current);
            var legacyAuth = new ApplicationGroupAuthorization(legacy);
            for (String namespace : NAMES) {
                boolean selected = namespace.equals("company") || descendants && namespace.startsWith("company.");
                boolean expected = name.startsWith("!") ? !selected : selected;
                var point = new ApplicationGroupAuthorization.Request("main", namespace, "FLOW", "READ", "flow", false);
                assertEquals(expected, modernAuth.authorize(identity, point, directory).allowed(), expression + ":" + namespace);
                assertEquals(expected, legacyAuth.authorize(identity, point, directory).allowed());
            }
            for (var query : List.of(ApplicationGroupAuthorization.Domain.all(),
                new ApplicationGroupAuthorization.Domain(Set.of(), Set.of("company")),
                new ApplicationGroupAuthorization.Domain(Set.of("company2"), Set.of()))) {
                var request = new ApplicationGroupAuthorization.Request("main", null, "FLOW", "READ", null, true, query);
                var modernList = modernAuth.authorizeList(identity, request, directory);
                var legacyList = legacyAuth.authorizeList(identity, request, directory);
                assertEquals(legacyList, modernList);
                for (String namespace : NAMES) {
                    boolean selected = namespace.equals("company") || descendants && namespace.startsWith("company.");
                    boolean expected = (name.startsWith("!") ? !selected : selected) && query.contains(namespace);
                    assertEquals(expected, modernList.contains(namespace, "flow"), expression + ":" + namespace);
                    assertEquals(expected, modernList.domain().contains(namespace));
                }
            }
        }
    }

    @Test void mixedSuffixAndEitherLegacyBooleanAreRejectedEverywhere() throws Exception {
        for (String expression : List.of("company.*", "!company.*")) for (boolean flag : List.of(false, true)) {
            String json = grant(expression, "\"descendants\":" + flag + ",");
            assertThrows(IllegalArgumentException.class, () -> parse(policy(json)));
            assertThrows(Exception.class, () -> mapper.readValue(json, ApplicationGroupPolicy.Grant.class));
        }
    }

    @Test void rejectsDuplicateNullNonbooleanUnknownAndTenantExpressions() {
        String base = grant("company", "");
        for (String json : List.of(
            grant("company", "\"descendants\":null,"), grant("company", "\"descendants\":\"true\","),
            grant("company", "\"descendants\":1,"), grant("company", "\"descendants\":true,\"descendants\":false,"),
            base.replace("\"namespace\":\"company\"", "\"namespace\":null"),
            base.replace("\"namespace\":\"company\"", "\"namespace\":\"company\",\"namespace\":\"other\""),
            grant("company", "\"unknown\":true,"), grant("company.*", "").replace("NAMESPACE", "TENANT"),
            grant("!company.*", "").replace("NAMESPACE", "TENANT").replace("FLOW", "AI"))) {
            assertThrows(IllegalArgumentException.class, () -> parse(policy(json)), json);
        }
    }

    @Test void strictNestedJacksonRoundTripUsesExpressionsAndPreservesTenantGrants() throws Exception {
        for (String expression : List.of("company", "company.*", "!company", "!company.*")) {
            var value = parse(policy(grant(expression, "")));
            String json = mapper.writeValueAsString(value);
            assertFalse(json.contains("descendants"));
            assertEquals(value, mapper.readValue(json, ApplicationGroupPolicy.class));
        }
        var tenant = new ApplicationGroupPolicy.Grant("TENANT", "main", null, false, "AI", Set.of("READ", "EXECUTE"), Set.of(), true);
        String json = mapper.writeValueAsString(tenant);
        assertTrue(mapper.readTree(json).get("namespace").isNull());
        assertFalse(json.contains("descendants"));
        assertEquals(tenant, mapper.readValue(json, ApplicationGroupPolicy.Grant.class));
    }
}
