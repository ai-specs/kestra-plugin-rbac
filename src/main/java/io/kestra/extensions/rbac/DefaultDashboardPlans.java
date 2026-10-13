package io.kestra.extensions.rbac;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import io.kestra.webserver.rbac.RbacAuthorizer.Kind;

final class DefaultDashboardPlans {
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final List<JsonNode> charts;
    DefaultDashboardPlans() {
        var defaults = new ArrayList<JsonNode>();
        for (String name : List.of("main", "flow", "namespace")) {
            try (var source = getClass().getResourceAsStream("/dashboards/default_" + name + "_definition.yaml")) {
                if (source == null) throw new IllegalStateException("Published default dashboard missing");
                for (JsonNode chart : yaml.readTree(source).path("charts")) defaults.add(chart);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid published dashboard", failure); }
        }
        charts = List.copyOf(defaults);
    }
    Kind resource(byte[] body) {
        try {
            if (body.length == 0 || body.length > 2_000_000) return null;
            JsonNode request = json.readTree(body);
            if (!request.isObject() || !request.path("chart").isTextual()) return null;
            var fields = request.fieldNames();
            while (fields.hasNext()) if (!Set.of("chart", "globalFilter").contains(fields.next())) return null;
            JsonNode chart = yaml.readTree(request.path("chart").asText());
            if (!charts.contains(chart)) return null;
            String type = chart.path("data").path("type").asText();
            if (Set.of("io.kestra.plugin.core.dashboard.data.Executions", "io.kestra.plugin.core.dashboard.data.ExecutionsKPI").contains(type)) return Kind.EXECUTION;
            if (Set.of("io.kestra.plugin.core.dashboard.data.Flows", "io.kestra.plugin.core.dashboard.data.FlowsKPI").contains(type)) return Kind.FLOW;
            if (type.equals("io.kestra.plugin.core.dashboard.data.Logs")) return Kind.LOG;
            if (type.equals("io.kestra.plugin.core.dashboard.data.Triggers")) return Kind.TRIGGER;
            return null;
        } catch (Exception invalid) { return null; }
    }
}
