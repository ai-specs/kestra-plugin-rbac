package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationPermission;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;
import static io.kestra.extensions.rbac.management.ManagementInput.*;

@Controller("/api/v1/{tenant}/extensions/rbac/application-groups/{group}/applications/{client}/permissions")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.typed.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ApplicationPermissionController {
    private final ApplicationGroupDirectoryService directory;
    private final ApplicationGroupRuntimePolicies policies;
    private final ManagementInput input=new ManagementInput();
    private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);

    public ApplicationPermissionController(ApplicationGroupDirectoryService directory,ApplicationGroupRuntimePolicies policies) {
        this.directory=directory;this.policies=policies;
    }

    @Get("{?page,size}")
    public HttpResponse<?> list(String tenant,String group,String client,Authentication authentication,
                               @QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        return request(tenant,group,client,authentication,ManagementAction.READ,(connection,context,application)->{
            page(page,size);var ordered=application.permissions().stream().sorted(java.util.Comparator.comparing(ApplicationPermission::key)).toList();
            int start=(int)Math.min(ordered.size(),Math.multiplyExact((long)page-1,size));
            var items=ordered.subList(start,Math.min(ordered.size(),start+size)).stream()
                .map(value->new ApplicationPermission.View(value,directory.kind(application.clientId()),application.revision())).toList();
            return ok(Map.of("items",items,"total",application.permissions().size(),"page",page,"size",size,"parentRevision",application.revision(),"implementationKind",directory.kind(application.clientId())));
        });
    }

    @Get("/{key}")
    public HttpResponse<?> find(String tenant,String group,String client,String key,Authentication authentication) {
        return request(tenant,group,client,authentication,ManagementAction.READ,(connection,context,application)->{
            var value=find(application,key);return ok(new ApplicationPermission.View(value,directory.kind(application.clientId()),application.revision()))
                .header("ETag",etag(value.revision()));
        });
    }

    @Get("/{key}/mutation-metadata")
    public HttpResponse<?> metadata(String tenant,String group,String client,String key,Authentication authentication) {
        return request(tenant,group,client,authentication,ManagementAction.MANAGE,(connection,context,application)->{
            var permission=find(application,key);
            return ok(Map.of("key",permission.key(),"revision",permission.revision(),"parentRevision",application.revision(),"actions",List.of("MANAGE")));
        });
    }

    @Get("/-/mutation-metadata")
    public HttpResponse<?> applicationMetadata(String tenant,String group,String client,Authentication authentication) {
        return request(tenant,group,client,authentication,ManagementAction.MANAGE,(connection,context,application)->
            ok(Map.of("clientId",application.clientId(),"revision",application.revision(),"actions",List.of("MANAGE"))));
    }

    @Post @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> create(String tenant,String group,String client,Authentication authentication,
                                  @Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,group,client,authentication,ManagementAction.MANAGE,(connection,context,application)->{
            long expected=revision(match);if(expected!=application.revision())throw new Failure(412,"revision_conflict");
            var candidate=parse(bytes,directory.kind(application.clientId()),null,1,true);
            if(application.permissions().stream().anyMatch(value->value.key().equals(candidate.key())))throw new Failure(409,"already_exists");
            var next=new ArrayList<>(application.permissions());next.add(candidate);
            return save(connection,context,group,application,next,candidate.key(),candidate.revision());
        });
    }

    @Put("/{key}") @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> update(String tenant,String group,String client,String key,Authentication authentication,
                                  @Header("If-Match") @Nullable String match,@Body byte[] bytes) {
        return request(tenant,group,client,authentication,ManagementAction.MANAGE,(connection,context,application)->{
            long expected=revision(match);var previous=find(application,key);
            if(expected!=previous.revision())throw new Failure(412,"revision_conflict");
            var candidate=parse(bytes,directory.kind(application.clientId()),key,Math.addExact(expected,1),false);
            var next=application.permissions().stream().map(value->value.key().equals(key)?candidate:value).toList();
            return save(connection,context,group,application,next,key,candidate.revision());
        });
    }

    @Delete("/{key}")
    public HttpResponse<?> delete(String tenant,String group,String client,String key,Authentication authentication,
                                  @Header("If-Match") @Nullable String match) {
        return request(tenant,group,client,authentication,ManagementAction.MANAGE,(connection,context,application)->{
            long expected=revision(match);var previous=find(application,key);
            if(expected!=previous.revision())throw new Failure(412,"revision_conflict");
            var next=application.permissions().stream().filter(value->!value.key().equals(key)).toList();
            return save(connection,context,group,application,next,key,Math.addExact(expected,1));
        });
    }

    private HttpResponse<?> save(Connection connection,ApplicationGroupRuntimePolicies.Context context,String group,
                                  ApplicationGroupDirectory.Application application,List<ApplicationPermission> next,
                                  String key,long revision)throws SQLException {
        if(ApplicationPermission.supportChanged(application.permissions(),next)
            && directory.kind(application.clientId())==ApplicationPermission.Kind.KESTRA_POLICY
            && !context.authorizeManagement(ManagementDomain.IDENTITY,ManagementAction.MANAGE,group))
            throw new Failure(403,"access_denied");
        var snapshot=directory.find(connection,group);
        var scopes=snapshot.scopes().stream().map(ApplicationGroupDirectory.Scope::key).collect(java.util.stream.Collectors.toSet());
        next.forEach(value->value.validate(directory.kind(application.clientId()),scopes));
        var saved=directory.update(connection,group,snapshot.group().revision(),current->
            ApplicationGroupChanges.permissions(current,application.clientId(),application.revision(),next));
        long parent=saved.applications().stream().filter(value->value.clientId().equals(application.clientId())).findFirst().orElseThrow().revision();
        return ok(new ApplicationPermission.Receipt(key,revision,parent)).header("ETag",etag(revision));
    }

    private ApplicationPermission parse(byte[] bytes,ApplicationPermission.Kind kind,String key,long revision,boolean creating) {
        Set<String> common=Set.of("key","name","scopeKeys");
        var required=new java.util.HashSet<>(common);
        required.addAll(kind==ApplicationPermission.Kind.KESTRA_POLICY?Set.of("grants","managementGrants"):Set.of("details"));
        if(kind==ApplicationPermission.Kind.KESTRA_POLICY && !creating)required.add("enabled");
        var optional=new java.util.HashSet<>(Set.of("description"));
        if(kind==ApplicationPermission.Kind.KESTRA_POLICY && creating)optional.add("enabled");
        var node=input.body(bytes,required,optional);
        if(key!=null)same(key,node,"key");
        var scopes=node.get("scopeKeys");if(!scopes.isArray() || scopes.size()>1000)throw new IllegalArgumentException("Scope array required");
        var keys=new java.util.HashSet<String>();
        scopes.forEach(value->{if(!value.isTextual() || !keys.add(value.textValue()))throw new IllegalArgumentException("Unique scope keys required");});
        boolean enabled=kind!=ApplicationPermission.Kind.KESTRA_POLICY || !node.has("enabled") || bool(node,"enabled");
        List<Grant> grants=List.of();List<ManagementGrant> management=List.of();String details="";
        if(kind==ApplicationPermission.Kind.KESTRA_POLICY) {
            if(!node.get("grants").isArray() || !node.get("managementGrants").isArray())throw new IllegalArgumentException("Typed grant arrays required");
            grants=mapper.convertValue(node.get("grants"),mapper.getTypeFactory().constructCollectionType(List.class,Grant.class));
            management=mapper.convertValue(node.get("managementGrants"),mapper.getTypeFactory().constructCollectionType(List.class,ManagementGrant.class));
        } else details=text(node,"details");
        return new ApplicationPermission(text(node,"key"),text(node,"name"),node.has("description")?text(node,"description"):"",keys,grants,management,details,enabled,revision);
    }

    private HttpResponse<?> request(String tenant,String group,String client,Authentication authentication,ManagementAction action,Operation operation) {
        try {
            if(!"main".equals(tenant))throw new Failure(404,"not_found");project(group);String decoded=opaque(client);
            if(authentication==null)throw new Failure(401,"authentication_required");
            try(var admitted=policies.open(authentication,tenant)) {
                return directory.transaction(connection->{
                    try(var context=policies.open(connection,admitted.identity(),tenant)) {
                        var domain=directory.kind(decoded)==ApplicationPermission.Kind.KESTRA_POLICY?ManagementDomain.LOCAL_POLICY:ManagementDomain.IDENTITY;
                        if(!context.authorizeManagement(domain,action,group))throw new Failure(403,"access_denied");
                        var application=directory.find(connection,group).applications().stream().filter(value->value.clientId().equals(decoded)).findFirst()
                            .orElseThrow(()->new Failure(404,"not_found"));
                        return operation.run(connection,context,application);
                    }
                });
            }
        }catch(Failure failure){return error(failure.status,failure.code);}
        catch(ApplicationGroupChanges.NotFound failure){return error(404,"not_found");}
        catch(ApplicationGroupDirectoryStore.Conflict|ApplicationGroupChanges.Conflict failure){return error(412,"revision_conflict");}
        catch(IdentityLifecycleGuardV1.Rejected failure){return error(failure.failure()==IdentityLifecycleGuardV1.Failure.LAST_ADMIN?409:403,
            failure.failure()==IdentityLifecycleGuardV1.Failure.LAST_ADMIN?"last_administrator":"access_denied");}
        catch(ApplicationGroupRuntimePolicies.Rejected failure){return error(switch(failure.failure()){
            case DIRECTORY_UNAVAILABLE,POLICY_UNAVAILABLE,INVALID_DIRECTORY->503;default->403;},"access_denied");}
        catch(IllegalArgumentException failure){return error(400,"invalid_request");}
        catch(Exception failure){return error(503,"permission_unavailable");}
    }

    private static ApplicationPermission find(ApplicationGroupDirectory.Application application,String key) {
        if(key==null || !key.matches("[a-z][a-z0-9-]{0,127}"))throw new IllegalArgumentException("Invalid permission key");
        return application.permissions().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow(()->new Failure(404,"not_found"));
    }
    private interface Operation {HttpResponse<?> run(Connection connection,ApplicationGroupRuntimePolicies.Context context,
        ApplicationGroupDirectory.Application application)throws SQLException;}
    private static String etag(long revision){return "\""+revision+"\"";}
    private static io.micronaut.http.MutableHttpResponse<?> ok(Object value){return HttpResponse.ok(value).header("Cache-Control","no-store");}
    private static HttpResponse<?> error(int status,String code){return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code",code)).header("Cache-Control","no-store");}
}
