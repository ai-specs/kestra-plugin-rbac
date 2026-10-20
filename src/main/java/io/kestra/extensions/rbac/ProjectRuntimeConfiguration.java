package io.kestra.extensions.rbac;

import io.kestra.extensions.rbac.project.ProjectPolicyParser;
import io.kestra.extensions.rbac.store.ProjectPolicyStore;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

@Factory
public final class ProjectRuntimeConfiguration {
    @Singleton
    @Requires(property="kestra.rbac.enabled",value="true")
    @Requires(property="kestra.rbac.policy-source",value="database",defaultValue="database")
    @Requires(beans=DataSource.class)
    public ProjectPolicyStore projectPolicyStore(DataSource source){
        var store=new ProjectPolicyStore(io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(source),new ProjectPolicyParser());
        return store;
    }

    @Singleton
    @Requires(property="kestra.rbac.enabled",value="true")
    @Requires(property="kestra.rbac.application-groups.enabled",value="true")
    @Requires(beans=DataSource.class)
    public ApplicationScopeDeclarationStore applicationScopeDeclarationStore(DataSource source, ObjectMapper mapper) {
        return new ApplicationScopeDeclarationStore(source, mapper);
    }
}
