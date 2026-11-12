# Domain Glossary

Ubiquitous language defining the core domain concepts for the chat system.

## Language

* **Workspace**: 成員使用助理並歸屬對話與用量的工作範圍，可為個人或共用。
* **WorkspaceMember**: 使用者在特定 Workspace 中的成員關係及其角色。

* **Bot**: 具名稱、用途說明與工作指示的可管理助理或 Agent 定義，其對話執行可由不同環境（如本地或遠端執行環境）承接。
  * _Avoid_: ChatModel 或 ChatClient（底層推論基礎設施）。
* **Conversation**: 使用者與某 Bot 之間持續、具備獨立身分識別且可重新開啟查閱的對話主題歷程。
  * _Avoid_: Session（模型推論記憶脈絡）。
* **Message**: 對話歷程中由特定角色（USER、ASSISTANT、SYSTEM）提供的單一文字或內容發言。
* **Run**: 一次從使用者請求發起到達成終態（完成、失敗或取消）的完整生成執行生命週期。
  * _Avoid_: Generation（單次模型文字候選）。
* **ProviderAttempt**: 一筆模型供應商呼叫的嘗試紀錄，可在派送前終止；一個 Run 可包含多筆。
* **UsageSnapshot**: 一次嘗試中的不可變原生用量或缺失證據紀錄，可有多個快照。
* **Session**: 提供給模型延續對話上下文狀態、短期記憶與相關事件的脈絡工作區。
  * _Avoid_: Conversation（使用者完整歷史歷程）。
* **SessionEvent**: 模型推論脈絡（Session）中所記錄的一件原子事件。
* **Prompt**: 送給模型進行推論、已完整組裝系統指示、脈絡訊息與執行選項的完整請求物件。
  * _Avoid_: 單純指涉使用者當前輸入文字（Input Text）。
* **Generation**: 單次模型推論完成時所產生的一個候選文字回應輸出。
  * _Avoid_: 指涉整次生成執行生命週期（應為 Run）。
