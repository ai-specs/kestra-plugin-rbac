package io.kestra.extensions.rbac.store;

import javax.sql.DataSource;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Factory
public final class PolicyStoreFactory {
    @Singleton
    @Requires(property = "kestra.rbac.enabled", value = "true")
    @Requires(beans = DataSource.class)
    @Requires(property = "kestra.rbac.policy-source", value = "database", defaultValue = "database")
    public JdbcPolicyStore policyStore(DataSource source) {
        var store = new JdbcPolicyStore(io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(source),
            new PolicyDocumentValidator());
        store.initializeSchema();
        return store;
    }
}
