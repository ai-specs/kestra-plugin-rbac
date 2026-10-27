package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
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
import java.util.LinkedHashMap;
import java.util.Map;
import static io.kestra.extensions.rbac.management.ManagementInput.*;

@Controller("/api/v1/{tenant}/extensions/rbac/application-groups/{group}/applications/{client}/credentials")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.typed.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ClientCredentialController {
    private final ApplicationGroupRuntimePolicies policies;
    private final ApplicationGroupDirectoryService directory;
    private final BeanProvider<ClientCredentialManagement.Host> hosts;

    public ClientCredentialController(ApplicationGroupRuntimePolicies policies,ApplicationGroupDirectoryService directory,
        BeanProvider<ClientCredentialManagement.Host> hosts) {
        this.policies=policies;this.directory=directory;this.hosts=hosts;
    }

    @Get
    public HttpResponse<?> status(String tenant,String group,String client,Authentication authentication) {
        return request(tenant,group,client,authentication,false,null,null);
    }

    @Post("/rotate")
    public HttpResponse<?> rotate(String tenant,String group,String client,Authentication authentication,
        @Header("If-Match") @Nullable String match,@Body @Nullable byte[] body) {
        return request(tenant,group,client,authentication,true,match,body);
    }

    private HttpResponse<?> request(String tenant,String group,String client,Authentication authentication,
        boolean rotate,String match,byte[] body) {
        try {
            if(!"main".equals(tenant))throw new Failure(404,"not_found");
            var target=new ClientCredentialManagement.Target(project(group),opaque(client));
            if(authentication==null)throw new Failure(401,"authentication_required");
            long expected=rotate?revision(match):0;
            if(rotate && body!=null && body.length>0)throw new Failure(400,"invalid_request");
            try(var admitted=policies.open(authentication,tenant)) {
                var service=new ClientCredentialManagement(host(),(requested,action)->directory.transaction(connection->{
                    try(var context=policies.open(connection,admitted.identity(),tenant)) {
                        if(!context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,
                            action==ClientCredentialManagement.Action.READ?ApplicationGroupPolicy.ManagementAction.READ:ApplicationGroupPolicy.ManagementAction.MANAGE,
                            requested.applicationGroupId()))throw new Failure(403,"access_denied");
                        if(directory.find(connection,requested.applicationGroupId()).applications().stream()
                            .noneMatch(application->application.clientId().equals(requested.clientId())))throw new Failure(404,"not_found");
                        return null;
                    }
                }));
                if(rotate) {
                    var receipt=service.rotate(target,expected);
                    return HttpResponse.ok(receipt).header("ETag","\""+receipt.credentialRevision()+"\"").header("Cache-Control","no-store");
                }
                var state=service.state(target);var result=new LinkedHashMap<String,Object>();
                result.put("clientId",state.clientId());result.put("clientType",state.clientType().name().toLowerCase(java.util.Locale.ROOT));
                result.put("secretSet",state.secretSet());result.put("maskedHint",state.maskedHint());
                result.put("credentialRevision",state.credentialRevision());
                result.put("rotationMode",state.rotationMode().name().toLowerCase(java.util.Locale.ROOT).replace('_','-'));
                return HttpResponse.ok(result).header("ETag","\""+state.credentialRevision()+"\"").header("Cache-Control","no-store");
            }
        }catch(Failure failure){return error(failure.status,failure.code);}
        catch(ApplicationDirectoryAccess.DirectoryFailure failure){return error(failure.status(),failure.code());}
        catch(ApplicationGroupRuntimePolicies.Rejected failure){return error(switch(failure.failure()) {
            case DIRECTORY_UNAVAILABLE,INVALID_DIRECTORY,POLICY_UNAVAILABLE->503;default->403;
        },"access_denied");}
        catch(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupChanges.NotFound failure){return error(404,"not_found");}
        catch(IllegalArgumentException failure){return error(400,"invalid_request");}
        catch(Exception failure){return error(503,"credentials_unavailable");}
    }

    private ClientCredentialManagement.Host host() {
        if(!hosts.isUnique())throw new Failure(503,"credentials_unavailable");
        return hosts.get();
    }
    private static HttpResponse<?> error(int status,String code) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code",code)).header("Cache-Control","no-store");
    }
}
