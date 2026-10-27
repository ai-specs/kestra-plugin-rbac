package io.kestra.extensions.rbac.management;

import java.util.List;
import java.util.Optional;

public interface UserDirectoryAccess extends TemporaryAccountCommands.Host {
    record User(String subject, String name, String email, boolean active, long revision) {}
    record Page(List<User> items, long total, int page, int size) {
        public Page { items = List.copyOf(items); }
    }
    enum Failure { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN, LAST_ADMIN, UNAVAILABLE }
    final class DirectoryFailure extends RuntimeException {
        private final Failure failure;
        public DirectoryFailure(Failure failure) { super("Directory operation failed"); this.failure = failure; }
        public Failure failure() { return failure; }
    }
    Page list(int page, int size);
    Optional<User> find(String subject);
    User create(String username, String name, String email, String password);
    @Override default TemporaryAccountCommands.Created createTemporary(TemporaryAccountCommands.Create input) {
        throw new DirectoryFailure(Failure.UNAVAILABLE);
    }
    @Override default void delete(String subject,long expectedRevision) {
        throw new DirectoryFailure(Failure.UNAVAILABLE);
    }
    User update(String subject, long revision, String name, String email);
    User setActive(String subject, long revision, boolean active);
    User resetPassword(String subject, long revision, String password);
}
