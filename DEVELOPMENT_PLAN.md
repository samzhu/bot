# Bot 平台開發計畫

> 狀態：開發規劃草案，2026-11-10。  
> **當前策略邊界：當前 Local MVP 優先**（單一本地開發身分、Seed Bot、GraphQL 歷史清單、POST SSE 聊天閉環、短交易持久化）；最新決策確定 MVP 徹底不使用 Spring Security/OAuth2、Liquibase、MCP 與 Spring Modulith，以應用層 Loopback 固定身分與編號 SQL 初始化腳本完成純淨閉環。登入認證、多使用者隔離、多租戶與外部 OAuth2 發證延後至後續階段處理。

## 文件導航

- **總體架構與長期規劃**：[DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)（本文件）
- **Local Chat MVP 規格說明書**：[docs/specs/local-chat-mvp.md](docs/specs/local-chat-mvp.md)（行為規格、DB 約束、狀態機與 API 契約）
- **MVP 可執行實作計畫**：[docs/MVP_IMPLEMENTATION_PLAN.md](docs/MVP_IMPLEMENTATION_PLAN.md)（G0~G5 門禁任務拆解、編號 SQL 初始化、Mock/真模型分離、生命週期契約）
- **客觀環境基線與命令證據**：[docs/research/environment-baseline.md](docs/research/environment-baseline.md)（JDK、Docker Server 狀態、Gradle dry-run、GitHub CLI 盤點實測原始輸出）
- **官方套件與相容性研析**：[docs/research/mvp-dependencies.md](docs/research/mvp-dependencies.md)（由 Luna 獨立研析維護，本專案不直接修改）

## 1. 產品目標與規劃範圍

建立可管理與使用 AI Bot 的工作平台，提供對話、工具呼叫、知識檢索，以及未來的工作流程能力。介面方向參考 AI Bot／Agent 服務；具體互動與產品邊界尚未確定，不預設複製特定產品。

首版建議以「登入後選擇 Bot、送出訊息、看到串流回答、返回歷史對話」形成完整可驗證流程，再逐步加入可授權工具與知識來源。Bot 編輯、多 Bot 協作、排程、工作流程編輯、複雜權限、語音與多模態暫列後續提案，不視為已確認需求。

## 2. 現有專案基線與路徑現況

- **程式碼結構**：
  - 後端：`backend/build.gradle` 基於 Spring Boot 4.1.2、Spring AI BOM 2.0.1、Java toolchain 27。最新架構決策落實抽象介面與手動配置（Google GenAI 改用 `spring-ai-google-genai` 核心模組，以 Java Config 集中管理 bean，業務層僅依賴 `ChatModel`/`StreamingChatModel`）；MVP 徹底不使用 Liquibase、Security、MCP、Modulith，以應用層 Loopback 固定身分與編號 SQL 初始化完成極簡純淨閉環。
  - 後端配置與資源：`backend/compose.yaml`（提供開發用 PostgreSQL/pgvector 及 Grafana OTEL LGTM）、`backend/src/main/resources/application.yaml`（目前僅設定應用名稱）。
  - 後端原始碼：`backend/src/main` 僅有啟動類別 `BotApplication.java`；`backend/src/test` 具備 context-load 與 Testcontainers 範本。
  - 前端：`frontend/` 目錄目前為空目錄，尚未建立骨架。
- **環境盤點現況（2026-11-10 實測保留證據）**：
  - Java：`BellSoft OpenJDK 25.0.1 LTS`（位於 `/Users/samzhu/.sdkman/candidates/java/25.0.1-librca`）；`backend/build.gradle` 宣告 Java toolchain 27，但實測缺少下載解析器，目前 G0 門禁狀態為 **BLOCKED**。
  - Gradle Wrapper：位於 `backend/gradlew`，經實測已下載 Gradle 9.8.2 並成功回報版本（支援 Java 27）。
  - Docker：Client 版本為 29.8.2 (OrbStack)，但實測 daemon 處於未運行狀態（socket 斷開），啟動本機資料庫前需啟動 Docker daemon。
  - Node / NPM：Node v26.1.0, npm 11.13.0。
  - Git：git version 2.55.0。專案根目錄目前尚未建立 `.git`；遠端 `git@github.com:samzhu/bot.git` 已存在 `main` 分支（commit: `43db8a93d4df5fed13e1c34c28d44c31136e73ba`）。等待文件驗收後方可進行初始化與無破壞性推送。


## 3. 架構方向

