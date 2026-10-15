package io.kestra.extensions.rbac.store;

import javax.sql.DataSource;
import io.kestra.core.migration.MigrationScript;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "kestra.rbac.policy-source", value = "database")
@Requires(property = "kestra.repository.type", value = "postgres")
public final class PolicyStoreMigration implements MigrationScript {
    private final DataSource source;
    public PolicyStoreMigration(DataSource source) { this.source = source; }
    @Override public String scriptId() { return "2.0.01-extension-rbac-policy-v1"; }
    @Override public String description() { return "RBAC extension policy storage schema version 1"; }
    @Override public String checksum() { return "rbac-policy-schema-v1"; }
    @Override public void migrate() {
        new JdbcPolicyStore(io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(source),
            new PolicyDocumentValidator()).initializeSchema();
    }
}
