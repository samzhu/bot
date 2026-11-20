# 核心開發準則與原則

- **先思考**：釐清問題、假設與取捨，先查證再決定。
- **保持簡單**：用足以解決當前需求的最小設計。
- **精準變更**：每項變更可追溯需求，保留無關工作。
- **可驗目標**：定義可觀察的成功條件，以證據驗證。

## 一、研發流程與官方文件優先

1. **查核優先的工作流程**
   - 標準順序：凡涉及套件、API、配置、生命週期、資料模型或打包的技術規劃，循序執行『讀目前程式碼/實際版本 → 上網查對應官方 reference/API/release notes → 必要時對固定 tag 原碼/JAR/types 交叉核對 → 分析取捨 → 規劃/開票 → 實作 → 驗收』。
   - 先查後定：規劃與開票前須開啟並閱讀官方相關章節，搜尋摘要與二手文章僅作索引線索，不算已讀官方；禁止憑既有印象或舊知識先行定案再事後補來源。
   - 思考與查證：先確認目的、可觀察的成功條件、事實與假說及可行取捨；SDK／API 參數與 CLI 工具先核實際版本及官方文件、必要時對照公開 types，再使用。查不到時如實說明，不猜測 API 或參數；可自行查得的資訊先查，已核定的介面與邊界不反覆詢問。
   - 計畫與驗證：多步驟工作列出步驟及各自驗證條件；涉及行為設計的計畫附簡潔流程圖或循序圖（可用 Mermaid），標示邊界、狀態及失敗恢復。文件小修等簡單工作按影響選用，不強制儀式。

2. **版本選型與依賴管理**
   - 穩定優先：新功能套件選型以官方最新穩定版為準。
   - 依賴邊界：專案現有依賴版本或用戶指定之特定 RC 版本優先，不單純為了追求「最新」而自行升級依賴。

3. **事實分流與查核紀律**
   - 來源標記：結論須附上官方來源連結、對應版本及查核日期。
   - 事實分流：明確區分官方事實、專案實證、推論與尚未執行的驗證假說，不將未驗證之推論包裝成既定事實。
   - 誠實記錄落差：文件與套件不一致或查證失敗時，如實記錄取得狀況與版本差異，絕不用他版或未讀來源冒充已驗證。

4. **優先官方能力與註解原則**
   - 善用原生預設：優先採用框架內建能力與官方預設機制，採官方預設前先確認專案有無覆寫，避免重複造輪或開設重複工作。
   - 註解說明動機：需求目的、特殊限制、框架邊界或非直覺的設計取捨，須在有助理解時於相鄰程式碼註解說明「為何這樣做」，不逐行復述「做了什麼」；每行變更須可追溯需求，不等於每行都要註解。
   - 架構決策收納：重大架構決策建立專用決策文件，並在此處提供觸發閱讀之索引指標，不於本規範內堆砌易查版本或腳本細節。

5. **角色協作與技能調用**
   - 職責分工：Root 專注於思考、分析、架構判斷與最終驗收；agy1/agy2/agy3 專注於研究、實作、執行命令與日誌排查。
   - Herdr 工作區：每位代理使用獨立 Tab，不使用分割 Pane；調整既有配置時保留代理會話與工作進度。
   - 查證義務：Root 與 agy1/agy2/agy3 於規劃與實作前均有先查證官方資料之義務；驗收發現缺陷退回修正後須落實複驗。
   - 技能流程：功能開發依下節執行；既有已核准規格、票及驗收邊界直接續作，小型修正依影響選用所需步驟。

### Matt 技能開發流程

適用於需求規劃、Issue 實作及跨票協作。依 Matt 官方技能組合並配合本專案原則；使用者已核定的決策持續有效。技能名稱是閱讀入口，執行前讀實際 `SKILL.md` 與它指向的必要參考檔，不只憑名稱推測流程。

