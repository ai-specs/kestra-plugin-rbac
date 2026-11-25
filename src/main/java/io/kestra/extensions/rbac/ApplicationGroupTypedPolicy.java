package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.*;
import io.kestra.webserver.rbac.TypedAuthorization;
import java.util.*;

/** Typed resource adapter. No legacy principal or role participates in a decision. */
final class ApplicationGroupTypedPolicy {
    static TypedAuthorization.Decision authorize(ApplicationGroupRuntimePolicies.Context context,TypedAuthorization.Operation op){
        try{
            if(op==null || op.contractVersion()!=TypedAuthorization.CONTRACT_VERSION || !"main".equals(op.tenant())
                || op.resource()==null || op.action()==null || op.queryDomain()==null)return deny();
            var identity=context.identity();var policy=context.snapshot().policy();var local=policy.applicationGroups().get(identity.applicationGroupId());
            if(local==null || !context.hasTrustedLoginAudience() && !local.audiences().containsAll(identity.audiences()))return deny();
            var auth=new ApplicationGroupAuthorization(policy);var directory=context.directories().get(identity.applicationGroupId());
            var id=new ApplicationGroupAuthorization.Identity(identity.subject(),identity.issuer(),identity.audiences().stream().sorted().findFirst().orElseThrow(),identity.applicationGroupId(),context.upperBound(identity.applicationGroupId()).applicationGroup().revision(),identity.applicationGroupScopes(),identity.protocolScopes(),identity.tenant(),context.hasTrustedLoginAudience());
            String resource=op.resource().name(),action=op.action().name();
            boolean query=op.coverage()==TypedAuthorization.Coverage.COMPLETE_QUERY;
            if(query && (!Set.of("READ","LIST").contains(action) || op.sideEffect()!=TypedAuthorization.SideEffect.READ_ONLY))return deny();
            if(resource.equals("EXTENSION_ROUTE")){
                if(op.resourceId()==null || op.resourceId().isBlank() || !op.resourceId().equals(op.resourceId().trim())
                    || op.resourceId().chars().anyMatch(Character::isISOControl) || !action.equals("EXECUTE"))return deny();
                var decision=auth.authorize(id,new ApplicationGroupAuthorization.Request(op.tenant(),op.namespace(),resource,action,op.resourceId(),false),directory);
                return decision.allowed()?TypedAuthorization.Decision.allow(op.queryDomain()):deny();
            }
            if(resource.equals("EXTENSION")){
                if(!"rbac".equals(op.resourceId()) || !Set.of("READ","UPDATE").contains(action))return deny();
                boolean allowed=false;
                for(String target:context.managementTargets()){
                    for(var domain:List.of(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementDomain.LOCAL_POLICY)){
                        allowed|=context.authorizeManagement(domain,ApplicationGroupPolicy.ManagementAction.MANAGE,target);
                        if(action.equals("READ"))allowed|=context.authorizeManagement(domain,ApplicationGroupPolicy.ManagementAction.READ,target);
                    }
                }
                return allowed?TypedAuthorization.Decision.allow(op.queryDomain()):deny();
            }
            if(resource.equals("SYSTEM"))return deny();
            var requested=op.queryDomain();
            if(query){
                var request=new ApplicationGroupAuthorization.Request(op.tenant(),op.namespace(),resource,action,op.resourceId(),true,ApplicationGroupAuthorization.Domain.all());
                var decision=auth.authorizeList(id,request,directory);if(!decision.allowed())return deny();
                var ceiling=TypedAuthorization.QueryDomain.structured(decision.domain(),true,false);
                var bounded=requested.intersect(ceiling);
                if(bounded.effectiveDomain().terms().isEmpty())return deny();
                return TypedAuthorization.Decision.allow(bounded);
            }
            var decision=auth.authorize(id,new ApplicationGroupAuthorization.Request(op.tenant(),op.namespace(),resource,action,op.resourceId(),false),directory);
            return decision.allowed()?TypedAuthorization.Decision.allow(requested):deny();
        }catch(RuntimeException failure){return deny();}
    }
    private static TypedAuthorization.Decision deny(){return TypedAuthorization.Decision.deny("Project policy denied");}
}
