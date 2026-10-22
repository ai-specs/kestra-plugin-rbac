package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ApplicationGroupPolicyManagement {
    public interface DirectoryBoundary {
        void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate)throws java.sql.SQLException;
    }
    public static final class Missing extends RuntimeException {}
    public static final class Exists extends RuntimeException {}
    private final ApplicationGroupPolicyStore store;
    private final DirectoryBoundary directory;
    private final ApplicationGroupPolicyStore.Snapshot snapshot;
    private final ApplicationGroupPolicyParser parser=new ApplicationGroupPolicyParser();
    private final ObjectMapper mapper=new ObjectMapper();
    public ApplicationGroupPolicyManagement(ApplicationGroupPolicyStore store,DirectoryBoundary directory) {
        this(store,directory,null);
    }
    /** Request adapter supplies the one policy snapshot already used for authorization. */
    public ApplicationGroupPolicyManagement(ApplicationGroupPolicyStore store,DirectoryBoundary directory,ApplicationGroupPolicyStore.Snapshot snapshot) {
        this.store=Objects.requireNonNull(store);this.directory=Objects.requireNonNull(directory);this.snapshot=snapshot;
    }
    public ApplicationGroupPolicyStore.Snapshot createProject(long revision,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode projects=(ObjectNode)document.get("applicationGroups");
        String id=identifier(input,"id");if(projects.has(id))throw new Exists();
        ObjectNode project=input.deepCopy();project.remove("id");
        if(project.has("scopes"))throw new IllegalArgumentException("Create the local project before its scope bindings");
        project.putObject("scopes");projects.set(id,project);return save(revision,document,actor);
    }
    public ApplicationGroupPolicyStore.Snapshot updateProject(long revision,String id,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode existing=project(document,id);
        if(!identifier(input,"id").equals(id))throw new IllegalArgumentException("Project id is immutable");
        ObjectNode replacement=input.deepCopy();replacement.remove("id");
        if(replacement.has("scopes"))throw new IllegalArgumentException("Update scopes through their own endpoint");
        replacement.set("scopes",existing.get("scopes"));((ObjectNode)document.get("applicationGroups")).set(id,replacement);
        return save(revision,document,actor);
    }
    public ApplicationGroupPolicyStore.Snapshot createScope(long revision,String id,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        String name=identifier(input,"name");if(scopes.has(name))throw new Exists();
        ObjectNode scope=input.deepCopy();scope.remove("name");scopes.set(name,scope);return save(revision,document,actor);
    }
    public ApplicationGroupPolicyStore.Snapshot replaceScope(long revision,String id,String name,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        if(!scopes.has(name))throw new Missing();if(!identifier(input,"name").equals(name))throw new IllegalArgumentException("Scope name is immutable");
        ObjectNode scope=input.deepCopy();scope.remove("name");scopes.set(name,scope);return save(revision,document,actor);
    }
    public ApplicationGroupPolicyStore.Snapshot deleteScope(long revision,String id,String name,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        if(scopes.remove(name)==null)throw new Missing();return save(revision,document,actor);
    }
    private ApplicationGroupPolicyStore.Snapshot current() { return snapshot == null ? store.readCurrent() : snapshot; }
    private ObjectNode document(long revision) {
        var current=current();if(current.revision()!=revision)throw new ApplicationGroupPolicyStore.Conflict();
        try{return (ObjectNode)mapper.readTree(current.canonicalJson());}
        catch(java.io.IOException failure){throw new IllegalStateException("Invalid stored project policy",failure);}
    }
    private ObjectNode project(ObjectNode document,String id) {
        JsonNode value=document.path("applicationGroups").get(id);if(value==null||!value.isObject())throw new Missing();return (ObjectNode)value;
    }
    private ApplicationGroupPolicyStore.Snapshot save(long revision,ObjectNode document,String actor) {
        var previous=current();if(previous.revision()!=revision)throw new ApplicationGroupPolicyStore.Conflict();
        var candidate=parser.parse(document.toString().getBytes(StandardCharsets.UTF_8));
        return store.replace(revision,parser.canonicalJson(candidate).getBytes(StandardCharsets.UTF_8),actor,
            (connection,current,proposed)->directory.validate(connection,current.policy(),proposed));
    }
    private static String identifier(JsonNode input,String field) {
        if(input==null||!input.isObject()||!input.path(field).isTextual()||input.get(field).textValue().isBlank())throw new IllegalArgumentException("Identifier required");
        return input.get(field).textValue();
    }
}
