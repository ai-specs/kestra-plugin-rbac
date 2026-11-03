package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.InitialApplicationGroupPlan;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.OidcInitialAccountParticipantV1;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

@Singleton
@Requires(property="kestra.rbac.enabled",value="true")
@Requires(property="kestra.rbac.application-groups.enabled",value="true")
public final class InitialApplicationGroupParticipant implements OidcInitialAccountParticipantV1 {
    private final ApplicationGroupDirectoryStore store;
    private final OidcProtocolStateV1 protocol;
    private final String issuer;
    private final String loginClientId;

    public InitialApplicationGroupParticipant(ApplicationGroupDirectoryStore store,OidcProtocolStateV1 protocol,
        @Value("${kestra.oidc.issuer}") String issuer,
        @Value("${micronaut.security.oauth2.clients.kestra.client-id}") String loginClientId) {
        this.store=Objects.requireNonNull(store);this.protocol=Objects.requireNonNull(protocol);
        this.issuer=Objects.requireNonNull(issuer);this.loginClientId=Objects.requireNonNull(loginClientId);
    }

    @Override public void initialize(Connection connection,InitialAccount account)throws SQLException {
        Objects.requireNonNull(account);
        if(!issuer.equals(account.issuer()) || !loginClientId.equals(account.configuredLoginClientId()))
            throw new IllegalArgumentException("Initial-account context does not match deployment");
        var plan=InitialApplicationGroupPlan.create(account.issuer(),account.installationId(),account.configuredLoginClientId(),account.subject());
        protocol.lockSecurityState(connection);
        try(var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next())if(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT).startsWith("rbac_"))
                throw new IllegalStateException("Existing RBAC storage requires explicit maintenance, not first installation");
        }
        store.initializeSchema(connection);
        store.initializeBuiltin(connection,plan.applicationGroups().getFirst().snapshot());
    }
}
