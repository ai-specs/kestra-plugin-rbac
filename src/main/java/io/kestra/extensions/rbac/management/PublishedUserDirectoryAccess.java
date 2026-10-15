package io.kestra.extensions.rbac.management;

import io.kestra.webserver.oidc.UserDirectoryV1;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.function.Function;

@Singleton
@Requires(property = "kestra.rbac.enabled", value = "true")
@Requires(property = "kestra.rbac.typed.enabled", value = "true")
@Requires(property = "kestra.rbac.management.enabled", value = "true")
@Requires(beans = UserDirectoryV1.class)
public final class PublishedUserDirectoryAccess implements UserDirectoryAccess {
    private final BeanProvider<UserDirectoryV1> directories;

    public PublishedUserDirectoryAccess(BeanProvider<UserDirectoryV1> directories) {
        this.directories = directories;
    }

    private <Target> Target invoke(Function<UserDirectoryV1, Target> operation) {
        try {
            if (!directories.isUnique()) throw new DirectoryFailure(Failure.UNAVAILABLE);
            return operation.apply(directories.get());
        } catch (UserDirectoryV1.DirectoryException failure) {
            throw new DirectoryFailure(switch (failure.failure()) {
                case INVALID -> Failure.INVALID;
                case NOT_FOUND -> Failure.NOT_FOUND;
                case CONFLICT -> Failure.CONFLICT;
                case UNAVAILABLE -> Failure.UNAVAILABLE;
            });
        } catch (RuntimeException failure) {
            throw new DirectoryFailure(Failure.UNAVAILABLE);
        }
    }

    private User copy(UserDirectoryV1.User user) {
        return new User(user.subject(), user.name(), user.email(), user.active(), user.revision());
    }

    @Override public Page list(int page, int size) {
        return invoke(directory -> {
            var result = directory.list(page, size);
            return new Page(result.items().stream().map(this::copy).toList(), result.total(), result.page(), result.size());
        });
    }
    @Override public Optional<User> find(String subject) {
        return invoke(directory -> directory.find(subject).map(this::copy));
    }
    @Override public User create(String username, String name, String email, String password) {
        return invoke(directory -> copy(directory.create(username, name, email, password)));
    }
    @Override public User update(String subject, long revision, String name, String email) {
        return invoke(directory -> copy(directory.update(subject, revision, name, email)));
    }
    @Override public User setActive(String subject, long revision, boolean active) {
        return invoke(directory -> copy(directory.setActive(subject, revision, active)));
    }
    @Override public User resetPassword(String subject, long revision, String password) {
        return invoke(directory -> copy(directory.resetPassword(subject, revision, password)));
    }
}
