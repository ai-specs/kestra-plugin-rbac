package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;

public interface ApplicationDirectoryAccess {
    enum Resource { GROUP, SCOPE, CLIENT, ASSIGNMENT }
    enum Operation { LIST, FIND, CREATE, UPDATE, DELETE }
    record Request(Resource resource,Operation operation,io.kestra.webserver.oidc.TrustedIdentityV1.Identity identity,String applicationGroupId,String key,
                   int page,int size,Long revision,long directoryRevision,JsonNode body,
                   io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain authorizationDomain,
                   io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction authorizationAction) {
        public Request(Resource resource,Operation operation,io.kestra.webserver.oidc.TrustedIdentityV1.Identity identity,String applicationGroupId,String key,
                       int page,int size,Long revision,long directoryRevision,JsonNode body) {
            this(resource,operation,identity,applicationGroupId,key,page,size,revision,directoryRevision,body,
                io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementDomain.IDENTITY,
                operation==Operation.LIST||operation==Operation.FIND?io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.READ:
                    io.kestra.extensions.rbac.applicationgroup.ApplicationGroupPolicy.ManagementAction.MANAGE);
        }
    }
    final class DirectoryFailure extends RuntimeException {
        private final int status;
        private final String code;
        public DirectoryFailure(int status,String code) { super(code);this.status=status;this.code=code; }
        public int status() {return status;}
        public String code() {return code;}
    }
    Object invoke(Request request);
}
