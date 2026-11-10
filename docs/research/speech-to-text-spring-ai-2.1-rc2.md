# Spring AI 2.1 RC2 語音轉文字查證

查證日期：2026-11-10。範圍限 Google Gemini 音訊轉文字、Spring AI 2.1.0-RC2 Google GenAI transcription adapter、及本 MVP 後續整合邊界。未呼叫模型、未讀取任何 API key 或私人設定，未修改程式碼。

## 結論

**MVP 若未做語音，可延後；未來批次音檔轉文字首選保留 `gemini-3.5-transcribe` 與 Spring AI Google GenAI transcription adapter，不必因 endpoint 差異換模型。** Google 官方的專用轉錄指南明列 `gemini-3.5-transcribe:generateContent`，Spring AI 2.1.0-RC2 實作也呼叫 Google GenAI SDK 的 `Models.generateContent(...)`；協定路徑一致。Google 同時提供 Interactions API 範例，並非只能走 Interactions。

Spring adapter 適用**單次音檔請求、等待最終文字**。它不支援即時 Live WebSocket；將 `gemini-3.5-transcribe-live` 放進 batch adapter 的 model 欄位也不會使它變成 Live client。即時語音若未來納入，須走 Google GenAI Java SDK 的 Live API，另設 session / event 介面。

## 已證實：批次 `gemini-3.5-transcribe`

Google 的專用轉錄頁將 `gemini-3.5-transcribe` 的 REST 路徑寫為 `POST /v1beta/models/gemini-3.5-transcribe:generateContent`，並提供 Python `client.models.generate_content(...)`、JavaScript `ai.models.generateContent(...)` 例子。另可先用 Files API 上傳，再以 `fileData.fileUri` 傳入；頁面也說明 `audioTranscriptionConfig` 的語言、custom vocabulary、word timestamp、diarization 與 SMART/VERBATIM 模式。一般 unary 音檔最長 1 小時；啟用 diarization 或 word timestamps 時最多 30 分鐘。這些官方證據確認 dedicated model 確有 GenerateContent 路徑。

Google API `Part` schema 將 `inlineData` 定義為原始媒體 bytes，要求提供固定 IANA MIME type。Google 的音訊轉錄指南列有 WAV、MP3、AIFF、AAC、OGG、FLAC、MPEG、M4A、L16、Opus、ALAW、MULAW、WebM 等 MIME 類型。

Spring AI 2.1 文件目前頁首標示 `Spring AI 2.1.0-RC1`，並明確寫 `gemini-3.5-transcribe` 用標準 `generateContent`；線上 2.1 文件不能單獨當成 RC2 證據。本機已解析的 `spring-ai-google-genai-transcription:2.1.0-RC2` jar 經唯讀檢視確認：

- 實作類別：`org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionModel`。
- 每次 `call(AudioTranscriptionPrompt)` 將 `Resource` 全部讀為 bytes，建立 Google GenAI `Part.fromBytes(bytes, mimeType)`，再附上文字指令 Part。MIME 依檔名副檔名組為 `audio/<extension>`；無法從檔名取副檔名時 fallback `audio/wav`。因此提供有正確副檔名的 Resource，避免 MIME 誤判。
- 呼叫 `com.google.genai.Client.models.generateContent(model, content, config)`，model 取自 options；回傳 `AudioTranscriptionResponse`，其中 result output 是 response text，metadata 含 model version 和 usage。
- options 支援 `model`, `prompt`, `language`, `temperature`, `topP`, `topK`, `candidateCount`, `maxOutputTokens`, `stopSequences`, `responseMimeType`, `presencePenalty`, `frequencyPenalty`, `seed`。沒有 RC2 option 對應 Google 的 `audioTranscriptionConfig`，所以不能由此 adapter 精確設定 Google 專用的 SMART/VERBATIM、custom vocabulary、diarization 或 word timestamps；prompt 文字提示不等同這些結構化 API 參數。
- RC2 類別實作的 `stream(prompt)` 是同步呼叫 `call(prompt)` 後包成 `Flux.just(response)`；不是逐字或增量轉錄串流。

標準 API 全限定名為 `org.springframework.ai.audio.transcription.TranscriptionModel`。它繼承 `StreamingTranscriptionModel`，但 Google RC2 的 stream 實作只在同步模型完成後發出單一完整 response。

## RC2 手動 bean 與設定路徑

此 repo 使用底層模組、排除 Google transcription auto-configuration，所以 YAML 的模型屬性可作為「未來選定預設值」記錄；單獨寫屬性不會建立模型 bean。若採手動 bean，讀既有 secret 值後建立 connection details/options/model：

```java
GoogleGenAiTranscriptionConnectionDetails.builder()
    .apiKey(apiKeyValue)
    .build();

GoogleGenAiAudioTranscriptionOptions.builder()
    .model("gemini-3.5-transcribe")
    .build();

new GoogleGenAiTranscriptionModel(connectionDetails, options);
```

類別 package：

- `org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionConnectionDetails`
- `org.springframework.ai.google.genai.transcription.GoogleGenAiAudioTranscriptionOptions`
- `org.springframework.ai.google.genai.transcription.GoogleGenAiTranscriptionModel`

Spring 官方 auto-config 屬性路徑是 `spring.ai.google.genai.transcription.model`；使用目前的手動 bean 時則以 `GoogleGenAiAudioTranscriptionOptions.builder().model(...)` 設值。auto-config 開關若日後使用 starter，官方屬性為 `spring.ai.model.audio.transcription=none` 關閉、`google-genai` 啟用。

## 即時 Live 與 batch 邊界

