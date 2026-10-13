# WI-1428 模型服務拒絕請求時，失敗訊息帶出對方的錯誤說明

本文回答：模型服務以 4xx 拒絕請求時，管理員如何在 run 的失敗訊息中直接看到原因。狀態：已核可（2026-10-13）。相依：WI-1430（runline 的 4xx 說明通道，[ADR-043](../../pipeline-engine/adr/ADR-043-upstream-error-detail.md)）。儲存庫：runline-transcribe；runbook 在 runline。

## 背景

arr 上第一次 run 在轉錄階段收到 HTTP 400，三次重試後失敗，訊息只有 `REQUEST_REJECTED (HTTP 400)`。Lemonade 的回應本文說明該模型不是轉錄型模型，但說明沒有進入失敗訊息。WI-1430 讓存取端把 `error.type` 與 `error.message` 放在 `ResourceAccessException` 上；本項在 pipeline 端使用它。

## 行為與驗收條件

1. 模型服務以 4xx 回應時，`error.message` 與 `error.type`（若有）進入 run 的失敗訊息與該次呼叫的 `exchange` 紀錄；沒有時維持現行訊息。
2. 4xx 中「重試不會改變結果」的類別不浪費重試：至少 `error.type` 為 `unsupported_operation` 的 400；判定規則寫成明確、可測的條件；無法判定時維持現行的重試行為。此類失敗歸為設定類錯誤（`CONFIGURATION`）。
3. 失敗訊息不包含金鑰、授權標頭或音訊內容。
4. 轉錄與各對話階段（潤飾、核對、摘要、翻譯）行為一致，由同一處組出訊息。
5. 以 `FakeOpenAiServer` 驗證：400 帶 `unsupported_operation`、400 帶其他 `error.type`、400 無說明、5xx（仍依現行重試與降級規則）；既有測試維持通過。
6. 薄 jar 約束不變；測試不使用 Stub 或 Mock；嚴格 TDD。
7. 文件：runbook 的排錯補上「轉錄回 400 且訊息指出端點不支援該模型」的診斷與處置（`asrModel` 必須是 Lemonade 的 transcription 型模型，例如 whispercpp 系列；chat 型模型走 chat 路徑，見 [ADR-042](../adr/ADR-042-asr-by-model-labels.md)）。同一資訊只寫一處。

## 架構約束

- 不改變 2xx、5xx、逾時、連線失敗的任何行為。
- 不新增參數。
- 不自行修改 runline 的存取端；WI-1430 未完成時停下來回報。
