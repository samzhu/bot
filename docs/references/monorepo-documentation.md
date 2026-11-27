# Monorepo 文件配置參考

查核日期為 2026-11-27。Grafana 與 Zulip 以連結中的提交版本為準。GitHub Docs 與 Google 文件風格指南使用查核當日的線上版本。

## 官方儲存庫與文件指南

- Grafana 將 UI 套件的本機開發說明放在套件的 README，將告警服務的排程與求值說明放在服務的 README，並在共用貢獻入口連結開發指南及前、後端規範。[UI 套件 README](https://github.com/grafana/grafana/blob/1625e2c5cf8dcfa61e6606e1f977455b4377294d/packages/grafana-ui/README.md)、[告警服務 README](https://github.com/grafana/grafana/blob/1625e2c5cf8dcfa61e6606e1f977455b4377294d/pkg/services/ngalert/README.md)、[貢獻入口](https://github.com/grafana/grafana/blob/1625e2c5cf8dcfa61e6606e1f977455b4377294d/contribute/README.md)
- Zulip 在根層的 `docs/` 維護文件總覽，並從中連結 `docs/tutorials/` 的開發者教學目錄。[文件總覽](https://github.com/zulip/zulip/blob/55eba7b19022c83dfcd9771f89d7b966b1a3e415/docs/index.md)、[開發者教學目錄](https://github.com/zulip/zulip/blob/55eba7b19022c83dfcd9771f89d7b966b1a3e415/docs/tutorials/index.md)
- GitHub 支援 Markdown 相對連結，並會依所在分支解析。GitHub 會自動顯示符合位置規則的 README，因此可從根層 README 連到另立的文件索引。[GitHub README 說明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes#relative-links-and-image-paths-in-markdown-files)
- Google 開發者文件風格指南建議檔名、目錄名採小寫 ASCII，以連字號分隔單字，並允許因既有慣例或工具要求而採例外。[Google 檔名指南](https://developers.google.com/style/filenames#guidelines-for-names)

## 本專案採用方式

`frontend/docs/` 與 `backend/docs/` 放各自的開發文件。跨元件與全專案教學放 `docs/tutorials/`。根層 README 連至 `docs/INDEX.md`，由索引以相對連結導向各處的正式文件。一般文件使用 kebab-case，`INDEX.md` 依本專案約定命名。
