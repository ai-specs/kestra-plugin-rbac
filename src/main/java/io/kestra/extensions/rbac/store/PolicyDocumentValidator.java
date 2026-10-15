package io.kestra.extensions.rbac.store;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.PolicyEngine;
import io.kestra.extensions.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacAuthorizer.Action;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;
import io.kestra.webserver.rbac.RbacAuthorizer.Scope;

import static io.kestra.extensions.rbac.store.PolicyDocument.*;

public final class PolicyDocumentValidator {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_ROLES = 1000;
    public static final int MAX_GRANTS = 10000;
    public static final int MAX_BINDINGS = 10000;
    public static final int MAX_IDS = 1000;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public record ValidatedPolicy(PolicyDocument document, String canonicalJson, PolicyEngine engine) {}

    public ValidatedPolicy parse(byte[] json) {
        require(json != null && json.length <= MAX_BYTES, "策略 JSON 超过 1MiB 或为空。");
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString();
            JsonNode root;
            try (var parser = mapper.createParser(content)) {
                root = mapper.readTree(parser);
                require(parser.nextToken() == null, "策略只能包含一个 JSON 文档。");
            }
            fields(root, Set.of("schemaVersion", "roles", "subjects", "roleMapping"));
            require(root.path("schemaVersion").isIntegralNumber() && root.path("schemaVersion").canConvertToInt()
                && root.path("schemaVersion").intValue() == 1, "只支持 schemaVersion 1。");
            var roles = new ArrayList<Role>();
            for (JsonNode role : array(root.get("roles"), MAX_ROLES)) {
                fields(role, Set.of("id", "name", "description", "grants"));
                var grants = new ArrayList<Grant>();
                for (JsonNode grant : array(role.get("grants"), MAX_GRANTS)) {
                    fields(grant, Set.of("scope", "tenant", "namespace", "descendants", "resource", "actions", "ids", "allIds"));
                    var actions = strings(grant.get("actions"), Action.values().length).stream().map(Action::valueOf).toList();
                    var ids = grant.has("ids") ? strings(grant.get("ids"), MAX_IDS) : List.<String>of();
                    grants.add(new Grant(Scope.valueOf(text(grant.get("scope"))), optionalText(grant.get("tenant")),
                        optionalText(grant.get("namespace")), flag(grant, "descendants"), Kind.valueOf(text(grant.get("resource"))),
                        actions, ids, flag(grant, "allIds")));
                }
                roles.add(new Role(new RoleId(text(role.get("id"))), text(role.get("name")),
                    role.has("description") ? text(role.get("description")) : "", grants));
            }
            return validate(new PolicyDocument(1, roles, bindings(root.get("subjects")), bindings(root.get("roleMapping"))));
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("策略必须使用有效 UTF-8。", failure);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("策略 JSON 无效。", failure);
        }
    }

    public ValidatedPolicy validate(PolicyDocument document) {
        require(document != null && document.schemaVersion() == 1, "只支持 schemaVersion 1。");
        require(document.roles().size() <= MAX_ROLES, "角色超过 1000 个。");
        var roleIds = new HashSet<RoleId>();
        var names = new HashSet<String>();
        var roles = new ArrayList<Role>();
        int grantCount = 0;
        for (Role role : document.roles()) {
            require(role.id() != null && role.id().value() != null && role.id().value().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"), "角色 ID 无效。");
            concrete(role.name());
            require(role.description() != null, "角色说明不得为 null。");
            require(roleIds.add(role.id()) && names.add(role.name()), "角色 ID 或名称重复。");
            grantCount += role.grants().size();
            require(grantCount <= MAX_GRANTS, "总 grant 超过 10000 个。");
            var grants = new ArrayList<Grant>();
            for (Grant grant : role.grants()) {
                require(grant.scope() != null && grant.scope() != Scope.INSTANCE && grant.resource() != null && grant.resource() != Kind.SYSTEM,
                    "数据库策略不得授予实例管理权限。");
                require("main".equals(grant.tenant()), "数据库策略只支持 main tenant。");
                require(grant.ids().size() <= MAX_IDS, "单个 grant 的 ID 超过 1000 个。");
                require(new HashSet<>(grant.ids()).size() == grant.ids().size(), "grant 的 ID 重复。");
                require(new HashSet<>(grant.actions()).size() == grant.actions().size(), "grant 的 action 重复。");
                grants.add(new Grant(grant.scope(), grant.tenant(), grant.namespace(), grant.descendants(), grant.resource(),
                    grant.actions().stream().sorted().toList(), grant.ids().stream().sorted().toList(), grant.allIds()));
            }
            grants.sort(Comparator.comparing(grant -> grantNode(grant).toString()));
            roles.add(new Role(role.id(), role.name(), role.description(), grants));
        }
        checkBindings(document.subjects(), roleIds);
        checkBindings(document.roleMapping(), roleIds);
        roles.sort(Comparator.comparing(role -> role.id().value()));
        var normalized = new PolicyDocument(1, roles, normalizeBindings(document.subjects()), normalizeBindings(document.roleMapping()));
        var configuration = configuration(normalized);
        var engine = new PolicyEngine(configuration);
        String json = documentNode(normalized).toString();
        require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "规范策略 JSON 超过 1MiB。");
        return new ValidatedPolicy(normalized, json, engine);
    }

    private RbacConfiguration configuration(PolicyDocument document) {
        var configuration = new RbacConfiguration();
        var roles = new TreeMap<String, List<RbacConfiguration.Grant>>();
        for (Role role : document.roles()) {
            roles.put(role.id().value(), role.grants().stream().map(grant -> {
                var value = new RbacConfiguration.Grant();
                value.setScope(grant.scope());
                value.setTenant(grant.tenant());
                value.setNamespace(grant.namespace());
                value.setDescendants(grant.descendants());
                value.setResource(grant.resource());
                value.setActions(grant.actions());
                value.setIds(grant.ids());
                value.setAllIds(grant.allIds());
                return value;
            }).toList());
        }
        configuration.setRoles(roles);
        configuration.setSubjects(configurationBindings(document.subjects()));
        configuration.setRoleMapping(configurationBindings(document.roleMapping()));
        return configuration;
    }

    private Map<String, List<String>> configurationBindings(Map<String, List<RoleId>> bindings) {
        var result = new TreeMap<String, List<String>>();
        bindings.forEach((subject, identifiers) -> result.put(subject, identifiers.stream().map(RoleId::value).toList()));
        return result;
    }

    private Map<String, List<RoleId>> normalizeBindings(Map<String, List<RoleId>> bindings) {
        var result = new TreeMap<String, List<RoleId>>();
        bindings.forEach((subject, identifiers) -> result.put(subject, identifiers.stream().sorted(Comparator.comparing(RoleId::value)).toList()));
        return result;
    }

    private void checkBindings(Map<String, List<RoleId>> bindings, Set<RoleId> roleIds) {
        require(bindings.size() <= MAX_BINDINGS, "subject 或 mapping 超过 10000 个。");
        bindings.forEach((subject, identifiers) -> {
            concrete(subject);
            require(identifiers.size() <= MAX_ROLES && new HashSet<>(identifiers).size() == identifiers.size(), "角色引用重复或过多。");
            require(roleIds.containsAll(identifiers), "绑定引用了不存在的角色 ID。");
        });
    }

    private Map<String, List<RoleId>> bindings(JsonNode node) {
        require(node != null && node.isObject() && node.size() <= MAX_BINDINGS, "绑定必须是最多 10000 项的对象。");
        var result = new TreeMap<String, List<RoleId>>();
        node.properties().forEach(entry -> result.put(entry.getKey(), strings(entry.getValue(), MAX_ROLES).stream().map(RoleId::new).toList()));
        return result;
    }

    private ObjectNode documentNode(PolicyDocument document) {
        var root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        var roles = root.putArray("roles");
        for (Role role : document.roles()) {
            var node = roles.addObject().put("id", role.id().value()).put("name", role.name()).put("description", role.description());
            var grants = node.putArray("grants");
            role.grants().forEach(grant -> grants.add(grantNode(grant)));
        }
        writeBindings(root.putObject("subjects"), document.subjects());
        writeBindings(root.putObject("roleMapping"), document.roleMapping());
        return root;
    }

    private void writeBindings(ObjectNode node, Map<String, List<RoleId>> bindings) {
        bindings.forEach((subject, identifiers) -> {
            var values = node.putArray(subject);
            identifiers.forEach(identifier -> values.add(identifier.value()));
        });
    }

    private ObjectNode grantNode(Grant grant) {
        var node = mapper.createObjectNode();
        node.put("scope", grant.scope().name());
        node.put("tenant", grant.tenant());
        node.put("namespace", grant.namespace());
        node.put("descendants", grant.descendants());
        node.put("resource", grant.resource().name());
        var actions = node.putArray("actions");
        grant.actions().forEach(action -> actions.add(action.name()));
        var ids = node.putArray("ids");
        grant.ids().forEach(ids::add);
        node.put("allIds", grant.allIds());
        return node;
    }

    private void fields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject(), "必须提供 JSON 对象。");
        node.fieldNames().forEachRemaining(name -> require(allowed.contains(name), "策略包含未知或禁止的字段。"));
    }

    private JsonNode array(JsonNode node, int maximum) {
        require(node != null && node.isArray() && node.size() <= maximum, "数组缺失或超过限额。");
        return node;
    }

    private List<String> strings(JsonNode node, int maximum) {
        var result = new ArrayList<String>();
        array(node, maximum).forEach(value -> result.add(text(value)));
        return result;
    }

    private String text(JsonNode node) {
        require(node != null && node.isTextual(), "字段必须是字符串。");
        return node.textValue();
    }

    private String optionalText(JsonNode node) { return node == null || node.isNull() ? null : text(node); }

    private boolean flag(JsonNode node, String name) {
        if (!node.has(name)) return false;
        require(node.get(name).isBoolean(), "字段必须是布尔值。");
        return node.get(name).booleanValue();
    }

    static void concrete(String value) {
        require(value != null && !value.isBlank() && value.equals(value.trim()) && !value.contains("*"), "必须提供精确非空名称。");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
