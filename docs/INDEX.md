# 文件索引

## 分類說明

| 分類 | 位置 | 適用內容 |
| --- | --- | --- |
| 專案入口 | 根目錄 | 專案總覽、全倉開發守則、共用術語與專案規劃 |
| 後端 | `backend/` | 後端專用的開發、測試、資料庫與維運文件。新增主題文件放 `backend/docs/`。 |
| 前端 | `frontend/` | 前端專用的開發、測試、架構與介面規範。新增主題文件放 `frontend/docs/`。 |
| 教學 | `docs/tutorials/` | 按步驟完成一項任務的指南，包含單一元件及跨元件的教學。 |
| 共用參考 | `docs/references/` | 跨前後端的設計說明、技術參考與查證紀錄。 |
| 工具入口 | 工具所在目錄 | 跟工具一起維護的 README，說明用途、入口與基本操作。 |

依內容的用途分類。教學統一放在 `docs/tutorials/`，元件手冊以相對連結指向相關教學。跨元件的技術參考放在 `docs/references/`，元件專用的參考隨元件維護。

## 維護方式

1. 新文件使用小寫英文加連字號命名，例如 `event-sourcing.md`。既有入口檔沿用原名，包括 `README.md`、`AGENTS.md`、`DEVELOPMENT.md`、`GLOSSARY.md`、`DEVELOPMENT_PLAN.md` 和本索引 `INDEX.md`。
2. 新增文件後，必須在同一變更更新 `docs/INDEX.md`，在對應分類補上標題與相對連結。移動、改名或移除時，一起維護索引和引用該文件的連結。
3. 送審前，從根目錄 README 開啟本索引，確認變更過的索引項目都指向版控中的文件。現有 `docs/research/` 是本機草稿區，正式索引只收錄納入版控的文件。

## 專案入口

- [專案總覽](../README.md)
- [全倉開發守則](../AGENTS.md)
- [領域術語表](../GLOSSARY.md)
- [早期開發規劃](../DEVELOPMENT_PLAN.md)，供查閱歷史背景。

## 後端

- [後端開發手冊](../backend/DEVELOPMENT.md)，涵蓋啟動、資料庫遷移與測試。

## 前端

- [前端使用說明](../frontend/README.md)，涵蓋本機檢查與瀏覽器驗證。
- [前端開發守則](../frontend/DEVELOPMENT.md)，涵蓋模組、版面與驗收規範。

## 教學

- [啟動本機開發環境](tutorials/local-development.md)，涵蓋 Gemini、Ollama、前後端啟動與停止。

## 共用參考

- [Monorepo 文件配置參考](references/monorepo-documentation.md)，收錄官方範例與本專案採用方式。

## 工具入口

- [合成資料工具](../scripts/synthetic/README.md)
