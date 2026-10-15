package io.kestra.extensions.rbac.management;

import java.util.Collection;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.webserver.ui.UiExtensionDescriptorV1;
import io.kestra.webserver.ui.UiExtensionProviderV1;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.typed.enabled", value = "true")
@Requires(property = "kestra.rbac.management.enabled", value = "true")
public final class ManagementPageProvider implements UiExtensionProviderV1 {
    private final UiExtensionDescriptorV1 descriptor;

    public ManagementPageProvider() {
        try (var input = ManagementPageProvider.class.getResourceAsStream("/ui-extensions/rbac/manifest.json")) {
            if (input == null) throw new IllegalStateException("Management UI resources missing");
            descriptor = new ObjectMapper().readValue(input, UiExtensionDescriptorV1.class);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Invalid management UI manifest", failure);
        }
    }

    @Override
    public Collection<UiExtensionDescriptorV1> extensions() { return List.of(descriptor); }
}
