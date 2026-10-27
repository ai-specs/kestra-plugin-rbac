package io.kestra.extensions.rbac.declaration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.migration.MigrationScript;
import javax.sql.DataSource;

public final class ApplicationScopeDeclarationMigration implements MigrationScript {
    private final DataSource source;

    public ApplicationScopeDeclarationMigration(DataSource source) {
        this.source = source;
    }

    @Override public String scriptId() { return "2.0.02-extension-rbac-application-scope-declaration"; }
    @Override public String description() { return "Application scope permission declaration storage"; }
    @Override public String checksum() { return "rbac-application-scope-declaration-v1"; }

    @Override
    public void migrate() {
        new ApplicationScopeDeclarationStore(source, new ObjectMapper()).initializeSchema();
    }
}