> **演進提示**：以下架構圖與模組建議為專案長期演進方向。**當前 Local MVP 之精確規格、介面契約與任務門禁以 [docs/specs/local-chat-mvp.md](docs/specs/local-chat-mvp.md) 與 [docs/MVP_IMPLEMENTATION_PLAN.md](docs/MVP_IMPLEMENTATION_PLAN.md) 為準**。

```text
Angular + CopilotKit Angular ChatView
    ├─ GraphQL：對話／Bot 清單、歷史、設定等結構化操作（建議）
    └─ HTTPS POST + SSE：AI 回應與執行事件（建議）
                           │
Spring Boot WebMVC + Virtual Threads + Spring Modulith
    ├─ 安全及使用者身分／授權
    ├─ 對話與執行：Spring Data JDBC / PostgreSQL
    ├─ AI：Spring AI ChatClient / Google GenAI
    ├─ 工具：Spring AI MCP Client / 權限與審批
    ├─ 知識：文件擷取、embedding、pgvector
    └─ 可觀測性：Actuator / OpenTelemetry
```

不使用 Copilot Runtime。GraphQL、SSE 的精確 schema／事件契約及取消語意尚待定義；上圖是建議分工而非已實作 API。CopilotKit `CopilotChatView` 為呈現元件，訊息及後端串接需由應用管理；Angular 套件參考目前官方 Angular 22 API，實際前端版本與依賴需鎖版驗證。[CopilotKit Angular API](https://docs.copilotkit.ai/reference/angular)；[ChatView](https://docs.copilotkit.ai/reference/angular/components/CopilotChatView)。

前期可用前端 `provideCopilotKit({})`、自有 `ChatState` 和 API adapter；已有獨立 jsdom 測試驗證空 runtime URL 可呈現輸入框並觸發 submit，**未測真瀏覽器、SSE 或 Spring 整合**。研究草稿在 `/private/tmp/copilotkit-angular-spring-ai-research-2026-11-10.md`，屬臨時檔。

## 4. 基礎技術決策與驗證

| 項目 | 目前方向 | 驗證或限制 |
| --- | --- | --- |
| Java／建置 | Java 27、Gradle 9.8.2、Spring Boot 4.1.2 | Spring Boot 4.1.2 官方支援至 Java 27，Gradle 官方自 9.8 支援 Java 27；仍須驗證所有實際 plugins、driver、GraalVM 與 CI。[Boot](https://docs.spring.io/spring-boot/system-requirements.html)、[Gradle](https://docs.gradle.org/9.8.0/release-notes.html) |
| Spring AI | 2.0.1、Google GenAI chat + embeddings | Spring AI 2.0.x 官方支援 Boot 4.0／4.1；模型名稱、供應商配額、憑證及向量維度待設定和實測。[版本支援](https://docs.spring.io/spring-ai/reference/getting-started.html) |
| Web | MVC server，預計啟用 virtual threads | 明確配置 `spring.threads.virtual.enabled=true` 並驗證實際 request、GraphQL 與阻塞工作執行緒；不代表所有 Reactor 任務都自動切換。[官方設定](https://docs.spring.io/spring-boot/3.4/reference/features/spring-application.html) |
| Streaming | Spring AI `ChatClient.stream()` + SSE | 文件指出串流需要 reactive stack；即使服務選 MVC 仍需核對所選 Spring AI 版本與依賴樹，不能盲排 WebFlux/Reactor。MVC 支援 SSE；需驗證中斷、錯誤、代理緩衝。[Spring AI](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html) |
| GraphQL | Spring Boot GraphQL starter | 保留現有 Spring 模型，沒有預先加入 Netflix DGS；選 MVC 時由 WebMVC 提供傳輸。授權檢查必須綁定使用者及工作區。 |
| Persistence | Spring Data JDBC + PostgreSQL / pgvector | JDBC 為阻塞操作，虛擬執行緒不會增加資料庫連線池容量；避免模型串流期間持有連線／長交易。Liquibase 管理 schema。 |
| Copilot Runtime／AG-UI | 不使用 Runtime；AG-UI 暫不納入首版 | 如改採 AG-UI，另行決策與實測。`@ag-ui/spring-ai` 0.0.4 文件載明相容模式會丟棄多模態內容；CopilotKit 對自管 production 路徑有 Enterprise 條件待確認。[AG-UI release](https://github.com/ag-ui-protocol/ag-ui/releases)、[自管 Agent](https://docs.copilotkit.ai/angular/backend/self-managed-agents) |
| 原生映像／SBOM | 已宣告 GraalVM Native Build Tools 1.1.14、CycloneDX | 屬計畫中的能力，不表示 native build、套件相容或 SBOM 已產生；先完成 JVM 測試再另列 native 驗證。 |

### 套件與產品功能的界線

- `spring-boot-starter-security` 與 resource server 是安全元件，不代表登入、權限模型或 OAuth2 發證端已配置。WebAuthn 依賴不等於已決定採用 passkey。Thymeleaf 是否用於登入或管理頁待決，並非 Angular 的必要前提。
- `spring-ai-starter-model-chat-memory-repository-jdbc` 是模型上下文記憶的儲存能力，不應直接當作使用者可檢索的完整對話／工具稽核紀錄。其保存窗口與 UI 歷史、保留及刪除策略需分開設計；conversation ID 必須由伺服器綁定身分並驗權。[Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- pgvector schema 初始化預設為 opt-in；Liquibase 與自動初始化只能有清楚的所有權，避免重複建立。embedding 維度、模型變更／重建索引及權限過濾須驗證。[pgvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- `mcp-client-security-spring-boot:0.1.14` 官方說明支援 `McpSyncClient`；需核對所選 Spring AI 2.0.1 與實際依賴樹，並驗證 OAuth 流程及阻塞隔離；不能宣稱所有 MCP 安全或非同步傳輸已啟用。[MCP security](https://github.com/spring-ai-community/mcp-security/blob/main/README.md)
- Modulith JDBC starter、事件發布與可觀測性依賴不會自動完成所有非同步作業、重試與冪等；須設計事件交易邊界與重播政策。Actuator／OTel 與 CycloneDX 也需配置和驗證。

## 5. 初步模組及資料模型（提案）

以 `io.github.samzhu.bot` 的直接子套件作為 Modulith 候選，切分及依賴以後續 domain model／`ApplicationModules.verify()` 驗證為準：

| 候選模組 | 責任 | 邊界 |
| --- | --- | --- |
| `identity` | 身分、工作區與授權 | 身分提供者、租戶與角色政策待決 |
| `bot` | Bot 設定、模型及工具授權 | 可見性、擁有權與版本待決 |
| `conversation` | 對話、訊息及歷史查詢 | 與模型短期 memory 分開；狀態以伺服器為準 |
| `execution` | 一次 AI run、串流、取消、工具結果 | 狀態與事件契約須定義；與長期 workflow 不混為一談 |
| `tools` | MCP 連線、工具註冊與授權 | 高風險動作的確認與稽核待決 |
| `knowledge` | 文件解析、切塊、embedding、檢索 | 來源權限、引用、保留政策需設計 |
| `workflow` | 後續流程定義與執行 | 非首版既定範圍；先釐清引擎及執行語意 |

候選資料：`bot`、`conversation`、`message`、`agent_run`、`tool_call`、`knowledge_source`。需定義 ID、歸屬、ACL、狀態、建立／更新時間、刪除及稽核策略；不要把預期欄位寫成已存在 schema。串流期間持久化以短交易為原則，失敗／取消的部分答案及重試產生副作用的政策待決。

## 6. 分階段工作與驗收

> **演進提示**：以下 Phase 0 至 Phase 4 為產品長期藍圖；**首版 Local Chat MVP 之具體執行路徑與門禁任務以 [docs/MVP_IMPLEMENTATION_PLAN.md](docs/MVP_IMPLEMENTATION_PLAN.md) 之 G0 ~ G5 門禁為準**。

### Phase 0：環境與版本基線

1. 安裝／指定 Java 27，核對 Gradle Wrapper、容器環境、Google GenAI 測試憑證的安全供應方式；先執行 `./gradlew --version`、`./gradlew dependencies --configuration runtimeClasspath`、`./gradlew test`。測試會用 Testcontainers／容器，應先確認環境；不得提交憑證。
2. 核對 BOM 解析、MCP security 與 Spring AI 的實際依賴版本，驗證 Modulith 及 Boot 相容性；若 Java 27 / plugin 不合需記錄證據再決定版本，不擅自降版。
3. 啟動一次 PostgreSQL/pgvector 的隔離開發環境；建立 Liquibase 首個 migration 並測試乾淨資料庫與重複啟動。明確測試 Chat Memory / pgvector schema 所有權。

驗收：乾淨環境可建置與跑基礎測試，依賴樹及錯誤有記錄；尚未設定的模型和授權明確列出。

### Phase 1：最小聊天閉環

1. 定義登入／身分的最小可用機制，以及 Bot、conversation、message 的持久化與查詢。
2. 定義 GraphQL 查詢與 mutation、POST + SSE 事件格式：run ID、開始、文字增量、完成、失敗、取消；加入斷線與取消處理。建立服務端授權與跨使用者隔離。
3. 建立 Angular 前端並串接 CopilotKit ChatView 與自有 ChatService；不安裝或依賴 Copilot Runtime。接入 Spring AI 模型，明確區分 UI 對話歷史與上下文記憶。

驗收：兩個獨立使用者的對話不可互讀；重新整理可讀取自己的歷史；回答可逐段顯示；停止、失敗、斷線狀態可見；模型金鑰不進入瀏覽器。測試以 mock 模型為基礎，另以隔離環境執行一條真實模型 smoke test。

### Phase 2：工具與安全

1. 建立 MCP server 設定、連線、工具清單、逾時及錯誤處理；先支援低風險、明確允許的工具。
2. 定義工具授權、操作前人工確認與結果稽核；`McpSyncClient` 的阻塞工作與取消行為需實測。
3. 不自動重送具副作用的工具；建立執行 ID、冪等／重試和失敗結果語意。

驗收：未授權工具不能呼叫；使用者可辨識執行中／成功／失敗；測試取消、重複請求及 MCP 連線異常。

### Phase 3：知識與 RAG

1. 選定首版文件類型、上傳／抓取管道、擷取與切塊方式；檢查 MIME、大小、來源與存取權。
2. 配置 embedding、pgvector 索引與依工作區／使用者過濾；保存來源與引用。
3. 設計重試、重新索引、刪除／撤權後的向量清理；量測成本及檢索品質。

驗收：跨工作區文件無法被檢索；可追溯來源；刪除／撤權後依定義不再可被引用；失敗文件可定位並重試。

### Phase 4：工作流程與多 Bot（待決）

確認是否需要流程設計器、人工審批、排程、多 Agent 協作及所需 workflow engine。若需要視覺編輯，可評估 Angular Foblex Flow；流程畫布不是流程執行器。定義版本、權限、持久化、重跑、冪等與部分失敗後再進入實作。

## 7. 非功能與驗證

- 安全：分離前端身分驗證、後端 resource server 與 MCP OAuth client；模型 API key 僅留後端。逐端點檢查身份、資源歸屬、工具授權與機密資料遮罩。
- 容量：根據實際模型與工具設定連線／用量上限、排隊、背壓、timeout；監控首字時間、總延遲、活躍串流、資料庫池、錯誤率和工具失敗。virtual threads 不保證性能。
- 可靠性：SSE 經 ingress/proxy 的 buffering、閒置 timeout、取消傳遞、斷線後重連與副作用重送要端到端測試；日誌避免記錄 prompt、文件或 token 等敏感內容。
- 資料：對話歷史、上下文 memory、知識向量和審計紀錄各自定義來源、保留期限、清除、備份及授權；不要假設 Spring AI memory 包含完整工具交換紀錄。
- 測試：單元測試、GraphQL 授權與分頁、SSE 事件與取消、Testcontainers PostgreSQL/pgvector/Liquibase、Modulith 邊界、模型假件、工具異常、實際瀏覽器 smoke test 及壓力測試；逐項區分 mock 和真服務。
- 發行：先驗證 JVM 版本、CI、Docker、SBOM，再決定 native image；核對開源套件及第三方服務授權。既有 `compose.yaml` 是本機便利設定，部署時需獨立安全配置。

## 8. 待產品與技術決策

1. 第一版使用者、單人／多工作區、身分提供者及 Bot 與資料可見範圍。
2. Bot 是否可由使用者建立、修改或分享；模型及工具清單由誰管理。
3. 首版是純聊天，還是需要文件上傳、引用、MCP 工具、工作流程／排程。
4. Google GenAI 使用方式、模型清單、資料政策、配額、金鑰管理與費用歸屬。
5. 對話與工具／執行紀錄保留、刪除、取消、重跑及稽核政策。
6. 預估並發串流、延遲與恢復目標；是否需要跨裝置或中斷後回復。
7. Angular 版本、UI 視覺系統、前後端同 repo 或獨立 repo；是否使用 WebAuthn、Thymeleaf。
8. 是否需要 GraphQL Federation／DGS、AG-UI、CopilotKit Intelligence；目前均不因套件清單而視為已採用。
