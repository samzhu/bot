# 環境基線與實測證據 (Environment Baseline)

> 紀錄時間：2026-11-10T15:15:30+08:00  
> 執行者：agy  
> 說明：本文件保留本機只讀盤點、Git 遠端探查、GitHub CLI 盤點、Docker Server 狀態與 Gradle dry-run 解析驗證之原始命令輸出，作為 G0 階段客觀證據，避免證據只留於終端。

---

## 1. 系統環境盤點原始輸出

### 1.1 命令執行記錄
```bash
echo "=== Git Root & Status ==="
git rev-parse --show-toplevel 2>&1 || true
git status 2>&1 || true

echo "=== Git Remote Check (git@github.com:samzhu/bot.git) ==="
git ls-remote git@github.com:samzhu/bot.git 2>&1 || true

echo "=== Environment Check ==="
echo "Java:"
java -version 2>&1 || true
echo "Gradle Wrapper / Gradle:"
./gradlew -version 2>&1 || gradle -version 2>&1 || true
echo "Node / NPM / Pnpm:"
node -v 2>&1 || true
npm -v 2>&1 || true
pnpm -v 2>&1 || true
echo "Git:"
git --version 2>&1 || true
```

### 1.2 執行結果原始文字
```text
=== Git Root & Status ===
致命錯誤: 不是一個 git 版本庫（或者任何親代目錄）：.git
致命錯誤: 不是一個 git 版本庫（或者任何親代目錄）：.git

=== Git Remote Check (git@github.com:samzhu/bot.git) ===
43db8a93d4df5fed13e1c34c28d44c31136e73ba	HEAD
43db8a93d4df5fed13e1c34c28d44c31136e73ba	refs/heads/main

=== Environment Check ===
Java:
openjdk version "25.0.1" 2025-10-21 LTS
OpenJDK Runtime Environment (build 25.0.1+11-LTS)
OpenJDK 64-Bit Server VM (build 25.0.1+11-LTS, mixed mode, sharing)

Gradle Wrapper / Gradle:
zsh:11: no such file or directory: ./gradlew

------------------------------------------------------------
Gradle 9.1.0
------------------------------------------------------------

Build time:    2025-09-18 13:05:56 UTC
Revision:      e45a8dbf2470c2e2474ccc25be9f49331406a07e

Kotlin:        2.2.0
Groovy:        4.0.28
Ant:           Apache Ant(TM) version 1.10.15 compiled on August 25 2024
Launcher JVM:  25.0.1 (BellSoft 25.0.1+11-LTS)
Daemon JVM:    /Users/samzhu/.sdkman/candidates/java/25.0.1-librca (no JDK specified, using current Java home)
OS:            Mac OS X 27.2 aarch64

Node / NPM / Pnpm:
v26.1.0
11.13.0
zsh:18: command not found: pnpm

Git:
git version 2.55.0
```

---

## 2. Docker Daemon 實測檢查（證明 Server 未運行）

### 2.1 執行命令
```bash
docker version 2>&1 || true
```

### 2.2 原始輸出
```text
Client:
 Version:           29.8.2
 API version:       1.56
 Go version:        go1.26.8
 Git commit:        7fc2dff
 Built:             Sun Oct 11 12:42:20 2026
 OS/Arch:           darwin/arm64
 Context:           orbstack
failed to connect to the docker API at unix:///Users/samzhu/.orbstack/run/docker.sock; check if the path is correct and if the daemon is running: dial unix /Users/samzhu/.orbstack/run/docker.sock: connect: no such file or directory
Server:
```

> **客觀證據結論**：雖然 Docker Client (29.8.2) 安裝存在，但本機 Docker daemon（OrbStack）目前處於**未運行（Daemon stopped / socket not found）**狀態。在啟動 Spring Boot Docker Compose 或進行容器測試前，必須先啟動本機 Docker daemon。

---

## 3. GitHub CLI (gh) 遠端唯讀盤點輸出

