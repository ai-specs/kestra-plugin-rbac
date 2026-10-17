package io.kestra.extensions.rbac.store;

import java.util.Set;
import io.kestra.extensions.rbac.RbacConfiguration;

public record PolicySourceSelection(Source source, Set<String> managementAdminSubjects) {
    public enum Source { DATABASE }

    public PolicySourceSelection {
        PolicyDocumentValidator.require(source != null, "必须选择唯一策略来源。");
        managementAdminSubjects = Set.copyOf(managementAdminSubjects);
        managementAdminSubjects.forEach(PolicyDocumentValidator::concrete);
        if (source == Source.DATABASE) PolicyDocumentValidator.require(!managementAdminSubjects.isEmpty(), "数据库模式必须配置部署管理员 subject。");
    }

    public void validateConfiguration(RbacConfiguration configuration) {
        if (source == Source.DATABASE) {
            PolicyDocumentValidator.require(configuration.getRoles().isEmpty() && configuration.getSubjects().isEmpty()
                && configuration.getRoleMapping().isEmpty() && configuration.getInstanceAdminRoles().isEmpty(),
                "数据库模式不得合并配置策略或配置管理员角色。");
        }
    }
}
