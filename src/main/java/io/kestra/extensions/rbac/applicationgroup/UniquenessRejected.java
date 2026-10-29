package io.kestra.extensions.rbac.applicationgroup;

/** Valid transactional mutation conflicts with an existing business identity, not revision CAS. */
public final class UniquenessRejected extends IllegalStateException {
    public enum Failure { SCOPE_NAME_EXISTS, CLIENT_ALREADY_ASSIGNED }
    private final Failure failure;
    public UniquenessRejected(Failure failure) {
        super(java.util.Objects.requireNonNull(failure).name());
        this.failure=failure;
    }
    public Failure failure() { return failure; }
}
