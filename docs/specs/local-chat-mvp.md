# Local Chat MVP 規格說明書 (Specification)

> 狀態：草案更新（Gate B 票單準備中），2026-11-10。
> 依據 `to-spec` 規格模板生成。
> 決策邊界：本規格完全不包含任何程式碼片段、GraphQL Schema 語法塊、JSON 酬載或 SQL 宣告。僅以純語意精確定義外部可觀察行為、介面契約、資料庫約束與系統狀態機。  
> 架構演進：基於 Java 25、Gradle 9.8.2、Spring Boot 4.1.2 與 Spring AI 2.1.0-RC2。採用模組化套件邊界（Pre-Modulith 單向無環架構）與標準 SessionService API，手動配置模型 Bean 並排除底層自動裝配。

---

## Problem Statement

開發者在本地評估與研發 AI 聊天平台時，缺乏一個架構清晰、具備完整持久化與串流事件契約的最小聊天閉環。現有工具通常過度耦合外部雲端 Runtime 或多租戶認證系統，導致本機開發啟動成本高昂，且在面對斷線、並發重送、任務中斷時缺乏可靠的資料庫狀態保證與短交易邊界。

## Solution

建立一個「Local-only 單一開發身分」的最小可用聊天平台（Local Chat MVP）：
1. 提供結構化 GraphQL API，用於查詢內建 Seed Bot、分頁檢索歷史對話、分頁查詢歷史訊息、核對 Run 狀態與建立對話。
2. 提供基於 HTTP POST 發起、後續以單向 Server-Sent Events (SSE) 進行伺服器串流的通訊協定，支援客戶端去重防重送、即時文字增量、單一活躍執行約束與原子取消機制。
3. 提供前後端解耦的聊天介面（ChatView），前端採用純 UI 模式對接後端串流與 GraphQL，無需任何第三方雲端 Runtime。
4. 全面採用資料庫短交易與宣告式約束，保證高並發下每個對話在同一時間最多僅存在一個活躍任務，中斷與失敗狀態皆具備權威記錄。

---

## User Stories

