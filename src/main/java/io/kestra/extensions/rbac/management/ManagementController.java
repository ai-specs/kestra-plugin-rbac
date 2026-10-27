package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
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
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.*;

/** Schema 2 only. Host authentication and CSRF filters run before this controller. */
@Controller("/api/v1/{tenant}/extensions/rbac")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.typed.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
public final class ManagementController {
    private final ApplicationGroupRuntimePolicies policies;
    private final ApplicationGroupPolicyStore store;
    private final BeanProvider<UserDirectoryAccess> users;
    private final BeanProvider<ApplicationDirectoryAccess> applications;
    private final ManagementInput input=new ManagementInput();
    private final ObjectMapper mapper=new ObjectMapper();
    @Value("${kestra.rbac.application-groups.enabled:false}")
    private boolean applicationGroups;
    @Value("${micronaut.security.oauth2.clients.kestra.client-id:}")
    private String runtimeApplicationClientId="";
    public ManagementController(ApplicationGroupRuntimePolicies policies,ApplicationGroupPolicyStore store,
        BeanProvider<UserDirectoryAccess> users,BeanProvider<ApplicationDirectoryAccess> applications) {
        this.policies=policies;this.store=store;this.users=users;this.applications=applications;
    }
    @jakarta.inject.Inject
    public ManagementController(ApplicationGroupRuntimePolicies policies,BeanProvider<UserDirectoryAccess> users,BeanProvider<ApplicationDirectoryAccess> applications) {
        this(policies,null,users,applications);
    }
    private HttpResponse<?> request(String tenant,Authentication authentication,Function<ApplicationGroupRuntimePolicies.Context,HttpResponse<?>> action) {
        try {
            if (!"main".equals(tenant)) throw new Failure(404,"not_found");
            if (authentication==null) throw new Failure(401,"authentication_required");
            try (var context=policies.open(authentication,tenant)) { return action.apply(context); }
        } catch (Failure failure) { return error(failure.status,failure.code); }
        catch (ApplicationGroupRuntimePolicies.Rejected failure) { return runtimeFailure(failure); }
        catch (ApplicationGroupPolicyStore.Conflict failure) { return error(412,"revision_conflict"); }
        catch (ApplicationGroupPolicyStore.Unavailable failure) { return error(503,"policy_unavailable"); }
        catch (ApplicationGroupPolicyManagement.Missing failure) { return error(404,"not_found"); }
        catch (ApplicationGroupPolicyManagement.Exists failure) { return error(409,"already_exists"); }
        catch (ApplicationDirectoryAccess.DirectoryFailure failure) {
            if(!failure.references().isEmpty())return HttpResponse.status(HttpStatus.valueOf(failure.status()))
                .body(Map.of("code",failure.code(),"references",failure.references())).header("Cache-Control","no-store");
            return error(failure.status(),failure.code());
        }
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
    private HttpResponse<?> runtimeFailure(ApplicationGroupRuntimePolicies.Rejected failure) {
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
    private void local(ApplicationGroupRuntimePolicies.Context context,ApplicationGroupPolicy.ManagementAction action,String target) {
        if (!context.authorizeManagement(LOCAL_POLICY,action,project(target))) throw new Failure(403,"access_denied");
    }
    private void identity(ApplicationGroupRuntimePolicies.Context context,ApplicationGroupPolicy.ManagementAction action) {
        if (!context.authorizeManagement(IDENTITY,action,context.identity().applicationGroupId())) throw new Failure(403,"access_denied");
    }
    private UserDirectoryAccess users() { if (!users.isUnique()) throw new Failure(503,"directory_unavailable");return users.get(); }
    private ApplicationDirectoryAccess applications() { if (!applications.isUnique()) throw new Failure(503,"directory_unavailable");return applications.get(); }
    private ApplicationGroupPolicyManagement service(ApplicationGroupRuntimePolicies.Context context,String target) {
        return new ApplicationGroupPolicyManagement(store,new ApplicationGroupPolicyManagement.DirectoryBoundary() {
            public void validate(java.sql.Connection connection,ApplicationGroupPolicy previous,ApplicationGroupPolicy candidate)throws java.sql.SQLException {
                policies.validatePolicyChange(connection,context.identity(),context.snapshot().revision(),target,candidate);
            }
        },context.snapshot());
    }
    private HttpResponse<?> saved(ApplicationGroupPolicyStore.Snapshot snapshot) {
        return ok(Map.of("revision",snapshot.revision())).header("ETag","\""+snapshot.revision()+"\"");
    }
    private void runtime(ApplicationGroupRuntimePolicies.Context context,String id,String clientKey,ApplicationGroupPolicy.ManagementAction action) {
        if(!applicationGroups)throw new Failure(404,"not_found");
        if(runtimeApplicationClientId==null || runtimeApplicationClientId.isBlank())throw new Failure(503,"runtime_application_unavailable");
        if(!runtimeApplicationClientId.equals(opaque(clientKey)))throw new Failure(404,"not_found");
        local(context,action,id);
        if(!context.identity().issuer().equals(context.upperBound(id).applicationGroup().issuer()))throw new Failure(403,"access_denied");
        Object registered=applications().invoke(new ApplicationDirectoryAccess.Request(ApplicationDirectoryAccess.Resource.CLIENT,
            ApplicationDirectoryAccess.Operation.FIND,context.identity(),id,runtimeApplicationClientId,1,1,null,
            context.upperBound(id).applicationGroup().revision(),null,LOCAL_POLICY,action));
        if(!(registered instanceof ApplicationGroupApplication member)
            || !id.equals(member.applicationGroupId()) || !runtimeApplicationClientId.equals(member.clientId()) || member.revision()<=0)
            throw new Failure(503,"directory_unavailable");
    }
    private String scopeKey(String value) {
        if(value==null || !value.matches("[a-z][a-z0-9-]{0,63}"))throw new Failure(400,"invalid_request");
        return value;
    }
    private JsonNode settingsBody(byte[] bytes,String id) {
        var value=(ObjectNode)input.body(bytes,Set.of("issuer","audiences","enabled"));
        value.put("id",project(id));return value;
    }
    private JsonNode scopeBody(byte[] bytes) {
        var value=(ObjectNode)input.body(bytes,Set.of("scopeKey","description","enabled","grants"),Set.of("managementGrants"));
        String key=scopeKey(text(value,"scopeKey"));value.remove("scopeKey");value.put("name",key);return value;
    }
    @Get("/capabilities{?applicationGroupId}")
    public HttpResponse<?> capabilities(String tenant,Authentication authentication,@QueryValue @Nullable String applicationGroupId) {
        return request(tenant,authentication,context -> {
            String target=applicationGroupId==null?context.identity().applicationGroupId():project(applicationGroupId);
            if(!context.identity().issuer().equals(context.upperBound(target).applicationGroup().issuer()))throw new Failure(403,"access_denied");
            boolean ir=context.authorizeManagement(IDENTITY,READ,target), im=context.authorizeManagement(IDENTITY,MANAGE,target);
            var targets=applicationGroupId==null?context.managementTargets():Set.of(target);
            boolean lr=targets.stream().anyMatch(id->context.authorizeManagement(LOCAL_POLICY,READ,id));
            boolean lm=targets.stream().anyMatch(id->context.authorizeManagement(LOCAL_POLICY,MANAGE,id));
            var capabilities=new java.util.LinkedHashMap<String,Object>();
            capabilities.put("allowed",ir||im||lr||lm);capabilities.put("subject",context.identity().subject());
            capabilities.put("applicationGroupId",target);capabilities.put("runtimeApplicationClientId",runtimeApplicationClientId);
            capabilities.put("identityManagement",im);capabilities.put("localPolicyManagement",lm);
            capabilities.put("localPolicyManage",lm);
            if(lr || lm)capabilities.put("runtimePolicyRevision",context.snapshot().revision());
            capabilities.put("identityRead",ir);capabilities.put("localPolicyRead",lr);
            capabilities.put("userDirectory",users.isUnique());
            capabilities.put("applicationScopeDeclarations",applicationGroups);
            capabilities.put("mode","database");capabilities.put("readOnly",false);return ok(capabilities);
        });
    }
    public HttpResponse<?> runtimePolicy(String tenant,String id,String clientKey,Authentication authentication) {
        return request(tenant,authentication,context -> {
            runtime(context,id,clientKey,READ);
            var result=mapper.createObjectNode();result.put("revision",context.snapshot().revision());
            result.put("applicationGroupId",id);result.put("clientId",runtimeApplicationClientId);
            result.set("policy",mapper.valueToTree(context.snapshot().policy().applicationGroups().get(id)));
            return ok(result).header("ETag","\""+context.snapshot().revision()+"\"");
        });
    }
    public HttpResponse<?> createRuntimePolicy(String tenant,String id,String clientKey,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);runtime(context,id,clientKey,MANAGE);
            return saved(service(context,id).createProject(rev,settingsBody(bytes,id),context.identity().subject()));
        });
    }
    public HttpResponse<?> updateRuntimePolicy(String tenant,String id,String clientKey,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);runtime(context,id,clientKey,MANAGE);
            return saved(service(context,id).updateProject(rev,id,settingsBody(bytes,id),context.identity().subject()));
        });
    }
    public HttpResponse<?> runtimeScopes(String tenant,String id,String clientKey,Authentication authentication) {
        return request(tenant,authentication,context -> {runtime(context,id,clientKey,READ);return ok(context.visibleGroup(id).scopes());});
    }
    public HttpResponse<?> runtimeScope(String tenant,String id,String clientKey,String key,Authentication authentication) {
        return request(tenant,authentication,context -> {
            runtime(context,id,clientKey,READ);var value=context.visibleGroup(id).scopes().get(scopeKey(key));
            if(value==null)throw new Failure(404,"not_found");return ok(value);
        });
    }
    public HttpResponse<?> createRuntimeScope(String tenant,String id,String clientKey,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);runtime(context,id,clientKey,MANAGE);
            return saved(service(context,id).createScope(rev,id,scopeBody(bytes),context.identity().subject()));
        });
    }
    public HttpResponse<?> updateRuntimeScope(String tenant,String id,String clientKey,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);runtime(context,id,clientKey,MANAGE);String name=scopeKey(key);var node=scopeBody(bytes);same(name,node,"name");
            return saved(service(context,id).replaceScope(rev,id,name,node,context.identity().subject()));
        });
    }
    public HttpResponse<?> deleteRuntimeScope(String tenant,String id,String clientKey,String key,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,context -> {
            long rev=revision(match);runtime(context,id,clientKey,MANAGE);
            return saved(service(context,id).deleteScope(rev,id,scopeKey(key),context.identity().subject()));
        });
    }
    @Get("/users{?page,size}")
    public HttpResponse<?> users(String tenant,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,authentication,context -> {identity(context,READ);page(page,size);return ok(users().list(page,size));});
    }
    @Get("/users/detail{?subject}")
    public HttpResponse<?> user(String tenant,Authentication authentication,@QueryValue String subject) {
        return request(tenant,authentication,context -> {identity(context,READ);return ok(users().find(subject(subject)).orElseThrow(()->new Failure(404,"not_found")));});
    }
    @Post("/users") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createUser(String tenant,Authentication authentication,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {
            var node=input.body(bytes,Set.of("username"),Set.of("name","email"));identity(context,MANAGE);
            var command=new TemporaryAccountCommands.Create(subject(text(node,"username")),
                node.has("name")?text(node,"name"):"",node.has("email")?text(node,"email"):"");
            try(var mutation=io.kestra.extensions.rbac.applicationgroup.ApplicationGroupMutationContext.bind(context.identity())) {
                var receipt=new TemporaryAccountCommands(users(),()->identity(context,MANAGE)).create(command);
                return HttpResponse.created(receipt).header("ETag","\""+receipt.revision()+"\"").header("Cache-Control","no-store");
            }
        });
    }
    @Delete("/users/detail{?subject}")
    public HttpResponse<?> deleteUser(String tenant,Authentication authentication,@QueryValue String subject,
        @Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,context->{
            long rev=revision(match);String target=subject(subject);identity(context,MANAGE);
            if(context.identity().subject().equals(target))throw new Failure(403,"access_denied");
            try(var mutation=io.kestra.extensions.rbac.applicationgroup.ApplicationGroupMutationContext.bind(context.identity())) {
                new TemporaryAccountCommands(users(),()->identity(context,MANAGE)).delete(target,rev);
                return HttpResponse.noContent();
            }catch(UserDirectoryAccess.DirectoryFailure failure) {
                if(failure.failure()==UserDirectoryAccess.Failure.CONFLICT)throw new Failure(412,"revision_conflict");
                throw failure;
            }
        });
    }
    private HttpResponse<?> account(ApplicationGroupRuntimePolicies.Context context,Supplier<UserDirectoryAccess.User> operation) {
        try(var mutation=io.kestra.extensions.rbac.applicationgroup.ApplicationGroupMutationContext.bind(context.identity())) {
            var user=operation.get();return ok(Map.of("subject",user.subject(),"revision",user.revision())).header("ETag","\""+user.revision()+"\"");
        }catch(UserDirectoryAccess.DirectoryFailure failure) {
            if(failure.failure()==UserDirectoryAccess.Failure.CONFLICT)throw new Failure(412,"revision_conflict");throw failure;
        }
    }
    @Put("/users/profile") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> profile(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","name","email"));identity(context,MANAGE);
            return account(context,()->users().update(subject(text(n,"subject")),rev,text(n,"name"),text(n,"email")));});
    }
    @Put("/users/state") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> state(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","active"));identity(context,MANAGE);
            return account(context,()->users().setActive(subject(text(n,"subject")),rev,bool(n,"active")));});
    }
    @Put("/users/password") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> password(String tenant,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,context -> {long rev=revision(match);var n=input.body(bytes,Set.of("subject","password"));identity(context,MANAGE);
            return account(context,()->users().resetPassword(subject(text(n,"subject")),rev,text(n,"password")));});
    }
    private HttpResponse<?> directory(ApplicationGroupRuntimePolicies.Context context,ApplicationDirectoryAccess.Resource resource,
        ApplicationDirectoryAccess.Operation operation,String id,String key,int page,int size,Long revision,JsonNode body) {
        boolean read=operation==ApplicationDirectoryAccess.Operation.LIST || operation==ApplicationDirectoryAccess.Operation.FIND;
        identity(context,read?READ:MANAGE);
        if(id!=null) {
            project(id);
            // IDENTITY is issuer-wide, never cross issuer. Check the actual target before proxying.
            var boundary=context.upperBound(id);
            if(!context.identity().issuer().equals(boundary.applicationGroup().issuer()))throw new Failure(403,"access_denied");
        }
        Object result=applications().invoke(new ApplicationDirectoryAccess.Request(resource,operation,context.identity(),id,key,page,size,revision,id==null?0:context.upperBound(id).applicationGroup().revision(),body));
        if(!read) {
            if(!(result instanceof Map<?,?> receipt))throw new Failure(503,"directory_unavailable");
            String field=switch(resource){case GROUP->"id";case SCOPE->"key";case CLIENT->"clientId";case ASSIGNMENT->"subject";};
            String expected=resource==ApplicationDirectoryAccess.Resource.GROUP?(id==null?text(body,"id"):id):key==null?text(body,field):key;
            if(!receipt.keySet().equals(Set.of(field,"revision","parentRevision")) || !expected.equals(receipt.get(field))
                || !(receipt.get("revision") instanceof Number objectRevision) || objectRevision.longValue()<1
                || !(receipt.get("parentRevision") instanceof Number parentRevision) || parentRevision.longValue()<0)
                throw new Failure(503,"directory_unavailable");
            return ok(receipt).header("ETag","\""+objectRevision.longValue()+"\"");
        }
        if(resource!=ApplicationDirectoryAccess.Resource.GROUP && operation!=ApplicationDirectoryAccess.Operation.DELETE) {
            if(operation==ApplicationDirectoryAccess.Operation.LIST) {
                if(!(result instanceof ApplicationGroupDirectoryManagement.Page<?> values))throw new Failure(503,"directory_unavailable");
                var records=values.items().stream().map(value->directoryRecord(resource,id,context.identity().issuer(),value)).toList();
                return ok(Map.of("items",records,"total",values.total(),"page",values.page(),"size",values.size(),
                    "directoryRevision",context.upperBound(id).applicationGroup().revision()));
            }
            Object record=directoryRecord(resource,id,context.identity().issuer(),result);
            String field=switch(resource) {case SCOPE->"key";case CLIENT->"clientId";case ASSIGNMENT->"subject";default->throw new Failure(503,"directory_unavailable");};
            String expected=key==null?text(body,field):key;
            if(!expected.equals(mapper.valueToTree(record).path(field).textValue()))throw new Failure(503,"directory_unavailable");
            return ok(record);
        }
        if(operation==ApplicationDirectoryAccess.Operation.LIST && id!=null) {
            JsonNode value=mapper.valueToTree(result);
            if(!value.isObject() || !value.path("items").isArray())throw new Failure(503,"directory_unavailable");
            ((ObjectNode)value).put("directoryRevision",context.upperBound(id).applicationGroup().revision());
            return ok(value);
        }
        return ok(result);
    }
    private Object directoryRecord(ApplicationDirectoryAccess.Resource resource,String id,String issuer,Object value) {
        try {
            if(resource==ApplicationDirectoryAccess.Resource.SCOPE && value instanceof ApplicationGroupScope scope
                && id.equals(scope.applicationGroupId()))return scope;
            if(resource==ApplicationDirectoryAccess.Resource.CLIENT && value instanceof ApplicationGroupApplication application
                && id.equals(application.applicationGroupId()))return application;
            if(resource==ApplicationDirectoryAccess.Resource.ASSIGNMENT && value instanceof ApplicationGroupScopeGrant grant
                && id.equals(grant.applicationGroupId()) && issuer.equals(grant.issuer()))return grant;
        } catch(IllegalArgumentException|NullPointerException failure){throw new Failure(503,"directory_unavailable");}
        throw new Failure(503,"directory_unavailable");
    }
    @Get("/application-groups{?page,size}")
    public HttpResponse<?> applicationGroups(String tenant,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.GROUP,ApplicationDirectoryAccess.Operation.LIST,null,null,page,size,null,null);});
    }
    @Get("/application-groups/{id}")
    public HttpResponse<?> applicationGroup(String tenant,String id,Authentication authentication) {
        return request(tenant,authentication,c -> {project(id);return directory(c,ApplicationDirectoryAccess.Resource.GROUP,ApplicationDirectoryAccess.Operation.FIND,id,null,1,1,null,null);});
    }
    @Post("/application-groups") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createApplicationGroup(String tenant,Authentication authentication,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=null;var n=input.body(bytes,Set.of("id","tenant","displayName","description"));if(!"main".equals(text(n,"tenant")))throw new Failure(400,"invalid_request");project(text(n,"id"));
            return directory(c,ApplicationDirectoryAccess.Resource.GROUP,ApplicationDirectoryAccess.Operation.CREATE,null,null,1,1,rev,n);});
    }
    @Put("/application-groups/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateApplicationGroup(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("displayName","description","active"));
            return directory(c,ApplicationDirectoryAccess.Resource.GROUP,ApplicationDirectoryAccess.Operation.UPDATE,id,null,1,1,rev,n);});
    }
    @Delete("/application-groups/{id}")
    public HttpResponse<?> deleteApplicationGroup(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,context->directory(context,ApplicationDirectoryAccess.Resource.GROUP,
            ApplicationDirectoryAccess.Operation.DELETE,project(id),null,1,1,revision(match),null));
    }
    @Get("/application-groups/{id}/scopes{?page,size}")
    public HttpResponse<?> applicationGroupScopes(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/application-groups/{id}/scopes/{key}")
    public HttpResponse<?> applicationGroupScope(String tenant,String id,String key,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.FIND,id,scopeKey(key),1,1,null,null);});
    }
    @Post("/application-groups/{id}/scopes") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> createApplicationGroupScope(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("key","name","description"));scopeKey(text(n,"key"));
            return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/application-groups/{id}/scopes/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateApplicationGroupScope(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("key","name","description"));same(scopeKey(key),n,"key");
            return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.UPDATE,id,key,1,1,rev,n);});
    }
    @Delete("/application-groups/{id}/scopes/{key}")
    public HttpResponse<?> deleteApplicationGroupScope(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,c -> {long rev=revision(match);
            return directory(c,ApplicationDirectoryAccess.Resource.SCOPE,ApplicationDirectoryAccess.Operation.DELETE,id,scopeKey(key),1,1,rev,null);});
    }
    @Get("/application-groups/{id}/applications{?page,size}")
    public HttpResponse<?> clients(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/application-groups/{id}/applications/{key}")
    public HttpResponse<?> clientsDetail(String tenant,String id,String key,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.FIND,id,opaque(key),1,1,null,null);});
    }
    @Post("/application-groups/{id}/applications") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clientsCreate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("clientId","description","audiences"));
            return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/application-groups/{id}/applications/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clientsUpdate(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("clientId","description","audiences"));same(opaque(key),n,"clientId");
            return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.UPDATE,id,opaque(key),1,1,rev,n);});
    }
    @Delete("/application-groups/{id}/applications/{key}")
    public HttpResponse<?> clientsDelete(String tenant,String id,String key,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,c -> {long rev=revision(match);return directory(c,ApplicationDirectoryAccess.Resource.CLIENT,ApplicationDirectoryAccess.Operation.DELETE,id,opaque(key),1,1,rev,null);});
    }
    @Get("/application-groups/{id}/scope-grants{?page,size}")
    public HttpResponse<?> assignments(String tenant,String id,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,authentication,c -> {page(page,size);return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.LIST,id,null,page,size,null,null);});
    }
    @Get("/application-groups/{id}/scope-grants/detail{?subject}")
    public HttpResponse<?> assignmentsDetail(String tenant,String id,@QueryValue String subject,Authentication authentication) {
        return request(tenant,authentication,c -> {return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.FIND,id,subject(subject),1,1,null,null);});
    }
    @Post("/application-groups/{id}/scope-grants") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> assignmentsCreate(String tenant,String id,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {Long rev=revision(match);var n=input.body(bytes,Set.of("issuer","subject","scopeKeys"));if(!c.identity().issuer().equals(text(n,"issuer")))throw new Failure(403,"access_denied");subject(text(n,"subject"));
            return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.CREATE,id,null,1,1,rev,n);});
    }
    @Put("/application-groups/{id}/scope-grants/detail{?subject}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> assignmentsUpdate(String tenant,String id,@QueryValue String subject,Authentication authentication,@Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,authentication,c -> {long rev=revision(match);var n=input.body(bytes,Set.of("issuer","subject","scopeKeys"));same(subject(subject),n,"subject");if(!c.identity().issuer().equals(text(n,"issuer")))throw new Failure(403,"access_denied");
            return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.UPDATE,id,subject(subject),1,1,rev,n);});
    }
    @Delete("/application-groups/{id}/scope-grants/detail{?subject}")
    public HttpResponse<?> assignmentsDelete(String tenant,String id,@QueryValue String subject,Authentication authentication,@Header("If-Match") @Nullable String match) {
        return request(tenant,authentication,c -> {long rev=revision(match);return directory(c,ApplicationDirectoryAccess.Resource.ASSIGNMENT,ApplicationDirectoryAccess.Operation.DELETE,id,subject(subject),1,1,rev,null);});
    }
}
