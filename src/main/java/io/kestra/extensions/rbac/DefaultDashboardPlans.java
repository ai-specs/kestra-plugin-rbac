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
            if (!matchesDefault(chart)) return null;
            String type = chart.path("data").path("type").asText();
            if (Set.of("io.kestra.plugin.core.dashboard.data.Executions", "io.kestra.plugin.core.dashboard.data.ExecutionsKPI").contains(type)) return Kind.EXECUTION;
            if (Set.of("io.kestra.plugin.core.dashboard.data.Flows", "io.kestra.plugin.core.dashboard.data.FlowsKPI").contains(type)) return Kind.FLOW;
            if (type.equals("io.kestra.plugin.core.dashboard.data.Logs")) return Kind.LOG;
            if (type.equals("io.kestra.plugin.core.dashboard.data.Triggers")) return Kind.TRIGGER;
            return null;
        } catch (Exception invalid) { return null; }
    }
    private boolean matchesDefault(JsonNode chart) {
        if (charts.contains(chart)) return true;
        if (chart == null || !chart.isObject() || !chart.path("data").isObject()) return false;
        JsonNode where = chart.path("data").path("where");
        if (!where.isArray() || where.isEmpty() || where.size() > 2) return false;
        var seen = new HashSet<String>();
        for (JsonNode filter : where) {
            String field = filter.path("field").asText();
            if (filter.size() != 3 || !filter.path("type").asText().equals("EQUAL_TO")
                || !Set.of("NAMESPACE", "FLOW_ID").contains(field) || !seen.add(field)
                || !filter.path("value").isTextual() || !filter.path("value").asText().matches("[A-Za-z0-9_.-]+")) return false;
        }
        var copy = chart.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.path("data")).remove("where");
        for (JsonNode builtin : charts) {
            var candidate = copy.deepCopy();
            JsonNode legend = candidate.path("chartOptions").path("legend");
            JsonNode builtinLegend = builtin.path("chartOptions").path("legend");
            if (legend.isObject() && legend.size() == 1 && legend.path("enabled").isBoolean() && builtinLegend.path("enabled").isBoolean())
                ((com.fasterxml.jackson.databind.node.ObjectNode) legend).set("enabled", builtinLegend.path("enabled"));
            if (builtin.equals(candidate)) return true;
        }
        return false;
    }
    io.kestra.webserver.rbac.RbacAuthorizer.Resource description(byte[] body) {
        try {
            JsonNode request = json.readTree(body);
            JsonNode chart = yaml.readTree(request.path("chart").asText());
            if (!chart.path("id").asText().equals("description") || !chart.path("type").asText().equals("io.kestra.plugin.core.dashboard.chart.Markdown")) return null;
            JsonNode source = chart.path("source");
            if (!source.path("type").asText().equals("FlowDescription")
                || !source.path("namespace").isTextual() || !source.path("flowId").isTextual()) return null;
            var normalized = chart.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) normalized.path("source")).put("namespace", "--NAMESPACE--").put("flowId", "--FLOW--");
            if (!charts.contains(normalized)) return null;
            return new io.kestra.webserver.rbac.RbacAuthorizer.Resource(Kind.FLOW, io.kestra.webserver.rbac.RbacAuthorizer.Scope.NAMESPACE, "main", source.path("namespace").asText(), source.path("flowId").asText());
        } catch (Exception invalid) { return null; }
    }
}