1. As a local developer, I want the system to bind my session to a fixed server-managed developer identity on loopback, so that I cannot inadvertently impersonate arbitrary user IDs from client requests.
2. As a local developer, I want the system to fail fast and abort startup on non-local network profiles, so that deployment boundaries remain fail-closed without requiring complex security filter chains.
3. As a user, I want the system to automatically initialize its database schema using versioned SQL initialization scripts with execution tracking, so that schema migration is reliable without requiring external migration starters.
4. As a user, I want the system to provide a seeded default Assistant Bot on first startup, so that I can immediately start a conversation without manually configuring bot definitions.
5. As a user, I want to list available bots via GraphQL with stable sorting and unified pagination limits, so that I can select an assistant without overwhelming network responses.
6. As a user, I want the bot system prompt to be securely managed by the server and excluded from client UI models, so that operational system instructions remain encapsulated.
7. As a user, I want to create a new conversation for a chosen bot via a GraphQL mutation, so that my discussions are organized into separate dialogue sessions.
8. As a user, I want to paginate through my conversation list with a default limit of 20 and a maximum cap of 100 items ordered deterministically, so that large conversation histories load predictably.
9. As a user, I want to fetch conversation messages with uniform pagination limits capped at 100, so that message retrieval remains bounded and reliable.
10. As a client application, I want to initiate an AI generation run by sending an HTTP POST request with a unique client request identifier and message text, so that the server can orchestrate generation over a unidirectional server-sent event stream.
11. As a client application, I want the server to respond with HTTP 200 and Content-Type application/json containing the existing run metadata if a duplicate client request identifier with identical message content is received, so that retries are strictly idempotent without erroneously attempting to replay live SSE streams.
12. As a client application, I want the server to respond with HTTP 409 Conflict if a duplicate client request identifier is received with conflicting message content, so that invalid retries are caught immediately.
13. As a user, I want the system to enforce that each conversation can have at most one active run across the database, so that competing model outputs do not corrupt dialogue history.
14. As a user, I want to receive an immediate rejection error if I trigger a new run while another run is still pending or running in the same conversation, so that I am clearly informed of ongoing activity.
15. As a client application, I want the streaming response to be delivered over unidirectional SSE where every event contains an incrementing integer sequence number starting with run started at sequence 1 and deltas starting at sequence 2, so that I can detect dropped or out-of-order packets.
16. As a user, I want to see token deltas streamed incrementally to the chat interface, so that I experience responsive and low-latency interaction.
17. As a user, I want to see a clear completion event containing the persisted message identifier when the model finishes answering, so that the message state transitions cleanly to finalized.
18. As a user, I want to explicitly cancel an ongoing generation run via an abort button or disconnect, so that unwanted generation is promptly stopped.
19. As a client application, I want the cancellation endpoint to be idempotent and verify user ownership, so that repeating a cancel request succeeds and returns the true terminal status without side effects.
20. As a user, I want generation to transition to a terminal state at most once, so that a finished run can never be retroactively overwritten as cancelled by network tear-down callbacks.
21. As a user, I want partial responses generated prior to interruption to be preserved in history with an interrupted flag set to true, so that I can inspect what was generated before cancellation.
22. As a user, I want subsequent conversation turns to only include successful historical messages in the model prompt window, so that interrupted or failed partial fragments do not pollute subsequent model reasoning.
23. As a user, I want the conversation history injected into the model prompt to be managed via standard session services constrained to a sliding window of recent complete turns, so that token usage remains bounded over lengthy discussions.
24. As a client application, I want to query the authoritative status and metadata of any run via GraphQL by its identifier, so that if a connection drops and misses terminal events, I can verify the final outcome upon reconnection.
25. As a developer, I want unhandled stream timeouts to be marked deterministically as failed with a timeout reason, so that hung runs do not linger indefinitely.
26. As a developer, I want any runs left in an active state when the server restarts to be automatically recovered and finalized as failed using the updated timestamp, so that orphan records do not block new runs indefinitely.
27. As a frontend user, I want to interact with a clean chat interface matching the approved prototype layout, so that I have an intuitive and uncluttered experience.
28. As a frontend user, I want all non-MVP features visible in prototype mocks (such as workspace switching, kanban boards, usage metrics, file repositories, whiteboards, attachments, model dropdowns, and voice input) to be completely excluded, so that the interface contains zero dead or inactive controls.
29. As a tester, I want the test suite to execute against a deterministic mock model in local/test mode, so that tests pass reliably offline without incurring model API costs or random failures.
30. As a tester, I want deterministic error injection capabilities in the mock model, so that failure and cancellation edge cases can be reproduced consistently.
31. As an operator running with the real-ai profile, I want the system to fail fast upon startup if the required API key or chat model is missing, so that misconfigurations are detected before traffic is served.
32. As an operator running with both local and real-ai profiles, I want the system to invoke the real model directly rather than generating mock responses, so that end-to-end functionality can be validated in development.

---

## Implementation Decisions

### 1. Identity & Network Security Architecture
- Local development requests are strictly constrained to loopback network addresses.
- The authenticated identity is resolved exclusively on the server as a fixed developer principal; incoming requests cannot pass or override user identifiers.
- Non-local deployment profiles trigger an immediate fail-fast abort on application startup. Spring Security and OAuth2 filter chains are completely excluded from the MVP.

### 2. State Machine & Database Constraints
- The run lifecycle is modeled as a monotonic state machine with five discrete states: pending, running, completed, failed, and cancelled. Completed, failed, and cancelled are terminal states.
- Transitions to any terminal state are atomic and occur at most once using compare-and-swap semantics; subsequent updates to a terminal run are disregarded.
- A single active run per conversation is strictly enforced at the database tier using a partial unique index over the conversation identifier filtered by active states. Cross-row check constraints are explicitly avoided.
- Client request deduplication is enforced by a composite unique constraint on conversation identifier and client request identifier.
- Server startup executes a mandatory reconciliation routine that transitions any dangling active runs to failed with an orphan restart reason code, recording the change using the updated timestamp.

### 3. Modular Package Architecture (Pre-Modulith)
- The codebase is partitioned into distinct functional modules directly beneath the root package: identity, bot, conversation, execution, and bootstrap.
- Every module exposes only facade interfaces and data transfer objects at its module root. All database entities, repositories, and web controllers are encapsulated within internal subpackages.
- Cross-module access is restricted to root facade interfaces; importing internal subpackages across modules is strictly prohibited.
- Module dependencies flow in a strict directed acyclic graph: execution depends on conversation and bot, conversation depends on bot, and all modules depend on identity.
- Conversation module contains zero references to execution types. In GraphQL, the runs field on conversations is resolved by an execution web controller mapping, avoiding circular dependencies.

