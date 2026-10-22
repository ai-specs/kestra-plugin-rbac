package io.kestra.extensions.rbac.applicationgroup;

import java.util.List;
import java.util.Set;

public interface ApplicationGroupDirectoryReader {
    record GroupView(String id,String tenant,String issuer,String displayName,boolean active,
                     long revision,long authorizationVersion,boolean builtIn) {
        public GroupView(String id,String tenant,String issuer,String displayName,boolean active,long revision,long authorizationVersion) {
            this(id,tenant,issuer,displayName,active,revision,authorizationVersion,false);
        }
    }
    record ScopeView(String applicationGroupId,String key,String name,String description,long revision) {}
    record ProtocolScope(String scope,String category) {}
    record Boundary(GroupView applicationGroup,List<ScopeView> scopes,Set<String> audiences,
                    Set<String> reachableManagementScopes,List<ProtocolScope> protocolScopes) {
        public Boundary {
            scopes=List.copyOf(scopes);audiences=Set.copyOf(audiences);
            reachableManagementScopes=Set.copyOf(reachableManagementScopes);protocolScopes=List.copyOf(protocolScopes);
        }
    }
    List<Boundary> boundaries();
    List<Boundary> boundaries(java.sql.Connection connection)throws java.sql.SQLException;
    void verifyIdentity(java.sql.Connection connection,io.kestra.webserver.oidc.TrustedIdentityV1.Identity identity)throws java.sql.SQLException;
    void validatePolicy(java.sql.Connection connection,ApplicationGroupPolicy policy)throws java.sql.SQLException;
}
