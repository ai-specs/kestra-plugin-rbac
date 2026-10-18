package io.kestra.extensions.rbac;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
@Singleton
@Context
@Requires(property="kestra.rbac.enabled",value="true")
public final class ProjectRuntimeGuard {
    public ProjectRuntimeGuard(RbacConfiguration config,
        @Value("${kestra.rbac.typed.enabled:false}") boolean typed,
        @Value("${kestra.rbac.evaluation.enabled:false}") boolean evaluation,
        @Value("${kestra.rbac.application-projects.enabled:false}") boolean projects){
        if(!typed || !evaluation || !projects || !"database".equals(config.getPolicySource()))
            throw new IllegalArgumentException("Schema 2 requires database, typed, evaluation and application-projects");
        if(config.hasLegacyFieldsConfigured() || !config.getManagementAdminSubjects().isEmpty() || !config.getRoles().isEmpty() || !config.getSubjects().isEmpty()
            || !config.getRoleMapping().isEmpty() || !config.getInstanceAdminRoles().isEmpty())
            throw new IllegalArgumentException("Legacy subject, role and administrator configuration is forbidden");
    }
}
