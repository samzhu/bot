package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ConversationTitleRecoveryHttpIntegrationTests {
    @Container static final PostgreSQLContainer postgres = PostgresTestImage.container();
    static final ObjectMapper json = new ObjectMapper();
    static final HttpClient http = HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean @Primary ControlledChatModel controlledChatModel() { return new ControlledChatModel(); }
        @Bean TitleFake titleFake() { return new TitleFake(); }
        @Bean TaskTracker taskTracker() { return new TaskTracker(); }
        @Bean static BeanPostProcessor trackTitleExecutor(TaskTracker tracker) {
            return new BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (name.equals("conversationTitleExecutor")) {
                        var executor = (SimpleAsyncTaskExecutor) bean;
                        executor.setTaskDecorator(task -> () -> {
                            tracker.started.incrementAndGet();
                            try { task.run(); } finally { tracker.finished.incrementAndGet(); }
                        });
                        executor.setTaskTerminationTimeout(10000);
                        executor.setCancelRemainingTasksOnClose(true);
                    }
                    return bean;
                }
            };
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ChatOnly {
        @Bean @Primary ControlledChatModel controlledChatModel() { return new ControlledChatModel(); }
    }
    static class TaskTracker { final AtomicInteger started = new AtomicInteger(); final AtomicInteger finished = new AtomicInteger(); }
    static class TitleFake implements ConversationTitleGenerator {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch firstTwo = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        @Override public String generate(String input) throws InterruptedException {
            calls.incrementAndGet(); firstTwo.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("fake timed out");
            returned.countDown();
            return "Recovered title";
        }
    }
    ConfigurableApplicationContext start() { return start(true); }
    ConfigurableApplicationContext start(boolean titleEnabled) { return start(titleEnabled, "60s"); }
    ConfigurableApplicationContext start(boolean titleEnabled, String executionTimeout) {
        SpringApplication app = new SpringApplication(BotApplication.class, TestUsageCaptureConfiguration.class,
                titleEnabled ? Fakes.class : ChatOnly.class);
        app.setAdditionalProfiles("test");
        app.addInitializers(context -> {
            context.getEnvironment().setActiveProfiles("test");
            context.getEnvironment().getPropertySources().addFirst(
            new org.springframework.core.env.MapPropertySource("isolated-test-container", Map.of(
                "server.port", "0", "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", postgres.getUsername(), "spring.datasource.password", postgres.getPassword(),
                "bot.conversation.title.queue-timeout", "60s", "bot.conversation.title.execution-timeout", executionTimeout)));
        });
        var context = app.run();
        try {
        assertNull(context.getEnvironment().getProperty("bot-ai-provider"));
        assertEquals(titleEnabled ? 1 : 0, context.getBeansOfType(ConversationTitleGenerator.class).size());
        assertSame(context.getBean(ControlledChatModel.class), context.getBean(ChatModel.class));
        assertEquals(1, context.getBeansOfType(ChatModel.class).size());
        try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
            assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL());
        } catch (java.sql.SQLException ex) { throw new IllegalStateException(ex); }
        return context;
        } catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }
    int port(ConfigurableApplicationContext context) { return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class); }
    String post(int port, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
    String createAndReply(int port, String scope, String input) throws Exception {
        var result = json.readTree(post(port, "/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))));
        String id = result.path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), result.toString());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(),
            "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", input)),
            "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        String events = post(port, "/api/ag-ui", body);
        assertTrue(events.contains("TEXT_MESSAGE_CONTENT"), events);
        assertTrue(events.contains("RUN_FINISHED"), events);
        return id;
    }
    @Test void eligibleUndispatchedWorkResumesInNewInstance() throws Exception {
        ConfigurableApplicationContext first = start();
        TitleFake firstFake = first.getBean(TitleFake.class);
        try {
            var jdbc = first.getBean(JdbcTemplate.class);
            String scope = "recovery-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic recovery");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            createAndReply(port(first), scope, "occupy one");
            createAndReply(port(first), scope, "occupy two");
            assertTrue(firstFake.firstTwo.await(10, TimeUnit.SECONDS));
            String id = createAndReply(port(first), scope, "recover this message");
            assertEquals(2, firstFake.calls.get(), "the eligible target must not have been dispatched");
            assertEquals("READY", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id),
                "visible reply must persist an independently recoverable state");
            TaskTracker tracker = first.getBean(TaskTracker.class);
            long waitForTasks = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (tracker.started.get() < 3 && System.nanoTime() < waitForTasks) Thread.sleep(10);
            assertEquals(3, tracker.started.get());
            first.close();
            assertEquals(tracker.started.get(), tracker.finished.get(), "all old executor tasks must have exited");
            assertEquals(2, firstFake.calls.get(), "closed instance must not dispatch the queued target");
            try (var second = start()) {
                TitleFake secondFake = second.getBean(TitleFake.class);
                assertNotSame(firstFake, secondFake);
                secondFake.release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                String title = "";
                while (System.nanoTime() < deadline) {
                    var response = json.readTree(post(port(second), "/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { id title } }", "variables", Map.of("id", id))));
                    title = response.path("data").path("conversation").path("title").asText();
                    if ("Recovered title".equals(title)) break;
                    Thread.sleep(50);
                }
                assertEquals("Recovered title", title);
                assertEquals(1, secondFake.calls.get());
                assertEquals(1L, second.getBean(JdbcTemplate.class).queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id));
            }
        } finally { firstFake.release.countDown(); if (first.isActive()) first.close(); }
    }
    @Test void expiredUnknownClaimNeverDispatchesAgainOnAnotherInstance() throws Exception {
        try (var first = start()) {
            var jdbc = first.getBean(JdbcTemplate.class);
            String scope = "unknown-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unknown claim");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            String id = createAndReply(port(first), scope, "unknown claim");
            TitleFake fake = first.getBean(TitleFake.class);
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fake.calls.get() == 0 && System.nanoTime() < limit) Thread.sleep(10);
            assertEquals(1, fake.calls.get());
            assertEquals("CLAIMED", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id));
            // Model an expired persisted claim while its original caller still exists: the outcome is unknown.
            jdbc.update("update conversation_title_work set claimed_until=clock_timestamp() - interval '1 second' where conversation_id=?", id);
            try (var second = start()) {
                TitleFake other = second.getBean(TitleFake.class);
                limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                String state = "";
                while (System.nanoTime() < limit) {
                    state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                    if ("FAILED".equals(state)) break;
                    Thread.sleep(25);
                }
                assertEquals("FAILED", state);
                fake.release.countDown();
                assertTrue(fake.returned.await(3, TimeUnit.SECONDS));
                assertEquals(0, other.calls.get());
                assertEquals(1, fake.calls.get());
                assertEquals(0L, jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id));
                var response = json.readTree(post(port(second), "/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { id title } }", "variables", Map.of("id", id))));
                assertEquals("New Conversation", response.path("data").path("conversation").path("title").asText());
            } finally { fake.release.countDown(); }
        }
    }

    @Test void notificationReadsCommittedTitleFromAnotherInstanceWithoutGenerator() throws Exception {
        try (var first = start()) {
            var jdbc = first.getBean(JdbcTemplate.class);
            String scope = "notify-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic notification");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            String id = createAndReply(port(first), scope, "committed elsewhere");
            TitleFake fake = first.getBean(TitleFake.class);
            fake.release.countDown();
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            long version = 0;
            while (System.nanoTime() < limit) {
                version = jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id);
                if (version == 1) break;
                Thread.sleep(25);
            }
            assertEquals(1, version);
            try (var second = start(false)) {
                var body = Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id version state } }", "variables", Map.of("id", id));
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(second) + "/graphql"))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                var result = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
                assertTrue(result.body().contains("\"state\":\"DONE\""), result.body());
                assertTrue(result.body().contains("\"version\":\"1\""), result.body());
            }
        }
    }

    @Test void concurrentInstancesClaimEligibleWorkOnlyOnce() throws Exception {
        try (var first = start()) {
            var jdbc = first.getBean(JdbcTemplate.class);
            String scope = "compete-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic competing workers");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            TitleFake firstFake = first.getBean(TitleFake.class);
            try {
                createAndReply(port(first), scope, "hold one");
                createAndReply(port(first), scope, "hold two");
                assertTrue(firstFake.firstTwo.await(10, TimeUnit.SECONDS));
                String id = createAndReply(port(first), scope, "competing workers");
                assertEquals("READY", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id));
                try (var second = start()) {
                    TitleFake other = second.getBean(TitleFake.class);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (other.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
                    assertEquals(1, other.calls.get());
                    firstFake.release.countDown();
                    other.release.countDown();
                    deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    long version = 0;
                    while (System.nanoTime() < deadline) {
                        version = jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id);
                        if (version == 1) break;
                        Thread.sleep(20);
                    }
                    assertEquals(1L, version);
                    assertEquals(2, firstFake.calls.get());
                    assertEquals(1, other.calls.get());
                }
            } finally { firstFake.release.countDown(); }
        }
    }

    @Test void changedTitleAndDeletedConversationAreProtected() throws Exception {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String scope = "protect-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic protected titles");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            TitleFake fake = context.getBean(TitleFake.class);
            String id = createAndReply(port(context), scope, "title changed while waiting");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fake.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, fake.calls.get());
            jdbc.update("update conversation set title='User title' where id=?", id);
            fake.release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < deadline) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            assertEquals(0L, jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id));
            var result = json.readTree(post(port(context), "/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { id title } }", "variables", Map.of("id", id))));
            assertEquals("User title", result.path("data").path("conversation").path("title").asText());

            var created = json.readTree(post(port(context), "/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))));
            String deleted = created.path("data").path("createConversation").path("id").asText();
            assertFalse(deleted.isBlank());
            // No Run or admission references: this synthetic READY tests the FK cascade only.
            jdbc.update("update conversation_title_work set state='READY',ready_until=clock_timestamp() + interval '1 minute' where conversation_id=?", deleted);
            jdbc.update("delete from conversation where id=?", deleted);
            assertEquals(0, jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id=?", Integer.class, deleted));
            assertEquals(1, fake.calls.get());
        }
    }

    @Test void waitingAndLegacyRowsAreNotPromotedAndRevokedMemberCannotSubscribe() throws Exception {
        try (var context = start()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String scope = "isolated-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic isolation");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var mutation = Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope));
            String waiting = json.readTree(post(port(context), "/graphql", mutation)).path("data").path("createConversation").path("id").asText();
            String legacy = json.readTree(post(port(context), "/graphql", mutation)).path("data").path("createConversation").path("id").asText();
            assertFalse(waiting.isBlank()); assertFalse(legacy.isBlank());
            // Model an older conversation, created before title-work rows existed.
            jdbc.update("delete from conversation_title_work where conversation_id=?", legacy);
            assertFalse(context.getBean(TitleFake.class).firstTwo.await(2200, TimeUnit.MILLISECONDS));
            assertEquals(0, context.getBean(TitleFake.class).calls.get());
            assertEquals("WAITING", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, waiting));
            assertEquals(0, jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id=?", Integer.class, legacy));
            jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'", scope);
            var body = Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id version state } }", "variables", Map.of("id", waiting));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port(context) + "/graphql"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var result = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(result.body().contains("errors"), result.body());
            assertFalse(result.body().contains("\"conversationTitleChanged\":{\"id\""), result.body());
        }
    }

    @Test void changedTitleAtQueueHeadCannotStarveLaterEligibleWork() throws Exception {
        try (var first = start()) {
            var jdbc = first.getBean(JdbcTemplate.class);
            String scope = "fair-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic fair recovery");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            TitleFake fake = first.getBean(TitleFake.class);
            try {
                createAndReply(port(first), scope, "hold one");
                createAndReply(port(first), scope, "hold two");
                assertTrue(fake.firstTwo.await(10, TimeUnit.SECONDS));
                String changed = createAndReply(port(first), scope, "changed before dispatch");
                jdbc.update("update conversation set title='Custom title' where id=?", changed);
                String target = createAndReply(port(first), scope, "later eligible");
                assertEquals("READY", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, changed));
                assertEquals("READY", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, target));
                try (var second = start()) {
                    TitleFake other = second.getBean(TitleFake.class);
                    other.release.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    long version = 0;
                    while (System.nanoTime() < deadline) {
                        version = jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, target);
                        if (version == 1) break;
                        Thread.sleep(20);
                    }
                    assertEquals(1, version, "invalid earlier READY must not block a later eligible job");
                    assertEquals("Custom title", jdbc.queryForObject("select title from conversation where id=?", String.class, changed));
                    assertEquals(1, other.calls.get());
                }
            } finally { fake.release.countDown(); }
        }
    }

    @Test void blockedRecoveryQueryCannotDelayAnotherJobsDeadline() throws Exception {
        try (var context = start(true, "2s")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            String scope = "deadline-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic deadline isolation");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var mutation = Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope));
            String blocked = json.readTree(post(port(context), "/graphql", mutation)).path("data").path("createConversation").path("id").asText();
            assertFalse(blocked.isBlank());
            jdbc.update("update conversation_title_work set state='CLAIMED', claimed_until=clock_timestamp() - interval '1 second' where conversation_id=?", blocked);
            try (var lock = context.getBean(javax.sql.DataSource.class).getConnection()) {
                lock.setAutoCommit(false);
                int blocker;
                try (var st = lock.createStatement(); var rows = st.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker = rows.getInt(1); }
                try (var st = lock.prepareStatement("select conversation_id from conversation_title_work where conversation_id=? for update")) {
                    st.setString(1, blocked); try (var rows = st.executeQuery()) { assertTrue(rows.next()); }
                }
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean waiting = false;
                while (System.nanoTime() < limit) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class, blocker));
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertTrue(waiting, "recovery did not reach the controlled lock");
                String target = createAndReply(port(context), scope, "deadline while recovery is blocked");
                limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                String state = "";
                while (System.nanoTime() < limit) {
                    state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, target);
                    if ("FAILED".equals(state)) break;
                    Thread.sleep(20);
                }
                assertEquals("FAILED", state, "deadline must settle while recovery remains blocked");
                assertEquals(0L, jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, target));
                assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class, blocker)));
                lock.rollback();
            }
        }
    }

    @Test void twoWorkersBlockedOnSameReadyRowClaimOnlyOnce() throws Exception {
        try (var origin = start(false)) {
            var jdbc = origin.getBean(JdbcTemplate.class);
            String scope = "claim-race-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic claim race");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            String id = createAndReply(port(origin), scope, "same ready row");
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String state = "";
            while (System.nanoTime() < limit) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("READY".equals(state)) break;
                Thread.sleep(10);
            }
            assertEquals("READY", state);
            try (var lock = origin.getBean(javax.sql.DataSource.class).getConnection()) {
                lock.setAutoCommit(false);
                int blocker;
                try (var st = lock.createStatement(); var rows = st.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker = rows.getInt(1); }
                try (var st = lock.prepareStatement("select conversation_id from conversation_title_work where conversation_id=? for update")) {
                    st.setString(1, id); try (var rows = st.executeQuery()) { assertTrue(rows.next()); }
                }
                try (var one = start(); var two = start()) {
                    limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    int waiting = 0;
                    while (System.nanoTime() < limit) {
                        waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%update conversation_title_work w set state=%CLAIMED%'", Integer.class);
                        if (waiting >= 2) break;
                        Thread.sleep(20);
                    }
                    assertTrue(waiting >= 2, "both workers must contend for the same work row");
                    assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class, blocker)));
                    lock.rollback();
                    TitleFake a = one.getBean(TitleFake.class), b = two.getBean(TitleFake.class);
                    try {
                        limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        while (a.calls.get() + b.calls.get() == 0 && System.nanoTime() < limit) Thread.sleep(20);
                        assertEquals(1, a.calls.get() + b.calls.get());
                        a.release.countDown(); b.release.countDown();
                        limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        long version = 0;
                        while (System.nanoTime() < limit) {
                            version = jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id);
                            if (version == 1) break;
                            Thread.sleep(20);
                        }
                        assertEquals(1L, version);
                        assertEquals(1, a.calls.get() + b.calls.get());
                    } finally { a.release.countDown(); b.release.countDown(); }
                }
            }
        }
    }

}
