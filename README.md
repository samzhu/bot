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

### Backend Test Suites
```bash
cd backend

# 1. Full offline unit & integration test suite (31 tests, strictly mock/isolated, excludes live calls)
./gradlew test

# 2. Dedicated fail-fast configuration tests (missing API key, missing model, zero network)
./gradlew test --tests RealAiFailFastTests

# 3. Dedicated opt-in real model live smoke test (requires Docker + backend/config/application-real-ai.yaml)
# Outputs only safe model metadata (gemini-3.8-flash); outputs.upToDateWhen { false } prevents cached skips.
./gradlew realAiSmokeTest
```

### Frontend Test Suites
```bash
cd frontend

# 1. Unified test command (runs Vitest unit tests + Node SSE parser tests)
npm test

# 2. Vitest unit tests alone (3 tests)
npm run test:unit

# 3. SSE Stream Parser test suite alone (13 tests)
npm run test:parser

# 4. Production bundle build (actual bundle: 4.87 MB < 5.5 MB budget; component CSS: 7.07 kB < 8 kB budget)
npm run build
```

### End-to-End Browser Automation
```bash
cd frontend

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

- **In Scope**: Loopback developer identity, PostgreSQL JDBC schema migrations with version tracking, GraphQL conversation/message/run queries, unidirectional SSE streaming (`message.delta`, `run.completed`, `run.cancelled`, `run.failed`), single active run per conversation constraint, multi-turn sliding window history (10 turns), optimistic UI updates with conflict/retry recovery.
- **Out of Scope (MVP)**: Multi-tenant JWT/OAuth2 authentication, speech-to-text (transcription), text-to-speech (TTS), embedding vector search / RAG, live web tools, GraalVM native image compilation.