### 4. Run Lifecycle & Concurrency Monitor (RunHandle)
- The lifecycle of an active stream is governed by an encapsulated monitor using a single lock.
- Delta buffering, sequence counter increment, and event emission occur under this monitor to eliminate packet reordering races.
- Sequence numbering starts with run started at sequence 1, followed by deltas beginning at sequence 2, and concludes with a terminal event.
- Subscription lifecycle is managed through swap disposables. If a run reaches a terminal state before the reactive subscription is attached, the late subscription is disposed immediately upon arrival.
- Normal stream completion is cleanly distinguished from client disconnection: normal completion sets terminal status and closes the emitter, making subsequent framework completion callbacks a no-op; unexpected network disconnection prior to completion transitions the run to cancelled and preserves partial text with an interrupted flag.

### 5. Short Transactions & Session Memory Integration
- The application integrates standard SessionService capabilities backed by relational session tables, replacing legacy chat memory repositories.
- Short database transactions are strictly enforced. Generation occurs outside of database transactions; no connection is held while streaming model tokens.
- Upon completion, a single short transaction atomically executes the compare-and-swap status transition, persists the assistant message, and appends the user and assistant event pair to SessionService with expected version validation.
- If the compare-and-swap update affects zero rows because another path has already finalized the run (a CAS loser), the transaction does not attempt to mark the run as failed; it simply rolls back and queries the existing authoritative terminal status from the database.
- If the compare-and-swap succeeds but subsequent transaction operations fail (such as session event append returning false or throwing an exception), the entire transaction is rolled back, and a subsequent independent transaction executes a compare-and-swap to transition the run to failed with the failure description.
- Historical context passed to the model prompt is retrieved directly from active session messages, bounded to a sliding window of the last 10 completed turns (20 messages), ensuring interrupted or failed turns are excluded.

### 6. Model Configuration & YAML Resolution
- External model integrations (such as Google GenAI) are configured manually as standalone beans. Automated model starter configurations are explicitly excluded via application configuration to prevent duplicate bean conflicts and unintended initializations.
- Model names are specified directly in configuration files rather than environment variable fallbacks: chat model is configured as gemini-3.8-flash, text-to-speech as gemini-3.8-flash-tts, and audio transcription as gemini-3.5-transcribe.
- Private configurations and API credentials reside in a dedicated configuration file within the backend configuration directory, which is excluded by source control and never read, output, or modified by automation agents.
- In local development mode, a deterministic mock model is active by default, supporting synthetic delays, token chunking, and deterministic error injection when prompted with an error keyword.
- When the real-ai profile is activated, the manual Google configuration is loaded, and absence of credentials triggers a fail-fast startup abort.

### 7. API Contracts & Transports
- The API is partitioned into structured operations (GraphQL) and streaming execution (HTTP POST with unidirectional Server-Sent Events).
- GraphQL Queries:
  - Retrieval of available bots with bounded pagination.
  - Retrieval of conversations with bounded pagination.
  - Retrieval of a single conversation with its associated message history and execution runs, where messages and runs support identical, bounded pagination limits.
  - Retrieval of an individual execution run by its identifier.
  - System prompt text is an internal bot configuration and is excluded from external GraphQL bot types.
- GraphQL Mutations:
  - Creation of a new conversation assigned to a specific bot.
- All GraphQL listing queries enforce a default page limit of 20 and an absolute maximum cap of 100, sorted deterministically by creation timestamp descending followed by identifier descending.
- Streaming Endpoint:
  - Initialized via HTTP POST containing the client request identifier and message text. Client request identifier is limited to 128 characters and message text is limited to 10,000 characters.
  - For a new request, the server responds with HTTP 200 and Content-Type text/event-stream. The stream emits run started, message delta tokens, and a terminal event (run completed, run failed, or run cancelled).
  - For a repeated request with an identical client request identifier and identical message content, the server responds with HTTP 200 and Content-Type application/json containing existing run metadata. Live SSE streams are never replayed.
  - For a repeated request with an identical client request identifier but conflicting message content, the server responds with HTTP 409 Conflict.
  - Concurrent requests with duplicate client request identifiers that encounter database unique constraint violations roll back and re-query to return idempotent metadata or conflict responses.
  - Concurrent requests on the same conversation with different client request identifiers are rejected with HTTP 409 Conflict via database partial unique index enforcement.
