# 環境基線與實測證據 (Environment Baseline)

> **最新版本與覆蓋決策宣告（2026-11-10）**：
> - Spring AI BOM 更新為 `2.1.0-RC2`（舊有 2.0.1 決策標註為**歷史參考**）。
> - 顯式宣告兩項 Google GenAI 語音依賴：`org.springframework.ai:spring-ai-google-genai-transcription:2.1.0-RC2` 與 `org.springframework.ai:spring-ai-starter-model-google-genai-tts:2.1.0-RC2`。
> - 語音 UI 與功能仍屬後續提案，MVP 暫不實作。語音介面為標準抽象 TextToSpeechModel 與 TranscriptionModel。依官方文件（https://docs.spring.io/spring-ai/reference/2.1/api/audio/speech/google-genai-speech.html），TTS auto-config 於 local 關閉之精確 property 唯一為 `spring.ai.model.audio.speech=none`；註記此 property 僅關閉 speech model，不保證 connection auto-config 消失，尚未實測，未用功能暫不立即實作配置程式碼。
> 紀錄時間：2026-11-10T15:45:30+08:00
> 執行者：agy
> 說明：本文件保留本機只讀盤點、Git 遠端探查、GitHub CLI 盤點、Docker Server 狀態、SDKMAN Java 27 安裝實測與 Gradle dry-run 解析驗證之原始命令輸出，作為 G0 階段客觀證據，避免證據只留於終端。

---

## 1. 系統環境盤點現況

- **Java**：OpenJDK 25.0.1 (BellSoft 25.0.1+11-LTS, aarch64)，路徑 `/Users/samzhu/.sdkman/candidates/java/25.0.1-librca`；全新 shell `sdk current java` 驗證仍為 `25.0.1-librca`。
- **Gradle**：`backend/gradle/wrapper/gradle-wrapper.properties` 指定 Gradle 9.8.2；經 `./gradlew -v` 實測支援 Java 27。
- **Docker**：Docker version 29.8.2 (OrbStack context)。Server (daemon) 目前未運行（socket 斷開）。
- **Node / NPM**：Node v26.1.0, npm 11.13.0。
- **Git**：git version 2.55.0；本地 `main` 分支已安全銜接遠端 `samzhu/bot` 之 `origin/main`（commit: `612ab20`）。

---

## 2. SDKMAN Java 27 安裝實測客觀證據

### 2.1 執行安裝指令與 URL 檢查
```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 27.0.1.0+11-librca
sdk install java 27.0.1.0-fx+11-librca

# 檢查 URL 與 %2B 編碼端點狀態
curl -s -I "https://api.sdkman.io/2/broker/download/java/27.0.1.0+11-librca/darwinarm64"
curl -s -I "https://api.sdkman.io/2/broker/download/java/27.0.1.0%2B11-librca/darwinarm64"
curl -s -I "https://api.sdkman.io/2/broker/download/java/27.0.1.0-fx%2B11-librca/darwinarm64"
```

### 2.2 原始輸出與實測事實
```text
Downloading: java 27.0.1.0+11-librca
Repackaging Java 27.0.1.0+11-librca...
mv: rename /Users/samzhu/.sdkman/tmp/out/* to /Users/samzhu/.sdkman/tmp/Java-27.0.1.0+11-librca: No such file or directory
Stop! The archive was corrupt and has been removed! Please try installing again.

HTTP/1.1 404 Not Found (上述所有端點均返回 404)
```

> **客觀證據結論**：
> - 實測證實**本次 SDKMAN broker 下載端點不可用（返回 404 Not Found）**。
> - 不過度斷言官方二進位釋出狀態（Luna 正查詢官方同 release 下載管道）。
> - 本機未完成 Java 27 安裝與預設切換，全新 shell 經 `sdk current java` 證實預設仍為 `25.0.1-librca`。
> - G0 門禁維持 **BLOCKED**（缺少可用 JDK 27）。

---

## 3. Gradle 依賴解析與 DependencyInsight 實測摘要

### 3.1 執行紀錄 (儲存於 `/tmp/gradle-check/`)
- 命令：`./gradlew dependencies --configuration runtimeClasspath`（Log: `/tmp/gradle-check/runtime-deps.log`）
- 命令：`./gradlew dependencyInsight --dependency google-genai --configuration runtimeClasspath`（Log: `/tmp/gradle-check/insight-google.log`）
- **實測結果**：Exit Code: 1 (FAILED)。
- **失敗原因**：Gradle 9.8.2 在 configuration 階段計算 `:compileTestJava` 任務之 `javaCompiler` 屬性時，因找不到 Java 27 且未配置 toolchain repository 而中斷構建。
- **音訊與 Google GenAI 依賴現狀**：
  - `springAiVersion` 已在 `backend/build.gradle` 更新宣告為 `2.1.0-RC2`。
  - `spring-ai-google-genai-transcription`（底層模組）與 `spring-ai-starter-model-google-genai-tts:2.1.0-RC2`（starter）已在 `build.gradle` 宣告。
  - 完整 dependency tree 需待 JDK 27 取得或 toolchain 解析器配置後方能解析驗證。

---

## 4. Docker Daemon 實測檢查（證明 Server 未運行）

```text
Client:
 Version:           29.8.2
 Context:           orbstack
failed to connect to the docker API at unix:///Users/samzhu/.orbstack/run/docker.sock; check if the path is correct and if the daemon is running: dial unix /Users/samzhu/.orbstack/run/docker.sock: connect: no such file or directory
```

---

## 5. GitHub CLI (gh) 遠端唯讀盤點輸出

- 遠端 `samzhu/bot` 存在，`main` 分支 commit 為 `612ab20`。
- `ready-for-agent` 標籤已建立。
- 規格 Issue [#1](https://github.com/samzhu/bot/issues/1)（標題：`Local Chat MVP：串流聊天與持久化歷史`）已成功發布。
