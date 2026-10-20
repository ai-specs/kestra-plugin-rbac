package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.webserver.oidc.ApplicationDirectoryV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static io.kestra.extensions.rbac.management.ManagementInput.*;

/** Direct public-directory proxy. No host tables, token decoding, local assignments or role inference. */
@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(beans=ApplicationDirectoryV1.class)
public final class PublishedApplicationDirectoryAccess implements ApplicationDirectoryAccess {
    private final BeanProvider<ApplicationDirectoryV1> providers;
    public PublishedApplicationDirectoryAccess(BeanProvider<ApplicationDirectoryV1> providers) {this.providers=providers;}
    @Override public Object invoke(Request request) {
        try {
            if(!providers.isUnique())throw new DirectoryFailure(503,"directory_unavailable");
            var directory=providers.get();
            if(request.project()!=null) checkProject(directory,request);
            Object result=switch(request.resource()) {
                case PROJECT -> project(directory,request);
                case SCOPE -> scope(directory,request);
                case CLIENT -> client(directory,request);
                case ASSIGNMENT -> assignment(directory,request);
            };
            boolean read=request.operation()==Operation.FIND || request.operation()==Operation.LIST;
            if(read && request.project()!=null) checkProject(directory,request);
            return result;
        } catch(ApplicationDirectoryV1.DirectoryException failure) {
            throw switch(failure.failure()) {
                case INVALID -> new DirectoryFailure(400,"invalid_request");
                case NOT_FOUND -> new DirectoryFailure(404,"not_found");
                case CONFLICT -> new DirectoryFailure(request.operation()==Operation.CREATE?409:412,"revision_conflict");
                case FORBIDDEN -> new DirectoryFailure(403,"access_denied");
                case LAST_ADMIN -> new DirectoryFailure(409,"last_administrator");
                case UNAVAILABLE,UNSUPPORTED -> new DirectoryFailure(503,"directory_unavailable");
            };
        } catch(DirectoryFailure|ManagementInput.Failure failure) {throw failure;}
        catch(IllegalArgumentException failure) {throw new DirectoryFailure(400,"invalid_request");}
        catch(RuntimeException failure) {throw new DirectoryFailure(503,"directory_unavailable");}
    }
    private ApplicationDirectoryV1.Project checkProject(ApplicationDirectoryV1 directory,Request r) {
        var p=directory.project(r.project()).orElseThrow(()->new DirectoryFailure(404,"not_found"));
        if(!r.issuer().equals(p.issuer()) || !"main".equals(p.tenant()))throw new DirectoryFailure(403,"access_denied");
        if(p.revision()!=r.directoryRevision())throw new DirectoryFailure(409,"directory_changed");
        return p;
    }
    private Object project(ApplicationDirectoryV1 d,Request r) {
        return switch(r.operation()) {
            case LIST -> {
                var page=d.projects(r.page(),r.size());
                if(page.items().stream().anyMatch(p->!r.issuer().equals(p.issuer()) || !"main".equals(p.tenant())))
                    throw new DirectoryFailure(503,"directory_unavailable");
                yield page;
            }
            case FIND -> checkProject(d,r);
            case CREATE -> {
                var n=r.body();var result=d.createProject(new ApplicationDirectoryV1.ProjectInput(text(n,"id"),text(n,"tenant"),text(n,"displayName")));
                if(!r.issuer().equals(result.issuer()) || !"main".equals(result.tenant()))throw new DirectoryFailure(503,"directory_unavailable");
                yield result;
            }
            case UPDATE -> d.updateProject(r.project(),r.revision(),text(r.body(),"displayName"),bool(r.body(),"active"));
            case DELETE -> throw new DirectoryFailure(405,"method_not_allowed");
        };
    }
    private Object scope(ApplicationDirectoryV1 d,Request r) {
        return switch(r.operation()) {
            case LIST -> d.scopes(r.project(),r.page(),r.size());
            case FIND -> d.scope(r.project(),r.key()).orElseThrow(()->new DirectoryFailure(404,"not_found"));
            case CREATE -> d.createScope(r.project(),r.revision(),scopeInput(r.body()));
            case UPDATE -> d.updateScope(r.project(),r.key(),r.revision(),scopeInput(r.body()));
            case DELETE -> {d.deleteScope(r.project(),r.key(),r.revision());yield java.util.Map.of("deleted",true);}
        };
    }
    private Object client(ApplicationDirectoryV1 d,Request r) {
        return switch(r.operation()) {
            case LIST -> d.clients(r.project(),r.page(),r.size());
            case FIND -> findClient(d,r);
            case CREATE -> d.approveClient(r.project(),r.revision(),membership(r.body()));
            case UPDATE -> d.updateClient(r.project(),r.key(),r.revision(),membership(r.body()));
            case DELETE -> {d.removeClient(r.project(),r.key(),r.revision());yield java.util.Map.of("deleted",true);}
        };
    }
    private ApplicationDirectoryV1.ClientMembership findClient(ApplicationDirectoryV1 d,Request r) {
        // No public client detail method. Read complete, bounded pages under the same project revision.
        var ids=new HashSet<String>();long total=-1;ApplicationDirectoryV1.ClientMembership found=null;
        for(int page=1;page<=100;page++) {
            checkProject(d,r);var batch=d.clients(r.project(),page,100);
            if(batch.page()!=page || batch.size()!=100 || batch.total()<0 || batch.total()>10000
                || total>=0 && batch.total()!=total)throw new DirectoryFailure(409,"directory_changed");
            total=batch.total();
            for(var member:batch.items()) {
                if(!r.project().equals(member.projectId()) || !ids.add(member.clientId()))throw new DirectoryFailure(409,"directory_changed");
                if(member.clientId().equals(r.key()))found=member;
            }
            if(ids.size()==total) {checkProject(d,r);if(found==null)throw new DirectoryFailure(404,"not_found");return found;}
            if(batch.items().size()!=100 || ids.size()>total)throw new DirectoryFailure(409,"directory_changed");
        }
        throw new DirectoryFailure(409,"directory_changed");
    }
    private Object assignment(ApplicationDirectoryV1 d,Request r) {
        return switch(r.operation()) {
            case LIST -> d.assignments(r.project(),r.page(),r.size());
            case FIND -> d.assignment(r.project(),r.issuer(),r.key()).orElseThrow(()->new DirectoryFailure(404,"not_found"));
            case CREATE -> d.assign(r.project(),r.revision(),assignment(r.body(),r.issuer()));
            case UPDATE -> d.replaceAssignment(r.project(),r.issuer(),r.key(),r.revision(),assignment(r.body(),r.issuer()));
            case DELETE -> {d.revokeAssignment(r.project(),r.issuer(),r.key(),r.revision());yield java.util.Map.of("deleted",true);}
        };
    }
    private ApplicationDirectoryV1.AssignmentInput assignment(JsonNode n,String issuer) {
        if(!issuer.equals(text(n,"issuer")))throw new DirectoryFailure(403,"access_denied");
        return new ApplicationDirectoryV1.AssignmentInput(issuer,subject(text(n,"subject")),strings(n.get("projectScopes")));
    }
    private ApplicationDirectoryV1.MembershipInput membership(JsonNode n) {
        return new ApplicationDirectoryV1.MembershipInput(text(n,"clientId"),bool(n,"approved"),strings(n.get("audiences")),strings(n.get("allowedProjectScopes")),bool(n,"firstPartyUi"));
    }
    private ApplicationDirectoryV1.ScopeInput scopeInput(JsonNode n) {
        var resources=new ArrayList<ApplicationDirectoryV1.ResourceIntent>();
        for(JsonNode value:array(n.get("resourceIntents"),1000)) {
            fields(value,Set.of("vocabularyVersion","resourceType","namespaces","resourceIds","actions"));
            if(!"kestra-resources-v1".equals(text(value,"vocabularyVersion")))throw new IllegalArgumentException();
            var ns=value.get("namespaces");fields(ns,Set.of("kind","exact","subtrees"));
            var ids=value.get("resourceIds");fields(ids,Set.of("kind","ids"));
            String type=text(value,"resourceType");io.kestra.extensions.rbac.project.ProjectPolicy.Resource.valueOf(type);
            var namespaceKind=ApplicationDirectoryV1.DomainKind.valueOf(text(ns,"kind"));
            if(!Set.of("METADATA","MCP_SERVER").contains(type) && namespaceKind!=ApplicationDirectoryV1.DomainKind.RESTRICTED)throw new IllegalArgumentException();
            Set<String> actions=strings(value.get("actions"));if(actions.isEmpty())throw new IllegalArgumentException();
            actions.forEach(action->io.kestra.webserver.rbac.RbacAuthorizer.Action.valueOf(action));
            resources.add(new ApplicationDirectoryV1.ResourceIntent(text(value,"vocabularyVersion"),type,
                new ApplicationDirectoryV1.NamespaceDomain(namespaceKind,strings(ns.get("exact")),strings(ns.get("subtrees"))),
                new ApplicationDirectoryV1.ResourceIdDomain(ApplicationDirectoryV1.DomainKind.valueOf(text(ids,"kind")),strings(ids.get("ids"))),actions));
        }
        var management=new ArrayList<ApplicationDirectoryV1.ManagementIntent>();var domains=new HashSet<String>();
        for(JsonNode value:array(n.get("managementIntents"),3)) {
            fields(value,Set.of("domain","actions","projectIds"));String domain=text(value,"domain");
            if(!domains.add(domain))throw new IllegalArgumentException();
            Set<String> actions=strings(value.get("actions"));if(actions.isEmpty())throw new IllegalArgumentException();
            management.add(new ApplicationDirectoryV1.ManagementIntent(ApplicationDirectoryV1.ManagementDomain.valueOf(domain),actions,strings(value.get("projectIds"))));
        }
        return new ApplicationDirectoryV1.ScopeInput(text(n,"scope"),text(n,"name"),text(n,"description"),bool(n,"active"),List.copyOf(resources),List.copyOf(management));
    }
    private static JsonNode array(JsonNode value,int max) {
        if(value==null || !value.isArray() || value.size()>max)throw new IllegalArgumentException();return value;
    }
    private static Set<String> strings(JsonNode value) {
        var result=new HashSet<String>();for(JsonNode item:array(value,1000)) {
            if(!item.isTextual() || item.textValue().isBlank() || !item.textValue().equals(item.textValue().trim())
                || item.textValue().codePoints().anyMatch(Character::isISOControl) || !result.add(item.textValue()))throw new IllegalArgumentException();
        }return Set.copyOf(result);
    }
    private static void fields(JsonNode value,Set<String> fields) {
        if(value==null || !value.isObject() || value.size()!=fields.size())throw new IllegalArgumentException();
        value.fieldNames().forEachRemaining(key->{if(!fields.contains(key) || value.get(key).isNull())throw new IllegalArgumentException();});
    }
}