- Stream Cancellation Endpoint:
  - An HTTP POST endpoint accepts the run identifier, verifies requesting ownership, and triggers cooperative cancellation. The operation returns HTTP 200 with the authoritative terminal status and is strictly idempotent.

### 8. Frontend Visual, Interaction & Code Annotation Specifications
- Frontend Architecture:
  - Built with Angular using `@copilotkit/angular` in layout-only mode for `ChatView`, without relying on external CopilotKit cloud runtime or backend agent runtime proxy.
- Visual Layout & Controls:
  - Clean white background.
  - Left navigation is a narrow 240px sidebar displaying the Seed Bot indicator and recent conversations list.
  - Top main header displays the active conversation title and a "New Conversation" action button.
  - Central dialogue area displays user messages right-aligned with rounded corners, and assistant text left-aligned.
  - Bottom composer features a full-width input container with a combined send/stop toggle button at the bottom-right.
  - Message bubbles support a copy-to-clipboard action.
- Omitted Non-MVP Controls:
  - Out-of-scope prototype artifacts (such as workspace switchers, kanban views, usage metrics, file attachments, whiteboards, attachments, model selector dropdowns, and microphone controls) are entirely omitted, guaranteeing zero non-functional controls.
- Code Annotation & Architectural Traceability:
  - Whenever special technical constraints or architectural trade-offs are encountered (such as single-lock lifecycle monitors, swap disposables, CAS rollback boundaries, database partial unique indices with collision re-query, or manual bean registrations with auto-configuration exclusions), developers must place inline comments in close proximity to the code explaining the "why" rationale, active constraints, and necessary future release conditions, rather than merely restating step-by-step actions.

---

## Testing Decisions

### What Makes a Good Test
- Tests assert external behavior, HTTP status codes, event sequences, and final database states rather than private internal implementation details.
- Tests exercise boundary conditions (concurrency, deduplication, restart recovery, timeout handling, and cancellation idempotency) deterministically without sleep delays or polling loops.

### Approved Testing Seams
1. **API Integration Seam**:
   - HTTP/SSE and GraphQL endpoints tested against real PostgreSQL (via containerized instances) with the deterministic mock model.
   - Validates short transaction release, partial unique index concurrency enforcement, sequence number ordering, CAS terminal updates, and GraphQL pagination limits.
2. **End-to-End Browser UI Seam**:
   - Validates the Angular chat interface executing against the backend API, testing streaming token rendering, stream abort reaction, and full conversation restoration upon page reload.
3. **Real Model Smoke Testing Seam**:
   - Isolated verification suite executing exclusively when the real-ai profile is activated with valid vendor credentials, verifying live network round-trips and streaming payload compatibility.

---

## Out of Scope

- Multi-tenant authentication, user registration, Spring Security, and OAuth2 authorization servers.
- Liquibase migration starter.
- Model Context Protocol (MCP) clients and tool execution.
- Spring Modulith starter core and architecture boundary verifiers (prepared via package boundaries for post-MVP adoption).
- Retrieval-Augmented Generation (RAG), document ingestion, embedding generation, and pgvector semantic search.
- Audio and speech capabilities (batch audio transcription with gemini-3.5-transcribe and real-time live transcription with gemini-3.5-transcribe-live are documented as future adapter extensions).
- Workflow orchestration engines, scheduling, and multi-agent coordination.
- GraalVM Native Image compilation and enterprise OpenTelemetry collectors.
- Inactive UI prototype controls (workspace switching, kanban, usage metrics, file/whiteboard attachments, model selection dropdowns, voice input).

---

## Further Notes

- Environment Baseline: Java 25 (toolchain 25), Gradle 9.8.2, Spring Boot 4.1.2, Spring AI 2.1.0-RC2.
- Environment Gate G0 backend compilation has successfully passed. Gate A modular package boundaries have been verified with clean compilation. Gate B integration testing is currently in draft ticket sequencing prior to execution.
