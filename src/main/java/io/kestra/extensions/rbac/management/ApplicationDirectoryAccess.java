package io.kestra.extensions.rbac.management;

import com.fasterxml.jackson.databind.JsonNode;

/** Plugin wire adapter. Only its published-SPI implementation may contact the identity directory. */
public interface ApplicationDirectoryAccess {
    enum Resource { PROJECT, SCOPE, CLIENT, ASSIGNMENT }
    enum Operation { LIST, FIND, CREATE, UPDATE, DELETE }
    record Request(Resource resource,Operation operation,String issuer,String project,String key,
                   int page,int size,Long revision,long directoryRevision,JsonNode body) {}
    final class DirectoryFailure extends RuntimeException {
        private final int status;
        private final String code;
        public DirectoryFailure(int status,String code) { super(code);this.status=status;this.code=code; }
        public int status() {return status;}
        public String code() {return code;}
    }
    Object invoke(Request request);
}
