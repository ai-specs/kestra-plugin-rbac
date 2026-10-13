package io.kestra.extensions.rbac;
import io.kestra.webserver.rbac.RbacAuthorizer.*;
public record AuthorizationRequest(String tenant, String namespace, Kind resource, Action action, String id, Scope scope) {}
