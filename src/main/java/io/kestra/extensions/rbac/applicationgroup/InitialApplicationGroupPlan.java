package io.kestra.extensions.rbac.applicationgroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.*;

public final class InitialApplicationGroupPlan {
    public static final String GROUP_ID="main";
    public static final String SCOPE_KEY="admin";
    public static final String NAMESPACE="company";

    private InitialApplicationGroupPlan() {}

    public static ApplicationGroupInstallationPlan create(String issuer,String installationId,String clientId,String subject) {
        if(subject==null || !subject.matches("[A-Za-z0-9._@+-]{1,128}"))
            throw new IllegalArgumentException("Invalid first-account subject");
        var actions=Set.of("READ","LIST","CREATE","UPDATE","DELETE","EXECUTE","ACCESS_OUTPUTS");
        var grants=new ArrayList<Grant>();
        for(var resource:List.of(Resource.FLOW,Resource.EXECUTION,Resource.TRIGGER,Resource.NAMESPACE,
            Resource.KVSTORE,Resource.SECRET,Resource.NAMESPACE_FILE,Resource.LOG,Resource.METRIC,
            Resource.DASHBOARD,Resource.BLUEPRINT,Resource.MCP_SERVER,Resource.AI,Resource.METADATA)) {
            if(resource==Resource.METADATA)
                grants.add(new Grant("TENANT","main",null,false,resource.name(),Set.of("READ","LIST"),Set.of(),true));
            else if(resource==Resource.MCP_SERVER)
                grants.add(new Grant("TENANT","main",null,false,resource.name(),Set.of("READ","LIST","CREATE","UPDATE","DELETE"),Set.of(),true));
            else grants.add(new Grant("NAMESPACE","main",NAMESPACE,true,resource.name(),actions,Set.of(),true));
        }
        var management=List.of(
            new ManagementGrant(ManagementDomain.IDENTITY,Set.of(ManagementAction.READ,ManagementAction.MANAGE)),
            new ManagementGrant(ManagementDomain.LOCAL_POLICY,Set.of(ManagementAction.READ,ManagementAction.MANAGE),Set.of(GROUP_ID)),
            new ManagementGrant(ManagementDomain.BUSINESS_INSTANCE,Set.of(ManagementAction.READ,ManagementAction.MANAGE)));
        var permission=new ApplicationPermission("initial-administrator","Initial administrator",
            "Explicit identity, local-policy and business-instance administration; additional company resource grants",Set.of(SCOPE_KEY),grants,management,"",true,1);
        var group=new Group(GROUP_ID,"Kestra","Created during first-account setup",true,1,1);
        var scope=new Scope(SCOPE_KEY,"Administrator","Explicit initial administration and additional company resource grants",1);
        var application=new Application(clientId,"Kestra Web UI",Set.of(issuer.replaceAll("/+$","")+"/api/v1/main/mcp/default"),List.of(permission),1);
        var grant=new UserGrant(issuer,subject,Set.of(SCOPE_KEY),1);
        return new ApplicationGroupInstallationPlan(2,installationId,issuer,clientId,GROUP_ID,
            List.of(new ApplicationGroupInstallationPlan.Entry(group,List.of(scope),List.of(application),List.of(grant))));
    }
}
