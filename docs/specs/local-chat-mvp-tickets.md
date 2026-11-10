# Local Chat MVP 垂直 Tracer-Bullet 票單

> Parent Issue: [#1 (Local Chat MVP：串流聊天與持久化歷史)](https://github.com/samzhu/bot/issues/1)
> 依據 `to-tickets` 規範產出，嚴格拆分為貫穿全層（Schema、API、UI、測試）的垂直切片，不拆純前端或純後端水平票。
> 狀態：已核准並發布為 GitHub Native Sub-issues (`ready-for-agent`)。
> 注意：本票單遵循 `to-tickets` 與 `to-spec` 規範，內文不包含具體檔案路徑與程式碼區塊。

---

## 票單相依圖（DAG）

```
[Issue #2: 01 本機聊天最小閉環] (已完成)
       ├──> [Issue #3: 02 多輪歷史與停止生成] (已完成)
       └──> [Issue #4: 03 重送與連線異常恢復] (已完成) ──> [Issue #5: 04 真模型設定與交付驗收] (已完成待交付)
```

---

## 票單清單

### Ticket 1: 本機聊天最小閉環 (Local Chat Minimal Loop)
- **GitHub Issue**：[#2 (Local Chat MVP 01：本機聊天最小閉環)](https://github.com/samzhu/bot/issues/2)
- **識別代號**：`01-local-chat-minimal-loop`
- **相依阻擋**：None（已完成）
- **狀態**：已驗收完成 (`completed`)
- **使用者可見交付**：
  使用者開啟本機聊天介面，可見預先植入的助理機器人名稱與乾淨的白底佈局。點擊「開啟新對話」按鈕後，系統於本機身分下建立新的交談階段。於輸入框輸入文字並送出，介面隨即建立即時串流並流式渲染模型回應增量。串流完成後，完整對話紀錄與回合資料保存至資料庫與 Session Memory，重新整理瀏覽器頁面能完整重新載入歷史對話與訊息泡泡。
- **驗收準則**：
  - [x] 開啟 Web 介面能顯示預設助理機器人名稱與乾淨白底佈局。
  - [x] 點擊「開啟新對話」能透過 GraphQL Mutation 建立新對話並綁定至伺服器固定開發身分。
  - [x] 送出訊息能以串流方式即時渲染文字增量至前端介面。
  - [x] 串流事件以序號 1 之啟動事件開始，且文字增量事件序號嚴格遞增連續。
  - [x] 收到包含已持久化助理訊息識別碼的完成事件。
  - [x] 重新整理瀏覽器頁面能正確檢索並還原該對話之使用者與助理訊息。
  - [x] 資料庫中正確記錄對話、使用者訊息、助理訊息與完成狀態之執行紀錄。
  - [x] Session Memory 正確寫入使用者與助理訊息對，供後續對話檢索。

---

### Ticket 2: 多輪歷史與停止生成 (Multi-turn History & Stop Generation)
- **GitHub Issue**：[#3 (Local Chat MVP 02：多輪歷史與停止生成)](https://github.com/samzhu/bot/issues/3)
- **識別代號**：`02-multi-turn-history-and-stop-generation`
- **相依阻擋**：[#2 (Local Chat MVP 01：本機聊天最小閉環)](https://github.com/samzhu/bot/issues/2) (已完成)
- **狀態**：已驗收完成 (`completed`)
- **使用者可見交付**：
  使用者在同一對話中進行多輪問答時，系統自動將前次回合歷史納入模型提示詞上下文，範圍限定為取自 Session Memory 的最近 10 個完整成功回合。當模型正在串流輸出時，輸入框送出按鈕自動切換為「停止生成」按鈕；使用者點擊後立即發送協同取消請求。真正完成與取消之並發競爭由資料庫 CAS 先成功者唯一勝出，結果可能為 COMPLETED 亦可能為 CANCELLED；已 COMPLETED 之任務不被事後取消覆寫，前端停止生成操作僅在取消贏得 CAS 競爭時發送 cancelled 事件。取消前已產生的片段文字被保留於對話紀錄中並標記中斷狀態，且該中斷回合自後續模型提示詞中排除。重複發送取消操作具備冪等性並回傳權威終態。
- **驗收準則**：
  - [x] 多輪對話中，後續提問包含取自 Session Memory 的前次對話紀錄，最長包含 10 個完整回合。
  - [x] 串流生成過程中，輸入區顯示可點擊的停止生成控制按鈕。
  - [x] 點擊停止生成能中斷串流，且僅在取消贏得 CAS 競爭時發送 cancelled 事件。
  - [x] 真正完成與取消之並發競爭由資料庫 CAS 確定性裁決；已 COMPLETED 之任務不被事後覆寫為 CANCELLED。
  - [x] 中斷前生成的片段文字被保留並帶有中斷屬性與對應視覺標記。
  - [x] 被取消的中斷回合自動從後續模型提示詞的 Session Memory 上下文中排除。
  - [x] 重複點擊取消操作維持冪等成功，並回傳真實的最終狀態。
  - [x] 訊息與對話清單支援預設 20 筆、上限 100 筆之有界分頁檢索。

---

### Ticket 3: 重送與連線異常恢復 (Retry & Connection Anomaly Recovery)
- **GitHub Issue**：[#4 (Local Chat MVP 03：重送與連線異常恢復)](https://github.com/samzhu/bot/issues/4)
- **識別代號**：`03-retry-and-connection-recovery`
- **相依阻擋**：[#2 (Local Chat MVP 01：本機聊天最小閉環)](https://github.com/samzhu/bot/issues/2) (已完成), [#3 (Local Chat MVP 02：多輪歷史與停止生成)](https://github.com/samzhu/bot/issues/3) (已完成)
- **狀態**：已驗收完成 (`completed`)
- **使用者可見交付**：
  當使用者遇到網路連線異常或連點送出按鈕時，系統提供具前端提示的自我修復與確定性復原。以相同請求識別碼與相同內容重送時，伺服器立即回傳 JSON 執行中介資料而不重放串流，且前端不重複產生訊息泡泡；若內容衝突則回傳 409 衝突拒絕並彈出前端錯誤提示。當使用者在同一對話已有任務進行時發起新任務，系統依據單一活躍任務規則回傳 409 衝突拒絕。當連線中斷或錯過終態事件時，前端可於重新連線後透過 GraphQL 權威 run 查詢自動恢復本機狀態。若串流遇到斷線、逾時或模型內部錯誤，伺服器安全中止上游，片段文字必須存在於資料庫並標註中斷。所有並發競爭使用受控發布者與鎖存器驗證而非任意等待。伺服器重啟時自動將所有殘留活躍任務收斂為失敗。
- **驗收準則**：
  - [x] 相同請求識別碼與相同訊息內容重送時，回傳包含執行中介資料之 JSON 回應而非串流，前端不重複新增訊息。
  - [x] 相同請求識別碼但不同訊息內容重送時，回傳 409 衝突拒絕，前端呈現錯誤提示。
  - [x] 同一對話中存在執行中任務時發起新任務，回傳 409 衝突拒絕。
  - [x] 並發重送在資料庫唯一約束衝突時能自動自包裝異常中復原重查，並回傳正確中介資料。
  - [x] 並發競爭與取消時序以受控 Reactive 發布者與鎖存器驗證，不使用任意休眠。
  - [x] 遇到模型錯誤時發送失敗事件，並將資料庫狀態標記為失敗，不污染 Session Memory。
  - [x] 客戶端意外斷線時，伺服器自動中止生成並更新狀態為已取消，片段文字嚴格斷言存在於資料庫中。
  - [x] 串流逾時觸發確定性失敗終態轉換與逾時原因註記。
  - [x] 若 Session Memory 寫入失敗，整筆終態短交易完全復原並於獨立交易記錄失敗。
  - [x] 連線中斷恢復後，客戶端能透過 GraphQL 查詢 run 識別碼取得權威終態並同步介面。
  - [x] 伺服器重啟時自動將殘留活躍任務標記為失敗並記錄更新時間。

---

### Ticket 4: 真模型設定與交付驗收 (Real Model Configuration & Delivery Acceptance)
- **GitHub Issue**：[#5 (Local Chat MVP 04：真模型設定與交付驗收)](https://github.com/samzhu/bot/issues/5)
- **識別代號**：`04-real-model-config-and-delivery-acceptance`
- **相依阻擋**：[#3 (Local Chat MVP 02：多輪歷史與停止生成)](https://github.com/samzhu/bot/issues/3) (已完成), [#4 (Local Chat MVP 03：重送與連線異常恢復)](https://github.com/samzhu/bot/issues/4) (已完成)
- **狀態**：已完成待交付 (`ready-for-review`)
- **使用者可見交付**：
  開發者可透過指定 Profile 與設定檔切換至真實雲端模型進行端到端對話。模型名稱直接於組態檔中指定，無需額外環境變數。若未提供憑證或聊天模型名稱，系統於啟動時立即 Fail-Fast 報錯。提供獨立測試驗證與外部供應商之聊天雙向通訊，並完成本機瀏覽器端到端驗收流程與標準本機開發啟動說明文件。語音與嵌入向量功能嚴格維持 MVP 範圍外，本票不要求啟用或測試。
- **驗收準則**：
  - [x] 啟用真實模型 Profile 時應用程式能成功啟動並載入供應商聊天模型組件，不載入 Mock。
  - [x] 未設定 API 憑證或聊天模型名稱時啟動立即觸發 Fail-Fast 阻擋並輸出清楚指引。
  - [x] 聊天模型名稱直接自 YAML 設定檔解析（Chat 模型為 `gemini-3.8-flash`）。
  - [x] 代理不得檢視或輸出秘密內容；應用程式正常載入私有組態可用於明確啟用的 Smoke 測試。
  - [x] 提供獨立 Smoke 驗證測試，僅針對聊天模型確保與外部供應商完成真實網路串流往返。
  - [x] 完成完整本機瀏覽器端到端驗收（新對話、多輪對話、取消、重整還原）。
  - [x] 提供精確標準的專案本機開發啟動指令與文件。

---

## 完整交接與當前狀態

### 1. 目前編譯與測試真實狀態
- **後端狀態**：
  - 編譯：`./gradlew compileJava compileTestJava` 通過（Exit Code 0）。
  - 全量離線整合測試：`./gradlew test` 共 31 項測試全數通過（31 passed, 0 skipped, 0 failed，含 23 項端到端 API 契約、4 項隔離 real-ai fail-fast、3 項 ProfileGuard、1 項 Spring Context）。
  - 獨立真模型煙測：`./gradlew realAiSmokeTest`（1 passed, 0 skipped, 0 failed，確認 GoogleGenAiChatModel 實例，配置模型為 `gemini-3.8-flash`，即時外部雲端推理取得 `'PONG'`，嚴格序號、單一 Consistent `runId`、DB/Session 一致性均通過，`outputs.upToDateWhen { false }` 確保無快取假測）。
- **前端狀態**：
  - 建置：`npm run build` 通過（Exit Code 0，初始 bundle 4.87 MB < 5.5 MB 預算，組件樣式 7.07 kB < 8 kB 預算）。
  - 單元測試套件：`npm test` 共 16 項測試全數通過（3 項 Vitest 前端組件測試含真實剪貼簿複製行為 + 13 項 SSE Parser 嚴格串流解析測試）。
  - 真 Browser 自動化：
    - `verify-ticket-01.mjs`（最小閉環串流與重載還原）通過。
    - `verify-ticket-02.mjs`（IME composition guard、Shift+Enter 換行、協同 Stop 取消、非首頁 deep conv 還原、22 輪 44 筆訊息分頁對齊）通過。
    - `verify-ticket-03.mjs` 全 6 項情境通過（Partial Error Failed Badge、In-Flight Disconnect 無 reload 自動恢復、Lost-Response 200 JSON 去重、Before-Persist 重試保留 temp ID 對齊 DB、Zero-Delta 取消無 ghost assistant row、Real 409 衝突拒絕無 retry-btn 與下一輪正常串流）。
    - `verify-clipboard.mjs` 通過（真實 Chrome CDP 授權剪貼簿讀寫、按鈕「Copied!」狀態切換、逾時還原）。
- **已驗收票單**：
  - Issue #2 (Ticket 01：本機聊天最小閉環) - 已交付關閉
  - Issue #3 (Ticket 02：多輪歷史與停止生成) - 已交付關閉
  - Issue #4 (Ticket 03：重送與連線異常恢復) - 已交付關閉
  - Issue #5 (Ticket 04：真模型設定與交付驗收) - 已完成待交付審核
- **Parent Issue 狀態**：
  - Issue #1（Local Chat MVP：串流聊天與持久化歷史）維持 **OPEN** 作為整體功能追蹤。