### 3.1 執行命令
```bash
gh auth status
gh repo view samzhu/bot
gh label list --repo samzhu/bot
gh issue list --repo samzhu/bot --state all
```

### 3.2 原始輸出
```text
=== GitHub CLI Auth Status ===
github.com
  ✓ Logged in to github.com account samzhu (keyring)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_************************************
  - Token scopes: 'gist', 'read:org', 'repo'

=== GitHub Repo Info (samzhu/bot) ===
samzhu/bot
No description provided
This repository does not have a README
View this repository on GitHub: https://github.com/samzhu/bot

=== GitHub Repo Labels (samzhu/bot) ===
NAME              DESCRIPTION                                 COLOR  
bug               Something isn't working                     #d73a4a
accessibility     Barrier affecting people with disabilities  #f143ab
documentation     Improvements or additions to documentation  #0075ca
duplicate         This issue or pull request already exists   #cfd3d7
enhancement       New feature or request                      #a2eeef
good first issue  Good for newcomers                          #7057ff
help wanted       Extra天 attention is needed                 #008672
invalid           This doesn't seem right                     #e4e669
question          Further information is requested            #d876e3
wontfix           This will not be worked on                  #ffffff

=== GitHub Repo Issues (samzhu/bot) ===
no issues match your search in samzhu/bot
```

> **分析結論**：
> - 經 `git ls-remote` 證實遠端 `samzhu/bot` 已經有既有提交與分支（`43db8a93d4df5fed13e1c34c28d44c31136e73ba refs/heads/main`）。
> - 經 `gh repo view` 欄位確認：倉庫目前無 description、無 README、無現存 issue，具備 GitHub 預設 10 個標籤（尚無 `ready-for-agent` 標籤）。
> - 遵照指示，目前**不發布 Issue**。

---

## 4. Gradle Wrapper 下載與就緒驗證

### 4.1 執行命令
```bash
cd backend && ./gradlew -v
```

### 4.2 原始輸出
```text
Fetching distribution.
Downloading https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
..............10%..............20%...............30%..............40%...............50%..............60%...............70%..............80%...............90%..............100%

Welcome to Gradle 9.8.2!

------------------------------------------------------------
Gradle 9.8.2
------------------------------------------------------------

Build time:    2026-10-19 16:29:26 UTC
Revision:      8de7bcf76e785507ad92f0741ab9235a430c95e6

Kotlin:        2.4.10
Groovy:        4.0.33
Ant:           Apache Ant(TM) version 1.10.17 compiled on April 6 2026
Launcher JVM:  25.0.1 (BellSoft 25.0.1+11-LTS)
Daemon JVM:    /Users/samzhu/.sdkman/candidates/java/25.0.1-librca (no Daemon JVM specified, using current Java home)
OS:            Mac OS X 27.2 aarch64
```

---

## 5. Java Toolchain 解析失敗原始證據

### 5.1 執行命令
```bash
cd backend && ./gradlew --dry-run
```

### 5.2 原始輸出
```text
Starting a Gradle Daemon (subsequent builds will be faster)

FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring root project 'bot'.
> Failed to calculate the value of task ':compileTestJava' property 'javaCompiler'.
   > Cannot find a Java installation on your machine (Mac OS X 27.2 aarch64) matching: {languageVersion=27, vendor=any vendor, implementation=vendor-specific, nativeImageCapable=false}. Toolchain download repositories have not been configured.

* Try:
> Learn more about toolchain auto-detection and auto-provisioning at https://docs.gradle.org/9.8.2/userguide/toolchains.html#sec:auto_detection.
> Learn more about toolchain repositories at https://docs.gradle.org/9.8.2/userguide/toolchains.html#sub:download_repositories.

BUILD FAILED in 32s
```

> **客觀證據結論**：Gradle toolchain 缺少 resolver plugin，且環境未配置 download repositories，因此 Gradle **不會且無法自動下載 JDK 27**。G0 階段目前狀態確認為 **BLOCKED**。
