package io.kestra.extensions.rbac.management;

import io.kestra.webserver.oidc.ClientCredentialsV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.management.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class PublishedClientCredentials implements ClientCredentialManagement.Host {
    private final BeanProvider<ClientCredentialsV1> hosts;
    public PublishedClientCredentials(BeanProvider<ClientCredentialsV1> hosts) {this.hosts=hosts;}
    private <T> T invoke(java.util.function.Function<ClientCredentialsV1,T> work) {
        try {
            if(!hosts.isUnique())throw new ApplicationDirectoryAccess.DirectoryFailure(503,"credentials_unavailable");
            return work.apply(hosts.get());
        }catch(ClientCredentialsV1.CredentialException failure) {
            throw new ApplicationDirectoryAccess.DirectoryFailure(switch(failure.failure()) {
                case INVALID->400;case NOT_FOUND->404;case CONFLICT->412;case FORBIDDEN->403;case UNAVAILABLE->503;
            },switch(failure.failure()) {
                case INVALID->"invalid_request";case NOT_FOUND->"not_found";case CONFLICT->"revision_conflict";case FORBIDDEN->"access_denied";case UNAVAILABLE->"credentials_unavailable";
            });
        }catch(io.kestra.webserver.oidc.IdentityLifecycleGuardV1.Rejected failure) {
            throw new ApplicationDirectoryAccess.DirectoryFailure(switch(failure.failure()) {case LAST_ADMIN->409;case FORBIDDEN->403;case UNAVAILABLE->503;},
                failure.failure()==io.kestra.webserver.oidc.IdentityLifecycleGuardV1.Failure.LAST_ADMIN?"last_administrator":"credentials_unavailable");
        }catch(ApplicationDirectoryAccess.DirectoryFailure failure) {throw failure;}
        catch(RuntimeException failure) {throw new ApplicationDirectoryAccess.DirectoryFailure(503,"credentials_unavailable");}
    }
    private ClientCredentialManagement.State state(ClientCredentialsV1.CredentialStatus value) {
        return new ClientCredentialManagement.State(value.clientId(),ClientCredentialManagement.ClientType.valueOf(value.clientType().name()),
            value.secretSet(),value.maskedHint(),value.credentialRevision(),ClientCredentialManagement.RotationMode.valueOf(value.rotationMode().name()));
    }
    @Override public ClientCredentialManagement.State state(ClientCredentialManagement.Target target) {
        return invoke(host->state(host.status(target.clientId())));
    }
    @Override public ClientCredentialManagement.Rotation rotate(ClientCredentialManagement.Target target,long revision) {
        return invoke(host->{var result=host.rotate(target.clientId(),revision);return new ClientCredentialManagement.Rotation(result.status().clientId(),result.status().credentialRevision(),result.clientSecret());});
    }
}
