package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LiquibaseReleaseIntegrationTests {
    @TempDir Path output;
    private static String[] args(PostgreSQLContainer pg) {
        return new String[]{"--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(), "--spring.datasource.password=" + pg.getPassword(), "--bot.release.action=schema-only"};
    }
    private static void initialize(PostgreSQLContainer pg) throws Exception {
        ReleaseProvisioningApplication.main(new String[]{"--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(), "--spring.datasource.password=" + pg.getPassword(),
            "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl()});
    }
    private static JdbcTemplate jdbc(PostgreSQLContainer pg) {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }
    private static String snapshot(JdbcTemplate jdbc) {
        var result = new StringBuilder(jdbc.queryForList("""
                select c.relname || ':' || c.relkind::text || ':' || coalesce(pg_get_viewdef(c.oid), '')
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname=current_schema() order by 1
                """, String.class).toString());
        result.append(jdbc.queryForList("""
                select conname || ':' || pg_get_constraintdef(c.oid) from pg_constraint c
                join pg_namespace n on n.oid=c.connamespace where n.nspname=current_schema() order by 1
                """, String.class));
        for (String table : jdbc.queryForList("""
                select tablename from pg_tables where schemaname=current_schema() order by tablename
                """, String.class)) {
            String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
            result.append(table).append(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]'::jsonb)::text from " + quoted + " t", String.class));
        }
        return result.toString();
    }
    private static String causes(Throwable ex) {
        var result = new StringBuilder();
        for (; ex != null; ex = ex.getCause()) result.append(ex.getMessage()).append('\n');
        return result.toString();
    }

    private Process admin(PostgreSQLContainer pg, String log) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.addAll(java.util.List.of(args(pg)));
        command.add("--management.otlp.metrics.export.enabled=false");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(log).toFile()).start();
    }
    private void succeeded(Process process, String log) throws Exception {
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("admin timed out: " + log);
        }
        assertEquals(0, process.exitValue(), () -> {
            try { return java.nio.file.Files.readString(output.resolve(log)); }
            catch (java.io.IOException e) { return e.toString(); }
        });
    }

    @Test void concurrentAdminProcessesSerializePublication() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var a = admin(pg, "first.log");
            var b = admin(pg, "second.log");
            succeeded(a, "first.log"); succeeded(b, "second.log");
            initialize(pg);
            var db = jdbc(pg);
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEquals(1, db.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(1, db.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            assertEquals(0, db.queryForObject("select count(*) from schema_version", Integer.class));
            assertNotNull(db.queryForObject("select obj_description('workspace'::regclass)", String.class));
            assertTrue(db.queryForObject("select exists(select 1 from pg_constraint where conname='fk_conversation_workspace')", Boolean.class));
            assertTrue(db.queryForObject("select exists(select 1 from pg_indexes where indexname='idx_conversation_workspace_owner')", Boolean.class));
        }
    }

    @Test void genuinePartialPrefixResumesRegardlessOfOrderExecuted() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            try (var connection = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var resources = new liquibase.resource.ClassLoaderResourceAccessor();
                 var lb = new liquibase.Liquibase("db/changelog/db.changelog-master.yaml", resources,
                         new liquibase.database.jvm.JdbcConnection(connection))) {
                lb.update(14, new liquibase.Contexts(), new liquibase.LabelExpression());
            }
            var db = jdbc(pg);
            assertEquals(14, db.queryForObject("select count(*) from databasechangelog", Integer.class));
            db.execute("insert into databasechangelog select * from databasechangelog where id='V1__initial_schema.sql'");
            String duplicate = snapshot(db);
            assertThrows(Exception.class, () -> ReleaseProvisioningApplication.main(args(pg)));
            assertEquals(duplicate, snapshot(db));
            db.execute("delete from databasechangelog where ctid in (select ctid from databasechangelog where id='V1__initial_schema.sql' limit 1)");
            db.update("update databasechangelog set orderexecuted=1");
            succeeded(admin(pg, "resume.log"), "resume.log");
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertNotNull(db.queryForObject("select to_regclass('run_ownership')::text", String.class));
        }
    }

    @Test void workspaceMigrationBackfillsExistingConversations() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg);
            try (var connection = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var resources = new liquibase.resource.ClassLoaderResourceAccessor();
                 var lb = new liquibase.Liquibase("db/changelog/db.changelog-master.yaml", resources,
                         new liquibase.database.jvm.JdbcConnection(connection))) {
                lb.update(4, new liquibase.Contexts(), new liquibase.LabelExpression());
            }
            db.update("insert into bot(id,name,target,system_prompt) values ('old-bot','Old','LOCAL','test')");
            db.update("insert into conversation(id,bot_id,title,user_id) values ('old-conv','old-bot','Old','alice')");
            ReleaseProvisioningApplication.main(args(pg));
            assertEquals("personal-" + db.queryForObject("select md5('alice')", String.class),
                    db.queryForObject("select workspace_id from conversation where id='old-conv'", String.class));
            assertEquals(1, db.queryForObject("select count(*) from workspace_member where user_id='alice' and role='MANAGER'", Integer.class));
            assertTrue(db.queryForObject("select exists(select 1 from pg_constraint where conname='fk_conversation_workspace')", Boolean.class));
        }
    }

    @Test void unknownAndFabricatedHistoryAreRejectedBeforeWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg);
            db.execute("create table unrelated(id int)");
            String before = snapshot(db);
            var ex = assertThrows(Exception.class, () -> ReleaseProvisioningApplication.main(args(pg)));
            assertTrue(causes(ex).contains("unknown or legacy"), causes(ex));
            assertEquals(before, snapshot(db));
            db.execute("create table databasechangelog(id varchar(255), author varchar(255), filename varchar(255), md5sum varchar(35), exectype varchar(10), orderexecuted int)");
            before = snapshot(db);
            assertThrows(Exception.class, () -> ReleaseProvisioningApplication.main(args(pg)));
            assertEquals(before, snapshot(db));
            db.update("insert into databasechangelog values ('fake','bot','fake','9:bad','EXECUTED',1)");
            before = snapshot(db);
            assertThrows(Exception.class, () -> ReleaseProvisioningApplication.main(args(pg)));
            assertEquals(before, snapshot(db));
            assertEquals(1, db.queryForObject("select count(*) from databasechangelog", Integer.class));
        }
    }

    @Test void serviceRejectsEmptyAndMissingHistoryWithoutWritingThenStartsReady() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg); String before = snapshot(db);
            var ex = assertThrows(Exception.class, () -> service(pg));
            assertTrue(causes(ex).contains("Release provisioning required"), causes(ex));
            assertEquals(before, snapshot(db));
            ReleaseProvisioningApplication.main(args(pg));
            initialize(pg);
            db.update("delete from databasechangelog where id in ('V16__run_ownership.sql','V17__postgres_comments.sql','V18__title_denial_reason.sql')");
            before = snapshot(db);
            ex = assertThrows(Exception.class, () -> service(pg));
            assertTrue(causes(ex).contains("unapplied migration"), causes(ex));
            assertEquals(before, snapshot(db));
            // Restore the missing change's actual state before rerunning its changeSet.
            db.execute("alter table conversation_title_work drop column denial_reason");
            db.execute("drop table run_ownership");
            db.execute("alter table workspace_admission_registration drop column start_lease_until");
            ReleaseProvisioningApplication.main(args(pg));
            before = snapshot(db);
            try (var context = service(pg)) {
                int port = ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) context)
                        .getWebServer().getPort();
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://127.0.0.1:" + port + "/actuator/health/readiness")).build();
                var response = java.net.http.HttpClient.newHttpClient().send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("UP"), response.body());
            }
            assertEquals(before, snapshot(db));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
        }
    }
    private static org.springframework.context.ConfigurableApplicationContext service(PostgreSQLContainer pg) {
        var arguments = new java.util.ArrayList<>(java.util.List.of(args(pg)));
        arguments.add("--spring.liquibase.enabled=true");
        return new SpringApplicationBuilder(BotApplication.class, TestChatModelConfiguration.class,
                TestUsageCaptureConfiguration.class).profiles("test").web(WebApplicationType.SERVLET)
                .properties("server.port=0", "management.endpoint.health.probes.enabled=true")
                .run(arguments.toArray(String[]::new));
    }
}
