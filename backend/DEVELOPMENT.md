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

## 驗收

- 修改一般後端行為時，從 `backend/` 執行 `./gradlew test`，確認相關公開介面與測試通過。
- Gradle 測試將 Spring TestContext 快取上限設為 4，測試 JVM heap 上限設為 1536 MiB。Spring 管理的 PostgreSQL Testcontainers 會隨各自的 application context 存活，快取淘汰時會關閉 context 並停止容器。若測試中斷後容器仍在執行，先用 Docker 的 `org.testcontainers.sessionId` 標籤確認歸屬，再檢查該輪的 Gradle 測試程序。 [Spring Boot 容器生命週期](https://docs.spring.io/spring-boot/4.2/reference/testing/testcontainers.html#testing.testcontainers.lifecycle) · [Spring TestContext 快取](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- 修改遷移或開發啟動設定時，用隔離的空 PostgreSQL 驗證首次啟動成功，`databasechangelog` 記錄預期 changeset，預設 Bot 與定價符合使用中的 context。用同一資料庫重啟，確認 readiness 再次為 `UP`、已套用的 changeset 沒有重跑，既有資料仍可讀取。

2026-11-26 查核 Spring Boot 4.2.0 官方文件。Compose 啟停、profile 與等待條件以專案設定為準。 [Gradle bootRun](https://docs.spring.io/spring-boot/4.2/gradle-plugin/running.html) · [Compose 設定屬性](https://docs.spring.io/spring-boot/4.2/appendix/application-properties/index.html#application-properties.docker-compose)
