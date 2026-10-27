package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

public final class ApplicationPermissionUpgrade {
    public record DeclarationMapping(String groupId,String clientId,String scopeKey,String permissionKey,String targetKey) {}
    public record RuntimeMapping(String groupId,String scopeKey,String targetKey) {}
    public record UnapprovedDecision(String groupId,String clientId,String decision) {
        public UnapprovedDecision {if(!"DROP_SUPPORT".equals(decision))throw new IllegalArgumentException("Explicit DROP_SUPPORT decision required");}
    }
    public record Plan(int formatVersion,String sourceHash,List<ApplicationGroupDirectory.Snapshot> applicationGroups,
                       List<DeclarationMapping> declarationMappings,List<RuntimeMapping> runtimeMappings,List<UnapprovedDecision> approvedFalseDecisions) {
        public Plan(int formatVersion,String sourceHash,List<ApplicationGroupDirectory.Snapshot> applicationGroups,
            List<DeclarationMapping> declarationMappings,List<RuntimeMapping> runtimeMappings) {
            this(formatVersion,sourceHash,applicationGroups,declarationMappings,runtimeMappings,List.of());
        }
        public Plan {
            if(formatVersion!=1 || sourceHash==null || !sourceHash.matches("[a-f0-9]{64}") || applicationGroups==null
                || declarationMappings==null || runtimeMappings==null || approvedFalseDecisions==null)throw new IllegalArgumentException("Invalid declaration upgrade plan");
            applicationGroups=List.copyOf(applicationGroups);declarationMappings=List.copyOf(declarationMappings);runtimeMappings=List.copyOf(runtimeMappings);
            approvedFalseDecisions=List.copyOf(approvedFalseDecisions);
        }
    }
    public record Review(String sourceHash,String approvalHash,List<String> conflicts,Plan plan) {
        public Review {conflicts=List.copyOf(conflicts);}
    }
    public record Source(List<JsonNode> groups,ApplicationGroupPolicy policy,long policyRevision,List<ApplicationScopeDeclarationStore.StoredScope> declarations,String hash) {
        public Source {groups=List.copyOf(groups);declarations=List.copyOf(declarations);}
    }
    private final ApplicationGroupDirectoryStore directory;
    private final ApplicationGroupPolicyStore policies;
    private final ApplicationScopeDeclarationStore declarations;
    private final ApplicationGroupDirectoryService service;
    private final ObjectMapper mapper=new ObjectMapper();

    public ApplicationPermissionUpgrade(ApplicationGroupDirectoryStore directory,ApplicationGroupPolicyStore policies,
        ApplicationScopeDeclarationStore declarations,ApplicationGroupDirectoryService service) {
        this.directory=directory;this.policies=policies;this.declarations=declarations;this.service=service;
    }

    public Source source(Connection connection)throws SQLException {
        var groups=new ArrayList<JsonNode>();var stored=new ArrayList<ApplicationScopeDeclarationStore.StoredScope>();
        try(var statement=connection.prepareStatement("SELECT document_json FROM rbac_application_group_state ORDER BY group_id");var rows=statement.executeQuery()) {
            while(rows.next()) {
                var group=parse(rows.getString(1));groups.add(group);
                if(present(connection,"rbac_application_scope_declaration"))for(var application:group.path("applications"))
                    stored.addAll(declarations.list(connection,group.at("/group/id").asText(),application.path("clientId").asText()));
            }
        }
        var policySnapshot=policies.readCurrent(connection);var policy=policySnapshot.policy();
        var payload=mapper.createObjectNode();payload.set("groups",mapper.valueToTree(groups));payload.set("policy",mapper.valueToTree(policy));
        payload.put("policyRevision",policySnapshot.revision());
        payload.set("declarations",mapper.valueToTree(stored));
        return new Source(groups,policy,policySnapshot.revision(),stored,hash(payload));
    }

    public Source source() {return directory.transaction(this::source);}

    public Review preview(Plan plan) {return directory.transaction(connection->preview(connection,plan));}

