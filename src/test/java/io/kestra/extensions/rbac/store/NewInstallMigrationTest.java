package io.kestra.extensions.rbac.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.migration.MigrationScript;
import io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectory;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationMigration;
import io.kestra.extensions.rbac.declaration.ApplicationScopeDeclarationStore;
import io.kestra.webserver.oidc.ApplicationScopeDeclarationsV1;
import io.kestra.webserver.oidc.OidcProtocolStateV1;
import io.kestra.webserver.oidc.TrustedIdentityV1;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewInstallMigrationTest {
    private static final Set<String> NEW_INSTALL_TABLES=Set.of("rbac_application_group_state","rbac_application_group_bootstrap",
        "rbac_application_group_invalidation","rbac_application_group_retired","rbac_management_audit");

    private DataSource database()throws Exception {
        String jdbc=System.getenv("RBAC_STORE_TEST_JDBC");DataSource source;
        if(jdbc!=null && !jdbc.isBlank()) {
            var database=new org.postgresql.ds.PGSimpleDataSource();database.setURL(jdbc);database.setUser("postgres");database.setPassword("policy-test-only");
            String schema="new_install_migrations_"+UUID.randomUUID().toString().replace("-","");
            try(var connection=database.getConnection();var statement=connection.createStatement()) {statement.execute("CREATE SCHEMA "+schema);}
            database.setCurrentSchema(schema);source=database;
        }else {
            var database=new org.h2.jdbcx.JdbcDataSource();database.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");source=database;
        }
        try(var connection=source.getConnection()) {
            String backend=connection.getMetaData().getDatabaseProductName();assertEquals(jdbc!=null && !jdbc.isBlank()?"PostgreSQL":"H2",backend);
            System.out.println("New-install migration backend: "+backend);
        }
        return source;
    }

    private ApplicationContext context(DataSource source,ApplicationGroupDirectoryStore directory,boolean enabled,boolean groups) {
        var properties=Map.<String,Object>of("kestra.repository.type","postgres","kestra.rbac.enabled",enabled,
            "kestra.rbac.application-groups.enabled",groups,"kestra.rbac.typed.enabled",true,"kestra.rbac.evaluation.enabled",true,
            "kestra.rbac.management.enabled",true,"kestra.oidc.issuer","https://issuer.example","micronaut.security.oauth2.clients.kestra.client-id","ui");
        return ApplicationContext.builder().packages("io.kestra.extensions.rbac").properties(properties)
            .singletons(source,directory,mock(OidcProtocolStateV1.class),mock(TrustedIdentityV1.class)).start();
    }

    private Set<String> tables(DataSource source)throws Exception {
        var names=new java.util.TreeSet<String>();
        try(var connection=source.getConnection();var tables=connection.getMetaData().getTables(null,connection.getSchema(),null,new String[]{"TABLE"})) {
            while(tables.next()) {
                String name=tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT);
                if(name.startsWith("rbac_"))names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    private void runProductionMigrations(ApplicationContext context)throws Exception {
        var migrations=context.getBeansOfType(MigrationScript.class);
        System.out.println("Production plugin migration beans: "+migrations.stream().map(migration->migration.getClass().getName()).sorted().toList());
        for(var migration:migrations)migration.migrate();
        System.out.println("Tables after production plugin migrations: "+tables(context.getBean(DataSource.class)));
        assertFalse(context.containsBean(ApplicationScopeDeclarationMigration.class),"Legacy declaration migration must not be discovered in production");
    }

    @Test void featureOffProductionMigrationsDoNotCreateLegacyDeclarationStorage()throws Exception {
        for(boolean groups:List.of(false,true)) {
            var source=database();var directory=new ApplicationGroupDirectoryStore(source);
            try(var context=context(source,directory,false,groups)) {runProductionMigrations(context);}
            assertEquals(Set.of(),tables(source),"Feature-off installation must not create plugin storage");
        }
    }

    @Test void featureOffInstallAndFeatureOnRestartKeepOnlyFiveUnifiedTables()throws Exception {
        var source=database();var directory=new ApplicationGroupDirectoryStore(source);
        try(var context=context(source,directory,false,false)) {runProductionMigrations(context);}
        assertEquals(Set.of(),tables(source));
        directory.initializeSchema();
        var initial=new ApplicationGroupDirectory.Snapshot(new ApplicationGroupDirectory.Group("main","Main",true,1,1),List.of(),List.of(),List.of());
        directory.initializeBuiltin(initial);
        assertEquals(NEW_INSTALL_TABLES,tables(source));
        try(var context=context(source,directory,true,true)) {runProductionMigrations(context);assertNotNull(context.getBean(io.kestra.extensions.rbac.applicationgroup.ApplicationGroupDirectoryService.class));}
        assertEquals(NEW_INSTALL_TABLES,tables(source));assertEquals(List.of(initial),directory.readAll());
        try(var context=context(source,directory,false,false)) {runProductionMigrations(context);}
        assertEquals(NEW_INSTALL_TABLES,tables(source));
    }

    @Test void explicitHistoricalMigrationAndStoreStillReadExistingSchema2Declarations()throws Exception {
        var source=database();new ApplicationScopeDeclarationMigration(source).migrate();
        assertEquals(Set.of("rbac_application_scope_declaration"),tables(source));
        var store=new ApplicationScopeDeclarationStore(source,new ObjectMapper());
        store.initialize("group","client",List.of(new ApplicationScopeDeclarationsV1.Scope("reader",List.of(new ApplicationScopeDeclarationsV1.Permission("read","Reader")))));
        var rows=store.list("group","client");assertEquals(1,rows.size());assertEquals("reader",rows.getFirst().scopeKey());
        assertEquals("read",rows.getFirst().permissions().getFirst().key());
    }
}