| 階段 | 使用技能與工作 | 完成條件 |
| --- | --- | --- |
| 研究與對齊 | `research` 查目前程式碼、版本與官方文件；需求或領域名詞尚不清楚時使用 `grill-with-docs`，由 `domain-modeling` 維護 GLOSSARY／ADR；介面邊界有疑問時讀 `codebase-design`。 | 已區分來源事實、專案實證與待驗假說；需求及重要取捨可追溯。 |
| 規格 | 使用者要求 `to-spec` 時，綜合既有討論，描述問題、使用者行為、實作決策、範圍及測試邊界，發布 GitHub spec。 | 測試邊界經使用者確認；已核邊界沿用，改變時才確認差異；Issue 使用 `ready-for-agent`。 |
| 拆票 | 使用者要求 `to-tickets` 時，拆成各自貫穿必要資料、API、UI 與測試的可驗收切片，列明真正的阻擋關係。 | 使用者核准粒度與相依後才開票；已有票先核對，避免重複建立或擅改父票。 |
| 派工 | Root 按相依圖指派 blockers 已完成的票；全 spec 的並行協作可參考 `implement-spec`。使用 Herdr 獨立 Tab，明定票、檔案所有權、公開合約、驗收條件與環境 owner，以指標交接。 | 每位代理知道可修改範圍及交付證據；共享檔、Gradle 與服務啟停有唯一協調者。分支／worktree 調整保留既有未提交工作。 |
| 實作 | `implement` 先讀完整票與 comments、說明票名，再用 `tdd` 在已核准的公開介面逐次做「一個行為紅測 → 最小實作轉綠」，避免先批次寫完全部測試。前端修改先依 `modern-web-guidance` 搜尋並讀取適用指南；故障排查可用 `diagnosing-bugs`。 | 失敗確因目標行為而非環境／編譯問題；型別與定向測試持續通過，最終候選跑完整檢查。修後才補的測試如實標記，不聲稱事前紅測。 |
| 審查與驗收 | `code-review` 固定比較基點及變更範圍，分開由獨立代理審查 Standards（本專案準則）與 Spec（原票需求）；Root 複核結果，退回缺陷並重驗。 | 公開 API、真 PG、Fake／Clock 及必要瀏覽器證據對應同一候選；未驗與注入情境明示，兩軸審查均已處理。重構在審查階段評估。 |
| 提交與結案 | 驗收後依 `implement` 提交當前分支；僅 stage 本票已審內容，保留他人修改。依專案授權推送並更新 Issue 證據。 | 提交內容與驗收候選一致；只有驗收條件完成的票可關閉，父 spec 等子票及整合驗收完成再結案。 |

