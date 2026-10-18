package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Local binding writes. The directory boundary is mandatory and is supplied by the published SPI adapter. */
public final class ProjectPolicyManagement {
    public interface DirectoryBoundary {
        /** Validate exact directory revision, trusted issuer/audiences, each scope and management/resource upper bound. */
        void validate(ProjectPolicy candidate);
        /** Check local management reachability before removing or narrowing an existing management binding. */
        void validateManagementChange(ProjectPolicy previous, ProjectPolicy candidate);
    }
    public static final class Missing extends RuntimeException {}
    public static final class Exists extends RuntimeException {}
    private final ProjectPolicyStore store;
    private final DirectoryBoundary directory;
    private final ProjectPolicyStore.Snapshot snapshot;
    private final ProjectPolicyParser parser=new ProjectPolicyParser();
    private final ObjectMapper mapper=new ObjectMapper();
    public ProjectPolicyManagement(ProjectPolicyStore store,DirectoryBoundary directory) {
        this(store,directory,null);
    }
    /** Request adapter supplies the one policy snapshot already used for authorization. */
    public ProjectPolicyManagement(ProjectPolicyStore store,DirectoryBoundary directory,ProjectPolicyStore.Snapshot snapshot) {
        this.store=Objects.requireNonNull(store);this.directory=Objects.requireNonNull(directory);this.snapshot=snapshot;
    }
    public ProjectPolicyStore.Snapshot createProject(long revision,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode projects=(ObjectNode)document.get("applicationProjects");
        String id=identifier(input,"id");if(projects.has(id))throw new Exists();
        ObjectNode project=input.deepCopy();project.remove("id");
        if(project.has("scopes"))throw new IllegalArgumentException("Create the local project before its scope bindings");
        project.putObject("scopes");projects.set(id,project);return save(revision,document,actor);
    }
    public ProjectPolicyStore.Snapshot updateProject(long revision,String id,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode existing=project(document,id);
        if(!identifier(input,"id").equals(id))throw new IllegalArgumentException("Project id is immutable");
        ObjectNode replacement=input.deepCopy();replacement.remove("id");
        if(replacement.has("scopes"))throw new IllegalArgumentException("Update scopes through their own endpoint");
        if(!existing.path("directoryRevision").equals(replacement.path("directoryRevision")))
            throw new IllegalArgumentException("Directory revision confirmation requires complete reconciliation");
        replacement.set("scopes",existing.get("scopes"));((ObjectNode)document.get("applicationProjects")).set(id,replacement);
        return save(revision,document,actor);
    }
    /** Replace one existing project's entire reviewed binding in one policy CAS.
     * The HTTP adapter must require current authorizeReconciliation authority for this exact project.
     * Candidate validation always uses the latest complete directory boundary; this method never
     * accepts a stale project by changing only its revision or silently truncating invalid grants.
     */
    public ProjectPolicyStore.Snapshot reconcileProject(long revision,String id,JsonNode input,String actor) {
        ObjectNode document=document(revision);project(document,id);
        if(!identifier(input,"id").equals(id))throw new IllegalArgumentException("Project id is immutable");
        if(!input.path("scopes").isObject())throw new IllegalArgumentException("Complete scope bindings required");
        ObjectNode replacement=input.deepCopy();replacement.remove("id");
        ((ObjectNode)document.get("applicationProjects")).set(id,replacement);
        return save(revision,document,actor);
    }
    public ProjectPolicyStore.Snapshot createScope(long revision,String id,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        String name=identifier(input,"name");if(scopes.has(name))throw new Exists();
        ObjectNode scope=input.deepCopy();scope.remove("name");scopes.set(name,scope);return save(revision,document,actor);
    }
    public ProjectPolicyStore.Snapshot replaceScope(long revision,String id,String name,JsonNode input,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        if(!scopes.has(name))throw new Missing();if(!identifier(input,"name").equals(name))throw new IllegalArgumentException("Scope name is immutable");
        ObjectNode scope=input.deepCopy();scope.remove("name");scopes.set(name,scope);return save(revision,document,actor);
    }
    public ProjectPolicyStore.Snapshot deleteScope(long revision,String id,String name,String actor) {
        ObjectNode document=document(revision);ObjectNode scopes=(ObjectNode)project(document,id).get("scopes");
        if(scopes.remove(name)==null)throw new Missing();return save(revision,document,actor);
    }
    private ProjectPolicyStore.Snapshot current() { return snapshot == null ? store.readCurrent() : snapshot; }
    private ObjectNode document(long revision) {
        var current=current();if(current.revision()!=revision)throw new ProjectPolicyStore.Conflict();
        try{return (ObjectNode)mapper.readTree(current.canonicalJson());}
        catch(java.io.IOException failure){throw new IllegalStateException("Invalid stored project policy",failure);}
    }
    private ObjectNode project(ObjectNode document,String id) {
        JsonNode value=document.path("applicationProjects").get(id);if(value==null||!value.isObject())throw new Missing();return (ObjectNode)value;
    }
    private ProjectPolicyStore.Snapshot save(long revision,ObjectNode document,String actor) {
        var previous=current();if(previous.revision()!=revision)throw new ProjectPolicyStore.Conflict();
        var candidate=parser.parse(document.toString().getBytes(StandardCharsets.UTF_8));
        directory.validate(candidate);directory.validateManagementChange(previous.policy(),candidate);
        return store.replace(revision,parser.canonicalJson(candidate).getBytes(StandardCharsets.UTF_8),actor);
    }
    private static String identifier(JsonNode input,String field) {
        if(input==null||!input.isObject()||!input.path(field).isTextual()||input.get(field).textValue().isBlank())throw new IllegalArgumentException("Identifier required");
        return input.get(field).textValue();
    }
}
