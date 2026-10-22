package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicyParser;
import io.kestra.extensions.rbac.store.ApplicationGroupPolicyStore;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.extensions.rbac.store.ApplicationGroupDirectoryStore;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

@Factory
public final class ApplicationGroupRuntimeConfiguration {
    @Singleton
    @Requires(property="kestra.rbac.enabled",value="true")
    @Requires(property="kestra.rbac.policy-source",value="database",defaultValue="database")
    @Requires(property="kestra.rbac.application-groups.enabled",value="true")
    @Requires(beans=DataSource.class)
    public ApplicationGroupPolicyStore applicationGroupPolicyStore(DataSource source,OidcProtocolStateV1 protocol){
        var store=new ApplicationGroupPolicyStore(io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(source),new ApplicationGroupPolicyParser(),protocol::lockSecurityState);
        return store;
    }

    @Singleton
    @Requires(property="kestra.rbac.enabled",value="true")
    @Requires(property="kestra.rbac.application-groups.enabled",value="true")
    @Requires(beans=DataSource.class)
    public ApplicationGroupDirectoryStore applicationGroupDirectoryStore(DataSource source,OidcProtocolStateV1 protocol) {
        return new ApplicationGroupDirectoryStore(io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(source),protocol::lockSecurityState);
    }

    @Singleton
    @Requires(property="kestra.rbac.enabled",value="true")
    @Requires(property="kestra.rbac.application-groups.enabled",value="true")
    @Requires(beans=DataSource.class)
    public ApplicationScopeDeclarationStore applicationScopeDeclarationStore(DataSource source, ObjectMapper mapper) {
        return new ApplicationScopeDeclarationStore(source, mapper);
    }
}
