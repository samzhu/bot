# 核心聊天 MVP 套件研究

研究日期：2026-11-10。核對來源：DEVELOPMENT_PLAN.md、backend/build.gradle、backend/compose.yaml、backend/src/main/resources/application.yaml、backend/gradle/wrapper/gradle-wrapper.properties。本文件只做官方資料核查；沒有執行 Gradle 解析、編譯、測試或容器啟動，所以「版本存在／官方宣稱相容」不代表本專案已成功解析或實測相容。

## 決策摘要

- **版本基線可作為解析起點，不能視為已驗收。** Boot 4.1.2、Gradle 9.8.2、Java 27、Spring AI 2.0.1、Spring Modulith 2.1.1 均有官方版本證據。Boot 4.1.2 支援 Java 27、Gradle 9.x，Spring AI 2.0.x 宣稱支援 Boot 4.1。Modulith 2.1.1 發行記錄明確升至 Boot 4.1.1，但其版本文件的相容表未列出 2.1 系列，故不把 Boot 4.1.2 相容性寫成已由矩陣確認。[Boot 系統需求](https://docs.spring.io/spring-boot/system-requirements.html)、[Gradle 9.8.2 發行說明](https://docs.gradle.org/9.8.2/release-notes.html)、[Spring AI 版本支援](https://docs.spring.io/spring-ai/reference/getting-started.html)、[Modulith 發行記錄](https://github.com/spring-projects/spring-modulith/releases)、[Modulith 相容表](https://docs.spring.io/spring-modulith/reference/appendix.html)
- **Dev Compose 是開發期能力。** spring-boot-docker-compose 已列為 developmentOnly，預設只在應用啟動時執行 docker compose up、結束時 stop，測試預設跳過，repackaged archive 預設不含此支援。需要 Docker CLI、Compose v2.2.0+，以及 CLI 能連到 Docker daemon。容器預先在跑時只建立 service connection，不重複啟停。[Spring Boot Development-time Services](https://docs.spring.io/spring-boot/reference/features/dev-services.html)
- **Compose 檔與目錄有落差。** 計畫說根目錄 compose，實際是 backend/compose.yaml。從 backend 執行 ./gradlew bootRun 可自然找到；從 repo root 執行時明確設定 spring.docker.compose.file=backend/compose.yaml，或調整 working directory。Gradle project path 不一定等同 forked app 的 working directory，需以啟動 log 確認。[Boot Compose 檔案設定](https://docs.spring.io/spring-boot/how-to/docker-compose.html)
- **Security/OAuth2 是延後功能；關閉 resource server 本身也不保證 Security 預設放行。** 若 Security servlet auto-config 在 runtime classpath/條件下啟用，Boot 預設會保護 Web 應用；目前 security/resource-server/WebAuthn/Thymeleaf-Security direct dependencies 均註解，local MVP 不要求 SecurityFilterChain。MVP 採 loopback-only 固定 local identity，非 local 環境拒絕啟動。G0 resolve tree 仍確認沒有傳遞引入意外 Security auto-config；遠端部署和正式身份驗證屬後續產品決策。[Boot Security defaults](https://docs.spring.io/spring-boot/reference/web/spring-security.html) · [Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- **MVP 用 Spring AI ChatModel 抽象隔開供應商，Google chat 可採手動 model bean。** 官方 Google GenAI 文件提供 `spring-ai-google-genai` + `GoogleGenAiChatModel` 手動配置；供應商替換可限於 model adapter/bean，業務層依賴 `ChatModel` 或本地 app port。保留現有依賴宣告的原則，延後功能先按版本證實的 property 或精確 auto-config class exclusion 停用；未用 starter 不一律刪除。Google embedding starter 是否引入共享 Google auto-config、在無 chat implementation 時是否只建立 embedding bean，需由 G0 resolved tree + condition report 證實。
- **MVC SSE 可承載 Spring AI 串流，但 ChatClient.stream() 需要 Reactive stack。** 保留 MVC server，另外確保 Reactor/WebFlux reactive 類型可用；若加入 spring-boot-starter-webflux，驗收 Boot 仍選 Servlet/MVC，並測 SSE、取消和錯誤事件。[Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Spring MVC async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)

## 宣告版本核查

| 宣告 | 官方存在／相容性證據 | 判定與限制 |
| --- | --- | --- |
| Java toolchain 27 | Boot 4.1.2 支援至 Java 27；Gradle 9.8.2 支援 Java 27 daemon/toolchain。[Boot](https://docs.spring.io/spring-boot/system-requirements.html) · [Gradle](https://docs.gradle.org/9.8.2/release-notes.html) | 主版本組合有官方支持；本機 Java 未驗證。build 僅宣告 toolchain，未配置 toolchain download resolver；不可假設 Gradle 會自動下載 JDK 27。第三方工具也可能有更高要求。 |
| Gradle Wrapper 9.8.2 | [Gradle 9.8.2 release notes](https://docs.gradle.org/9.8.2/release-notes.html) | 版本存在；Boot 支援 Gradle 9.x。未下載 wrapper distribution 或執行 build。 |
| Spring Boot plugin/BOM 4.1.2 | [Boot 4.1.2 系統需求](https://docs.spring.io/spring-boot/system-requirements.html)；[Gradle Plugin Portal](https://plugins.gradle.org/plugin/org.springframework.boot/4.1.2) | 存在；主版本支持 Java/Gradle 如上。 |
| io.spring.dependency-management 1.1.7 | [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.spring.dependency-management) | 插件版本存在。仍需解析確認兩 BOM precedence 與最終版本。 |
| GraalVM Native Build Tools 1.1.14 | Boot 4.1.2 system requirements 列為支持版本。[來源](https://docs.spring.io/spring-boot/system-requirements.html) | Boot 支持證據存在；plugin marker 未獨立確認。Native build 未驗，MVP 可延後。 |
| CycloneDX Gradle plugin 3.5.1 | [Gradle Plugin Portal](https://plugins.gradle.org/plugin/org.cyclonedx.bom) 列 3.5.1，建立日期 2026-10-09 | 版本存在；Gradle 9.8.2 相容尚無本專案解析證據。宣告插件不等於 SBOM 已產生。 |
| Spring AI BOM 2.0.1 | [Spring AI 2.0.1 ChatClient Javadoc](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/chat/client/ChatClient.html)；Spring AI 2.0.x 官方支持 Boot 4.0.x/4.1.x。[版本支援](https://docs.spring.io/spring-ai/reference/getting-started.html) | 版本與 API 存在；尚未解析 starter 座標是否全部在 2.0.1 BOM 內，也未驗 Google SDK/Boot 4.1.2 transitive constraints。 |
| Spring Modulith BOM 2.1.1 | [Modulith 2.1.1 參考/BOM](https://docs.spring.io/spring-modulith/reference/index.html)；[官方 release](https://github.com/spring-projects/spring-modulith/releases) | 版本存在，release 記錄升至 Boot 4.1.1。Appendix compatibility table 未列 2.1.1；對 Boot 4.1.2 屬發行記錄支持，但矩陣證據不完整。須實際解析/測試。 |
| Boot direct artifacts | 目前有效：spring-boot-starter-actuator、spring-boot-starter-data-jdbc、spring-boot-starter-graphql、spring-boot-starter-opentelemetry、spring-boot-starter-validation、spring-boot-starter-webmvc、spring-boot-docker-compose、spring-boot-configuration-processor；有效 test starters：actuator/cache/data-jdbc/graphql/opentelemetry/validation/webmvc、spring-boot-testcontainers。註解：主 cache/liquibase/security/security-oauth2-resource-server/thymeleaf starters 與 test liquibase/security-oauth2-resource-server/thymeleaf starters。Boot 座標由 Boot plugin/BOM 預期管理。[Boot Reference](https://docs.spring.io/spring-boot/reference/) | 每項有效宣告精確解析版本未取得，尤其 Boot 4 新 starter 拆分。 |
| Spring AI direct artifacts | 目前有效：spring-ai-jsoup-document-reader、spring-ai-markdown-document-reader、spring-ai-pdf-document-reader、spring-ai-starter-model-chat-memory-repository-jdbc、spring-ai-google-genai（raw implementation）、spring-ai-google-genai-embedding（raw implementation）、spring-ai-starter-vector-store-pgvector、spring-ai-vector-store-advisor、spring-ai-spring-boot-docker-compose。已註解：Google chat/embedding starters、tool-search starter、MCP starter。BOM 2.0.1 預期管理。參考：[Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)、[Google Chat](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html)、[Embedding](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html)、[MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)、[PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) | 目前主文件為 2.0.2，不是 build 宣告的 2.0.1；G0 仍須解析本專案的實際版本與依賴樹。 |
| MCP security 0.1.14 | 曾有官方版本及相容性證據，但 `mcp-client-security-spring-boot:0.1.14` 目前已註解，非有效 direct dependency。[官方 README](https://github.com/spring-ai-community/mcp-security) | 不納入目前 resolved dependency tree；若恢復 MCP 時再核對 Spring Security/MCP SDK。 |
| Modulith direct artifacts | observability-api、starter-core、starter-jdbc、runtime actuator/observability-core、test starter 目前全部註解，非有效 dependency。[Modules/properties](https://docs.spring.io/spring-modulith/reference/appendix.html) | BOM 2.1.1 仍存在於 build dependencyManagement；有效依賴是否被引入須以解析樹確認，不假設功能已啟用。 |
| Security / Thymeleaf / DB / Testcontainers | WebAuthn、Thymeleaf extras、MCP security 目前皆註解；有效的第三方 direct dependencies 為 PostgreSQL driver、testcontainers-grafana/junit-jupiter/postgresql、junit-platform-launcher，未寫版，預期由 Boot BOM 管理（待解析確認）。 | Security/Thymeleaf/MCP security 不列入目前直接 runtime declarations；Testcontainers 與 DB driver 的 resolved versions 未取得。 |
| Angular/CopilotKit frontend | 計畫只提 Angular 與 CopilotKit Angular API/ChatView，未宣告 Angular、Node、TypeScript、CopilotKit 或 adapter 版本。[Angular API](https://docs.copilotkit.ai/reference/angular) · [ChatView](https://docs.copilotkit.ai/reference/angular/components/CopilotChatView) | 無版本可驗存在/相容。開發前須有 lockfile、Node/Angular/CopilotKit 精確版與 peer/engine 支持；Angular 22 文件不代表專案版本。 |

### G0 direct dependency 解析清單

下表列出 backend/build.gradle 每個直接宣告座標；**resolved version = 尚未解析**，這是本研究的狀態而非版本不存在。工具條件允許後要逐項取得 Gradle resolved version 並記錄成功/失敗；若被 BOM 管理，表內 manager 僅指出預期來源，不預設一定管理該項。

| Scope / kind | Direct artifacts | 預期版本來源 | 狀態 |
| --- | --- | --- | --- |
| Plugins | org.springframework.boot:org.springframework.boot.gradle.plugin 4.1.2; io.spring.dependency-management:io.spring.dependency-management.gradle.plugin 1.1.7; org.graalvm.buildtools.native:org.graalvm.buildtools.native.gradle.plugin 1.1.14; org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin 3.5.1 | Plugin Portal/Boot supported versions | 宣告版有官方證據如上；Gradle 對每項 plugin artifact 的解析未執行。 |
| implementation, Boot | 有效：spring-boot-starter-actuator; spring-boot-starter-data-jdbc; spring-boot-starter-graphql; spring-boot-starter-opentelemetry; spring-boot-starter-validation; spring-boot-starter-webmvc。註解未啟用：spring-boot-starter-cache; spring-boot-starter-liquibase; spring-boot-starter-security; spring-boot-starter-security-oauth2-resource-server; spring-boot-starter-thymeleaf | Boot plugin 4.1.2 dependency management | 每項有效宣告 resolved version/variant 未取得；註解項不解析。 |
| developmentOnly, Boot / Spring AI | org.springframework.boot:spring-boot-docker-compose; org.springframework.ai:spring-ai-spring-boot-docker-compose | Boot dependency management / Spring AI BOM 2.0.1 respectively | 每項 resolved version/variant 未取得。 |
| testImplementation, Boot | 有效：spring-boot-starter-actuator-test; spring-boot-starter-cache-test; spring-boot-starter-data-jdbc-test; spring-boot-starter-graphql-test; spring-boot-starter-opentelemetry-test; spring-boot-starter-validation-test; spring-boot-starter-webmvc-test; spring-boot-testcontainers。註解：spring-boot-starter-liquibase-test; spring-boot-starter-security-oauth2-resource-server-test; spring-boot-starter-thymeleaf-test | Boot plugin 4.1.2 dependency management | 每項有效宣告 resolved version/variant 未取得；註解項不解析。 |
| testImplementation, Spring AI / Modulith | 有效：org.springframework.ai:spring-ai-spring-boot-testcontainers。註解：org.springframework.modulith:spring-modulith-starter-test | Spring AI BOM 2.0.1 / Modulith BOM 2.1.1 | 有效項 resolved version 未取得；註解項不解析。 |
| annotationProcessor, Boot | org.springframework.boot:spring-boot-configuration-processor | Boot plugin 4.1.2 dependency management | resolved version 未取得。 |
| implementation, Spring AI | 有效：spring-ai-jsoup-document-reader; spring-ai-markdown-document-reader; spring-ai-pdf-document-reader; spring-ai-starter-model-chat-memory-repository-jdbc; spring-ai-google-genai; spring-ai-google-genai-embedding; spring-ai-starter-vector-store-pgvector; spring-ai-vector-store-advisor。註解：spring-ai-starter-model-google-genai; spring-ai-starter-model-google-genai-embedding; spring-ai-starter-tool-search-advisor; spring-ai-starter-mcp-client | spring-ai-bom 2.0.1 | 有效宣告 resolved version/variant 未取得；註解項不解析。 |
| implementation/runtimeOnly, Modulith | 註解：spring-modulith-observability-api; spring-modulith-starter-core; spring-modulith-starter-jdbc; spring-modulith-actuator; spring-modulith-observability-core | spring-modulith-bom 2.1.1 | 無有效 direct Modulith dependency；註解項不解析。 |
| implementation, third-party | 註解：org.springaicommunity:mcp-client-security-spring-boot:0.1.14; org.springframework.security:spring-security-webauthn; org.thymeleaf.extras:thymeleaf-extras-springsecurity6 | 原 MCP explicit 0.1.14；其他預期 Boot management | 目前均未啟用、不解析。 |
| runtimeOnly, third-party | org.postgresql:postgresql | Expected Boot management (confirm) | resolved version not取得。 |
| testImplementation, third-party | org.testcontainers:testcontainers-grafana; org.testcontainers:testcontainers-junit-jupiter; org.testcontainers:testcontainers-postgresql | Expected Boot management (confirm) | 每項 resolved version 未取得。 |
| testRuntimeOnly | org.junit.platform:junit-platform-launcher | Expected Boot management (confirm) | resolved version 未取得。 |

### 版本 gate

開始任何實作前確認 Java 27、Wrapper、Gradle daemon，從 backend 解析 runtimeClasspath/testRuntimeClasspath、兩 BOM 與 dependencyInsight。特別記錄 Boot/Spring Framework/Security/Jackson/MCP SDK、thymeleaf extras、MCP security 和 Google SDK 的實際版本，檢查衝突或未管理依賴。分開記錄 **resolved**（Gradle 成功決定依賴版本）與 **tested**（該依賴組合已通過本專案 build/context/integration 驗收）；版本解析不等於行為相容，後續還需 context start、MVC SSE、Testcontainers、Compose 驗收。前端以 package manager lockfile 核定。

## Boot Compose：現況、命令與邊界

repo 的 Compose 檔是 backend/compose.yaml，application.yaml 只有 spring.application.name。spring-boot-docker-compose 是 developmentOnly；Boot 文件說 dev services 僅為開發，部署停用。預設啟動執行 docker compose up、JVM 關閉執行 stop；已運行服務只建立 service connection，不重複 up/stop。當前 compose.yaml 同時定義 grafana-lgtm 與 pgvector，預設會啟動兩個 services，不是只啟 PostgreSQL。service connection 會建立 Boot 的 ConnectionDetails bean，供支援的 auto-configuration 讀取；不是把設定複製到 Environment properties。這些連線細節優先於普通 connection properties，並連到容器映射給 host 的 port。自訂 image 可加 org.springframework.boot.service-connection=postgres label；現有 pgvector/pgvector:pg16 有此 label，可用 Boot PostgreSQL connection details。[Boot Compose lifecycle/service connections](https://docs.spring.io/spring-boot/reference/features/dev-services.html)

| 啟動方式 | Compose 檔搜尋 | 建議 |
| --- | --- | --- |
| cd backend && ./gradlew bootRun | working directory backend，找該處 compose.yaml | 本機開發首選。Docker daemon/context 必須可連線；本研究未檢查本機 daemon 狀態。 |
| repo root 執行 ./backend/gradlew -p backend bootRun | Gradle project path 不必然等於 app process working directory | 設 SPRING_DOCKER_COMPOSE_FILE=backend/compose.yaml 或傳 --spring.docker.compose.file；確認啟動 log。若 JVM cwd 是 backend，使用 compose.yaml。 |
| docker compose -f backend/compose.yaml up -d 後 IDE 啟動 | Boot 仍須找到匹配的 Compose 檔來建連線細節 | IDE working directory 設 backend，或 spring.docker.compose.file；不要以為手動啟容器即取得連線埠/credentials。 |

需要 Docker CLI 與 Compose v2.2.0+ 在 PATH，且 Docker daemon/context 可連線。Grafana 使用 `latest` (非固定版)，pgvector 使用 `pg16` image tag；Grafana host port 3000 固定，Postgres 只聲明 container port 5432，由 Compose 分配 host port。不要從 Docker CLI 存在或 `docker info` 的 Client 區段推斷 daemon 可用；啟動前確認 Server/daemon 回應。本研究沒有檢查目前 daemon。測試預設 `spring.docker.compose.skip.in-tests=true`，而 developmentOnly 也不在 test classpath；若要 Boot Compose 供測試需同時改 testAndDevelopmentOnly 並設 skip false。現有 Testcontainers 是獨立生命週期。repackaged archive 預設不含 dev compose；正式環境由部署平台提供 DB/collector 設定。[Boot Dev Services test/repackaging](https://docs.spring.io/spring-boot/reference/features/dev-services.html)

Spring AI Compose integration 不是 pgvector schema 初始化；其 2.0 文件列的額外 service factories 主要為 Ollama、Qdrant、Chroma、Milvus 等，Postgres 連線由 Boot 支援。[Spring AI Compose](https://docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/docker-compose.html) 此為 2.0-SNAPSHOT 文件，不用來斷言精確 2.0.1 factory 清單；本 MVP 只需 Boot 的 postgres connection label。

## Security：local 暫停與恢復

Boot security 文件指出預設 Web 應用全站保護，提供單一 generated in-memory user，表單登入或 HTTP Basic；Actuator 在 classpath 時也受保護。Resource-server starter 沒有 issuer/JWK 設定時不能建立預期的 JWT decoder，但不表示一般 servlet security chain 不攔截。[Boot Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html) · [Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

目前 MVP 決策是不啟用 Security/OAuth2，也不配置 SecurityFilterChain；只允許 loopback 綁定，使用固定 local identity，非 local 環境拒絕啟動。由 G0 依解析後 classpath 確認沒有傳遞依賴意外帶入 servlet security auto-config。若未來重新引入 Security starter，而 JWT/resource server 仍暫停，必須明確選擇 local-only 放行方式，因為關掉 resource-server 不等於關掉 Boot 的預設 web security。

未來遠端身份驗證是獨立產品決策：確定後再設可信 issuer/JWK、檢查 JWT iss、audience、簽章、期限、scopes，限制 GraphQL/history/run/actuator，並在 service 層綁定 user/workspace ownership。OAuth client for MCP（若恢復）是獨立出站授權，不替代 Web 入口驗證或租戶隔離。以上不是 local-only MVP gate。

## Auto-configuration 與 MVP 開關

| 功能 / 現有依賴 | MVP 決定 | 可確認的設定與證據 |
| --- | --- | --- |
| Google GenAI Chat | **依賴切換已套用：`spring-ai-google-genai` raw implementation 取代原 `spring-ai-starter-model-google-genai`；手動 `GoogleGenAiChatModel` bean 尚待實作；服務層依賴 `ChatModel`/app port** | build.gradle 已反映原 starter 註解、新 artifact 有效宣告。官方目前文件展示手動方式。設定 server key 或 Vertex credentials、明確 model；Google Search 預設關閉。G0 需解析 artifact 並驗 bean/context。[Google Chat 2.0.2 docs](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html) |
| Google GenAI Embedding | **依賴切換已套用：raw `spring-ai-google-genai-embedding` 取代 starter；EmbeddingModel bean/RAG 實作仍延後** | build.gradle 已註解 embedding starter，加入 implementation artifact。手動方式由 2.0.2 docs 記載；這比 starter 少引入 provider auto-config，但需實作 connection details/model bean。G0 檢查 resolved tree，功能開發時驗 EmbeddingModel bean。使用抽象/application port，embedding provider 切換涉及索引重算。[Google Embedding 2.0.2 docs](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html) |
| PGVector starter | **延後功能，保留依賴宣告** | schema init property `initialize-schema` 預設 false，true 才初始化 schema。2.0.1 Javadoc 確認 auto-config class 存在，但 package summary 未列 condition；確切 on/off property 待驗。解析 JAR 後檢查 condition，優先用精確 class exclusion；若考慮 `spring.ai.vectorstore.type=none`，先證明該版支援。[PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) · [2.0.1 auto-config package](https://docs.spring.io/spring-ai/docs/2.0.1/api/org/springframework/ai/vectorstore/pgvector/autoconfigure/package-summary.html) |
| JDBC chat memory starter | **延後功能，保留依賴宣告** | 目前 docs 顯示 JDBC repository schema `initialize-schema` 預設 embedded，可為 Liquibase 設 `never` (2.0.2；2.0.1 metadata 待驗)。無已驗證通用 enabled=false；解析 imports/conditions 後可用精確 auto-config exclusion。Current 2.0.2 docs 指 JDBC repository 不支援 tool-call messages，這項限制不預設套到 2.0.1。服務層使用應用層 `ConversationMemory` port 與 UI history 分開；具體 Spring AI `ChatMemory`/repository 僅由 adapter 使用。[Chat Memory 2.0.2](https://docs.spring.io/spring-ai/reference/api/chat-memory.html) |
| MCP starter/community security | **目前均為註解依賴，MCP 延後** | 若恢復 MCP，2.0.1 Javadoc 有 `spring.ai.mcp.client.enabled` 條件證據；目前沒有 MCP client/security direct dependency。確認恢復時依賴與 properties；不能視為 per-user session boundary。 |
| Modulith JDBC/observability | **目前全部為註解依賴，Modulith events/tracing 延後** | BOM 留在 dependencyManagement，但未宣告 runtime/test Modulith artifacts；只有恢復依賴時才驗 schema init/auto-config。 |
| Tool-search advisor | **目前註解依賴，延後** | 若恢復，2.0.2 reference 的 property 僅作參考；先查該依賴對應的 auto-config property/class 和 G0 resolved tree。 |
| Spring AI Docker Compose support | **可選 dev-only** | PostgreSQL connection 由 Boot core Compose 提供；AI Compose 模組僅在所需的額外 service connection 時加入。 |

目前 application.yaml 沒有上述設定。Auto-configuration 可能因條件不足而 back off，也可能要求 DataSource/credentials；不能由「沒有用」推論不建立 bean。優先用 exact-version condition/property；沒有官方 property 時，先查已解析 JAR 的 AutoConfiguration.imports 和條件，再以 spring.autoconfigure.exclude 排除單一目標。啟動時看 Condition Evaluation Report，驗停用 bean 不存在、DB schema 無意外變更、沒有外部連線。

## MVC 串流、SSE 與依賴

Spring AI ChatClient .stream() 回傳 Reactor Flux，文件明說 streaming 只由 Reactive stack 支援，imperative application 也須加入 Reactive stack（例如 WebFlux）。WebMVC 支援 async/reactive return values，因此保留 Servlet MVC 可行，但須有 Reactor/WebFlux types。可選加 org.springframework:spring-webflux 讓 MVC starter 主導 server；或加 Boot WebFlux starter 並驗 servlet/MVC 仍被選中。[ChatClient implementation notes](https://docs.spring.io/spring-ai/reference/api/chatclient.html) · [Spring MVC async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html) · [Boot MVC](https://docs.spring.io/spring-boot/reference/web/servlet.html)

POST chat request 回 text/event-stream，先定義 run ID、delta、complete/error/cancel envelope；驗斷線取消、代理 buffering、SSE flush。MVC controller 回 Flux<ServerSentEvent<...>> 需要 reactive adapter；使用 SseEmitter 則要明訂 Flux bridging/backpressure/cancellation。JDBC、工具呼叫仍是阻塞工作，不能因模型串流就持有長交易/connection。

## Provider 與生命週期邊界

| 能力 | 建議界面／實作切分 | 生命週期與替換範圍 |
| --- | --- | --- |
| 聊天 | 應用服務依賴本地 `ChatPort` 或 Spring AI `ChatModel`；`GoogleGenAiChatModel` 是 infra adapter | 每個供應商各自建 bean/adapter。替換 provider 只更換 adapter、憑證與模型設定，不讓 controller/domain 依賴 Google SDK。 |
| Embedding | `EmbeddingPort` / Spring AI `EmbeddingModel` 抽象，供應商實作只在 RAG adapter | 與 chat 分開管理，索引需記錄 model/version/dimensions；換模型通常要求重嵌入與重建向量，不只是換 bean。 |
| Vector store | 透過 `VectorStore` 或本地 `KnowledgeIndex` port；PGVector 封裝在 persistence adapter | tenant filters/schema/migration 由 adapter 負責；換向量庫需遷移與 ACL 驗收，不得假定換實作即資料可搬。 |
| Chat memory | 應用層 `ConversationMemory` 與完整對話 history repository 分開；Spring AI `ChatMemory`/`ChatMemoryRepository` 僅服務上下文窗口 | 記憶窗口、清除、持久化生命週期明確；勿把 adapter 記憶儲存當 UI history/audit。 |
| MCP、Modulith events/OTel | 協定/API 僅放 adapter 層；應用服務透過受控 port 發起工具/事件 | MCP session/權限、事件交易及 telemetry exporter 都有獨立生命週期，屬延期項；不要以 provider 抽象取代權限與重試契約。 |

Spring AI 提供 `ChatModel`、`EmbeddingModel`、`VectorStore` 等 provider-independent API；採這些 API 或更窄的應用 port 即可隔離供應商。沒必要把所有 starter 都改成手動 bean：核心 chat 採 Google implementation artifact + 手動 bean；其他延後模組依 G0 resolved tree、auto-config 條件、功能需求與生命週期再決定 starter 或 library artifact。[Spring AI API overview](https://docs.spring.io/spring-ai/reference/index.html) · [Google Chat manual config](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html) · [Google Embedding manual config](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html)

## MVP 啟用/延後矩陣

| 能力 | 決定 | 條件 |
| --- | --- | --- |
| MVC/GraphQL/validation/JDBC/PostgreSQL | 啟用 | DB access 與 PostgreSQL driver 已宣告；MVP 使用編號 SQL 初始化腳本並記錄執行狀態，不引入 Liquibase。Liquibase starter/test 目前註解且屬延後，不是此 MVP gate。非 local 環境拒絕啟動。 |
| Google GenAI chat/ChatClient | 啟用 | Server-side credentials、明確 model/配額，mock + 隔離 smoke test。 |
| Reactive stack + MVC SSE | 加必要依賴 | MVC 維持 Servlet；測增量、錯誤、取消、代理。 |
| Security/Auth | **Security/OAuth2 延後；local MVP 僅 loopback 固定 identity** | 非 local 環境拒絕啟動；不要求此 MVP 加 Spring Security、resource server、JWT 或 SecurityFilterChain。正式遠端身份驗證留待後續決策。 |
| embeddings/PGVector/vector advisors/document readers | 延後 | 明確 RAG scope、metadata ACL、維度/成本與 schema ownership。 |
| JDBC chat memory | 延後 | 確定 memory window、工具訊息限制、保留清除策略，與 UI history 分工。 |
| MCP client/security | 延後 | 伺服器 allowlist、tool authorization/HITL、副作用/稽核及 session 隔離。 |
| Modulith core/JDBC/observability | events/observability 延後 | 明確 event transaction/replay/schema ownership。 |
| OTel/Actuator | 延後完整 export | 指定 collector、sampling、敏感 tag；驗 traces 到 LGTM。 |
| WebAuthn、Thymeleaf、Native、SBOM | WebAuthn/Thymeleaf/Native/SBOM 延後 | 依產品/發行要求再決定，不作核心聊天 MVP gate。 |
| Angular/CopilotKit | 等版本 lock 核定 | Node/Angular/CopilotKit peer support、lockfile、browser SSE adapter。 |

## 建議驗收（尚未執行）

1. **供應鏈**：backend 解析 runtimeClasspath/testRuntimeClasspath、兩 BOM、所有有效 direct dependencies/plugins 的實際版；註解項記錄為未啟用、不解析；檢查 Spring/Security/Jackson/MCP SDK、Thymeleaf extras、Google SDK/MCP security 衝突。不要手動覆寫版來掩蓋。
2. **Compose**：backend working dir bootRun，確認 lifecycle、mapped port/credentials/DB、label 建立 JdbcConnectionDetails；再驗已有容器、daemon 不可用；測試中 Boot Compose 預設 skip、Testcontainers 獨立工作。
3. **Schema / architecture gaps**：按 MVP 契約用編號 SQL 初始化腳本及腳本執行記錄，驗 clean DB、重啟、重複執行保護；Liquibase 目前延後。Modulith implementation/runtime/test dependencies 目前註解，所以 `ApplicationModules.verify()` 不是本 MVP 可直接使用或必過的 gate。
4. **Local boundary**：只允許 loopback 綁定及固定 local identity；非 local 環境拒絕啟動。此設定不當作遠端身份驗證。
5. **AI/SSE**：mock 無 secret context 可啟動；真實 profile 後端密鑰呼叫指定模型；MVC 選擇未被 WebFlux 改變；瀏覽器驗 delta/error/cancel/disconnect，機密不記 log。
6. **延期 feature**：embedding 與 PGVector、memory、MCP、Modulith observability 依實際有效依賴啟用範圍，確認無意外 DDL、連線或外部 API。未引入的功能記錄為未引入，勿以移除依賴作唯一建議。
7. **前端/資料**：驗編號 SQL 初始化與 conversation ownership；前端實際 lockfile/Node/Angular/CopilotKit 精確版本與 peer support、真瀏覽器驗收。Security/OAuth2、Liquibase、MCP、Modulith 不列為本 MVP pass gate。

## 官方來源（瀏覽日期 2026-11-10）

- Boot: [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Dev Services](https://docs.spring.io/spring-boot/reference/features/dev-services.html), [Compose how-to](https://docs.spring.io/spring-boot/how-to/docker-compose.html), [Security](https://docs.spring.io/spring-boot/reference/web/spring-security.html), [OTel](https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html)
- Spring AI: [Version support](https://docs.spring.io/spring-ai/reference/getting-started.html), [ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html), [Google chat](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html), [Google embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html), [PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html), [Chat memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html), [MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html), [MCP Security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html), [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- Modulith: [2.1.1 reference/BOM](https://docs.spring.io/spring-modulith/reference/index.html), [properties/modules](https://docs.spring.io/spring-modulith/reference/appendix.html), [observability](https://docs.spring.io/spring-modulith/reference/production-ready.html), [release history](https://github.com/spring-projects/spring-modulith/releases)
- Security/Gradle: [JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [Gradle 9.8.2](https://docs.gradle.org/9.8.2/release-notes.html), [Boot plugin 4.1.2](https://plugins.gradle.org/plugin/org.springframework.boot/4.1.2), [Dependency Management 1.1.7](https://plugins.gradle.org/plugin/io.spring.dependency-management/1.1.7), [CycloneDX 3.5.1](https://plugins.gradle.org/plugin/org.cyclonedx.bom/3.5.1)
- Framework/frontend: [WebMVC async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html), [CopilotKit Angular API](https://docs.copilotkit.ai/reference/angular), [CopilotChatView](https://docs.copilotkit.ai/reference/angular/components/CopilotChatView)

### 必讀官方文件紀錄與版本差異

2026-11-10 實際瀏覽使用者指定的 [Spring AI 官方首頁](https://docs.spring.io/spring-ai/reference/index.html)、[Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)、[ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)、[Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)；頁面標示目前 **2.0.2**，而 backend/build.gradle 宣告 **2.0.1**。Getting Started 說 2.0.x 支援 Boot 4.0.x/4.1.x；因此 2.0.2 官方文件可作相鄰 patch 的 API/configuration 參考，不代表專案 2.0.1 已解析、編譯或實測相容。特別是 2.0.2 Chat Memory 中「JDBC repository 不支援 tool-call messages」的敘述只標為 2.0.2，不推定 2.0.1 相同。此差異交由 G0 dependency resolution 與後續 `tested` 驗收，不因此自行升版。
