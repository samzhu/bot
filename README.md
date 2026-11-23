# Herdr Bot (Local Chat MVP)

A minimal, deterministic, and resilient loopback AI chat platform built with Java 25, Spring Boot 4.2.0, Spring AI 2.1.0, Angular 22, and ThreadPlane 0.3.7. Chat uses the ThreadPlane AG-UI adapter; the backend remains Spring Boot / Spring AI.

---

## 1. Prerequisites & Environment

- **Java Development Kit (JDK)**: Java 25 (Java Language Version 25)
- **Node.js**: `^26.0.0`, with `npm`
- **Docker & Docker Compose**: Docker daemon running (used by Spring Boot Docker Compose for PostgreSQL 16 + pgvector)
- **Google Chrome**: (Optional for running headless browser E2E verification suites)

---

## 2. Development and test configuration

Development uses `dev` and an explicit Spring AI selector. `dev` activates local infrastructure; missing, invalid or conflicting selections fail closed. `spring.ai.model.chat=google-genai` requires the configured Gemini key; `spring.ai.model.chat=ollama` uses the local service. A key alone does not select a provider. The application remains loopback-only; production requires an authorization strategy not provided by this MVP.

Automated tests provide test-owned Fake models and use isolated Testcontainers where infrastructure is needed. They never require real model credentials or invoke a paid provider. The default Bot is application data and remains initialized independently of test fixtures.

---

## 3. Getting Started

### Local Gemini key

Only explicitly selected Gemini needs a key. From the repository root, create a private copy without overwriting an existing file:

```bash
(umask 077; set -C; cat backend/config/application-secrets.properties.example > backend/config/application-secrets.properties)
```

Privately set `bot-google-api-key` and keep permissions at `0600`; do not print the key or put it in command history. From `backend`, Spring Boot loads `config/application-dev.yaml`, whose guarded optional import reads that file. Missing or blank keys fail at startup for Gemini. OS environment variables, Java system properties and command-line properties take precedence over config data. Do not commit secrets.

### Backend Development: Gemini

```bash
cd backend
./gradlew bootRun --args="--spring.profiles.active=dev --spring.ai.model.chat=google-genai"
```

Spring Boot starts or connects the Compose database and Grafana. With `dev`, Boot reconciles selected services and leaves them running when the app exits. A provider switch does not stop an already running Ollama service. From `backend`, `docker compose --profile ollama stop ollama` stops only Ollama; `docker compose --profile ollama stop` also stops the unprofiled database and Grafana. Both preserve volumes.

### Local Ollama

Choose local Ollama with standard Spring settings:

```bash
cd backend
./gradlew bootRun --args="--spring.profiles.active=dev --spring.ai.model.chat=ollama"
```

Spring Boot starts or connects the Compose database and Ollama service. Ollama listens only on `127.0.0.1:11434`; the named `ollama-models` volume persists downloaded models. The Compose service disables Ollama cloud features with `OLLAMA_NO_CLOUD=1`; if you connect an independently managed loopback Ollama daemon, configure it for local-only operation as well. The configured model is `llama3.2:1b`. It is **not downloaded automatically**: after the service is running, explicitly obtain it when you choose to do so:

```bash
docker compose exec ollama ollama pull llama3.2:1b
```

The [Ollama model page](https://ollama.com/library/llama3.2:1b) lists the model at approximately 1.3 GB (checked 2026-11-23). Allow additional disk space and memory for inference; requirements depend on context and runtime. Initial image and model downloads need network access. If the service or model is unavailable, requests fail rather than switching provider or silently fetching a model. Actual model availability and hardware performance have not been validated by the automated tests.

Local Ollama attempts are recorded separately from Gemini. Spring AI 2.1 does not expose enough raw information to distinguish a missing token count from a real zero, so usage is reported as unavailable/incomplete and cost remains unpriced. Existing budget admission rules still apply.

### Frontend Development

The frontend has its own installation, development, build, and verification workflow; see the [frontend README](frontend/README.md) and [frontend development guide](frontend/DEVELOPMENT.md) for commands, module boundaries, app shell layout conventions, and verification requirements.

---

## 4. Verification & Testing

### Specifications & Sub-Tickets
- [ThreadPlane migration spec #23](https://github.com/samzhu/bot/issues/23), with delivery tickets [#25](https://github.com/samzhu/bot/issues/25), [#26](https://github.com/samzhu/bot/issues/26), [#27](https://github.com/samzhu/bot/issues/27), [#28](https://github.com/samzhu/bot/issues/28), and [#29](https://github.com/samzhu/bot/issues/29).
- [GitHub Spec #6: Spec: MVP 資料模型重構與 Local 示範載入](https://github.com/samzhu/bot/issues/6)
- [GitHub Sub-issue #7 (T1): 乾淨重建後可正常聊天](https://github.com/samzhu/bot/issues/7)
- [GitHub Sub-issue #8 (T2): local啟動即可查看完整示範](https://github.com/samzhu/bot/issues/8) (Blocked by #7)

### Test fixtures and artifacts

Tests explicitly provide Fake model boundaries and synthetic data where needed. Normal development startup does not insert demonstration conversations or usage. The application still creates its required default Bot after database migration. Test fixtures and private configuration are excluded from production artifacts.

### Backend Test Suites
```bash
cd backend

# 1. Unit and integration tests (isolated PostgreSQL & controlled model)
./gradlew test

# 2. Dedicated fail-fast configuration tests (missing API key, missing model, zero network)
./gradlew test --tests RealAiFailFastTests
```

### Backend OCI Image

With Docker running, build the backend image from the repository root:

```bash
cd backend
./gradlew bootBuildImage
```

The default local image is `docker.io/library/bot:0.0.1-SNAPSHOT` (also addressable as `bot:0.0.1-SNAPSHOT`), based on the current Gradle project name and version. It is created in the configured Docker daemon and is not published by default. The image contains the backend artifact, not the frontend or test fixtures. Running it requires an explicitly permitted profile and separately configured database connectivity; the image build itself does not start the application or establish a production deployment configuration. See [Spring Boot 4.2.0 OCI packaging](https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html) (checked 2026-11-23).

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

See [frontend verification details](frontend/README.md) for browser test commands and prerequisites. Do not point generation tests at a real provider.


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
