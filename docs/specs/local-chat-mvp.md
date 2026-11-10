# Local Chat MVP 規格說明書 (Specification)

> 狀態：待驗收規格，2026-11-10。  
> 依據 `to-spec` 模板生成。  
> 決策邊界：本規格完全不包含任何程式碼片段、GraphQL Schema 語法塊、JSON 酬載或 SQL 宣告。僅以純語意精確定義外部可觀察行為、介面契約、資料庫約束與系統狀態機。  
> 最新架構邊界：MVP 聚焦最小可用閉環，排除 Spring Security / OAuth2、Liquibase、MCP 與 Spring Modulith。以應用層 Loopback 檢查固定身分、編號 SQL 初始化腳本與 Spring Data JDBC 完成純淨閉環。

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
10. As a client application, I want to initiate an AI generation run by sending an HTTP POST request with a unique `clientRequestId` and prompt text, so that the server can orchestrate generation over a unidirectional server-sent event stream.
11. As a client application, I want the server to respond with HTTP 200 and Content-Type application/json containing the existing run metadata if a duplicate `clientRequestId` with identical message content is received, so that retries are strictly idempotent without erroneously attempting to replay live SSE streams.
12. As a client application, I want the server to respond with HTTP 409 Conflict if a duplicate `clientRequestId` is received with conflicting message content, so that invalid retries are caught immediately.
13. As a user, I want the system to enforce that each conversation can have at most one active run (in either pending or running status) across the database, so that competing model outputs do not corrupt dialogue history.
14. As a user, I want to receive an immediate rejection error if I trigger a new run while another run is still pending or running in the same conversation, so that I am clearly informed of ongoing activity.
15. As a client application, I want the streaming response to be delivered over unidirectional SSE where every event contains an incrementing integer sequence number and authoritative run identifier, so that I can detect dropped or out-of-order packets.
16. As a user, I want to see token deltas streamed incrementally to the chat interface, so that I experience responsive and low-latency interaction.
17. As a user, I want to see a clear completion event when the model finishes answering, so that the message state transitions cleanly to finalized.
18. As a user, I want to explicitly cancel an ongoing generation run via an abort button or disconnect, so that unwanted generation is promptly stopped.
19. As a client application, I want the cancellation endpoint to be idempotent, so that repeating a cancel request succeeds without side effects.
20. As a user, I want generation to transition to a terminal state (completed, failed, or cancelled) at most once, so that a finished run can never be retroactively overwritten as cancelled by network tear-down callbacks.
21. As a user, I want partial responses generated prior to interruption to be preserved in history with an interrupted flag set to true, so that I can inspect what was generated before cancellation.
22. As a user, I want subsequent conversation turns to only include successful historical messages in the model prompt window, so that interrupted or failed partial fragments do not pollute subsequent model reasoning.
23. As a user, I want the conversation history injected into the model prompt to be constrained by a configurable sliding window, so that token usage remains bounded over lengthy discussions.
24. As a client application, I want to query the authoritative status and metadata of any run via GraphQL by its identifier, so that if a connection drops and misses terminal events, I can verify the final outcome upon reconnection.
25. As a developer, I want unhandled stream timeouts to be marked deterministically as failed with a timeout reason, so that hung runs do not linger indefinitely.
26. As a developer, I want any runs left in a pending or running state when the server restarts to be automatically recovered and finalized as failed, so that orphan records do not block new runs indefinitely.
27. As a frontend user, I want to interact with a clean chat interface matching the approved prototype layout (white background, narrow 240px sidebar, top header with title and new conversation button, right-aligned user bubbles, left-aligned assistant text, bottom input box with send/stop states, and text copy actions), so that I have an intuitive and uncluttered experience.
28. As a frontend user, I want all non-MVP features visible in prototype mocks (such as workspace switching, kanban boards, usage metrics, file repositories, whiteboards, attachments, model dropdowns, and voice input) to be completely excluded, so that the interface contains zero dead or inactive controls.
29. As a tester, I want the test suite to execute against a deterministic mock model in local/test mode, so that tests pass reliably offline without incurring model API costs or random failures.
30. As a tester, I want deterministic error injection capabilities in the mock model, so that failure and cancellation edge cases can be reproduced consistently.
31. As an operator running with the real-ai profile, I want the system to fail fast upon startup if the required API key is missing, so that misconfigurations are detected before traffic is served.
32. As an operator running with both local and real-ai profiles, I want the system to invoke the real model directly rather than generating mock responses, so that end-to-end functionality can be validated in development.

---

## Implementation Decisions

### 1. Identity & Network Security Architecture
- Local development requests are strictly constrained to loopback network addresses (127.0.0.1 / ::1).
- The authenticated identity is resolved exclusively on the server as a fixed developer principal `dev-user`; incoming requests cannot pass or override user identifiers.
- Non-local deployment profiles trigger an immediate fail-fast abort on application startup. Spring Security and OAuth2 filter chains are completely excluded from the MVP.

### 2. State Machine & Database Constraints
- The run lifecycle is modeled as a monotonic state machine with five discrete states: pending, running, completed, failed, and cancelled. Completed, failed, and cancelled are terminal states.
- Transitions to any terminal state are atomic and occur at most once using compare-and-swap semantics; subsequent updates to a terminal run are disregarded.
- A single active run per conversation is strictly enforced at the database tier using a partial unique index over the conversation identifier filtered by active states (pending and running). Cross-row check constraints are explicitly avoided.
- Client request deduplication is enforced by a composite unique constraint on conversation identifier and client request identifier.
- Server startup executes a mandatory reconciliation routine that transitions any dangling pending or running runs to failed with an orphan restart reason code.