- **測試邊界**：`tdd` 以公開介面的行為為主，期望值來自規格或獨立 fixture；Fake 放在模型／外部服務、時間等系統邊界。沿用本專案已核准的真 PG 狀態核對與防付費呼叫觀測，不用私有方法或內部互叫測試取代公開行為驗收。
- **技能可用性**：區分 skill 文件與工具 API。沒有名為 `Skill` 的工具時，直接閱讀已安裝文件並遵循；找不到 skill 時先查技能目錄、連結及官方來源，報告缺項，不假稱已調用。`implement` 的 TDD 調用為「where possible」；缺少工具不阻擋已授權、可獨立進行的工作，審查責任仍須落實。安裝或更新依使用者授權處理。
- **文件寫法**：交接及原則使用 `writing-for-agents`，每步有可核對的完成條件；AGENTS 保留跨任務原則，功能決策留在 spec／Issue／ADR。機器特定路徑、暫存 PID 與候選 hash 放當次交接，不堆入本檔。
- **來源與版本**：2026-11-13 查核 [Matt 官方 README](https://github.com/mattpocock/skills)、[implement](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement/SKILL.md)、[TDD](https://github.com/mattpocock/skills/blob/main/skills/engineering/tdd/SKILL.md)、[code-review](https://github.com/mattpocock/skills/blob/main/skills/engineering/code-review/SKILL.md)及 [implement-spec](https://github.com/mattpocock/skills/blob/main/skills/engineering/implement-spec/SKILL.md)。本機技能為安裝副本，可能與上游不同；更新或流程不符時重新核對，不把上游 `main` 當固定版本，也不宣稱未安裝的技能可直接調用。

### 官方參考來源與重新查核時機

- 每次規劃、開票、修改或驗收涉及套件行為的工作，以及依賴升級、文件與實作不一致時，重新確認實際解析版本與對應章節；沿用舊結論前檢查版本與文件是否已變動。
- **AG-UI**：涉及 Agent 通訊時查閱[官方協定](https://docs.ag-ui.com/)、[HttpAgent](https://docs.ag-ui.com/sdk/js/client/http-agent)與 [Middleware](https://docs.ag-ui.com/sdk/js/client/middleware)，搭配 ThreadPlane adapter 與固定版本公開型別查核事件、識別字、取消及復原；區分瀏覽器能力與可信伺服器責任。
- **Spring AI**：使用[目前指定的 2.1 reference](https://docs.spring.io/spring-ai/reference/2.1/index.html)，並核對實際 BOM／module 版本；RC API 有落差時查同版 tag、JAR 與 release notes。
- **Spring Boot／Framework**：查閱 [Boot reference](https://docs.spring.io/spring-boot/reference/)、[配置屬性表](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)及 [Framework reference](https://docs.spring.io/spring-framework/reference/)，依工作選讀配置、測試、開發服務、GraphQL、串流生命週期與打包章節。
- **模型／雲端整合**：以 [Gemini API 官方文件](https://ai.google.dev/gemini-api/docs)、[Google AI Studio](https://aistudio.google.com/)及 [Google Cloud 官方文件](https://cloud.google.com/docs)確認模型識別字、能力、限制與部署選項；設定中的模型名稱與實際 SDK 支援須分別核對。
- **瀏覽器標準**：涉及 dialog、fetch、串流與取消時，查閱 [WHATWG HTML](https://html.spec.whatwg.org/)、[Fetch](https://fetch.spec.whatwg.org/)與 [Streams](https://streams.spec.whatwg.org/)規範；MDN 可協助理解，行為衝突以標準與實際瀏覽器驗證交叉確認。
- 技術結論的規格或研究記錄保留實際版本、查核日期、已讀章節連結與取得失敗；本節僅提供來源與觸發條件，不宣稱連結內容永遠適用。

### 原則參考來源

2026-11-17 查核 [Karpathy-inspired 社群整理](https://github.com/multica-ai/andrej-karpathy-skills)與其 [CLAUDE.md](https://raw.githubusercontent.com/multica-ai/andrej-karpathy-skills/main/CLAUDE.md)，以及 [AI Builder Club 文章](https://www.aibuilderclub.com/blog/karpathy-agents-md-framework)。前者是社群整理，後者明示推演，均非 Karpathy 本人發布的官方規範；本文件將四項思維改寫並結合使用者要求與專案原則。

## 二、資料庫與領域塑模 (DB & Schema Clean Code)

1. **命名規範與領域對齊**
   - 慣例與格式：資料表與欄位統一採小寫 `snake_case`；業務表名一律使用單數（如 `bot`、`conversation`、`chat_run`、`message`）。
   - 精準領域命名：優先使用具體領域名詞，語意精簡且一致；避免缺乏領域語意的 `data`/`info` 等泛稱、不明縮寫與重複前綴；既有合理欄位（例如 `chat_run.input_text`）予以保留，不進行無差別破壞性重構。
   - 領域術語指標：設計資料模型或更動領域術語時，先讀 [GLOSSARY.md](GLOSSARY.md) 與該領域既有 ADR；名詞意義有變動時同步對齊。

2. **領域術語與框架保留**
   - 核心概念劃分：沿用 Spring AI 術語但明確劃分邊界：清楚區分 Bot、Conversation、Message、Run、Session 與 Prompt；明確定義 Session 為模型推論記憶脈絡，不等於 Conversation 使用者對話歷史。
   - 框架原生保留：框架官方管理表保留官方原生命名與 Schema，不擅自改名扭曲整合。

3. **結構詮釋、種子資料與安全清理**
   - SQL 註解說明：業務 table 與 column 撰寫 SQL `COMMENT` 說明必要的用途、關聯約束、狀態列舉或數值單位，不每欄硬塞不適用內容。
   - 合成範例隔離：提供可理解且安全之合成展示資料（Seed），僅限於 local 環境自動載入，具備冪等性且重複執行不產生重複資料；與正式發布 artifact 隔離，禁止打包至 bootJar 或 production image；正式業務必要之參照資料不可混同展示 seed。
   - 受控清理原則：資料清理以當次明確授權範圍為限，不以 MVP 為藉口隨意清空全庫資料。

## 三、架構邊界與前端規範

1. **通用程式設計**
   - 問題與簡單性：從第一性原則確認真實問題、約束及證據，批判假設；以易讀、單一職責及簡短且具表達性的領域名稱實作，不為未要求的需求預設抽象。
   - DRY 與可測試性：重複業務規則維持單一來源，但不因形狀相似而創造通用框架；設計可測的公開介面，讓 I/O 與時間等外部邊界可控。
   - 精準變更：只改本需求所需內容並清理本次變更造成的未使用匯入、變數或函式，保留既有未提交修改及暫存檔；不順帶重構或格式化無關內容。

2. **後端無狀態與恢復**
   - 持久狀態：業務、對話、執行及可靠協調狀態存於外部持久服務，任何實例都能處理後續請求，不依賴本機記憶體、檔案或 session affinity 作為唯一真相。
   - 暫態與恢復：單次串流或連線的暫態可留在本機，但須設計重啟及失聯後的恢復；不要求每次重建所有物件。原則參考 [Twelve-Factor Processes](https://12factor.net/processes)（2026-11-17 查核，頁面最後更新 2017）。

3. **領域模組化與依賴邊界**
   - 清晰模組界線：按領域能力切分模組，為未來 Spring Modulith 整合預留清晰邊界。
   - 公開合約通訊：跨模組互動僅限存取公開 Facade 或合約介面，不直接 import 其他模組之內部實作（internal）或跨模組 Repository。

4. **AI Provider 與配置封裝**
   - 介面與配置邊界：具替換彈性之 AI Model/Provider 透過共用介面與組合配置封裝，適合時選用底層 module 取代全包 starter，實作細節收斂於配置邊界。
   - 避免冗餘封裝：無第二種實作需求時，不預先建立無實質效益的空介面；非必要不強迫移除所有 starter。
   - 排除配置查證：若需使用 `autoconfigure.exclude`，須先查閱官方與實際自動配置機制，並於程式碼中明確註解排除理由。

5. **前端**：規劃、修改或驗收 `frontend/` 時，先讀[前端開發守則](frontend/DEVELOPMENT.md)，依其中的模組邊界、版面約定與建置及瀏覽器驗收執行。

## 四、驗收品質與環境安全

1. **證據導向診斷**
   - 觀測不足時先列假說，補最小結構化診斷（如 trace／run ID、scope、狀態轉換與時序、錯誤碼），在相同情境重現；證據支持根因後再計畫修復，不盲改或猜測 SDK 行為。
   - 日誌不含機密、完整 prompt 或 response；控制診斷層級及存續時間，完成後移除臨時噪音並保留必要觀測。

2. **真實行為驗收與模型測試隔離**
   - 可觀察驗收：驗收聚焦於外部公開 API、可觀察行為、真實 PostgreSQL 資料庫狀態及必要之瀏覽器操作；依實際風險評估，不因未執行無關之發布流程視為缺口，亦不將常態文件維護包裝成功能缺口。
   - 測試成本防護：一般自動化測試一律使用 Fake/Spy 模擬模型，防止產生非預期付費呼叫；真實模型之 Smoke 測試須維持獨立隔離並明確手動觸發。

3. **系統狀態真實性**
   - 誠實呈現狀態：外部整合（如 GCP 連線或特定推論狀態）尚未實際串接時，須誠實呈現未連線或未就緒狀態，不虛構 READY 或成功狀態；允許明確標示的本機模擬，不能以本機實作冒充遠端成功。

4. **機密防護與檔案管理**
   - 防護機密外洩：所有路徑之 `application-real-ai.yaml` 均維持 gitignore，禁止將 API Key 與敏感憑證印出至終端、寫入 Log 或提交至 Git。
   - 暫存保護：使用者暫存檔（如 `*.swp`）保持不動。