    public Review preview(Connection connection,Plan plan)throws SQLException {
        var source=source(connection);var conflicts=new ArrayList<String>();
        if(!source.hash().equals(plan.sourceHash()))conflicts.add("source_changed");
        var targets=new TreeMap<String,ApplicationGroupDirectory.Snapshot>();
        for(var target:plan.applicationGroups())if(targets.put(target.group().id(),target)!=null)conflicts.add("duplicate_group");
        var sourceIds=new HashSet<String>();var seenDecisions=new HashSet<UnapprovedDecision>();
        for(var old:source.groups()) {
            String groupId=old.at("/group/id").asText();sourceIds.add(groupId);var target=targets.get(groupId);
            if(target==null){conflicts.add("missing_group:"+groupId);continue;}
            if(old.at("/group/revision").asLong()+1!=target.group().revision()
                || old.at("/group/authorizationVersion").asLong()+1!=target.group().authorizationVersion()
                || old.at("/group/active").asBoolean()!=target.group().active()
                || !old.at("/group/displayName").asText().equals(target.group().displayName())
                || !hash(old.path("scopes")).equals(hash(mapper.valueToTree(target.scopes())))
                || !hash(old.path("userGrants")).equals(hash(mapper.valueToTree(target.userGrants()))))conflicts.add("directory_changed:"+groupId);
            var clients=new HashSet<String>();
            for(var oldApplication:old.path("applications")) {
                String clientId=oldApplication.path("clientId").asText();clients.add(clientId);
                var application=target.applications().stream().filter(value->value.clientId().equals(clientId)).findFirst();
                if(application.isEmpty()){conflicts.add("missing_application:"+groupId+":"+clientId);continue;}
                var next=application.get();Set<String> support=strings(oldApplication.path("supportedScopeKeys"));
                Set<String> audiences=strings(oldApplication.path("audiences"));
                var oldPolicy=source.policy().applicationGroups().get(groupId);
                if(service.kind(clientId)==ApplicationPermission.Kind.KESTRA_POLICY && oldPolicy!=null) {
                    var knownAudiences=new HashSet<String>();old.path("applications").forEach(value->knownAudiences.addAll(strings(value.path("audiences"))));
                    if(!oldPolicy.audiences().containsAll(audiences) || !knownAudiences.containsAll(oldPolicy.audiences()))
                        conflicts.add("legacy_policy_audience_unregistered:"+groupId);
                    audiences=oldPolicy.audiences();
                }
                if(!oldApplication.path("approved").asBoolean()) {
                    var decisions=plan.approvedFalseDecisions().stream().filter(value->groupId.equals(value.groupId()) && clientId.equals(value.clientId())).toList();
                    if(decisions.size()!=1)conflicts.add("approved_false_decision_required:"+groupId+":"+clientId);
                    else seenDecisions.add(decisions.getFirst());
                    support=Set.of();
                }
                if(!support.equals(next.supportedScopeKeys()) || !audiences.equals(next.audiences())
                    || oldApplication.path("revision").asLong()+1!=next.revision())conflicts.add("support_or_application_changed:"+groupId+":"+clientId);
                var scopes=target.scopes().stream().map(ApplicationGroupDirectory.Scope::key).collect(java.util.stream.Collectors.toSet());
                try {for(var permission:next.permissions()) {
                    permission.validate(service.kind(clientId),scopes);
                    if(permission.revision()!=1)throw new IllegalArgumentException("Initial permission revision required");
                }}catch(IllegalArgumentException failure){conflicts.add("invalid_permission:"+groupId+":"+clientId);}
            }
            if(!clients.equals(target.applications().stream().map(ApplicationGroupDirectory.Application::clientId).collect(java.util.stream.Collectors.toSet())))
                conflicts.add("application_membership_changed:"+groupId);
        }
        if(!sourceIds.equals(targets.keySet()))conflicts.add("group_membership_changed");
        if(seenDecisions.size()!=plan.approvedFalseDecisions().size())conflicts.add("unknown_or_duplicate_approved_false_decision");
        validateMappings(source,plan,targets,conflicts);
        if(conflicts.isEmpty())try {service.requireAdministrators(connection,plan.applicationGroups(),service.projectPolicy(plan.applicationGroups()),null,null);}
        catch(RuntimeException failure){conflicts.add("administrator_unreachable");}
        return new Review(source.hash(),hash(mapper.valueToTree(plan)),conflicts,plan);
    }

