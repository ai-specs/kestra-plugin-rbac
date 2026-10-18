package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.project.*;
import io.kestra.webserver.rbac.TypedAuthorization;
import java.util.*;

/** Typed resource adapter. No legacy principal or role participates in a decision. */
final class ProjectTypedPolicy {
    private static final Set<String> BUSINESS=Set.of("FLOW","EXECUTION","NAMESPACE","LOG","METRIC","TRIGGER","KVSTORE","NAMESPACE_FILE","METADATA","SYSTEM","MCP_SERVER","DASHBOARD","BLUEPRINT");
    static TypedAuthorization.Decision authorize(ProjectRuntimePolicies.Context context,TypedAuthorization.Operation op){
        try{
            if(op==null || op.contractVersion()!=TypedAuthorization.CONTRACT_VERSION || !"main".equals(op.tenant())
                || op.resource()==null || op.action()==null || op.queryDomain()==null)return deny();
            var identity=context.identity();var policy=context.snapshot().policy();var local=policy.applicationProjects().get(identity.projectId());
            if(local==null || !local.audiences().containsAll(identity.audiences()))return deny();
            var auth=new ProjectAuthorization(policy);var directory=context.directories().get(identity.projectId());
            var id=new ProjectAuthorization.Identity(identity.subject(),identity.issuer(),identity.audiences().stream().sorted().findFirst().orElseThrow(),identity.projectId(),identity.directoryRevision(),identity.projectScopes(),identity.protocolScopes(),identity.tenant());
            String resource=op.resource().name(),action=op.action().name();
            boolean query=op.coverage()==TypedAuthorization.Coverage.COMPLETE_QUERY;
            if(query && (!Set.of("READ","LIST").contains(action) || op.sideEffect()!=TypedAuthorization.SideEffect.READ_ONLY))return deny();
            if(resource.equals("EXTENSION")){
                if(!"rbac".equals(op.resourceId()) || !Set.of("READ","UPDATE").contains(action))return deny();
                boolean allowed=false;
                for(String target:context.directories().keySet()){
                    allowed|=context.authorizeReconciliation(target);
                    for(var domain:List.of(ProjectPolicy.ManagementDomain.IDENTITY,ProjectPolicy.ManagementDomain.LOCAL_POLICY)){
                        allowed|=context.authorizeManagement(domain,ProjectPolicy.ManagementAction.MANAGE,target);
                        if(action.equals("READ"))allowed|=context.authorizeManagement(domain,ProjectPolicy.ManagementAction.READ,target);
                    }
                }
                return allowed?TypedAuthorization.Decision.allow(op.queryDomain()):deny();
            }
            ProjectPolicy.ManagementAction management=Set.of("READ","LIST").contains(action)?ProjectPolicy.ManagementAction.READ:
                Set.of("CREATE","UPDATE","DELETE","EXECUTE","ACCESS_OUTPUTS").contains(action)?ProjectPolicy.ManagementAction.MANAGE:null;
            if(BUSINESS.contains(resource) && management!=null && context.authorizeManagement(ProjectPolicy.ManagementDomain.BUSINESS_INSTANCE,management,identity.projectId()))
                return TypedAuthorization.Decision.allow(op.queryDomain());
            var requested=op.queryDomain();
            if(query){
                // The model computes unrestricted grant terms; the public query algebra applies the request and exclusions.
                var roots=new HashSet<String>();var exact=new HashSet<String>();
                for(var scope:local.scopes().values())for(var grant:scope.grants())if(grant.namespace()!=null){if(grant.descendants())roots.add(grant.namespace());else exact.add(grant.namespace());}
                var request=new ProjectAuthorization.Request(op.tenant(),op.namespace(),resource,action,op.resourceId(),true,new ProjectAuthorization.Domain(exact,roots));
                var decision=auth.authorizeList(id,request,directory);if(!decision.allowed())return deny();
                // METADATA has no resource identifier. Its explicit tenant grant is an
                // authorization of shared metadata, not an ID-filtered repository list.
                if(resource.equals("METADATA"))return TypedAuthorization.Decision.allow(requested);
                var terms=new ArrayList<TypedAuthorization.QueryDomain.ResourceDomain>();
                for(var term:decision.terms())terms.add(new TypedAuthorization.QueryDomain.ResourceDomain(List.copyOf(term.namespaces().exact()),List.copyOf(term.namespaces().subtrees()),List.of(),List.copyOf(term.ids()),
                    term.tenantResource()?TypedAuthorization.QueryDomain.ResourceScope.TENANT:TypedAuthorization.QueryDomain.ResourceScope.NAMESPACE,
                    term.allIds()?TypedAuthorization.QueryDomain.IdScope.ALL_IDS:TypedAuthorization.QueryDomain.IdScope.RESTRICTED_IDS));
                var ceiling=new TypedAuthorization.QueryDomain(List.of(),List.of(),List.of(),true,false,TypedAuthorization.DomainKind.GLOBAL,terms);
                var bounded=requested.intersect(ceiling);
                if(bounded.kind()!=TypedAuthorization.DomainKind.GLOBAL && bounded.resourceDomains().isEmpty()
                    && bounded.exactNamespaces().isEmpty() && bounded.namespaceSubtrees().isEmpty())return deny();
                return TypedAuthorization.Decision.allow(bounded);
            }
            var decision=auth.authorize(id,new ProjectAuthorization.Request(op.tenant(),op.namespace(),resource,action,op.resourceId(),false),directory);
            return decision.allowed()?TypedAuthorization.Decision.allow(requested):deny();
        }catch(RuntimeException failure){return deny();}
    }
    private static TypedAuthorization.Decision deny(){return TypedAuthorization.Decision.deny("Project policy denied");}
}
