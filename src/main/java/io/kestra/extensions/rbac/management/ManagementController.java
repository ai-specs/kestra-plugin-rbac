package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.ProjectRuntimePolicies;
import io.kestra.extensions.rbac.project.ProjectPolicy;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import static io.kestra.extensions.rbac.management.ManagementInput.*;
import static io.kestra.extensions.rbac.project.ProjectPolicy.ManagementDomain.*;
import static io.kestra.extensions.rbac.project.ProjectPolicy.ManagementAction.*;

/** Schema 2 only. Host authentication and CSRF filters run before this controller. */
@Controller("/api/v1/{tenant}/extensions/rbac")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.typed.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
public final class ManagementController {
    private final ProjectRuntimePolicies policies;
    private final ProjectPolicyStore store;
    private final BeanProvider<UserDirectoryAccess> users;
    private final BeanProvider<ApplicationDirectoryAccess> applications;
    private final ManagementInput input=new ManagementInput();
    private final ObjectMapper mapper=new ObjectMapper();
    public ManagementController(ProjectRuntimePolicies policies,ProjectPolicyStore store,
        BeanProvider<UserDirectoryAccess> users,BeanProvider<ApplicationDirectoryAccess> applications) {
        this.policies=policies;this.store=store;this.users=users;this.applications=applications;
    }
    private HttpResponse<?> request(String tenant,Authentication authentication,Function<ProjectRuntimePolicies.Context,HttpResponse<?>> action) {
        try {
            if (!"main".equals(tenant)) throw new Failure(404,"not_found");
            if (authentication==null) throw new Failure(401,"authentication_required");
            try (var context=policies.open(authentication,tenant)) { return action.apply(context); }
        } catch (Failure failure) { return error(failure.status,failure.code); }
        catch (ProjectRuntimePolicies.Rejected failure) { return runtimeFailure(failure); }
        catch (ProjectPolicyStore.Conflict failure) { return error(412,"revision_conflict"); }
        catch (ProjectPolicyStore.Unavailable failure) { return error(503,"policy_unavailable"); }
        catch (ProjectPolicyManagement.Missing failure) { return error(404,"not_found"); }
        catch (ProjectPolicyManagement.Exists failure) { return error(409,"already_exists"); }
        catch (ApplicationDirectoryAccess.DirectoryFailure failure) { return error(failure.status(),failure.code()); }
        catch (UserDirectoryAccess.DirectoryFailure failure) {
            return switch (failure.failure()) {
                case INVALID -> error(400,"invalid_request");
                case NOT_FOUND -> error(404,"not_found");
                case CONFLICT -> error(409,"directory_conflict");
                case FORBIDDEN -> error(403,"access_denied");
                case LAST_ADMIN -> error(409,"last_administrator");
                case UNAVAILABLE -> error(503,"directory_unavailable");
            };
        } catch (IllegalArgumentException failure) { return error(400,"invalid_request"); }
        catch (Exception failure) { return error(500,"management_failed"); }
    }
    private HttpResponse<?> runtimeFailure(ProjectRuntimePolicies.Rejected failure) {
        // Enum names are stable failure categories; no internal exception text crosses HTTP.
        return switch (failure.failure().name()) {
            case "INVALID_IDENTITY" -> error(403,"invalid_identity");
            case "DIRECTORY_UNAVAILABLE","INVALID_DIRECTORY" -> error(503,"directory_unavailable");
            case "POLICY_UNAVAILABLE" -> error(503,"policy_unavailable");
            case "FORBIDDEN" -> error(403,"access_denied");
            case "INVALID_CANDIDATE" -> error(400,"invalid_request");
            case "LAST_ADMIN" -> error(409,"last_administrator");
            default -> error(500,"management_failed");
        };
    }
    private HttpResponse<?> error(int status,String code) { return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code",code)).header("Cache-Control","no-store"); }
    private io.micronaut.http.MutableHttpResponse<?> ok(Object value) { return HttpResponse.ok(value).header("Cache-Control","no-store"); }
    private void local(ProjectRuntimePolicies.Context context,ProjectPolicy.ManagementAction action,String target) {
        if (!context.authorizeManagement(LOCAL_POLICY,action,project(target))) throw new Failure(403,"access_denied");
    }
    private void identity(ProjectRuntimePolicies.Context context,ProjectPolicy.ManagementAction action) {
        if (!context.authorizeManagement(IDENTITY,action,context.identity().projectId())) throw new Failure(403,"access_denied");
    }
    private UserDirectoryAccess users() { if (!users.isUnique()) throw new Failure(503,"directory_unavailable");return users.get(); }
    private ApplicationDirectoryAccess applications() { if (!applications.isUnique()) throw new Failure(503,"directory_unavailable");return applications.get(); }
    private ProjectPolicyManagement service(ProjectRuntimePolicies.Context context) {
        return new ProjectPolicyManagement(store,new ProjectPolicyManagement.DirectoryBoundary() {
            public void validate(ProjectPolicy candidate) { context.validateCandidate(candidate); }
            public void validateManagementChange(ProjectPolicy previous,ProjectPolicy candidate) { /* included in validateCandidate */ }
        },context.snapshot());
    }
    private HttpResponse<?> saved(ProjectPolicyStore.Snapshot snapshot,String id) {
        // MANAGE does not imply READ. Return exactly the changed target, never the other projects.
        var policy=mapper.createObjectNode().put("schemaVersion",2);
        policy.putObject("applicationProjects").set(id,mapper.valueToTree(snapshot.policy().applicationProjects().get(id)));
        return ok(Map.of("mode","database","readOnly",false,"revision",snapshot.revision(),"policy",policy))
            .header("ETag","\""+snapshot.revision()+"\"");
    }
    @Get("/capabilities")
    public HttpResponse<?> capabilities(String tenant,Authentication authentication) {
        return request(tenant,authentication,context -> {
            String target=context.identity().projectId();
            boolean ir=context.authorizeManagement(IDENTITY,READ,target), im=context.authorizeManagement(IDENTITY,MANAGE,target);
            var targets=context.managementTargets();
            boolean lr=targets.stream().anyMatch(id->context.authorizeManagement(LOCAL_POLICY,READ,id));
            boolean lm=targets.stream().anyMatch(id->context.authorizeManagement(LOCAL_POLICY,MANAGE,id));
            boolean recovery=targets.stream().anyMatch(context::authorizeReconciliation);
            var capabilities=new java.util.LinkedHashMap<String,Object>();
            capabilities.put("allowed",ir||im||lr||lm||recovery);capabilities.put("subject",context.identity().subject());
            capabilities.put("identityManagement",im);capabilities.put("localPolicyManagement",lm||recovery);
            capabilities.put("localPolicyManage",lm);capabilities.put("reconciliationAllowed",recovery);
            capabilities.put("identityRead",ir);capabilities.put("localPolicyRead",lr);
            capabilities.put("needsReconciliation",recovery&&!lm);capabilities.put("userDirectory",users.isUnique());
            capabilities.put("mode","database");capabilities.put("readOnly",false);return ok(capabilities);
        });
    }
    @Get("/policy")
    public HttpResponse<?> policy(String tenant,Authentication authentication) {
        return request(tenant,authentication,context -> ok(Map.of("mode","database","readOnly",false,"revision",context.snapshot().revision(),"policy",context.visiblePolicy())).header("ETag","\""+context.snapshot().revision()+"\""));
    }
    @Get("/projects")
    public HttpResponse<?> projects(String tenant,Authentication authentication) { return policy(tenant,authentication); }
    @Post("/projects") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createProject(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);var node=input.body(bytes,Set.of("id","issuer","audiences","enabled","directoryRevision"));
            String id=project(text(node,"id"));local(context,MANAGE,id);
            return saved(service(context).createProject(rev,node,context.identity().subject()),id);
        });
    }
    @Put("/projects/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateProject(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);var node=input.body(bytes,Set.of("id","issuer","audiences","enabled","directoryRevision"));same(project(id),node,"id");local(context,MANAGE,id);
            return saved(service(context).updateProject(rev,id,node,context.identity().subject()),id);
        });
    }
    @Get("/projects/{id}/scopes")
    public HttpResponse<?> scopes(String tenant,String id,Authentication authentication) {
        return request(tenant,authentication,context -> {local(context,READ,id);return ok(context.visibleProject(id).scopes());});
    }
    @Get("/projects/{id}/scopes/{key}")
    public HttpResponse<?> scope(String tenant,String id,String key,Authentication authentication) {
        return request(tenant,authentication,context -> {
            local(context,READ,id);String name=opaque(key);var value=context.visibleProject(id).scopes().get(name);
            if(value==null)throw new Failure(404,"not_found");return ok(value);
        });
    }
    @Post("/projects/{id}/scopes") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createScope(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);var node=scopeBody(bytes);local(context,MANAGE,id);
            return saved(service(context).createScope(rev,id,node,context.identity().subject()),id);
        });
    }
    private JsonNode scopeBody(byte[] bytes) { return input.body(bytes,Set.of("name","description","enabled","grants"),Set.of("managementGrants")); }
    @Put("/projects/{id}/scopes/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateScope(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);String name=opaque(key);var node=scopeBody(bytes);same(name,node,"name");local(context,MANAGE,id);
            return saved(service(context).replaceScope(rev,id,name,node,context.identity().subject()),id);
        });
    }
    @Delete("/projects/{id}/scopes/{key}")
    public HttpResponse<?> deleteScope(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);String name=opaque(key);local(context,MANAGE,id);
            return saved(service(context).deleteScope(rev,id,name,context.identity().subject()),id);
        });
    }
    @Get("/projects/{id}/binding-options")
    public HttpResponse<?> bindingOptions(String tenant,String id,Authentication authentication) {
        return request(tenant,authentication,context -> {local(context,MANAGE,id);return ok(projection(context,id,false));});
    }
    @Get("/projects/{id}/reconciliation")
    public HttpResponse<?> reconciliation(String tenant,String id,Authentication authentication) {
        return request(tenant,authentication,context -> {
            if(!context.authorizeReconciliation(project(id)))throw new Failure(403,"access_denied");
            return ok(projection(context,id,true));
        });
    }
    private JsonNode projection(ProjectRuntimePolicies.Context context,String id,boolean recovery) {
        // Explicit whitelist. PolicyBoundary may also contain reachability and client data: never serialize it.
        JsonNode raw=mapper.valueToTree(context.upperBound(id));JsonNode shared=raw.path("project");
        var result=mapper.createObjectNode();result.put("policyRevision",context.snapshot().revision());
        if(!shared.path("revision").isIntegralNumber())throw new Failure(503,"directory_unavailable");
        result.put("directoryRevision",shared.path("revision").longValue());
        var upper=result.putObject("upperBound");var p=upper.putObject("project");
        copy(shared,p,"id","issuer","revision");
        var bound=context.directories().get(id);if(bound==null)throw new Failure(409,"binding_target_incomplete");
        p.set("audiences",mapper.valueToTree(bound.upperBound().audiences()));p.put("enabled",bound.upperBound().enabled());
        var scopes=upper.putArray("scopes");
        if(!raw.path("scopes").isArray())throw new Failure(503,"directory_unavailable");
        for(JsonNode value:raw.path("scopes")) {
            var s=scopes.addObject();copy(value,s,"scope","description","active","resourceIntents","managementIntents");
        }
        if(recovery) {
            var local=context.snapshot().policy().applicationProjects().get(id);if(local==null)throw new Failure(404,"not_found");
            ObjectNode value=mapper.valueToTree(local);value.put("id",id);result.set("project",value);
        }
        return result;
    }
    private void copy(JsonNode from,ObjectNode to,String... fields) {
        for(String field:fields) { if(!from.hasNonNull(field))throw new Failure(503,"directory_unavailable");to.set(field,from.get(field)); }
    }
    @Put("/projects/{id}/reconciliation") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> reconcileProject(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);var node=input.body(bytes,Set.of("id","issuer","audiences","enabled","directoryRevision","scopes"));same(project(id),node,"id");
            if(!context.authorizeReconciliation(id))throw new Failure(403,"access_denied");
            return saved(service(context).reconcileProject(rev,id,node,context.identity().subject()),id);
        });
    }
    @Get("/users{?page,size}")
    public HttpResponse<?> users(String tenant,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="20") int size) {
        return request(tenant,authentication,context -> {identity(context,READ);page(page,size);return ok(users().list(page,size));});
    }
    @Get("/users/detail{?subject}")
    public HttpResponse<?> user(String tenant,Authentication authentication,@QueryValue String subject) {
        return request(tenant,authentication,context -> {identity(context,READ);return ok(users().find(subject(subject)).orElseThrow(()->new Failure(404,"not_found")));});
    }
    @Post("/users") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createUser(String tenant,Authentication authentication,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            var node=input.body(bytes,Set.of("username","name","email","password"));identity(context,MANAGE);
            return HttpResponse.created(users().create(subject(text(node,"username")),text(node,"name"),text(node,"email"),text(node,"password"))).header("Cache-Control","no-store");
        });
    }
    private HttpResponse<?> account(Supplier<UserDirectoryAccess.User> operation) {
        try {return ok(operation.get());}catch(UserDirectoryAccess.DirectoryFailure failure) {
            if(failure.failure()==UserDirectoryAccess.Failure.CONFLICT)throw new Failure(412,"revision_conflict");throw failure;
        }
    }
    @Put("/users/profile") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> profile(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","name","email"));identity(context,MANAGE);
            return account(()->users().update(subject(text(n,"subject")),rev,text(n,"name"),text(n,"email")));});
    }
    @Put("/users/state") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> state(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","active"));identity(context,MANAGE);
            return account(()->users().setActive(subject(text(n,"subject")),rev,bool(n,"active")));});
    }
    @Put("/users/password") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> password(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","password"));identity(context,MANAGE);
            return account(()->users().resetPassword(subject(text(n,"subject")),rev,text(n,"password")));});
    }
    private HttpResponse<?> directory(ProjectRuntimePolicies.Context context,ApplicationDirectoryAccess.Resource resource,
        ApplicationDirectoryAccess.Operation operation,String id,String key,int page,int size,Long revision,JsonNode body) {
        boolean read=operation==ApplicationDirectoryAccess.Operation.LIST || operation==ApplicationDirectoryAccess.Operation.FIND;
        identity(context,read?READ:MANAGE);
        if(id!=null) {
            project(id);
            // IDENTITY is issuer-wide, never cross issuer. Check the actual target before proxying.
            var boundary=context.upperBound(id);
            if(!context.identity().issuer().equals(boundary.project().issuer()))throw new Failure(403,"access_denied");
        }
        Object result=applications().invoke(new ApplicationDirectoryAccess.Request(resource,operation,context.identity().issuer(),id,key,page,size,revision,id==null?0:context.upperBound(id).project().revision(),body));
        if(operation==ApplicationDirectoryAccess.Operation.LIST && id!=null) {
            JsonNode value=mapper.valueToTree(result);
            if(!value.isObject() || !value.path("items").isArray())throw new Failure(503,"directory_unavailable");
            ((ObjectNode)value).put("directoryRevision",context.upperBound(id).project().revision());
            return ok(value);
        }
        return ok(result);
    }
    @Get("/identity/projects{?page,size}")
    public HttpResponse<?> identityProjects(String tenant,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="20") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.PROJECT,ApplicationDirectoryAccess.Operation.LIST,null,null,page,size,null,null);});
    }
    @Get("/identity/projects/{id}")
    public HttpResponse<?> identityProjectsDetail(String tenant,String id,Authentication authentication) {
        return request(tenant,authentication,c -> {project(id);return directory(c,ApplicationDirectoryAccess.Resource.PROJECT,ApplicationDirectoryAccess.Operation.FIND,id,null,1,1,null,null);});
    }
    @Post("/identity/projects") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> identityProjectsCreate(String tenant,Authentication authentication,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=null;var n=input.body(bytes,Set.of("id","tenant","displayName"));if(!"main".equals(text(n,"tenant")))throw new Failure(400,"invalid_request");project(text(n,"id"));
            return directory(c,ApplicationDirectoryAccess.Resource.PROJECT,ApplicationDirectoryAccess.Operation.CREATE,null,null,1,1,rev,n);});
    }
    @Put("/identity/projects/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> identityProjectsUpdate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("displayName","active"));
            return directory(c,ApplicationDirectoryAccess.Resource.PROJECT,ApplicationDirectoryAccess.Operation.UPDATE,id,null,1,1,rev,n);});
    }
    @Get("/identity/projects/{id}/scopes{?page,size}")
    public HttpResponse<?> identityScopes(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="20") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/identity/projects/{id}/scopes/{key}")
    public HttpResponse<?> identityScopesDetail(String tenant,String id,String key,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.FIND,id,opaque(key),1,1,null,null);});
    }
    @Post("/identity/projects/{id}/scopes") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> identityScopesCreate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("scope","description","active","resourceIntents","managementIntents"));
            return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/identity/projects/{id}/scopes/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> identityScopesUpdate(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("scope","description","active","resourceIntents","managementIntents"));same(opaque(key),n,"scope");
            return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.UPDATE,id,opaque(key),1,1,rev,n);});
    }
    @Get("/identity/projects/{id}/clients{?page,size}")
    public HttpResponse<?> clients(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="20") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/identity/projects/{id}/clients/{key}")
    public HttpResponse<?> clientsDetail(String tenant,String id,String key,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.FIND,id,opaque(key),1,1,null,null);});
    }
    @Post("/identity/projects/{id}/clients") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clientsCreate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("clientId","approved","firstPartyUi","audiences","allowedProjectScopes"));
            return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/identity/projects/{id}/clients/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clientsUpdate(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("clientId","approved","firstPartyUi","audiences","allowedProjectScopes"));same(opaque(key),n,"clientId");
            return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.UPDATE,id,opaque(key),1,1,rev,n);});
    }
    @Delete("/identity/projects/{id}/clients/{key}")
    public HttpResponse<?> clientsDelete(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,c -> {long rev=revision(match);return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.DELETE,id,opaque(key),1,1,rev,null);});
    }
    @Get("/identity/projects/{id}/assignments{?page,size}")
    public HttpResponse<?> assignments(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="20") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/identity/projects/{id}/assignments/detail{?subject}")
    public HttpResponse<?> assignmentsDetail(String tenant,String id,@QueryValue String subject,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.FIND,id,subject(subject),1,1,null,null);});
    }
    @Post("/identity/projects/{id}/assignments") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> assignmentsCreate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("issuer","subject","projectScopes"));if(!c.identity().issuer().equals(text(n,"issuer")))throw new Failure(403,"access_denied");subject(text(n,"subject"));
            return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/identity/projects/{id}/assignments/detail{?subject}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> assignmentsUpdate(String tenant,String id,@QueryValue String subject,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("issuer","subject","projectScopes"));same(subject(subject),n,"subject");if(!c.identity().issuer().equals(text(n,"issuer")))throw new Failure(403,"access_denied");
            return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.UPDATE,id,subject(subject),1,1,rev,n);});
    }
    @Delete("/identity/projects/{id}/assignments/detail{?subject}")
    public HttpResponse<?> assignmentsDelete(String tenant,String id,@QueryValue String subject,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,c -> {long rev=revision(match);return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.DELETE,id,subject(subject),1,1,rev,null);});
    }
    @Post("/roles") public HttpResponse<?> createRole(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body @Nullable Object bytes) {return error(410,"legacy_policy_api_removed");}
    @Put("/roles/{id}") public HttpResponse<?> updateRole(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body @Nullable Object bytes) {return error(410,"legacy_policy_api_removed");}
    @Delete("/roles/{id}") public HttpResponse<?> deleteRole(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match) {return error(410,"legacy_policy_api_removed");}
    @Put("/bindings") public HttpResponse<?> binding(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body @Nullable Object bytes) {return error(410,"legacy_policy_api_removed");}
    @Put("/role-mappings") public HttpResponse<?> mapping(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body @Nullable Object bytes) {return error(410,"legacy_policy_api_removed");}
}
