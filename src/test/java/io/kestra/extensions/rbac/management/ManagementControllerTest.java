package io.kestra.extensions.rbac.management;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.kestra.extensions.rbac.PolicyEngine;
import io.kestra.extensions.rbac.RbacConfiguration;
import io.kestra.extensions.rbac.RuntimePolicies;
import io.kestra.extensions.rbac.store.JdbcPolicyStore;
import io.kestra.extensions.rbac.store.PolicyDocumentValidator;
import io.micronaut.context.BeanProvider;
import io.micronaut.security.authentication.Authentication;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementControllerTest {
    private record Setup(ManagementController controller, JdbcPolicyStore store, AtomicInteger accountWrites) {}
    private static <Target> BeanProvider<Target> provider(Target value) {
        return new BeanProvider<>() {
            public Target get() { return value; }
            public boolean isUnique() { return value != null; }
            public boolean isPresent() { return value != null; }
        };
    }
    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Authentication owner() { return Authentication.build("owner", List.of()); }
    private Setup setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:controller-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var store = new JdbcPolicyStore(source, new PolicyDocumentValidator());
        store.initializeSchema();
        store.importInitial(json("{\"schemaVersion\":1,\"roles\":[],\"subjects\":{},\"roleMapping\":{}}"), "owner");
        var configuration = new RbacConfiguration(); configuration.setPolicySource("database"); configuration.setManagementAdminSubjects(List.of("owner"));
        var runtime = new RuntimePolicies(configuration, new PolicyEngine(configuration), provider(store), true, true);
        var writes = new AtomicInteger();
        UserDirectoryAccess directory = new UserDirectoryAccess() {
            public Page list(int page, int size) { return new Page(List.of(), 0, page, size); }
            public Optional<User> find(String subject) { return Optional.of(new User(subject, "Name", "email@example.invalid", true, 0)); }
            public User create(String subject, String name, String email, String password) { writes.incrementAndGet(); return new User(subject, name, email, true, 0); }
            public User update(String subject, long revision, String name, String email) { writes.incrementAndGet(); return new User(subject, name, email, true, revision + 1); }
            public User setActive(String subject, long revision, boolean active) { writes.incrementAndGet(); return new User(subject, "Name", "email@example.invalid", active, revision + 1); }
            public User resetPassword(String subject, long revision, String password) { writes.incrementAndGet(); return new User(subject, "Name", "email@example.invalid", true, revision + 1); }
        };
        return new Setup(new ManagementController(runtime, configuration, provider(directory)), store, writes);
    }
    @Test void untrustedRoleAndMissingIdentityCannotReadOrWrite() {
        var setup = setup();
        var impostor = Authentication.build("other", List.of("admin"));
        assertEquals(403, setup.controller().policy("main", impostor).code());
        assertEquals(401, setup.controller().policy("main", null).code());
        assertEquals(403, setup.controller().createRole("main", impostor, "\"1\"", json("{}")).code());
        assertEquals(403, setup.controller().createUser("main", impostor, json("{}")).code());
        assertEquals(1, setup.store().readCurrent().revision());
        assertEquals(0, setup.accountWrites().get());
    }
    @Test void roleWritesRequireRevisionAndRejectUnknownOrDuplicateFields() {
        var setup = setup();
        String role = "{\"id\":\"reader\",\"name\":\"Reader\",\"description\":\"initial\",\"grants\":[]}";
        assertEquals(428, setup.controller().createRole("main", owner(), null, json(role)).code());
        assertEquals(400, setup.controller().createRole("main", owner(), "*", json(role)).code());
        assertEquals(400, setup.controller().createRole("main", owner(), "\"1\"", json(role.replace("\"grants\":[]", "\"grants\":[],\"admin\":true"))).code());
        assertEquals(400, setup.controller().createRole("main", owner(), "\"1\"", json(role.replace("\"name\":\"Reader\"", "\"name\":\"Reader\",\"name\":\"Other\""))).code());
        assertEquals(200, setup.controller().createRole("main", owner(), "\"1\"", json(role)).code());
        assertEquals(412, setup.controller().createRole("main", owner(), "\"1\"", json(role)).code());
        assertEquals(2, setup.store().readCurrent().revision());
        assertEquals(200, setup.controller().updateRole("main", "reader", owner(), "\"2\"", json(role.replace("initial", "changed"))).code());
        assertEquals("changed", setup.store().readCurrent().document().roles().getFirst().description());
    }
    @Test void protectedUsersAndInvalidSubjectsCannotProduceAccountMutations() {
        var setup = setup();
        assertEquals(403, setup.controller().createUser("main", owner(), json("{\"username\":\"owner\",\"name\":\"Owner\",\"email\":\"owner@example.invalid\",\"password\":\"new-password\"}")).code());
        assertEquals(403, setup.controller().state("main", owner(), "\"0\"", json("{\"subject\":\"owner\",\"active\":false}")).code());
        assertEquals(403, setup.controller().password("main", owner(), "\"0\"", json("{\"subject\":\"owner\",\"password\":\"new-password\"}")).code());
        assertEquals(400, setup.controller().state("main", owner(), "\"0\"", json("{\"subject\":\"user\",\"active\":\"true\"}")).code());
        assertEquals(428, setup.controller().profile("main", owner(), null, json("{\"subject\":\"user\",\"name\":\"Changed\",\"email\":\"new@example.invalid\"}")).code());
        assertEquals(0, setup.accountWrites().get());
        assertEquals(200, setup.controller().profile("main", owner(), "\"0\"", json("{\"subject\":\"user\",\"name\":\"Changed\",\"email\":\"new@example.invalid\"}")).code());
        assertEquals(1, setup.accountWrites().get());
    }
}