### 3. Database Schema Lifecycle
- Database tables and initial seed data are managed via numbered SQL initialization scripts tracked by an internal schema execution table.
- Initialization executes idempotently, supporting repeated application launches and fresh PostgreSQL instances without Liquibase.

### 4. API & Communication Protocols
- The API is partitioned into structured operations (GraphQL) and streaming execution (HTTP POST with unidirectional Server-Sent Events).
- GraphQL Queries:
  - Retrieval of available bots with deterministic ordering and pagination limits.
  - Retrieval of conversations with deterministic ordering and pagination parameters.
  - Retrieval of a single conversation with its associated message history and execution runs, where messages and runs support identical, bounded pagination limits.
  - Retrieval of an individual execution run by its identifier.
  - System prompt text is an internal bot configuration and is excluded from external GraphQL bot types.
- GraphQL Mutations:
  - Creation of a new conversation assigned to a specific bot.
- All GraphQL listing queries enforce a default page limit of 20 and an absolute maximum cap of 100, sorted deterministically by creation timestamp descending followed by identifier descending.
- Streaming Endpoint:
  - Initialized via HTTP POST containing the client request identifier and prompt text.
  - For a new request, the server responds with HTTP 200 and Content-Type `text/event-stream`. The stream emits event-driven frames: run started, message delta tokens, run completed, run failed, and run cancelled. Every event frame contains a monotonically increasing integer sequence number and the run identifier.
  - For a repeated request with an identical client request identifier and identical message content, the server responds with HTTP 200 and Content-Type `application/json` containing the existing run metadata. Live SSE streams are never replayed.
  - For a repeated request with an identical client request identifier but conflicting message content, the server responds with HTTP 409 Conflict.
- Stream Cancellation Endpoint:
  - An HTTP POST endpoint accepts the run identifier to trigger immediate cooperative interruption. The operation is strictly idempotent.
- Client Transport Implementation:
  - The frontend streaming reader uses native `fetch` coupled with readable byte stream decoding and an abort controller, safely buffering chunk boundaries to handle multi-byte UTF-8 split characters and Server-Sent Event frame boundaries. Native browser `EventSource` is not used because it cannot dispatch HTTP POST requests.

### 5. Persistence & Context Management
- Short-lived database transactions are enforced. The database connection is acquired to persist the initial user message and pending run, immediately committed, and released prior to invoking the AI model or streaming chunks.
- During SSE streaming, no database connections or transaction contexts are held open.
- When the stream concludes or is aborted, a separate short transaction is opened to record the generated assistant response and update the run to its terminal state.
- If generation is cancelled or fails, the partial assistant response is saved with an interrupted attribute set to true.
- Model prompt context assembly retrieves only successfully completed, uninterrupted messages belonging to the conversation, sliced to a bounded sliding window.

### 6. Model Architecture & Manual Configuration
- The application relies on abstract model interfaces for chat and streaming chat capabilities. Business and domain components interact solely with standard abstractions and fluent client wrappers, without referencing vendor-specific classes.
- Model providers (such as Google GenAI) are integrated via standalone configuration classes using core client artifacts rather than automated provider starters, ensuring explicit bean lifecycle management and clean separation.
- In local development mode, a deterministic mock model implementation is active by default, supporting synthetic latency and deterministic error injection.
- When both local and real-ai profiles are active, the system configures the genuine external model provider and completely disables the mock model.
- If the real-ai profile is active but the vendor API credentials are absent, the application triggers a fail-fast startup abort.

### 7. Frontend Visual & Interaction Specification
- Layout adheres to the approved visual prototype using standard styling without external heavy UI component libraries:
  - Background is clean white.
  - Left navigation is a narrow sidebar (approximately 240px width) containing the workspace header block at the top, followed by the active Seed Bot indicator and recent conversations list.
  - Top main header displays the active conversation title and a "New Conversation" action button.
  - Central dialogue area displays user messages right-aligned with rounded corners and subtle background fill, while assistant responses are left-aligned in standard body typography.
  - Bottom composer features a full-width input container with a combined send/stop toggle button at the bottom-right.
  - Individual message bubbles support a copy-to-clipboard action.
- Scope boundary for MVP UI:
  - Only functional features are rendered (Seed Bot, recent conversations, new conversation, title, message bubbles, text input, stream stopping, text copying).
  - Out-of-scope prototype artifacts (such as workspace switchers, kanban views, usage gauges, file managers, whiteboards, attachments, model selector dropdowns, and microphone controls) are entirely omitted, guaranteeing that zero inactive or non-functional controls appear.

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
- Spring Modulith starter core and architecture boundary verifiers.
- Retrieval-Augmented Generation (RAG), document ingestion, embedding generation, and pgvector semantic search.
- Workflow orchestration engines, scheduling, and multi-agent coordination.
- GraalVM Native Image compilation and enterprise OpenTelemetry collectors.
- Inactive UI prototype controls (workspace switching, kanban, usage metrics, file/whiteboard attachments, model selection dropdowns, voice input).

---

## Further Notes

- Current environment gate G0 remains **BLOCKED** pending JDK 27 toolchain provisioning decisions and frontend dependency lockfile resolution. Implementation will proceed strictly in accordance with Gate prerequisites.
