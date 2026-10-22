package io.kestra.extensions.rbac.applicationgroup;

import io.kestra.webserver.oidc.TrustedIdentityV1;

public final class ApplicationGroupMutationContext implements AutoCloseable {
    private static final ThreadLocal<TrustedIdentityV1.Identity> CURRENT=new ThreadLocal<>();
    private final TrustedIdentityV1.Identity previous;
    private ApplicationGroupMutationContext(TrustedIdentityV1.Identity identity) {
        previous=CURRENT.get();CURRENT.set(java.util.Objects.requireNonNull(identity));
    }
    public static ApplicationGroupMutationContext bind(TrustedIdentityV1.Identity identity) { return new ApplicationGroupMutationContext(identity); }
    public static TrustedIdentityV1.Identity current() { return CURRENT.get(); }
    @Override public void close() { if(previous==null)CURRENT.remove();else CURRENT.set(previous); }
}
