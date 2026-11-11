package io.github.samzhu.bot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Local development seed initializer.
 * Isolated in the 'localDevelopment' sourceSet so it is never packaged into production JARs or container images.
 * Idempotently populates three demonstration conversations (COMPLETED, FAILED, CANCELLED) with fixed IDs.
 */
@Component
@Profile("local")
@Order(20)
public class LocalDataSeedInitializer implements ApplicationRunner {

    /*
     * WHY / ARCHITECTURAL TRADE-OFF:
     * LocalDataSeedInitializer is a development-only technical fixture assembly.
     * It interacts with application tables (bot, conversation, chat_run, message) directly via standard SQL
     * to completely isolate fixture setup, guarantee deterministic fixed IDs, avoid executing live AI models,
     * and strictly preserve module boundaries without importing or exposing any package-internal domain entities
     * or repositories.
     * Framework tables (ai_session, ai_session_index, ai_session_event) are manipulated exclusively through the
     * official Spring AI SessionService API within the same transaction to guarantee legitimate JSON payload
     * serialization, optimistic event versioning, and index consistency.
     */

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeedInitializer.class);

    private final SessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public LocalDataSeedInitializer(
            SessionService sessionService,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting LocalDataSeedInitializer for demonstration conversations...");

        // 1. Ensure default bot exists for local development
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, system_prompt, created_at)
            VALUES ('bot-general', 'General Assistant', 'You are a helpful, accurate, and concise AI coding assistant.', CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING;
        """);

        // 2. Re-entrancy check: if completed seed conv exists, do not duplicate or overwrite
        Integer existsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation WHERE id = 'seed-conv-completed-01'",
                Integer.class
        );
        if (existsCount != null && existsCount > 0) {
            log.info("Local demonstration seed data already present. Skipping initialization.");
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            loadCompletedScenario();
            loadFailedScenario();
            loadCancelledScenario();
        });

        log.info("Local demonstration seed data successfully initialized (3 conversations).");
    }

    private void loadCompletedScenario() {
        String convId = "seed-conv-completed-01";
        String runId = "seed-run-completed-01";
        String userId = "dev-user";
        Instant t0 = Instant.now().minusSeconds(300);
        Instant t1 = t0.plusSeconds(2);

        jdbcTemplate.update("""
            INSERT INTO conversation (id, bot_id, title, user_id, created_at, updated_at)
            VALUES (?, 'bot-general', '示範：成功問答 (COMPLETED)', ?, ?, ?)
        """, convId, userId, Timestamp.from(t0), Timestamp.from(t1));

        String userPrompt = "請用一句話介紹什麼是 Spring AI？";
        String assistantReply = """
            Spring AI 是一個旨在簡化 Java 開發者整合人工智慧功能的應用框架：

            * **核心抽象**：提供 `ChatClient`、`Prompt` 與多模型切換介面。
            * **對話記憶**：官方支援 `SessionService` 與短期對話歷史串接。

            ```java
            ChatResponse response = chatClient.prompt()
                .user("Hello World")
                .call()
                .chatResponse();
            ```
            """.stripIndent();

        jdbcTemplate.update("""
            INSERT INTO chat_run (id, conversation_id, client_request_id, input_text, status, error_message, created_at, updated_at)
            VALUES (?, ?, 'seed-req-completed-01', ?, 'COMPLETED', NULL, ?, ?)
        """, runId, convId, userPrompt, Timestamp.from(t0), Timestamp.from(t1));

        jdbcTemplate.update("""
            INSERT INTO message (id, conversation_id, run_id, role, content, interrupted, created_at)
            VALUES ('seed-msg-completed-user-01', ?, ?, 'USER', ?, FALSE, ?),
                   ('seed-msg-completed-assistant-01', ?, ?, 'ASSISTANT', ?, FALSE, ?)
        """, convId, runId, userPrompt, Timestamp.from(t0), convId, runId, assistantReply, Timestamp.from(t1));

        // Spring AI Session memory: Turn pair populated strictly for COMPLETED runs via official SessionService API
        sessionService.findOrCreate(CreateSessionRequest.builder()
                .id(convId)
                .metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, userId))
                .build());

        long expectedVersion = sessionService.getEventVersion(convId);
        SessionEvent userEvt = SessionEvent.builder()
                .sessionId(convId)
                .type(EventType.MESSAGE)
                .message(new UserMessage(userPrompt))
                .build();
        SessionEvent assistantEvt = SessionEvent.builder()
                .sessionId(convId)
                .type(EventType.MESSAGE)
                .message(new AssistantMessage(assistantReply))
                .build();

        boolean appendOk = sessionService.appendEvents(convId, List.of(userEvt, assistantEvt), expectedVersion);
        if (!appendOk) {
            throw new IllegalStateException("Failed to append seed session events for completed scenario");
        }
    }

    private void loadFailedScenario() {
        String convId = "seed-conv-failed-01";
        String runId = "seed-run-failed-01";
        String userId = "dev-user";
        Instant t0 = Instant.now().minusSeconds(200);
        Instant t1 = t0.plusSeconds(1);

        jdbcTemplate.update("""
            INSERT INTO conversation (id, bot_id, title, user_id, created_at, updated_at)
            VALUES (?, 'bot-general', '示範：模型錯誤 (FAILED)', ?, ?, ?)
        """, convId, userId, Timestamp.from(t0), Timestamp.from(t1));

        String userPrompt = "觸發超時與配額不足示範";
        String errorMsg = "MODEL_RATE_LIMIT_EXCEEDED: Quota exceeded for model provider";
        String partialReply = "處理請求時發生異常，無法連線至模型提供者";

        jdbcTemplate.update("""
            INSERT INTO chat_run (id, conversation_id, client_request_id, input_text, status, error_message, created_at, updated_at)
            VALUES (?, ?, 'seed-req-failed-01', ?, 'FAILED', ?, ?, ?)
        """, runId, convId, userPrompt, errorMsg, Timestamp.from(t0), Timestamp.from(t1));

        jdbcTemplate.update("""
            INSERT INTO message (id, conversation_id, run_id, role, content, interrupted, created_at)
            VALUES ('seed-msg-failed-user-01', ?, ?, 'USER', ?, FALSE, ?),
                   ('seed-msg-failed-assistant-01', ?, ?, 'ASSISTANT', ?, TRUE, ?)
        """, convId, runId, userPrompt, Timestamp.from(t0), convId, runId, partialReply, Timestamp.from(t1));

        // Session initialized with 0 turn events (failed runs do not persist memory turn pairs)
        sessionService.findOrCreate(CreateSessionRequest.builder()
                .id(convId)
                .metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, userId))
                .build());
    }

    private void loadCancelledScenario() {
        String convId = "seed-conv-cancelled-01";
        String runId = "seed-run-cancelled-01";
        String userId = "dev-user";
        Instant t0 = Instant.now().minusSeconds(100);
        Instant t1 = t0.plusSeconds(3);

        jdbcTemplate.update("""
            INSERT INTO conversation (id, bot_id, title, user_id, created_at, updated_at)
            VALUES (?, 'bot-general', '示範：中途取消 (CANCELLED)', ?, ?, ?)
        """, convId, userId, Timestamp.from(t0), Timestamp.from(t1));

        String userPrompt = "請幫我寫一篇五千字的量子力學詳解";
        String partialReply = "量子力學是描述微觀粒子（如電子、光子）行為的物理學基礎理論，其核心原理包括波粒二象性、不確定性原理以及疊加態。當我們觀察一個量子系統時，波函數會發生坍縮";

        jdbcTemplate.update("""
            INSERT INTO chat_run (id, conversation_id, client_request_id, input_text, status, error_message, created_at, updated_at)
            VALUES (?, ?, 'seed-req-cancelled-01', ?, 'CANCELLED', 'Run cancelled by user', ?, ?)
        """, runId, convId, userPrompt, Timestamp.from(t0), Timestamp.from(t1));

        jdbcTemplate.update("""
            INSERT INTO message (id, conversation_id, run_id, role, content, interrupted, created_at)
            VALUES ('seed-msg-cancelled-user-01', ?, ?, 'USER', ?, FALSE, ?),
                   ('seed-msg-cancelled-assistant-01', ?, ?, 'ASSISTANT', ?, TRUE, ?)
        """, convId, runId, userPrompt, Timestamp.from(t0), convId, runId, partialReply, Timestamp.from(t1));

        // Session initialized with 0 turn events (cancelled runs do not persist memory turn pairs)
        sessionService.findOrCreate(CreateSessionRequest.builder()
                .id(convId)
                .metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, userId))
                .build());
    }
}
