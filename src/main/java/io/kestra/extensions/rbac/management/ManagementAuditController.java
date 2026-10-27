package io.kestra.extensions.rbac.management;

import io.kestra.extensions.rbac.ApplicationGroupRuntimePolicies;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy;
import io.kestra.extensions.rbac.store.ManagementAuditStore;
import io.micronaut.context.annotation.Requires;
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
import static io.kestra.extensions.rbac.management.ManagementInput.*;

@Controller("/api/v1/{tenant}/extensions/rbac/audit")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.IO)
@Secured(SecurityRule.IS_AUTHENTICATED)
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.typed.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class ManagementAuditController {
    private final ApplicationGroupRuntimePolicies policies;
    private final ApplicationGroupDirectoryService directory;
    private final ManagementAuditStore audit;
    public ManagementAuditController(ApplicationGroupRuntimePolicies policies,ApplicationGroupDirectoryService directory,ManagementAuditStore audit) {
        this.policies=policies;this.directory=directory;this.audit=audit;
    }
    @Get("{?page,size}")
    public HttpResponse<?> list(String tenant,Authentication authentication,@QueryValue(defaultValue="1") int page,@QueryValue(defaultValue="25") int size) {
        try {
            if(!"main".equals(tenant))throw new Failure(404,"not_found");
            if(authentication==null)throw new Failure(401,"authentication_required");
            page(page,size);
            try(var admitted=policies.open(authentication,tenant)) {
                var result=directory.transaction(connection->{
                    try(var context=policies.open(connection,admitted.identity(),tenant)) {
                        if(!context.authorizeManagement(ApplicationGroupPolicy.ManagementDomain.IDENTITY,ApplicationGroupPolicy.ManagementAction.READ,context.identity().applicationGroupId()))
                            throw new Failure(403,"access_denied");
                        return audit.readIssuer(connection,context.identity().issuer(),page,size);
                    }
                });
                return HttpResponse.ok(result).header("Cache-Control","no-store");
            }
        }catch(Failure failure){return error(failure.status,failure.code);}
        catch(ApplicationGroupRuntimePolicies.Rejected failure){return error(switch(failure.failure()) {
            case DIRECTORY_UNAVAILABLE,INVALID_DIRECTORY,POLICY_UNAVAILABLE->503;default->403;
        },"access_denied");}
        catch(Exception failure){return error(503,"audit_unavailable");}
    }
    private static HttpResponse<?> error(int status,String code) {
        return HttpResponse.status(HttpStatus.valueOf(status)).body(Map.of("code",code)).header("Cache-Control","no-store");
    }
}