Google Live transcription 的精確模型 ID 是 `gemini-3.5-transcribe-live`。官方文件說明其走 Live API 的雙向 WebSocket，端點為 `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`。setup 設 `model: "models/gemini-3.5-transcribe-live"`、`responseModalities: ["TEXT"]`、`inputAudioTranscription`；後續送 realtime PCM 音訊，讀取 `serverContent.interimInputTranscription` 與 `serverContent.inputTranscription`。

Spring RC2 batch model 只呼叫 `Client.models.generateContent(...)`，沒有 `client.live.connect(...)`、WebSocket session 或 interim/final event lifecycle。設定 `spring.ai.google.genai.transcription.model=gemini-3.5-transcribe-live` 不足以提供 Live 能力。Google Gen AI Java SDK 有 `Live` / `AsyncLive`、`LiveConnectConfig`、`AsyncSession` 等 Live API 類型；可在未來由單獨 adapter 使用，但目前 MVP 無即時語音範圍。

未來抽象介面建議分開：

- Batch：`transcribe(Resource, options) -> final transcript`，可由 Spring AI `TranscriptionModel` adapter 實作。
- Live：`openSession(config) -> session`；session 提供音訊 chunk 輸入、interim/final transcript events、error 和 close/cancel。勿將雙向連線生命週期塞進 batch `TranscriptionModel`。

## 3.8 Flash 音訊輸入備選

Google 官方將 `gemini-3.8-flash` 列為 Stable，輸入類型包括 audio，輸出為 text；可走一般 GenerateContent。Spring AI Google GenAI Chat 支援 user message 的 `Media` 附件，可將音訊 Resource 與文字提示一併送給 `ChatModel`。因此未來可用既有 ChatModel 做一般「請轉錄這段音訊」文字任務的備選 smoke，不需另外引入語音模型 adapter。

但此路徑屬通用多模態聊天，不等同 dedicated `gemini-3.5-transcribe`。已查文件沒有證明 `gemini-3.8-flash` 具相同 ASR 專用能力、專用時間戳/diarization/custom vocabulary，也沒有本專案的模型呼叫結果。故它不是本次首選，也不建議為此更換聊天預設。

## 尚需 smoke 驗證

協定文件及 RC2 jar 已足以排除「API operation 不相容」這個原先疑慮，但沒有呼叫實際模型，因此以下仍未證實：

1. Google Developer API 對專案/帳號開放 `gemini-3.5-transcribe`，以及 Spring GenAI Java SDK `1.73.0` 對該 model ID 的實際請求成功。
2. 輸入音檔的實際副檔名/MIME、檔案大小與音訊格式適合 inline request；長檔要考慮 Google Files API/請求限制，但 RC2 adapter 目前是 inline bytes。
3. transcript 非空、語言符合已知樣本；metadata modelVersion 與預期一致。
4. 預設模型只驗一般文字輸出；若產品需要專用 timestamp/diarization/custom vocabulary，需先補 adapter options/API config，而不能假設 prompt 可取代 Google structured options。

最小 smoke：以環境中既有、未輸出的 API key 和 5–10 秒、已知內容、正確副檔名的短 WAV，手動建立 `GoogleGenAiTranscriptionModel`，指定 `gemini-3.5-transcribe`，呼叫一次 `call(new AudioTranscriptionPrompt(resource))`；驗證非空文字與 response model version。此 smoke 只證明批次基本路徑，不驗 Live 或專用轉錄進階設定。

## 官方來源

以下網頁文件可能更新；已知 Spring AI 2.1 transcription reference 當前頁首實際標 RC1。RC2 Java 行為另由已解析的 `2.1.0-RC2` artifact 類別/metadata 唯讀核對，避免把可移動的線上頁面標成 RC2 證據。

- [Google：Audio transcription via Generate Content](https://ai.google.dev/gemini-api/docs/generate-content/transcribe) — dedicated `gemini-3.5-transcribe:generateContent` 範例與音訊功能參數。
- [Google：Generate Content API reference](https://ai.google.dev/api/generate-content) — `Part.inlineData` 原始 bytes 與 MIME schema。
- [Google：Audio transcription overview](https://ai.google.dev/gemini-api/docs/transcribe) — 當前檔案轉錄指南與模型用途。
- [Google：Live transcription guide](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe) — Live 模型 ID、WebSocket、setup 與 transcript event。
- [Google：Live API WebSockets reference](https://ai.google.dev/api/live) — BidiGenerateContent 協定與訊息型別。
- [Google：Gemini 3.8 Flash model detail](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash) — Stable、輸入音訊與輸出文字能力。
- [Google Gen AI Java SDK Javadoc](https://googleapis.github.io/java-genai/javadoc/allclasses-index.html) — `Live` / `AsyncLive` / `AsyncSession` API 類別。
- [Spring AI 2.1 Google GenAI Transcription](https://docs.spring.io/spring-ai/reference/2.1/api/audio/transcriptions/google-genai-transcriptions.html) — `TranscriptionModel`、model property、manual configuration；該 reference 頁當前版頭顯示 RC1。
- [Spring AI Multimodality API](https://docs.spring.io/spring-ai/reference/2.1/api/multimodality.html) — `UserMessage` media input contract。
- [Spring AI Google GenAI Chat](https://docs.spring.io/spring-ai/reference/2.1/api/chat/google-genai-chat.html) — Google chat multimodal input。

### RC2 本機 artifact 核對記錄

唯讀檢視已解析的 `org.springframework.ai:spring-ai-google-genai-transcription:2.1.0-RC2`、`spring-ai-model:2.1.0-RC2` 與 transcribe classes。已確認 `GoogleGenAiTranscriptionModel` 的 endpoint call / audio Part / response mapping / stream 行為；`TranscriptionModel` FQCN 及介面簽名；options builders 與 properties metadata。未執行 Gradle、未送出模型請求。
