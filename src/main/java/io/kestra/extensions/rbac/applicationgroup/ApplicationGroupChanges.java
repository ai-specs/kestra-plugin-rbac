package io.kestra.extensions.rbac.applicationgroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import static io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory.*;

public final class ApplicationGroupChanges {
    public static final class NotFound extends IllegalArgumentException {}
    public static final class Conflict extends IllegalStateException {}

    private ApplicationGroupChanges() {}

    public static Snapshot groupMetadata(Snapshot current,String displayName,boolean active) {
        var group=current.group();
        return new Snapshot(new Group(group.id(),displayName,active,group.revision(),group.authorizationVersion()),
            current.scopes(),current.applications(),current.userGrants());
    }

    public static Snapshot createScope(Snapshot current,String key,String name,String description) {
        if(current.scopes().stream().anyMatch(value->value.key().equals(key)))throw new Conflict();
        var scopes=new ArrayList<>(current.scopes());scopes.add(new Scope(key,name,description,1));
        return new Snapshot(current.group(),scopes,current.applications(),current.userGrants());
    }

    public static Snapshot scopeMetadata(Snapshot current,String key,long expectedRevision,String name,String description) {
        var scopes=replace(current.scopes(),value->value.key().equals(key),value->{
            revision(value.revision(),expectedRevision);
            return value.withMetadata(name,description);
        });
        return new Snapshot(current.group(),scopes,current.applications(),current.userGrants());
    }

    public static Snapshot deleteScope(Snapshot current,String key,long expectedRevision) {
        var target=current.scopes().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow(NotFound::new);
        revision(target.revision(),expectedRevision);
        if(current.applications().stream().anyMatch(value->value.supportedScopeKeys().contains(key))
            || current.userGrants().stream().anyMatch(value->value.scopeKeys().contains(key)))throw new Conflict();
        return new Snapshot(current.group(),current.scopes().stream().filter(value->!value.key().equals(key)).toList(),
            current.applications(),current.userGrants());
    }

    public static Snapshot registerApplication(Snapshot current,String clientId,boolean approved,Set<String> audiences,Set<String> supportedScopeKeys) {
        if(current.applications().stream().anyMatch(value->value.clientId().equals(clientId)))throw new Conflict();
        var applications=new ArrayList<>(current.applications());
        applications.add(new Application(clientId,approved,audiences,supportedScopeKeys,1));
        return new Snapshot(current.group(),current.scopes(),applications,current.userGrants());
    }

    public static Snapshot applicationSupport(Snapshot current,String clientId,long expectedRevision,
        boolean approved,Set<String> audiences,Set<String> supportedScopeKeys) {
        var applications=replace(current.applications(),value->value.clientId().equals(clientId),value->{
            revision(value.revision(),expectedRevision);
            return new Application(value.clientId(),approved,audiences,supportedScopeKeys,Math.addExact(value.revision(),1));
        });
        return new Snapshot(current.group(),current.scopes(),applications,current.userGrants());
    }

    public static Snapshot removeApplication(Snapshot current,String clientId,long expectedRevision) {
        var target=current.applications().stream().filter(value->value.clientId().equals(clientId)).findFirst().orElseThrow(NotFound::new);
        revision(target.revision(),expectedRevision);
        return new Snapshot(current.group(),current.scopes(),
            current.applications().stream().filter(value->!value.clientId().equals(clientId)).toList(),current.userGrants());
    }

    public static Snapshot grantUser(Snapshot current,String issuer,String subject,Set<String> scopeKeys) {
        if(current.userGrants().stream().anyMatch(value->user(value,issuer,subject)))throw new Conflict();
        var grants=new ArrayList<>(current.userGrants());grants.add(new UserGrant(issuer,subject,scopeKeys,1));
        return new Snapshot(current.group(),current.scopes(),current.applications(),grants);
    }

    public static Snapshot userScopes(Snapshot current,String issuer,String subject,long expectedRevision,Set<String> scopeKeys) {
        var grants=replace(current.userGrants(),value->user(value,issuer,subject),value->{
            revision(value.revision(),expectedRevision);
            return new UserGrant(value.issuer(),value.subject(),scopeKeys,Math.addExact(value.revision(),1));
        });
        return new Snapshot(current.group(),current.scopes(),current.applications(),grants);
    }

    public static Snapshot revokeUser(Snapshot current,String issuer,String subject,long expectedRevision) {
        var target=current.userGrants().stream().filter(value->user(value,issuer,subject)).findFirst().orElseThrow(NotFound::new);
        revision(target.revision(),expectedRevision);
        return new Snapshot(current.group(),current.scopes(),current.applications(),
            current.userGrants().stream().filter(value->!user(value,issuer,subject)).toList());
    }

    private static boolean user(UserGrant grant,String issuer,String subject) {
        return grant.issuer().equals(issuer) && grant.subject().equals(subject);
    }

    private static void revision(long actual,long expected) { if(actual!=expected)throw new Conflict(); }

    private static <Value> List<Value> replace(List<Value> values,Predicate<Value> target,Function<Value,Value> change) {
        var result=new ArrayList<Value>();boolean found=false;
        for(var value:values) {
            if(target.test(value)){result.add(change.apply(value));found=true;}
            else result.add(value);
        }
        if(!found)throw new NotFound();
        return List.copyOf(result);
    }
}
