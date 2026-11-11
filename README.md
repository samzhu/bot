# Herdr Bot (Local Chat MVP)

A minimal, deterministic, and resilient loopback AI chat platform built with Java 25, Spring Boot 4.1.2, Spring AI 2.1.0-RC2, and Angular 22.

---

## 1. Prerequisites & Environment

- **Java Development Kit (JDK)**: Java 25 (Java Language Version 25)
- **Node.js**: `^26.0.0`, with `npm`
- **Docker & Docker Compose**: Docker daemon running (used by Spring Boot Docker Compose for PostgreSQL 16 + pgvector)
- **Google Chrome**: (Optional for running headless browser E2E verification suites)

---

## 2. Profiles & Configurations

The application adopts a fail-closed, loopback-only security architecture enforced by `ProfileGuard`.
An active profile (`local` or `test`) must be explicitly supplied; the application refuses to start if no permitted profile is specified.

| Profile | Purpose | AI Model Provider | Credentials Required |
| :--- | :--- | :--- | :--- |
| **`local`** (Explicit) | Local developer environment | `MockChatModel` (deterministic streaming) | None (zero credentials needed) |
| **`test`** | Automated testing suite | `MockChatModel` / `ControlledChatModel` | None |
| **`local,real-ai`** | Live cloud model inference | `GoogleGenAiChatModel` (`gemini-3.8-flash`) | Yes (`backend/config/application-real-ai.yaml`) |

### Fail-Fast Guard Rails
- **Profile Boundary**: Starting without either `local` or `test` profile immediately triggers `ProfileGuard` and aborts startup with an `IllegalStateException`.
- **Real AI Missing Key / Model**: Activating `real-ai` without a non-blank `spring.ai.google.genai.api-key` or `spring.ai.google.genai.chat.model` fails fast at startup with clear diagnostics.
- **Secrets Protection**: `backend/config/application-real-ai.yaml` is strictly ignored by version control (`.gitignore`).

---

## 3. Getting Started

### Step 1: Start Backend

Spring Boot automatically boots and configures the PostgreSQL container defined in `backend/compose.yaml`.

```bash
# Terminal 1: Start backend in deterministic mock mode (requires explicit local profile)
cd backend
./gradlew bootRun --args="--spring.profiles.active=local"
```

To run with real Google GenAI models:
```bash
# Terminal 1: Start backend in real AI mode (reads backend/config/application-real-ai.yaml)
cd backend
./gradlew bootRun --args="--spring.profiles.active=local,real-ai"
```

### Step 2: Start Frontend

```bash
# Terminal 2: Initial installation and start Angular dev server
cd frontend
npm ci
npm start
```

Access the UI at `http://localhost:4200`.

---

## 4. Verification & Testing

### Specifications & Sub-Tickets
- [GitHub Spec #6: Spec: MVP 資料模型重構與 Local 示範載入](https://github.com/samzhu/bot/issues/6)
- [GitHub Sub-issue #7 (T1): 乾淨重建後可正常聊天](https://github.com/samzhu/bot/issues/7)
- [GitHub Sub-issue #8 (T2): local啟動即可查看完整示範](https://github.com/samzhu/bot/issues/8) (Blocked by #7)

### Local Demonstration Seed & Artifact Isolation
- **SourceSet Isolation**: Demonstration loaders and local fixtures reside in the dedicated `localDevelopment` sourceSet (`src/local/java`), included only in `bootRun` and `test` classpaths.
- **Production Artifacts**: Neither production `bootJar` nor container images contain demo SQL, seed classes, or private configurations.
- **Idempotence**: When booting under the `local` profile, `LocalDataSeedInitializer` automatically populates three demonstration scenarios (`COMPLETED`, `FAILED`, `CANCELLED`) with fixed IDs. Subsequent restarts detect existing data and skip initialization without modifying or duplicating records.

### Backend Test Suites
```bash
cd backend

# 1. Full offline unit & integration test suite (isolated PostgreSQL & controlled model)
./gradlew test

# 2. Dedicated fail-fast configuration tests (missing API key, missing model, zero network)
./gradlew test --tests RealAiFailFastTests

# 3. Dedicated opt-in real model live smoke test (requires Docker + backend/config/application-real-ai.yaml)
./gradlew realAiSmokeTest
```

### Frontend Test Suites
```bash
cd frontend

# 1. Unified test command (runs Vitest unit tests + Node SSE parser tests)
npm test

# 2. Vitest unit tests alone
npm run test:unit

# 3. SSE Stream Parser test suite alone
npm run test:parser

# 4. Production bundle build (verified within angular.json budgets)
npm run build
```

### End-to-End Browser Automation
> **Note**: All browser verification suites run against the local deterministic mock backend (`./gradlew bootRun --args="--spring.profiles.active=local"`), NOT `real-ai`, to test deterministic SSE streaming, artificial faults (`[PARTIAL_ERROR]`), delays (`[DELAY]`), and cooperative cancellations without consuming external model quota.

```bash
cd frontend

# Seed Verification: 3 seed conversations, banners, eager New Chat baseline+1, and reload persistence
node verify-seed.mjs

# Ticket 01: Minimal loop (new chat, streaming deltas, reload persistence)
node verify-ticket-01.mjs

# Ticket 02: Multi-turn history, stop generation, IME guard, Shift+Enter, pagination
node verify-ticket-02.mjs

# Ticket 03: 6 connection & retry anomaly recovery scenarios
node verify-ticket-03.mjs

# Clipboard: Real browser copy button round-trip verification
node verify-clipboard.mjs
```

---

## 5. Architectural Boundaries & MVP Limitations

### Dependency Overrides (KaTeX Security Mitigation)
- **WHY**: `@copilotkit/angular@0.6.0` specifies `katex: "^0.16.22"`, which prevents standard semver resolution from installing the patched KaTeX version addressing [GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7). A narrow, scoped override (`@copilotkit/angular` -> `katex: 0.18.2`) forces the minimum patched version for this advisory. Public API compatibility (`renderToString`) and chat bundle behavior have been strictly verified.
- **Removal Condition**: Remove this scoped override once upstream `@copilotkit/angular` natively updates its KaTeX dependency range to `>=0.18.2`.

### Residual Security Advisories (Zod 3 Compatibility)
- **Status (as of 2026-11-11)**: To preserve runtime compatibility with `@copilotkit/angular` and schema validation pipelines, Zod 3 compatibility is retained. The remaining 10 high-severity `npm audit` entries originate from a single upstream advisory, [GHSA-mrhx-c7xc-m3w5](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5), cascading through 9 dependent `@copilotkit/*` and `@ag-ui/*` packages.
- **Upstream Tracking**: Zod maintainers have frozen v3 with no backports planned ([colinhacks/zod#6653](https://github.com/colinhacks/zod/issues/6653)), and CopilotKit tracks official Zod 4 migration in [CopilotKit#7967](https://github.com/CopilotKit/CopilotKit/issues/7967). This dependency restriction will be resolved once upstream natively supports `zod >= 4.6.6`. Zero risk is not claimed.

- **In Scope**: Loopback developer identity, PostgreSQL JDBC schema migrations with version tracking, GraphQL conversation/message/run queries, unidirectional SSE streaming (`message.delta`, `run.completed`, `run.cancelled`, `run.failed`), single active run per conversation constraint, multi-turn sliding window history (10 turns), optimistic UI updates with conflict/retry recovery.
- **Out of Scope (MVP)**: Multi-tenant JWT/OAuth2 authentication, speech-to-text (transcription), text-to-speech (TTS), embedding vector search / RAG, live web tools, GraalVM native image compilation.
