package io.kestra.extensions.rbac.applicationgroup;

import java.util.Map;
import java.util.stream.Collectors;

public final class DirectoryCatalogTestData {
    private DirectoryCatalogTestData() {}

    public static ApplicationGroupAuthorization.ScopeCatalog catalog(ApplicationGroupPolicy.ApplicationGroup policy, long revision) {
        return new ApplicationGroupAuthorization.ScopeCatalog(policy.issuer(),policy.audiences(),policy.enabled(),revision,
            policy.scopes().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new ApplicationGroupAuthorization.DirectoryScope(entry.getKey(),entry.getValue().description()))));
    }
}
