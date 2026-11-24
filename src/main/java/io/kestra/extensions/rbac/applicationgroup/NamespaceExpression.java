package io.kestra.extensions.rbac.applicationgroup;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record NamespaceExpression(String namespace, boolean descendants) {
    private static final Pattern PATTERN = Pattern.compile("(!?)([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*)(\\.\\*)?");

    public static NamespaceExpression parse(String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Namespace expression required");
        Matcher matcher = PATTERN.matcher(expression);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid namespace expression");
        return new NamespaceExpression(matcher.group(1) + matcher.group(2), matcher.group(3) != null);
    }

    public static NamespaceExpression read(String expression, Boolean legacyDescendants) {
        var parsed = parse(expression);
        if (legacyDescendants == null) return parsed;
        if (parsed.descendants()) throw new IllegalArgumentException("Cannot combine namespace subtree expression and descendants");
        return new NamespaceExpression(parsed.namespace(), legacyDescendants);
    }

    public String render() { return namespace + (descendants ? ".*" : ""); }
}
