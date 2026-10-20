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
        @Value("${kestra.rbac.application-groups.enabled:false}") boolean applicationGroups){
        if(!typed || !evaluation || !applicationGroups || !"database".equals(config.getPolicySource()))
            throw new IllegalArgumentException("Application groups require database, typed authorization and evaluation");
        if(config.hasLegacyFieldsConfigured() || !config.getManagementAdminSubjects().isEmpty() || !config.getRoles().isEmpty() || !config.getSubjects().isEmpty()
            || !config.getRoleMapping().isEmpty() || !config.getInstanceAdminRoles().isEmpty())
            throw new IllegalArgumentException("Legacy subject, role and administrator configuration is forbidden");
    }
}
