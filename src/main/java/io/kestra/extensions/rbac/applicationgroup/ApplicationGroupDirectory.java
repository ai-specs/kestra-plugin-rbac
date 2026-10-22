package io.kestra.extensions.rbac.applicationgroup;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ApplicationGroupDirectory {
    private ApplicationGroupDirectory() {}

    public record Group(String id,String displayName,boolean active,long revision,long authorizationVersion) {
        public Group {
            if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") || displayName==null || displayName.isBlank()
                || revision<=0 || authorizationVersion<=0)throw new IllegalArgumentException("Invalid application group");
        }
    }

    public record Scope(String key,String name,String description,long revision) {
        public Scope {
            scopeKey(key);
            if(name==null || name.isBlank() || description==null || revision<=0)
                throw new IllegalArgumentException("Invalid group scope metadata");
        }

        public Scope withMetadata(String name,String description) {
            return new Scope(key,name,description,Math.addExact(revision,1));
        }
    }

    public record Application(String clientId,boolean approved,Set<String> audiences,Set<String> supportedScopeKeys,long revision) {
        public Application {
            opaque(clientId);
            if(audiences==null || supportedScopeKeys==null || revision<=0)throw new IllegalArgumentException("Invalid group application");
            audiences=Set.copyOf(audiences);supportedScopeKeys=Set.copyOf(supportedScopeKeys);
            audiences.forEach(ApplicationGroupDirectory::opaque);
            if(audiences.stream().anyMatch(value->value.contains("*")))throw new IllegalArgumentException("Exact application audience required");
            supportedScopeKeys.forEach(ApplicationGroupDirectory::scopeKey);
            if(approved && audiences.isEmpty())throw new IllegalArgumentException("An approved application requires an audience");
        }
    }

    public record UserGrant(String issuer,String subject,Set<String> scopeKeys,long revision) {
        public UserGrant {
            opaque(subject);
            if(issuer==null || scopeKeys==null || revision<=0)throw new IllegalArgumentException("Invalid user scope grant");
            var parsed=URI.create(issuer);
            if(!Set.of("https","http").contains(parsed.getScheme()) || parsed.getHost()==null || parsed.getUserInfo()!=null
                || parsed.getQuery()!=null || parsed.getFragment()!=null)throw new IllegalArgumentException("Invalid grant issuer");
            scopeKeys=Set.copyOf(scopeKeys);scopeKeys.forEach(ApplicationGroupDirectory::scopeKey);
        }
    }

    public record Snapshot(Group group,List<Scope> scopes,List<Application> applications,List<UserGrant> userGrants) {
        public Snapshot {
            if(group==null || scopes==null || applications==null || userGrants==null)throw new IllegalArgumentException("Incomplete group snapshot");
            scopes=List.copyOf(scopes);applications=List.copyOf(applications);userGrants=List.copyOf(userGrants);
            var keys=new HashSet<String>();var names=new HashSet<String>();
            for(var scope:scopes)if(!keys.add(scope.key()) || !names.add(scope.name()))throw new IllegalArgumentException("Duplicate group scope key or role name");
            var clients=new HashSet<String>();
            for(var application:applications) {
                if(!clients.add(application.clientId()))throw new IllegalArgumentException("Duplicate group application");
                if(!keys.containsAll(application.supportedScopeKeys()))throw new IllegalArgumentException("Application references an undefined scope");
            }
            var subjects=new HashSet<List<String>>();
            for(var grant:userGrants) {
                if(!subjects.add(List.of(grant.issuer(),grant.subject())))throw new IllegalArgumentException("Duplicate user scope grant");
                if(!keys.containsAll(grant.scopeKeys()))throw new IllegalArgumentException("User references an undefined scope");
            }
        }

        public Set<String> eligibleScopeKeys(String clientId,String issuer,String subject) {
            if(!group.active())return Set.of();
            var application=applications.stream().filter(value->value.clientId().equals(clientId)&&value.approved()).findFirst();
            var grant=userGrants.stream().filter(value->value.issuer().equals(issuer)&&value.subject().equals(subject)).findFirst();
            if(application.isEmpty() || grant.isEmpty())return Set.of();
            var eligible=new HashSet<>(application.get().supportedScopeKeys());eligible.retainAll(grant.get().scopeKeys());
            return Set.copyOf(eligible);
        }
    }

    private static void scopeKey(String key) {
        if(key==null || !key.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid stable scope key");
    }

    private static void opaque(String value) {
        if(value==null || value.isBlank() || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid opaque identifier");
    }
}
