# 後端開發手冊

## 本機啟動

使用 Java 25 和專案的 Gradle Wrapper。啟動前確認 Docker daemon 可用，且 `docker compose version` 顯示 2.2.0 以上。從 `backend/` 執行以下命令，使用 Ollama 作為本機開發的聊天模型。

```sh
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev --spring.ai.model.chat=ollama --management.endpoint.health.probes.enabled=true'
```

使用 Gemini 時將模型選擇改為 `google-genai`，並依 README 設定本機金鑰。需要同時開啟 Angular 時，從專案根目錄執行 `./dev.sh`。 [模型與完整開發環境](../README.md)

## Compose 與 PostgreSQL

1. `bootRun` 使用 `main` 的 runtime classpath，其中包含開發用的 `spring-boot-docker-compose`。從 `backend/` 啟動時，Spring Boot 會找到 `compose.yaml`。`dev` profile 包含 `local`，後者啟用 Compose 並依模型選擇啟用對應的 Compose profile。 [Gradle 設定](build.gradle) · [Spring 設定](src/main/resources/application.yaml) · [本機設定](src/main/resources/application-local.yaml)
2. 本專案在 `dev` 設定 `spring.docker.compose.start.skip=never`，所以每次執行 `bootRun` 都會呼叫 `docker compose up`。`lifecycle-management=start-only` 讓容器在後端退出後繼續運行。需停止容器時，從 `backend/` 執行 `docker compose --profile ollama stop`。 [開發環境設定](config/application-dev.yaml)
3. `pgvector` 的 `org.springframework.boot.service-connection=postgres` label 讓 Spring Boot 以映射到主機的連接埠建立 PostgreSQL JDBC service connection。現有 PostgreSQL 容器沒有 `healthcheck`，Spring Boot 會用內建的 TCP readiness 檢查等待映射埠可連線。 [Compose 定義](compose.yaml) · [官方 Compose 支援](https://docs.spring.io/spring-boot/4.2/reference/features/dev-services.html#features.dev-services.docker-compose)

啟動完成時，`curl -fsS http://127.0.0.1:8080/actuator/health/readiness` 應回傳 `UP`。若初始化失敗，先看 Spring Boot 啟動日誌。容器或連線問題用 `docker compose ps` 與 `docker compose logs pgvector` 定位，遷移問題用 Liquibase 日誌中的 changeset ID 與 PostgreSQL 的 `databasechangelog` 定位。

## Liquibase 遷移

- `src/` 使用標準的 `main` 和 `test`。應用依賴 `spring-boot-starter-liquibase`，Spring Boot 在初始化 context 時使用主要 `DataSource` 執行 changelog。Compose 準備本機資料庫連線，Liquibase 套用尚未執行的 changeset。 [Gradle 設定](build.gradle) · [官方資料庫初始化](https://docs.spring.io/spring-boot/4.2/how-to/data-initialization.html#howto.data-initialization.migration-tool.liquibase)
- Schema、必要回填、預設 Bot 與參照定價由同一份 changelog 管理。新增 changeset 時，保留已套用的 changeset 路徑、ID、作者與內容。開發定價使用 `dev` context，預設使用 `prod` context。測試專用資料留在 `src/test`。 [主 changelog](src/main/resources/db/changelog/db.changelog-master.yaml) · [Liquibase contexts](https://docs.liquibase.com/community/reference-guide-5-0/changelog-attributes/what-are-contexts)
- 首次部署以新 PostgreSQL 為前提。部署環境提供連線及遷移所需權限，應用啟動時套用 changeset。測試使用隔離的 PostgreSQL 與 Fake 模型。 [資料庫啟動與測試](../README.md#database-startup)

## 功能回歸測試設計

新增或遷入一般後端功能整合測試時，在測試類別標註 `@BackendIntegrationTest`。啟動冒煙、對話與 Run、一般用量與成本案例已採用共用環境。既有專項仍有其他啟動設定，全套收斂與效能驗收在 #95 追蹤。 [共用測試註解](src/test/java/io/github/samzhu/bot/BackendIntegrationTest.java) · [#92](https://github.com/samzhu/bot/issues/92) · [#93](https://github.com/samzhu/bot/issues/93) · [#94](https://github.com/samzhu/bot/issues/94) · [#95](https://github.com/samzhu/bot/issues/95)

本機開發與預設回歸直接驗證一般 Bot 合約及 GKE 未連線行為，無須啟用 GKE 模擬器。各部署位置沿用同一應用套件、部署設定介面與應用程式路徑，服務連線等設定由執行環境注入。2026-11-26 查核。 [Twelve-Factor Config](https://12factor.net/config) · [Dev/prod parity](https://12factor.net/dev-prod-parity)

### 測哪一層

- 純計算及輸入輸出規則直接用 JUnit 驗證。需要驗證公開 API、交易、SQL 或持久狀態時，從真 HTTP 呼叫 GraphQL 或 AG-UI/SSE，並視需要查核真 PostgreSQL 的結果。
- 功能整合測試只替換會呼叫外部 LLM 的 model Bean。讓測試模型回傳受控的普通回應、串流與用量資料，以實際的應用 Bean 驗證用量擷取、成本計算和持久化。每個案例開始前重設模型狀態，結束時確認該案例的串流與背景工作已完成。
- 用量與成本依 API 已定義的輸入與計算規則驗證。用明確輸入在 API 或純計算測試涵蓋缺少匹配定價的結果。共用資料庫維持正常參照資料。功能回歸範圍以產品行為為準，空庫、舊版遷移、profile 切換、重啟和多實例等環境情境由相應變更個別驗收。

### 如何使用框架

1. **JUnit 與 Gradle**負責案例執行。`@BackendIntegrationTest` 固定 `test` profile、loopback `RANDOM_PORT`、classpath 設定與共用 imports。透過 `@LocalServerPort` 取得 HTTP 埠。需要控制模型時注入 `BackendTestChatModel`。Spring TestContext 在同一測試 JVM 依設定快取 context。個別 properties、動態設定或 Bean 覆寫可能改變快取鍵，案例差異透過輸入和 fixture 表達。 [共用 Run 測試](src/test/java/io/github/samzhu/bot/SharedRunHttpIntegrationTests.java) · [Spring Boot 測試環境](https://docs.spring.io/spring-boot/4.2/reference/testing/spring-boot-applications.html) · [Spring TestContext 快取](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
2. **Testcontainers 與 Liquibase**負責資料庫。沿用 Spring 管理的 PostgreSQL 容器 Bean 與 `@ServiceConnection`。第一個案例啟動 context 時，Boot 先啟動容器，由 Liquibase 初始化共用庫。後續案例重用該 context 和資料庫，正常退出時由 Spring 關閉相依 Bean 再停止容器。 [Spring Boot 容器生命週期](https://docs.spring.io/spring-boot/4.2/reference/testing/testcontainers.html#testing.testcontainers.lifecycle) · [Liquibase 啟動初始化](https://docs.spring.io/spring-boot/4.2/how-to/data-initialization.html#howto.data-initialization.migration-tool.liquibase)
3. **案例 fixture**負責資料隔離。以案例專屬的 Workspace、Conversation、Run 或測試使用者限定寫入、讀取與清理範圍。分頁與排序在伺服器完成，因此案例隔離必須在查詢範圍生效。真 HTTP 的伺服器交易會獨立提交，測試端 rollback 只涵蓋參與同一交易的操作。HTTP 案例使用已提交的 fixture 與限定範圍的清理。 [Spring Boot HTTP 測試交易](https://docs.spring.io/spring-boot/4.2/reference/testing/spring-boot-applications.html)

### 如何執行與判讀結果

- 從 `backend/` 用 `./gradlew test --tests 'TestClass.testMethod' --fail-fast` 定向重現。完整驗收使用 `./gradlew test`。記錄測試範圍與結果，涉及共用資料時再換序執行，確認案例可獨立通過。 [Gradle 測試篩選與 fail-fast](https://docs.gradle.org/current/userguide/java_testing.html)
- 驗收共用環境時，以 Spring cache 日誌、容器建立事件及資料庫識別確認整輪功能整合測試只啟動一個 context 和 PostgreSQL 容器。用相同測試選集與資源設定比較耗時。快取與 heap 上限的現況以 Gradle 設定為準。快取上限只控制同時保留的 context 數。若測試中斷後容器仍在執行，使用 Docker 的 `org.testcontainers.sessionId` 標籤確認歸屬，再檢查該輪測試程序。 [Gradle 設定](build.gradle) · [Spring 快取觀測](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)

## 遷移與啟動變更驗收

修改遷移或開發啟動設定時，用隔離的空 PostgreSQL 驗證首次啟動成功，確認 `databasechangelog` 記錄預期 changeset，預設 Bot 與定價符合使用中的 Liquibase context。以同一資料庫重啟，確認 readiness 再次為 `UP`、已套用的 changeset 沒有重跑，且既有資料仍可讀取。

Liquibase 前置提交驗收中，以下四個 `ReleaseReadinessIntegrationTests` 方法與兩個 PostgreSQL 註解測試在隔離的 pgvector／PostgreSQL 16 通過。四個啟動案例已自預設功能回歸移出。後續變更啟動或遷移時，沿用各案例的證據與條件。 [前置提交的啟動驗收案例](https://github.com/samzhu/bot/blob/478682b2c96982b1ffb3ce81e52559ef1a84aa0b/backend/src/test/java/io/github/samzhu/bot/ReleaseReadinessIntegrationTests.java) · [前置提交驗收紀錄](https://github.com/samzhu/bot/issues/92#issuecomment-7182319010)

- `springBootMigratesEmptyDatabaseAndRestartPreservesReferenceData` 驗證空庫 readiness、預設 Bot、正式定價、changeset ID／checksum／執行時間，以及重啟後保留修改。
- `serviceRestoresConfiguredRevisionEvenWhenAnotherRevisionIsNewer` 驗證較新的版本存在時，啟動仍採用指定定價。
- `serviceRejectsUnpublishedConfiguredCatalogWithoutWritingIt` 驗證缺少指定定價時拒絕啟動，且不會自動補寫。
- `retiredCatalogConfigurationIsRejectedByService` 驗證服務拒絕已退役的定價設定。

前置驗收也核對了 19 筆 changeset 的 ID、作者、路徑、執行類型，以及重跑前後的資料表與欄位註解。共用回歸中的 `PostgresCommentsIntegrationTests` 查核目前 schema 的註解。歷史 changeset 與遷移重跑由遷移驗收處理。 [前置提交的歷史驗收案例](https://github.com/samzhu/bot/blob/478682b2c96982b1ffb3ce81e52559ef1a84aa0b/backend/src/test/java/io/github/samzhu/bot/PostgresCommentsIntegrationTests.java) · [共用註解測試](src/test/java/io/github/samzhu/bot/PostgresCommentsIntegrationTests.java)

2026-11-26 查核 Spring Boot 4.2.0 官方文件。Compose 啟停、profile 與等待條件以專案設定為準。 [Gradle bootRun](https://docs.spring.io/spring-boot/4.2/gradle-plugin/running.html) · [Compose 設定屬性](https://docs.spring.io/spring-boot/4.2/appendix/application-properties/index.html#application-properties.docker-compose)
