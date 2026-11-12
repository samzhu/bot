# Herdr Bot (Local Chat MVP)

A minimal, deterministic, and resilient loopback AI chat platform built with Java 25, Spring Boot 4.1.2, Spring AI 2.1.0-RC2, Angular 22, and ThreadPlane 0.3.7. Chat uses the ThreadPlane AG-UI adapter; the backend remains Spring Boot / Spring AI.

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

### Start Both Servers

With Java 25, Node 26, Docker running, and frontend dependencies installed (`cd frontend && TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts`), run from the repository root:

```bash
# From the repository root: choose 1 (Mock AI) or 2 (Real AI)
./dev.sh

# Skip the menu: local mock model, no API key required
./dev.sh --mock

# Use the existing backend/config/application-real-ai.yaml through Spring Boot
./dev.sh --real-ai
```

Open `http://localhost:4200`. Output stays in the terminal; **Ctrl+C stops both servers**.
The script checks ports 8080/4200 and waits for the backend before starting Angular.
Spring Boot manages Compose services; containers already running before startup remain running on exit.
No database data is deleted. Use `./dev.sh --help` for usage.

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
TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts
npm start
```

Access the UI at `http://localhost:4200`.

---

## 4. Verification & Testing

### Specifications & Sub-Tickets
- [ThreadPlane migration spec #23](https://github.com/samzhu/bot/issues/23), with delivery tickets [#25](https://github.com/samzhu/bot/issues/25), [#26](https://github.com/samzhu/bot/issues/26), [#27](https://github.com/samzhu/bot/issues/27), [#28](https://github.com/samzhu/bot/issues/28), and [#29](https://github.com/samzhu/bot/issues/29).
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
The retained SSE parser tests cover the historical transport utility. Normal chat uses the standard AG-UI client and ThreadPlane adapter.

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

Start both servers with ./dev.sh --mock, then run the current browser suites:

    cd frontend
    export BROWSER_URL=http://127.0.0.1:4200
    export BACKEND_URL=http://127.0.0.1:8080
    node e2e/threadplane-chat.mjs
    node e2e/threadplane-navigation.mjs
    node e2e/threadplane-bot.mjs

The default verification URLs are 4300 / 8181 for an isolated test instance.
CHROME_PATH selects Chrome; the default is the macOS Chrome installation.
Both the backend and the frontend proxy must return all three SAFE_MOCK fields.
Generation requests are guarded individually; real-ai is not an accepted test backend.

The suites retain synthetic fixtures, write results / screenshots / console / network
evidence to temporary directories, and exit unsuccessfully if any check fails.
They cover chat and authoritative Run / Message state, native navigation and search,
and Bot management. Stream failures, selected GraphQL failures, OFF fixtures and IME
composition events are explicitly injected; these are not physical network outages
or native operating-system IME tests. Browser assertions use public UI and APIs.
Backend integration tests additionally verify PostgreSQL and Session consistency.

Historical verify-ticket-*, verify-seed, verify-draft, verify-native-ui,
verify-clipboard, verify-pinning* and issue16/18/22 layout scripts are retired
and fail immediately. Their preserved assertions document the old layout and do
not count as current validation. See [frontend verification details](frontend/README.md).

---

## 5. Architectural Boundaries & MVP Limitations

### UI and Protocol

ThreadPlane supplies the native Sidenav, thread list, search palette, chat window,
messages, Markdown, input, typing indicator, error UI and applicable management
controls. Controls without a matching published component use standard Angular /
HTML. CopilotKit is removed; LangGraph is not used.

Normal chat posts to /api/ag-ui through @threadplane/ag-ui and @ag-ui/client.
Bot management, history, pinning and authoritative status checks use GraphQL.
Each Conversation owns its Run and draft state independently of the selected view.
New Chat creates no persisted Conversation until the first valid submission.

### Dependency Verification

As verified on 2026-11-12, ThreadPlane packages are pinned to 0.3.7, AG-UI core /
client to 1.0.4 and Zod to 4.6.6. Optional KaTeX is omitted; the previous scoped
override is removed. The resolved tree contains no CopilotKit packages, and the
verification npm audit reported zero vulnerabilities. Recheck the actual lockfile
and audit when dependencies change. Runtime telemetry is disabled; installs used
TPLANE_TELEMETRY_DISABLED=1 and --ignore-scripts to disable the installation collector.

### Execution and Recovery Boundary

The MVP coordinates Run startup and cancellation within one application process.
The lifecycle stripes and terminal claims do not provide cross-node coordination.

If terminal persistence, fallback and status lookup all fail, the result remains
unknown. Local subscriptions and resources are released without claiming a
persisted FAILED state. A remaining RUNNING row blocks another Run for that
Conversation. After database recovery, explicitly cancel the original Run ID or
restart to invoke existing orphan recovery; database recovery alone does not
perform continuous reconciliation.

The MVP retains loopback developer identity, PostgreSQL migrations, one active Run
per Conversation, bounded Session context, local Spring AI Bot simulation and
versioned frontend drafts. GCP / GKE agents remain unconnected. OAuth2, audio,
RAG, live tools, generated A2UI surfaces, native images, cross-node execution and
continuing generation after browser closure remain outside this delivery.
