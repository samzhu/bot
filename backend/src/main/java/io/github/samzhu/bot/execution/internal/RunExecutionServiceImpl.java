package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.bot.BotView;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.MessageView;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.execution.RunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionAttributes;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RunExecutionServiceImpl implements ExecutionOperations {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionServiceImpl.class);

    private final ChatRunRepository runRepository;
    private final ConversationOperations conversationOperations;
    private final BotOperations botOperations;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SessionService sessionService;
    private final RunRegistry runRegistry;
    private final ChatModel chatModel;
    private final TaskScheduler taskScheduler;
    private final RunProperties runProperties;
    private final int windowTurns;

    public RunExecutionServiceImpl(
            ChatRunRepository runRepository,
            ConversationOperations conversationOperations,
            BotOperations botOperations,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            TransactionTemplate transactionTemplate,
            SessionService sessionService,
            RunRegistry runRegistry,
            ChatModel chatModel,
            TaskScheduler botTaskScheduler,
            RunProperties runProperties,
            @Value("${bot.conversation.window-turns:10}") int windowTurns) {
        this.runRepository = runRepository;
        this.conversationOperations = conversationOperations;
        this.botOperations = botOperations;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.transactionTemplate = transactionTemplate;
        this.sessionService = sessionService;
        this.runRegistry = runRegistry;
        this.chatModel = chatModel;
        this.taskScheduler = botTaskScheduler;
        this.runProperties = runProperties;
        this.windowTurns = windowTurns;
    }

    @Override
    public Optional<RunView> findRunById(String runId, String userId) {
        return runRepository.findById(runId)
                .filter(run -> {
                    Optional<ConversationView> conv = conversationOperations.findConversationById(run.conversationId(), userId);
                    return conv.isPresent();
                })
                .map(this::toView);
    }

    @Override
    public List<RunView> listRunsByConversation(String conversationId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return runRepository.findByConversationIdWithPaging(conversationId, safeLimit, safeOffset).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public StartRunOutcome startRun(String conversationId, String clientRequestId, String message, String userId) {
        // 1. Verify conversation ownership
        Optional<ConversationView> convOpt = conversationOperations.findConversationById(conversationId, userId);
        if (convOpt.isEmpty()) {
            return new StartRunOutcome(StartRunOutcome.Type.CONFLICT, null, null, "Conversation not found or access denied");
        }
        ConversationView conv = convOpt.get();

        // 1.1 Verify bot availability and resolve execution mode
        // WHY / ARCHITECTURAL DEFENSE:
        // Checking bot availability before any DB write, chat_run query, or model/session invocation guarantees
        // zero side-effects when attempting to execute against a disconnected GKE agent (HTTP 503 GKE_AGENT_NOT_CONNECTED).
        //
        // TODO: Currently a local development Spring AI simulation. When GCP project, official agent endpoint,
        // credentials/identity, and event protocols are established, replace with Gemini Enterprise Agent Platform
        // integration (retaining GKE deployment architecture, not turning into arbitrary managed runtime).
        // Preserves cancel/dedup/session contracts.
        Optional<BotView> botOpt = botOperations.findById(conv.botId(), userId);
        if (botOpt.isEmpty() || !botOpt.get().canChat()) {
            return new StartRunOutcome(StartRunOutcome.Type.SERVICE_UNAVAILABLE, null, null, "GKE_AGENT_NOT_CONNECTED");
        }
        BotView bot = botOpt.get();
        String actualExecutionMode = bot.executionMode().name();

        // 2. ClientRequestId deduplication check
        Optional<ChatRun> existing = runRepository.findByConversationIdAndClientRequestId(conversationId, clientRequestId);
        if (existing.isPresent()) {
            ChatRun oldRun = existing.get();
            if (oldRun.inputText() != null && oldRun.inputText().equals(message)) {
                return new StartRunOutcome(StartRunOutcome.Type.EXISTING, toView(oldRun), null, null);
            } else {
                return new StartRunOutcome(StartRunOutcome.Type.CONFLICT, toView(oldRun), null, "Conflicting message for clientRequestId");
            }
        }

        // 3. Atomically insert run + user message
        // Why delegate active adjudication to DB: checking early active runs in application memory creates
        // a race where a concurrent same-ID request is misclassified as 409 active run rather than 200 duplicate.
        // We let the database unique indexes (uq_active_run_per_conv and uq_client_request_per_conv) adjudicate.
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ChatRun newRun = new ChatRun(runId, conversationId, clientRequestId, message, RunStatus.RUNNING.name(), actualExecutionMode, null, now, now);

        final String[] userMsgHolder = new String[1];
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcAggregateTemplate.insert(newRun);
                var uMsg = conversationOperations.appendMessage(conversationId, "USER", message, runId, false);
                userMsgHolder[0] = uMsg.id();
            });
        } catch (Exception ex) {
            // Check if this is specifically a unique constraint collision on chat_run active or client_request
            if (isRunUniqueConstraintCollision(ex)) {
                // Why re-query same clientRequestId first: another concurrent thread with the SAME clientRequestId
                // may have won the insert race. If so, return EXISTING (200 duplicate) or CONFLICT (409 conflicting payload), NOT a generic active 409!
                Optional<ChatRun> raced = runRepository.findByConversationIdAndClientRequestId(conversationId, clientRequestId);
                if (raced.isPresent()) {
                    ChatRun oldRun = raced.get();
                    if (oldRun.inputText() != null && oldRun.inputText().equals(message)) {
                        return new StartRunOutcome(StartRunOutcome.Type.EXISTING, toView(oldRun), null, null);
                    } else {
                        return new StartRunOutcome(StartRunOutcome.Type.CONFLICT, toView(oldRun), null, "Conflicting message for clientRequestId");
                    }
                }
                // If not same clientRequestId, collision was uq_active_run_per_conv: another active run is in progress
                Optional<ChatRun> activeRaced = runRepository.findActiveRunByConversationId(conversationId);
                return new StartRunOutcome(StartRunOutcome.Type.CONFLICT, activeRaced.map(this::toView).orElse(null), null, "Another active run is in progress for this conversation");
            }
            // For non-unique-constraint exceptions (e.g. FK violation, NOT NULL, serialization failure), propagate!
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to start run due to unexpected database error", ex);
        }

        // 4. Create SseEmitter with 0L timeout (lifecycle strictly managed by app deadline and heartbeat)
        SseEmitter emitter = new SseEmitter(0L);
        RunHandle handle = new RunHandle(
                runId,
                conversationId,
                userId,
                emitter,
                runRegistry,
                userMsgHolder[0],
                new RunHandle.TerminalCallback() {
                    @Override
                    public RunHandle.TerminalResult commitTerminal(String rId, String convId, RunStatus targetStatus, String errorMessage, String assistantContent, boolean interrupted, String userPrompt) {
                        return commitTerminalInternal(rId, convId, targetStatus, errorMessage, assistantContent, interrupted, userPrompt);
                    }

                    @Override
                    public RunStatus queryCurrentStatus(String rId) {
                        return runRepository.findById(rId)
                                .map(r -> RunStatus.valueOf(r.status()))
                                .orElse(RunStatus.FAILED);
                    }
                }
        );

        runRegistry.register(runId, handle);

        // 5. Send run.started event (seq = 1) BEFORE starting scheduled tasks
        handle.sendStarted();

        // 6. Start scheduled tasks (application deadline & heartbeat) ONLY AFTER run.started has been sent
        // Why startScheduledTasks after sendStarted: prevents deadline or heartbeat frames from interleaving before seq 1.
        handle.startScheduledTasks(taskScheduler, runProperties.getTimeout(), runProperties.getHeartbeatInterval());

        // 6. Build prompt context using standard SessionService findOrCreate & bounded getActiveMessages (last 10 turns = 20 messages)
        List<org.springframework.ai.chat.messages.Message> aiMessages = new ArrayList<>();
        try {
            botOperations.getEffectiveSystemPrompt(conv.botId(), userId)
                    .filter(sp -> !sp.isBlank())
                    .ifPresent(sp -> aiMessages.add(new SystemMessage(sp)));

            // Why standard SessionService: Spring AI 2.1 deprecated legacy ChatMemory;
            // findOrCreate initializes the conversation session with standard principal metadata.
            sessionService.findOrCreate(CreateSessionRequest.builder()
                    .id(conversationId)
                    .metadata(Map.of(SessionAttributes.PRINCIPAL_NAME, userId))
                    .build());

            List<org.springframework.ai.chat.messages.Message> activeMessages = sessionService.getActiveMessages(conversationId);
            if (activeMessages != null && !activeMessages.isEmpty()) {
                // Why configurable window: Ticket 02 limits prompt memory context to the most recent N complete turns (default 10, i.e. 20 messages).
                int maxTurnMessages = Math.max(1, windowTurns) * 2;
                int startIndex = Math.max(0, activeMessages.size() - maxTurnMessages);
                aiMessages.addAll(activeMessages.subList(startIndex, activeMessages.size()));
            }
            aiMessages.add(new UserMessage(message));
        } catch (Exception ex) {
            log.error("Failed to assemble prompt instructions for run {}", runId, ex);
            handle.fail("Failed to build prompt context: " + ex.getMessage());
            return new StartRunOutcome(StartRunOutcome.Type.STARTED, toView(newRun), emitter, null);
        }

        // 7. Dispatch model streaming
        /*
         * WHY Schedulers.boundedElastic():
         * Provider SDKs such as Spring AI's Google GenAI wrap blocking iterable streams (e.g. ResponseStream via Flux.fromIterable).
         * Subscribing on the caller thread causes synchronous iteration on Tomcat's MVC worker thread, preventing startRun()
         * from returning the SseEmitter and delaying HTTP 200 headers and seq 1 run.started until generation is complete.
         * Offloading subscription to Reactor's shared boundedElastic scheduler releases Tomcat's worker thread, allowing
         * startRun() to return the SseEmitter so headers and run.started are flushed to the client without delay.
         * This enables real-time delta consumption and timely user cancellation.
         * The resulting Disposable is registered via handle.setSubscription(disposable), maintaining thread safety under
         * single-lock CAS, TransactionTemplate atomicity, and clean disposal if cancelled early or late.
         */
        if (chatModel instanceof StreamingChatModel streamingModel) {
            try {
                Disposable disposable = Flux.defer(() -> streamingModel.stream(new Prompt(aiMessages)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                chatResponse -> {
                                    if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                                        String text = chatResponse.getResult().getOutput().getText();
                                        if (text != null) {
                                            handle.delta(text);
                                        }
                                    }
                                },
                                error -> handle.fail(error != null ? error.getMessage() : "Model streaming error"),
                                () -> handle.complete(message)
                        );
                handle.setSubscription(disposable);
            } catch (Exception ex) {
                log.error("Synchronous exception subscribing to model stream for run {}", runId, ex);
                handle.fail("Model subscription error: " + ex.getMessage());
            }
        } else {
            /*
             * Non-streaming fallback: wrap synchronous ChatModel.call in Mono.fromCallable with the same boundedElastic
             * scheduler to prevent blocking the MVC request thread when alternative non-streaming models are configured.
             * Explicitly require non-null ChatResponse so null model returns trigger immediate failure rather than hanging
             * as an empty Mono until the deadline scheduler fires.
             */
            try {
                Disposable disposable = Mono.fromCallable(() -> {
                            var response = chatModel.call(new Prompt(aiMessages));
                            Objects.requireNonNull(response, "Model call returned null ChatResponse");
                            return response;
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe(
                                response -> {
                                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                                        String text = response.getResult().getOutput().getText();
                                        if (text != null) {
                                            handle.delta(text);
                                        }
                                    }
                                    handle.complete(message);
                                },
                                error -> handle.fail(error != null ? error.getMessage() : "Model execution error")
                        );
                handle.setSubscription(disposable);
            } catch (Exception ex) {
                log.error("Synchronous exception invoking non-streaming model for run {}", runId, ex);
                handle.fail("Model execution error: " + ex.getMessage());
            }
        }

        return new StartRunOutcome(StartRunOutcome.Type.STARTED, toView(newRun), emitter, null);
    }

    private RunHandle.TerminalResult commitTerminalInternal(
            String runId,
            String conversationId,
            RunStatus targetStatus,
            String errorMessage,
            String assistantContent,
            boolean interrupted,
            String userPrompt) {

        try {
            // Why short transaction: CAS terminal update, assistant message persistence,
            // and Session Memory append must succeed atomically. Model streaming occurs entirely
            // outside DB transactions to prevent connection pool exhaustion.
            return transactionTemplate.execute(status -> {
                // CAS status update: only updates if current status is PENDING or RUNNING.
                int rows = runRepository.updateTerminalStatus(runId, targetStatus.name(), errorMessage, Instant.now());
                if (rows == 0) {
                    // Why CAS loser does not mark FAILED: another thread/path already reached terminal
                    // (e.g. completed before cancel, or cancelled before complete). We roll back and return the existing DB terminal status.
                    status.setRollbackOnly();
                    RunStatus cur = runRepository.findById(runId)
                            .map(r -> RunStatus.valueOf(r.status()))
                            .orElse(RunStatus.FAILED);
                    return new RunHandle.TerminalResult(false, cur, null, null);
                }

                String messageId = null;
                // Append assistant message if content exists (for COMPLETED or partial CANCELLED/FAILED)
                if (assistantContent != null && !assistantContent.isBlank()) {
                    MessageView msg = conversationOperations.appendMessage(conversationId, "ASSISTANT", assistantContent, runId, interrupted);
                    messageId = msg.id();
                }

                // Why Session Memory append inside transaction: only successful COMPLETED turns
                // must be appended to session history. If appendEvents fails or throws,
                // the entire transaction rolls back so that no orphaned assistant message exists without session history.
                if (targetStatus == RunStatus.COMPLETED) {
                    long expectedVersion = sessionService.getEventVersion(conversationId);
                    SessionEvent userEvt = SessionEvent.builder()
                            .sessionId(conversationId)
                            .type(EventType.MESSAGE)
                            .message(new UserMessage(userPrompt))
                            .build();
                    SessionEvent assistantEvt = SessionEvent.builder()
                            .sessionId(conversationId)
                            .type(EventType.MESSAGE)
                            .message(new AssistantMessage(assistantContent))
                            .build();

                    boolean appendOk = sessionService.appendEvents(conversationId, List.of(userEvt, assistantEvt), expectedVersion);
                    if (!appendOk) {
                        throw new IllegalStateException("SessionService.appendEvents failed: expectedVersion mismatch");
                    }
                }

                return new RunHandle.TerminalResult(true, targetStatus, messageId, errorMessage);
            });
        } catch (Exception ex) {
            log.error("Transaction rolled back for run {} during terminal commit: {}", runId, ex.getMessage(), ex);
            // In a new independent short transaction, mark as FAILED with interrupted assistant partial (no Session pair)
            return fallbackToFailed(runId, conversationId, assistantContent, "Terminal commit failure: " + ex.getMessage());
        }
    }

    private RunHandle.TerminalResult fallbackToFailed(String runId, String conversationId, String assistantContent, String error) {
        try {
            return transactionTemplate.execute(status -> {
                int rows = runRepository.updateTerminalStatus(runId, RunStatus.FAILED.name(), error, Instant.now());
                if (rows == 0) {
                    status.setRollbackOnly();
                    RunStatus cur = runRepository.findById(runId)
                            .map(r -> RunStatus.valueOf(r.status()))
                            .orElse(RunStatus.FAILED);
                    return new RunHandle.TerminalResult(false, cur, null, error);
                }
                String msgId = null;
                if (assistantContent != null && !assistantContent.isBlank()) {
                    MessageView msg = conversationOperations.appendMessage(conversationId, "ASSISTANT", assistantContent, runId, true);
                    msgId = msg.id();
                }
                return new RunHandle.TerminalResult(true, RunStatus.FAILED, msgId, error);
            });
        } catch (Exception e) {
            log.error("Failed to mark run {} as FAILED in fallback transaction", runId, e);
            // Fresh query actual status from DB - DO NOT forge FAILED with casSuccess=true!
            RunStatus actual = runRepository.findById(runId)
                    .map(r -> RunStatus.valueOf(r.status()))
                    .orElse(null);
            return new RunHandle.TerminalResult(false, actual, null, error);
        }
    }

    private boolean isRunUniqueConstraintCollision(Throwable ex) {
        Throwable cur = ex;
        boolean isUniqueViolation = false;
        boolean isRunConstraint = false;

        while (cur != null) {
            if (cur instanceof org.springframework.dao.DuplicateKeyException) {
                isUniqueViolation = true;
            }
            if (cur instanceof java.sql.SQLException sqlEx) {
                if ("23505".equals(sqlEx.getSQLState())) {
                    isUniqueViolation = true;
                }
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("uq_active_run_per_conv") || msg.contains("uq_client_request_per_conv"))) {
                isRunConstraint = true;
            }
            cur = cur.getCause();
        }

        return isUniqueViolation && isRunConstraint;
    }

    @Override
    public CancelRunOutcome cancelRun(String runId, String userId) {
        RunHandle handle = runRegistry.get(runId);
        if (handle != null) {
            if (!handle.getOwnerId().equals(userId)) {
                return new CancelRunOutcome(runId, RunStatus.FAILED, false);
            }
            RunStatus status = handle.cancel(userId);
            return new CancelRunOutcome(runId, status, status == RunStatus.CANCELLED);
        }

        // DB Fallback if not active in registry
        Optional<ChatRun> runOpt = runRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return new CancelRunOutcome(runId, RunStatus.FAILED, false);
        }
        ChatRun run = runOpt.get();
        Optional<ConversationView> conv = conversationOperations.findConversationById(run.conversationId(), userId);
        if (conv.isEmpty()) {
            return new CancelRunOutcome(runId, RunStatus.FAILED, false);
        }

        // CAS cancel in DB
        runRepository.updateTerminalStatus(runId, RunStatus.CANCELLED.name(), "Run cancelled by user", Instant.now());
        RunStatus finalStatus = runRepository.findById(runId)
                .map(r -> RunStatus.valueOf(r.status()))
                .orElse(RunStatus.CANCELLED);
        return new CancelRunOutcome(runId, finalStatus, finalStatus == RunStatus.CANCELLED);
    }

    private RunView toView(ChatRun r) {
        return new RunView(
                r.id(),
                r.conversationId(),
                r.clientRequestId(),
                r.inputText(),
                r.status(),
                r.executionMode() == null ? "LOCAL" : r.executionMode(),
                r.errorMessage(),
                r.createdAt(),
                r.updatedAt()
        );
    }
}