    private void validateMappings(Source source,Plan plan,TreeMap<String,ApplicationGroupDirectory.Snapshot> targets,List<String> conflicts) {
        var seenDeclarations=new HashSet<DeclarationMapping>();
        for(var scope:source.declarations())for(var declaration:scope.permissions()) {
            var matches=plan.declarationMappings().stream().filter(value->value.groupId().equals(scope.applicationGroupId())
                && value.clientId().equals(scope.clientId()) && value.scopeKey().equals(scope.scopeKey()) && value.permissionKey().equals(declaration.key())).toList();
            if(matches.size()!=1){conflicts.add("declaration_mapping_required:"+scope.applicationGroupId()+":"+scope.clientId()+":"+scope.scopeKey()+":"+declaration.key());continue;}
            var mapping=matches.getFirst();seenDeclarations.add(mapping);
            var permission=permission(targets,mapping.groupId(),mapping.clientId(),mapping.targetKey());
            if(permission==null || !permission.name().equals(declaration.name()))conflicts.add("declaration_mapping_conflict:"+mapping.targetKey());
            else {
                boolean approved=source.groups().stream().filter(value->value.at("/group/id").asText().equals(mapping.groupId()))
                    .flatMap(value->{var items=new ArrayList<JsonNode>();value.path("applications").forEach(items::add);return items.stream();})
                    .filter(value->value.path("clientId").asText().equals(mapping.clientId())).allMatch(value->value.path("approved").asBoolean());
                if(approved && !permission.scopeKeys().contains(scope.scopeKey()))conflicts.add("declaration_scope_lost:"+mapping.targetKey());
            }
        }
        if(seenDeclarations.size()!=plan.declarationMappings().size())conflicts.add("unknown_or_duplicate_declaration_mapping");
        var seenRuntime=new HashSet<RuntimeMapping>();
        for(var group:source.policy().applicationGroups().entrySet())for(var scope:group.getValue().scopes().entrySet()) {
            var mappings=plan.runtimeMappings().stream().filter(value->value.groupId().equals(group.getKey()) && value.scopeKey().equals(scope.getKey())).toList();
            if(mappings.size()!=1){conflicts.add("runtime_mapping_required:"+group.getKey()+":"+scope.getKey());continue;}
            var mapping=mappings.getFirst();seenRuntime.add(mapping);var target=targets.get(group.getKey());
            var application=target==null?null:target.applications().stream().filter(value->service.kind(value.clientId())==ApplicationPermission.Kind.KESTRA_POLICY).findFirst().orElse(null);
            var permission=application==null?null:permission(targets,group.getKey(),application.clientId(),mapping.targetKey());
            var old=scope.getValue();
            if(permission==null || !permission.grants().equals(old.grants()) || !permission.managementGrants().equals(old.managementGrants())
                || permission.enabled()!=(group.getValue().enabled() && old.enabled())
                || application.supportedScopeKeys().contains(scope.getKey()) && !permission.scopeKeys().contains(scope.getKey()))
                conflicts.add("runtime_mapping_conflict:"+group.getKey()+":"+scope.getKey());
        }
        if(seenRuntime.size()!=plan.runtimeMappings().size())conflicts.add("unknown_or_duplicate_runtime_mapping");
        for(var target:targets.values())for(var application:target.applications()) {
            if(service.kind(application.clientId())!=ApplicationPermission.Kind.KESTRA_POLICY)continue;
            var projected=ApplicationPermission.project(application.permissions(),ApplicationPermission.Kind.KESTRA_POLICY);
            var old=source.policy().applicationGroups().get(target.group().id());
            var projectedGroup=service.projectPolicy(List.of(target)).applicationGroups().get(target.group().id());
            if(old!=null && (!old.issuer().equals(projectedGroup.issuer()) || !old.audiences().equals(projectedGroup.audiences())))
                conflicts.add("policy_identity_boundary_changed:"+target.group().id());
            var expected=new TreeMap<String,ApplicationGroupPolicy.ScopeDefinition>();
            if(old!=null && old.enabled())old.scopes().forEach((scope,definition)->{
                if(definition.enabled() && application.supportedScopeKeys().contains(scope))expected.put(scope,
                    new ApplicationGroupPolicy.ScopeDefinition("",true,definition.grants().stream().distinct().toList(),definition.managementGrants().stream().distinct().toList()));
            });
            if(!expected.equals(projected))conflicts.add("effective_policy_changed:"+target.group().id());
        }
    }

    public boolean apply(Plan plan,String approvalHash) {return directory.transaction(connection->apply(connection,plan,approvalHash,()->{}));}

