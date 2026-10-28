package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryReader;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.IdentityLifecycleGuardV1;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.kestra.extensions.rbac.management.ManagementInput.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.*;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ApplicationGroupDirectoryManagement implements ApplicationDirectoryAccess {
    public record Page<Target>(List<Target> items,long total,int page,int size,long directoryRevision) {
        public Page {items=List.copyOf(items);}
    }
    private final ApplicationGroupDirectoryService directory;
    private final ApplicationGroupRuntimePolicies policies;

    public ApplicationGroupDirectoryManagement(ApplicationGroupDirectoryService directory,ApplicationGroupRuntimePolicies policies) {
        this.directory=directory;this.policies=policies;
    }

    @Override public Object invoke(Request request) {
        try {
            if(request.operation()==Operation.CREATE || request.operation()==Operation.UPDATE) {
                var expected=switch(request.resource()) {
                    case GROUP->request.operation()==Operation.CREATE?Set.of("id","tenant","displayName","description"):Set.of("displayName","description","active");
                    case SCOPE->Set.of("key","name","description");
                    case CLIENT->Set.of("clientId","description","audiences");
                    case ASSIGNMENT->Set.of("issuer","subject","scopeKeys");
                };
                if(request.body()==null || !request.body().isObject() || request.body().size()!=expected.size())throw new IllegalArgumentException("Exact management fields required");
                request.body().fieldNames().forEachRemaining(name->{if(!expected.contains(name)||request.body().get(name).isNull())throw new IllegalArgumentException("Unknown management field");});
            }
            return directory.transaction(connection->{
                try(var context=policies.open(connection,request.identity(),"main")) {
                    boolean runtime=request.authorizationDomain()==ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY;
                    if(!runtime && request.authorizationAction()!=(request.operation()==Operation.LIST||request.operation()==Operation.FIND?
                        ApplicationGroupPolicy.ManagementAction.READ:ApplicationGroupPolicy.ManagementAction.MANAGE))throw new DirectoryFailure(403,"access_denied");
                    if(runtime && (request.resource()!=Resource.CLIENT || request.operation()!=Operation.FIND)
                        || !runtime && request.authorizationDomain()!=ApplicationGroupPolicy.ManagementDomain.IDENTITY
                        || !context.authorizeManagement(request.authorizationDomain(),request.authorizationAction(),
                            runtime?request.applicationGroupId():request.identity().applicationGroupId()))throw new DirectoryFailure(403,"access_denied");
                    if(request.applicationGroupId()!=null && directory.find(connection,request.applicationGroupId()).group().revision()!=request.directoryRevision())
                        throw new DirectoryFailure(409,"directory_changed");
                    Object result=switch(request.resource()) {
                        case GROUP->group(connection,request);
                        case SCOPE->{
                            try {yield scope(connection,request);}
                            catch(ApplicationGroupChanges.ScopeInUse failure) {
                                boolean readIdentity=context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.READ,request.applicationGroupId());
                                boolean readPolicy=context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.READ,request.applicationGroupId());
                                var references=failure.references().stream().filter(value->readIdentity && (!"permission".equals(value.kind())
                                    || directory.kind(value.clientId())==io.kestra.extensions.rbac.applicationgroup.ApplicationPermission.Kind.EXTERNAL_DECLARATION || readPolicy)).toList();
                                throw new DirectoryFailure(409,"scope_in_use",references);
                            }
                        }
                        case CLIENT->{
                            if(request.operation()==Operation.UPDATE || request.operation()==Operation.DELETE) {
                                var existing=directory.find(connection,request.applicationGroupId()).applications().stream()
                                    .filter(value->value.clientId().equals(request.key())).findFirst().orElseThrow(ApplicationGroupChanges.NotFound::new);
                                boolean policyChange=request.operation()==Operation.DELETE || !existing.audiences().equals(strings(request.body().get("audiences")));
                                if(policyChange && directory.kind(existing.clientId())==io.kestra.extensions.rbac.applicationgroup.ApplicationPermission.Kind.KESTRA_POLICY
                                    && !context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY,ApplicationGroupPolicy.ManagementAction.MANAGE,request.applicationGroupId()))
                                    throw new DirectoryFailure(403,"access_denied");
                            }
                            yield application(connection,request);
                        }
                        case ASSIGNMENT->grant(connection,request);
                    };
                    if(request.operation()==Operation.LIST || request.operation()==Operation.FIND)return result;
                    String field=switch(request.resource()){case GROUP->"id";case SCOPE->"key";case CLIENT->"clientId";case ASSIGNMENT->"subject";};
                    String key=request.resource()==Resource.GROUP?(request.applicationGroupId()==null?text(request.body(),"id"):request.applicationGroupId()):
                        request.key()==null?text(request.body(),field):request.key();
                    long revision=result instanceof ApplicationGroupDirectoryReader.GroupView value?value.revision():
                        result instanceof ApplicationGroupScope value?value.revision():result instanceof ApplicationGroupApplication value?value.revision():
                        result instanceof ApplicationGroupScopeGrant value?value.revision():Math.addExact(request.revision(),1);
                    long parent=request.resource()==Resource.GROUP?directory.catalogRevision(connection):directory.find(connection,request.applicationGroupId()).group().revision();
                    return Map.of(field,key,"revision",revision,"parentRevision",parent);
                }
            });
        }catch(ApplicationGroupChanges.NotFound failure){throw new DirectoryFailure(404,"not_found");}
        catch(ApplicationGroupDirectoryStore.DeletionRejected failure){
            throw new DirectoryFailure(409,switch(failure.failure()){case PROTECTED_GROUP->"protected_group";case GROUP_NOT_EMPTY->"group_not_empty";});
        }
        catch(ApplicationGroupDirectoryStore.Conflict|ApplicationGroupChanges.Conflict failure){
            throw new DirectoryFailure(request.operation()==Operation.CREATE?409:412,"revision_conflict");
        }catch(IdentityLifecycleGuardV1.Rejected failure){
            throw new DirectoryFailure(switch(failure.failure()){case FORBIDDEN->403;case LAST_ADMIN->409;case UNAVAILABLE->503;},
                failure.failure()==IdentityLifecycleGuardV1.Failure.LAST_ADMIN?"last_administrator":"access_denied");
        }catch(DirectoryFailure|ManagementInput.Failure|ApplicationGroupRuntimePolicies.Rejected failure){throw failure;}
        catch(IllegalArgumentException failure){throw new DirectoryFailure(400,"invalid_request");}
        catch(RuntimeException failure){throw new DirectoryFailure(503,"directory_unavailable");}
    }

    private Object group(Connection connection,Request request)throws SQLException {
        return switch(request.operation()) {
            case LIST->{
                var values=new java.util.ArrayList<ApplicationGroupDirectoryReader.GroupView>();
                for(var snapshot:directory.snapshots(connection).stream().sorted(java.util.Comparator.comparing(value->value.group().id())).toList())values.add(directory.view(connection,snapshot));
                yield page(values,request,directory.catalogRevision(connection));
            }
            case FIND->directory.view(connection,directory.find(connection,request.applicationGroupId()));
            case CREATE->{
                if(!"main".equals(text(request.body(),"tenant")))throw new IllegalArgumentException("Only main tenant supported");
                var group=new ApplicationGroupDirectory.Group(project(text(request.body(),"id")),text(request.body(),"displayName"),text(request.body(),"description"),true,1,1);
                yield directory.view(connection,directory.create(connection,new ApplicationGroupDirectory.Snapshot(group,List.of(),List.of(),List.of())));
            }
            case UPDATE->directory.view(connection,directory.update(connection,request.applicationGroupId(),request.revision(),current->
                groupMetadata(current,text(request.body(),"displayName"),text(request.body(),"description"),bool(request.body(),"active"))));
            case DELETE->{directory.delete(connection,request.applicationGroupId(),request.revision());yield Map.of("deleted",true);}
        };
    }

    private Object scope(Connection connection,Request request)throws SQLException {
        var current=directory.find(connection,request.applicationGroupId());
        if(request.operation()==Operation.LIST)return page(current.scopes().stream().sorted(java.util.Comparator.comparing(ApplicationGroupDirectory.Scope::key)).map(value->scope(current,value.key())).toList(),request,current.group().revision());
        if(request.operation()==Operation.FIND)return scope(current,request.key());
        if(request.operation()==Operation.CREATE && request.revision()!=current.group().revision())throw new ApplicationGroupDirectoryStore.Conflict();
        String key=request.key()==null?text(request.body(),"key"):request.key();
        var saved=directory.update(connection,current.group().id(),current.group().revision(),snapshot->switch(request.operation()) {
            case CREATE->createScope(snapshot,key,text(request.body(),"name"),text(request.body(),"description"));
            case UPDATE->{if(!key.equals(text(request.body(),"key")))throw new IllegalArgumentException("Scope key is immutable");
                yield scopeMetadata(snapshot,key,request.revision(),text(request.body(),"name"),text(request.body(),"description"));}
            case DELETE->deleteScope(snapshot,key,request.revision());
            default->throw new IllegalArgumentException("Unsupported scope operation");
        });
        return request.operation()==Operation.DELETE?Map.of("deleted",true):scope(saved,key);
    }

    private Object application(Connection connection,Request request)throws SQLException {
        var current=directory.find(connection,request.applicationGroupId());
        if(request.operation()==Operation.LIST)return page(current.applications().stream().sorted(java.util.Comparator.comparing(ApplicationGroupDirectory.Application::clientId,ApplicationGroupDirectory::compareCodePoints)).map(value->application(current,value.clientId())).toList(),request,current.group().revision());
        if(request.operation()==Operation.FIND)return application(current,request.key());
        if(request.operation()==Operation.CREATE && request.revision()!=current.group().revision())throw new ApplicationGroupDirectoryStore.Conflict();
        String client=request.key()==null?text(request.body(),"clientId"):request.key();
        var saved=directory.update(connection,current.group().id(),current.group().revision(),snapshot->switch(request.operation()) {
            case CREATE->registerApplication(snapshot,client,text(request.body(),"description"),strings(request.body().get("audiences")));
            case UPDATE->{if(!client.equals(text(request.body(),"clientId")))throw new IllegalArgumentException("Application identifier is immutable");
                yield applicationMetadata(snapshot,client,request.revision(),text(request.body(),"description"),strings(request.body().get("audiences")));}
            case DELETE->removeApplication(snapshot,client,request.revision());
            default->throw new IllegalArgumentException("Unsupported application operation");
        });
        return request.operation()==Operation.DELETE?Map.of("deleted",true):application(saved,client);
    }

    private Object grant(Connection connection,Request request)throws SQLException {
        var current=directory.find(connection,request.applicationGroupId());
        if(request.operation()==Operation.LIST)return page(current.userGrants().stream().sorted(java.util.Comparator.comparing(ApplicationGroupDirectory.UserGrant::subject,ApplicationGroupDirectory::compareCodePoints)
            .thenComparing(ApplicationGroupDirectory.UserGrant::issuer,ApplicationGroupDirectory::compareCodePoints)).map(value->grant(current,value.issuer(),value.subject())).toList(),request,current.group().revision());
        String issuer=request.identity().issuer();
        if(request.operation()==Operation.FIND)return grant(current,issuer,request.key());
        if(request.operation()==Operation.CREATE && request.revision()!=current.group().revision())throw new ApplicationGroupDirectoryStore.Conflict();
        String user=request.key()==null?text(request.body(),"subject"):request.key();
        if(request.body()!=null && (!issuer.equals(text(request.body(),"issuer")) || !user.equals(text(request.body(),"subject"))))
            throw new DirectoryFailure(403,"access_denied");
        var saved=directory.update(connection,current.group().id(),current.group().revision(),snapshot->switch(request.operation()) {
            case CREATE->grantUser(snapshot,issuer,user,strings(request.body().get("scopeKeys")));
            case UPDATE->userScopes(snapshot,issuer,user,request.revision(),strings(request.body().get("scopeKeys")));
            case DELETE->revokeUser(snapshot,issuer,user,request.revision());
            default->throw new IllegalArgumentException("Unsupported grant operation");
        });
        return request.operation()==Operation.DELETE?Map.of("deleted",true):grant(saved,issuer,user);
    }

    private static ApplicationGroupScope scope(ApplicationGroupDirectory.Snapshot snapshot,String key) {
        var value=snapshot.scopes().stream().filter(scope->scope.key().equals(key)).findFirst().orElseThrow(ApplicationGroupChanges.NotFound::new);
        return new ApplicationGroupScope(snapshot.group().id(),value.key(),value.name(),value.description(),value.revision());
    }
    private ApplicationGroupApplication application(ApplicationGroupDirectory.Snapshot snapshot,String clientId) {
        var value=snapshot.applications().stream().filter(application->application.clientId().equals(clientId)).findFirst().orElseThrow(ApplicationGroupChanges.NotFound::new);
        return new ApplicationGroupApplication(snapshot.group().id(),value.clientId(),value.description(),value.audiences(),value.supportedScopeKeys(),value.revision(),directory.kind(value.clientId()));
    }
    private static ApplicationGroupScopeGrant grant(ApplicationGroupDirectory.Snapshot snapshot,String issuer,String subject) {
        var value=snapshot.userGrants().stream().filter(grant->grant.issuer().equals(issuer)&&grant.subject().equals(subject)).findFirst().orElseThrow(ApplicationGroupChanges.NotFound::new);
        return new ApplicationGroupScopeGrant(snapshot.group().id(),value.issuer(),value.subject(),value.scopeKeys(),value.revision(),snapshot.group().authorizationVersion());
    }
    private static <Target> Page<Target> page(List<Target> items,Request request,long revision) {
        ManagementInput.page(request.page(),request.size());
        int start=(int)Math.min(items.size(),Math.multiplyExact((long)request.page()-1,request.size()));
        return new Page<>(items.subList(start,Math.min(items.size(),start+request.size())),items.size(),request.page(),request.size(),revision);
    }
    private static Set<String> strings(JsonNode value) {
        if(value==null || !value.isArray() || value.size()>1000)throw new IllegalArgumentException("String array required");
        var result=new java.util.HashSet<String>();
        for(var item:value)if(!item.isTextual() || item.textValue().isBlank() || !result.add(item.textValue()))throw new IllegalArgumentException("Unique nonempty strings required");
        return Set.copyOf(result);
    }
}
