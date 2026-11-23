# Herdr Bot (Local Chat MVP)

A minimal, deterministic, and resilient loopback AI chat platform built with Java 25, Spring Boot 4.2.0, Spring AI 2.1.0, Angular 22, and ThreadPlane 0.3.7. Chat uses the ThreadPlane AG-UI adapter; the backend remains Spring Boot / Spring AI.

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
| **`local`** | Local infrastructure and loopback connection | No model selected | None |
| **`local,mock-ai`** | Local development and demo data | `MockChatModel` (deterministic streaming) | None |
| **`test`** | Automated testing suite | `MockChatModel` / `ControlledChatModel` | None |
| **`local,dev` + `bot-ai-provider=gemini`** | Explicit live cloud model inference | `GoogleGenAiChatModel` (`gemini-3.8-flash`) | Yes (`backend/config/application-secrets.properties`) |

### Fail-Fast Guard Rails
- **Profile Boundary**: Starting without either `local` or `test` profile immediately triggers `ProfileGuard` and aborts startup with an `IllegalStateException`. The `prod` configuration alone does not authorize deployment; production still requires an appropriate authorization strategy. Gemini requires `local` and cannot run with `mock-ai`, `test`, or `prod`.
- **Real AI Missing Key / Model**: Selecting `bot-ai-provider=gemini` without a non-blank `bot-google-api-key` or `spring.ai.google.genai.chat.model` fails fast at startup with clear diagnostics.
- **Secrets Protection**: `backend/config/application-secrets.properties` and profile-specific variants are ignored by Git. The legacy `application-real-ai.yaml` ignore rule remains in place.

---

## 3. Getting Started

### Local Gemini key

Only real AI mode needs a Gemini API key. From the repository root, create a private copy of the empty template without overwriting an existing file:

```bash
(umask 077; set -C; cat backend/config/application-secrets.properties.example > backend/config/application-secrets.properties)
```

Privately set `bot-google-api-key` in that file and keep its permissions at `0600`; do not print the key or put it in command history.

The `--gemini` command activates `local,dev`; the external `backend/config/application-dev.yaml` conditionally imports the optional secrets file. `local,dev` can load the key without selecting Gemini; a key never activates a paid provider. `dev` alone, `local` alone, and combinations containing `mock-ai`, `test` or `prod` do not import it. Missing or blank keys fail fast for Gemini. Spring resolves the file relative to the backend process working directory: `dev.sh` sets this directory; when running Gradle manually, first `cd backend`. Existing Compose database settings are unchanged.

Model names stay in YAML. Gemini chat uses `gemini-3.8-flash` from the shared configuration. Local configuration preserves `gemini-3.8-flash-tts` for speech, `gemini-3.5-transcribe` for transcription, and `gpt-5-mini` for OpenAI chat. These three settings are reserved for their integrations. The current app starts the Gemini chat and title clients, so leave `bot-openai-api-key` blank unless you need it for an OpenAI integration.

The startup command selects profiles and explicitly selects Gemini. Spring Boot loads the external `application-dev.yaml` from the backend working directory; imported secrets override the declaring YAML document, while OS environment variables, Java system properties and command-line properties have higher precedence. The project runs Spring Boot 4.2.0. [Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html) · [Externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)

### Start Both Servers

With Java 25, Node 26, Docker running, and frontend dependencies installed (`cd frontend && TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts`), run from the repository root:

```bash
# From the repository root: choose 1 (Mock AI) or 2 (Real AI)
./dev.sh

# Skip the menu: local mock model, no API key required
./dev.sh --mock

# Use the local Gemini key configured above
./dev.sh --gemini
```

Open `http://localhost:4200`. Output stays in the terminal; **Ctrl+C stops both servers**.
The script checks ports 8080/4200 and waits for the backend before starting Angular.
Spring Boot manages Compose services; containers already running before startup remain running on exit.
No database data is deleted. Use `./dev.sh --help` for usage.

### Step 1: Start Backend

Spring Boot automatically boots and configures the PostgreSQL container defined in `backend/compose.yaml`.

```bash
# Terminal 1: Start backend in deterministic mock mode
cd backend
./gradlew bootRun --args="--spring.profiles.active=local,mock-ai"
```

To run with real Google GenAI models:
```bash
# Terminal 1: Start backend with explicitly selected Gemini and local dev configuration
cd backend
./gradlew bootRun --args="--spring.profiles.active=local,dev --bot-ai-provider=gemini"
```