    public boolean apply(Connection connection,Plan plan,String approvalHash,Runnable beforeCommit)throws SQLException {
        if(!hash(mapper.valueToTree(plan)).equals(approvalHash))throw new IllegalArgumentException("Reviewed upgrade hash required");
        ManagementAuditStore.initializeSchema(connection);
        try(var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS rbac_permission_upgrade_receipt (approval_hash VARCHAR(64) PRIMARY KEY, source_hash VARCHAR(64) NOT NULL, target_hash VARCHAR(64) NOT NULL)");
        }
        try(var statement=connection.prepareStatement("SELECT target_hash FROM rbac_permission_upgrade_receipt WHERE approval_hash=?")) {
            statement.setString(1,approvalHash);try(var rows=statement.executeQuery()) {
                if(rows.next()) {
                    if(!rows.getString(1).equals(currentTargetHash(connection)))throw new ApplicationGroupDirectoryStore.Conflict();
                    return false;
                }
            }
        }
        var review=preview(connection,plan);
        if(!review.conflicts().isEmpty())throw new IllegalArgumentException("Unresolved declaration upgrade conflicts: "+String.join(",",review.conflicts()));
        for(var target:plan.applicationGroups()) {
            var group=target.group();
            try(var statement=connection.prepareStatement("UPDATE rbac_application_group_state SET revision=?,authorization_version=?,document_json=? WHERE group_id=? AND revision=?")) {
                statement.setLong(1,group.revision());statement.setLong(2,group.authorizationVersion());statement.setString(3,json(target));statement.setString(4,group.id());statement.setLong(5,group.revision()-1);
                if(statement.executeUpdate()!=1)throw new ApplicationGroupDirectoryStore.Conflict();
            }
            try(var statement=connection.prepareStatement("UPDATE rbac_application_group_invalidation SET authorization_version=? WHERE group_id=?")) {
                statement.setLong(1,group.authorizationVersion());statement.setString(2,group.id());if(statement.executeUpdate()!=1)throw new ApplicationGroupDirectoryStore.Conflict();
            }
        }
        if(present(connection,"rbac_application_scope_declaration"))try(var statement=connection.createStatement()){statement.executeUpdate("DELETE FROM rbac_application_scope_declaration");}
        try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE rbac_application_group_bootstrap SET catalog_revision=catalog_revision+1 WHERE singleton_id=1");}
        try(var statement=connection.prepareStatement("INSERT INTO rbac_permission_upgrade_receipt(approval_hash,source_hash,target_hash) VALUES(?,?,?)")) {
            statement.setString(1,approvalHash);statement.setString(2,plan.sourceHash());statement.setString(3,currentTargetHash(connection));statement.executeUpdate();
        }
        beforeCommit.run();return true;
    }

    private String currentTargetHash(Connection connection)throws SQLException {return hash(mapper.valueToTree(directory.readAll(connection)));}
    private static ApplicationPermission permission(TreeMap<String,ApplicationGroupDirectory.Snapshot> targets,String group,String client,String key) {
        var snapshot=targets.get(group);if(snapshot==null)return null;
        return snapshot.applications().stream().filter(value->value.clientId().equals(client)).flatMap(value->value.permissions().stream())
            .filter(value->value.key().equals(key)).findFirst().orElse(null);
    }
    private static Set<String> strings(JsonNode node) {
        if(!node.isArray())throw new IllegalArgumentException("Legacy scope array required");
        var result=new HashSet<String>();node.forEach(value->{if(!value.isTextual() || !result.add(value.textValue()))throw new IllegalArgumentException("Invalid legacy set");});return Set.copyOf(result);
    }
    private static boolean present(Connection connection,String table)throws SQLException {
        try(var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next())if(table.equalsIgnoreCase(tables.getString("TABLE_NAME")))return true;return false;
        }
    }
    private JsonNode parse(String text) {
        try{return mapper.readTree(text);}catch(java.io.IOException failure){throw new IllegalArgumentException("Invalid upgrade source",failure);}
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(java.io.IOException failure){throw new IllegalArgumentException("Invalid upgrade target",failure);}
    }
    private String hash(JsonNode node) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(canonical(node)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            var result=mapper.createObjectNode();var keys=new java.util.TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key->result.set(key,canonical(node.get(key))));return result;
        }
        if(node.isArray()) {
            var values=new ArrayList<JsonNode>();node.forEach(value->values.add(canonical(value)));
            values.sort(java.util.Comparator.comparing(JsonNode::toString));var result=mapper.createArrayNode();values.forEach(result::add);return result;
        }
        return node;
    }
}
