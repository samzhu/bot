# 前端開發守則 · Frontend Development Guide

## 開始前

1. 核對使用者指定的需求、設計依據和驗收條件。把行為變更、取捨與畫面差異記在對應的規格 Issue。以 PDF 核對功能與版面配置，視覺樣式沿用產品和 SDK 的設計。
2. 核對 `package-lock.json` 的實際版本。Angular 與 CDK 的表單、signals、生命週期、無障礙及測試，以對應的官方文件為準。修改 HTML、CSS 或瀏覽器互動前，用 `modern-web-guidance` 查閱相關指南。 [Angular Style Guide](https://angular.dev/style-guide) · [Angular 文件](https://angular.dev/) · [Angular CDK](https://material.angular.dev/cdk/categories)
3. 使用或替換 ThreadPlane 元件及 Agent adapter 時，查閱官方指南及 API，對照已發布套件的 peer dependencies、公開 TypeScript 型別與必要的原碼。辨別文件所描述的是已發布介面或開發中介面，並查核應用對輸入、取消、持久化與生命週期的責任。 [ThreadPlane Angular 指南](https://threadplane.ai/docs/angular/chat/getting-started/introduction) · [ThreadPlane API 索引](https://threadplane.ai/docs/api?mode=docs)

## 模組與元件

- **應用組合（app composition）**由 `src/app/app.ts` 負責。`src/app/shell/` 持有共用導覽，`src/app/bot/` 持有 Bot 管理。依功能就近放置元件、版型、樣式及其 `.spec.ts`。 [Angular 功能目錄與檔案組織](https://angular.dev/style-guide#project-structure)
- **依賴方向（dependency direction）**由 App 組合 shell 和 Bot。兩個功能模組的正式程式碼透過功能公開介面或 inputs/outputs 合作，shell 和 Bot 都禁止匯入 App 組合層，Bot 也禁止匯入 shell。整合測試可匯入 App 驗證組合後的行為。修改路徑別名、動態匯入或中介模組時，人工檢查間接依賴仍符合方向。
- **SDK 邊界（SDK boundary）**採用 ThreadPlane 公開入口、原生元件、預設樣式及公開設計 token。樣式選擇器限於應用自有元素，保留 SDK 私有 CSS 的封裝。SDK 欠缺通用管理控制項時，先評估標準 Angular 或 HTML，接上實際 API 和事件處理。
- **狀態歸屬（state ownership）**由應用殼層管理共用導覽、版面切換與全域快捷鍵，功能元件負責自己的內容與互動，對話來源管理聊天狀態。收合側欄、切換功能、跨斷點時，依規格保留草稿、執行中的 Run、選取狀態及桌面停駐偏好。

## 應用殼層與版面

**應用殼層（app shell）**的 DOM 順序為**導覽軌（navigation rail）→ 情境側欄（contextual sidebar）→ 主內容區（main content area）**。導覽軌放主要功能入口及全域動作，側欄顯示工作區選取、對話清單或 Bot 清單。主內容區呈現目前功能。工作區選取可以在保留目前主內容的情況下開啟。 [根版型](src/app/app.html) · [側欄版型](src/app/shell/chat-navigation.component.html)

| 模式 | 位置與互動 |
| --- | --- |
| 桌面停駐（docked sidebar） | 導覽軌靠行內起始側（inline start，LTR 為左），側欄緊接其後，主內容填滿剩餘空間。三區並列時可稱為三欄式應用殼層（three-column app shell）。 |
| 桌面收合（collapsed sidebar） | 側欄收合後，主內容緊接導覽軌，收回側欄原本佔用的寬度。 |
| 桌面暫開（sidebar peek） | 側欄從導覽軌之後覆蓋主內容。主內容的起點與寬度維持收合時的幾何，仍可互動。Escape 或外點可關閉，固定可回到停駐。 |
| 窄版抽屜（modal sidebar drawer） | 窄版開啟側欄時，以有遮罩的模態抽屜呈現。背景導覽軌及主內容進入 `inert`，鍵盤焦點留在抽屜內，關閉後依合約恢復。 |

- **對齊（alignment）**側欄標題靠行內起始側，桌面「收合側欄」或「固定側欄」及窄版「關閉導覽」靠行內結束側（inline end，LTR 為右）。維持具名控制項與現有 44 × 44 CSS px 最小點擊區。以 DOM 順序維持相符的視覺與 Tab 順序。功能專屬動作的位置以其規格為準。 [側欄樣式](src/app/shell/chat-navigation.component.css) · [導覽軌樣式](src/app/app.css)
- **捲動（scroll）**讓長內容在負責的區域內捲動，保持側欄、主內容及輸入區可用。檢查窄視窗、短視窗、放大文字及長名稱的裁切、橫向溢出和焦點可見性。收合、暫開及跨斷點時，依規格保留對話的閱讀位置。
- **焦點（focus）**查驗快捷鍵、Escape、Tab、可存取名稱（accessible name）與展開狀態（expanded state）。桌面收合與暫開、窄版模態抽屜及跨斷點要分別驗證。關閉後將焦點還給原觸發器或可用的後備控制項，並保留使用者新移動的有效焦點、文字輸入和 IME 組字行為。 [Modern Web Guidance 導覽抽屜](https://github.com/GoogleChrome/modern-web-guidance/blob/main/skills/modern-web-guidance/guides/ui-components/navigation-drawer.md#implementation-for-a-drawer-that-also-acts-as-a-sidebar)

## 建置與驗收

1. **完整建置（full build）**在 `frontend/` 執行 `npm run build`。依序通過 ESLint、`npm run test:layout` 的公開版面和導覽行為測試、Angular 應用程式建置。直接執行 `ng build`、開發伺服器或 watch 用於開發迭代。 [專案指令](package.json) · [Angular CLI build](https://angular.dev/cli/build)
2. **靜態檢查（lint）**由 ESLint 核對 TypeScript 靜態匯入路徑、ThreadPlane 公開入口、Angular 行內與外部版型的圖片替代文字及表單標籤。DOM 測試覆蓋展開狀態、模態語意與鍵盤行為。樣式渲染後的實際位置、大小及裁切由 Chrome 測量。具體的新結構先在規格寫明適用範圍，待有穩定可觀察的合約再加自動檢查。 [ESLint 設定](eslint.config.mjs) · [Angular 測試環境](https://angular.dev/guide/testing#set-up-for-testing)
3. **瀏覽器驗收（browser verification）**變更共用版面或導覽時，同一候選須通過完整建置及受影響的公開行為測試。改到實際排版、捲動或響應式呈現時，建置後執行 `npm run test:layout:browser`，並在 Chrome 以相關桌面及手機視窗操作受影響的行為，記錄視窗尺寸、結果及必要截圖。幾何測試以隔離的唯讀合成資料量測 1280 × 800 的桌面欄位順序、側欄收合及暫開，以及 375 × 667 的窄版抽屜與控制項對齊。涉及實際後端的整合驗收使用已驗證的 Fake 模型環境。 [Chrome 執行方式](README.md#browser-verification)

現行 320 CSS px 視窗的模態抽屜右緣延伸至 340 px，超出視窗的 20 px 會裁切，頁面 `scrollWidth` 仍可能等於視窗寬度。評估更窄的畫面時，直接量測抽屜與控制項的可見範圍。

官方來源查核於 2026-11-20。專案查核時使用 Angular 22.2.5、Angular CLI 與 CDK 22.2.6、ThreadPlane 0.3.7。