### Step 2: Start Frontend

```bash
# Terminal 2: Initial installation and start Angular dev server
cd frontend
TPLANE_TELEMETRY_DISABLED=1 npm ci --ignore-scripts
npm start
```

Access the UI at `http://localhost:4200`.
See the [frontend development guide](frontend/DEVELOPMENT.md) for module
boundaries, app shell layout conventions, and verification requirements.

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
- **Idempotence**: When booting with `local,mock-ai`, `LocalDataSeedInitializer` automatically populates three demonstration scenarios (`COMPLETED`, `FAILED`, `CANCELLED`) with fixed IDs. Subsequent restarts detect existing data and skip initialization without modifying or duplicating records. Real AI does not import demonstration data; existing records are not deleted. The default assistant is initialized independently after schema migration.

### Backend Test Suites
```bash
cd backend

# 1. Full offline unit & integration test suite (isolated PostgreSQL & controlled model)
./gradlew test

# 2. Dedicated fail-fast configuration tests (missing API key, missing model, zero network)
./gradlew test --tests RealAiFailFastTests
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

# 4. Lint, layout behavior tests, and production build
npm run build
```

After building, run `npm run test:layout:browser` in `frontend/` to verify the
rendered app shell in Chrome with isolated fixtures. This check needs no
running servers. See [frontend browser verification](frontend/README.md#browser-verification).

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
Generation requests are guarded individually; Gemini is not an accepted test backend.

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

### 前端套件 ThreadPlane

ThreadPlane 提供 Angular 聊天元件與 AG-UI 轉接層。下表版本於 2026-11-20 依本專案的
[前端鎖檔](frontend/package-lock.json)與已安裝套件核對。套件介面可查閱 [ThreadPlane Angular 指南](https://threadplane.ai/docs/angular/chat/getting-started/introduction)與 [API 參考](https://threadplane.ai/docs/api)。

| 套件 | 鎖定版本 | 在本專案的用途 |
| --- | --- | --- |
| `@threadplane/chat` | `0.3.7` | 提供聊天視窗、訊息、輸入框、串流 Markdown、回覆中與錯誤提示、對話列表、搜尋面板、手機側欄遮罩、Bot 管理的下拉選單與基礎樣式。 |
| `@threadplane/ag-ui` | `0.3.7` | `toAgent()` 將 AG-UI 串流事件轉成聊天元件使用的 `Agent` 狀態。 |
| `@threadplane/a2ui` | `0.3.7` | 聊天 SDK 的同儕依賴，提供 A2UI 協定型別與解析工具。 |
| `@threadplane/render` | `0.3.7` | 聊天 SDK 的同儕依賴，可將結構化 UI 規格渲染成 Angular 元件。此 MVP 的助理回覆採串流 Markdown。 |
| `@threadplane/telemetry` | `0.3.7` | SDK 的遙測同儕依賴。聊天轉接層明確設定 `telemetry: false`。 |

應用程式自行管理整體版面、Workspace 切換、草稿、Run 的送出與取消。一般聊天透過
`@ag-ui/client@1.0.4` 的 `HttpAgent` 向 Spring Boot 的 `POST /api/ag-ui` 送出請求並接收 SSE，
再由 `@threadplane/ag-ui` 將事件交給 Angular 元件。使用者權限、提示詞、模型執行、預算與持久化由後端負責。
Bot 管理、歷史、釘選及 Run 狀態查詢走 GraphQL。每個 Conversation 分別管理 Run 和草稿，切換畫面可保留這些狀態。
新對話在首次有效送出時才會寫入資料庫。參閱 [ThreadPlane `toAgent()`](https://threadplane.ai/docs/api/ag-ui/toAgent)與 [AG-UI `HttpAgent`](https://docs.ag-ui.com/sdk/js/client/http-agent)。

### Dependency Verification

As verified on 2026-11-12, AG-UI core and client were pinned to 1.0.4 and Zod to
4.6.6. Optional KaTeX is omitted; the previous scoped
override is removed. The resolved tree contains no CopilotKit packages, and the
verification npm audit reported zero vulnerabilities. Recheck the actual lockfile
and audit when dependencies change. The documented install command uses
`TPLANE_TELEMETRY_DISABLED=1` and `--ignore-scripts` to disable the installation collector.
See [ThreadPlane telemetry and opt-out](https://threadplane.ai/privacy).

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
