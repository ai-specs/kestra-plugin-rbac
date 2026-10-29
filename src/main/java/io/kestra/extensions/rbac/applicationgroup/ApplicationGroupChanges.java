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
    public record ScopeReference(String kind,String clientId,String permissionKey,String issuer,String subject) {}
    public static final class ScopeInUse extends IllegalStateException {
        private final List<ScopeReference> references;
        public ScopeInUse(List<ScopeReference> references) {super("scope_in_use");this.references=List.copyOf(references);}
        public List<ScopeReference> references() {return references;}
    }

    private ApplicationGroupChanges() {}

    public static Snapshot groupMetadata(Snapshot current,String displayName,boolean active) {
        return groupMetadata(current,displayName,current.group().description(),active);
    }

    public static Snapshot groupMetadata(Snapshot current,String displayName,String description,boolean active) {
        var group=current.group();
        return new Snapshot(new Group(group.id(),displayName,description,active,group.revision(),group.authorizationVersion()),
            current.scopes(),current.applications(),current.userGrants());
    }

    public static Snapshot createScope(Snapshot current,String key,String name,String description) {
        if(current.scopes().stream().anyMatch(value->value.key().equals(key)))throw new Conflict();
        var candidate=new Scope(key,name,description,1);
        requireUniqueScopeName(current,key,name);
        var scopes=new ArrayList<>(current.scopes());scopes.add(candidate);
        return new Snapshot(current.group(),scopes,current.applications(),current.userGrants());
    }

    public static Snapshot scopeMetadata(Snapshot current,String key,long expectedRevision,String name,String description) {
        var scopes=replace(current.scopes(),value->value.key().equals(key),value->{
            revision(value.revision(),expectedRevision);
            var candidate=value.withMetadata(name,description);
            requireUniqueScopeName(current,key,name);
            return candidate;
        });
        return new Snapshot(current.group(),scopes,current.applications(),current.userGrants());
    }

    private static void requireUniqueScopeName(Snapshot current,String key,String name) {
        if(current.scopes().stream().anyMatch(value->!value.key().equals(key)&&value.name().equals(name)))
            throw new UniquenessRejected(UniquenessRejected.Failure.SCOPE_NAME_EXISTS);
    }

    public static Snapshot deleteScope(Snapshot current,String key,long expectedRevision) {
        var target=current.scopes().stream().filter(value->value.key().equals(key)).findFirst().orElseThrow(NotFound::new);
        revision(target.revision(),expectedRevision);
        var references=new ArrayList<ScopeReference>();
        for(var application:current.applications())for(var permission:application.permissions())
            if(permission.scopeKeys().contains(key))references.add(new ScopeReference("permission",application.clientId(),permission.key(),null,null));
        for(var grant:current.userGrants())if(grant.scopeKeys().contains(key))
            references.add(new ScopeReference("subject",null,null,grant.issuer(),grant.subject()));
        if(!references.isEmpty())throw new ScopeInUse(references);
        return new Snapshot(current.group(),current.scopes().stream().filter(value->!value.key().equals(key)).toList(),
            current.applications(),current.userGrants());
    }

    public static Snapshot registerApplication(Snapshot current,String clientId,String description,Set<String> audiences) {
        if(current.applications().stream().anyMatch(value->value.clientId().equals(clientId)))throw new Conflict();
        var applications=new ArrayList<>(current.applications());
        applications.add(new Application(clientId,description,audiences,List.of(),1));
        return new Snapshot(current.group(),current.scopes(),applications,current.userGrants());
    }

    public static Snapshot applicationMetadata(Snapshot current,String clientId,long expectedRevision,
        String description,Set<String> audiences) {
        var applications=replace(current.applications(),value->value.clientId().equals(clientId),value->{
            revision(value.revision(),expectedRevision);
            return new Application(value.clientId(),description,audiences,value.permissions(),Math.addExact(value.revision(),1));
        });
        return new Snapshot(current.group(),current.scopes(),applications,current.userGrants());
    }

    public static Snapshot permissions(Snapshot current,String clientId,long expectedRevision,List<ApplicationPermission> permissions) {
        var applications=replace(current.applications(),value->value.clientId().equals(clientId),value->{
            revision(value.revision(),expectedRevision);
            return new Application(value.clientId(),value.description(),value.audiences(),permissions,Math.addExact(value.revision(),1));
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
