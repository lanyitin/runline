# #50 core span 契約與 devkit 實作

本文回答：pipeline 端要能做什麼、如何驗收。狀態：已完成（2026-10-27；PR #56）。Issue：#50。決策見 [ADR-46](../adr/ADR-46-trace-export-resource.md)。相依：無。

## 行為

1. pipeline 可宣告一個 trace 匯出資源角色（沿用 ADR-019、ADR-025 的角色宣告與綁定語意），並取得存取端。
2. 存取端接受完成後的 span：名稱、起訖時間、狀態（成功、失敗與選填訊息）、屬性（字串鍵，值為字串、布林、整數、浮點數或其清單）、本地識別碼與父識別碼。
3. 型別全為 JDK 內建型別；沒有 OpenTelemetry 型別。
4. 提交立即返回，不拋出例外給 pipeline；未綁定資源時提交為空操作，pipeline 行為與沒有 tracing 時相同。
5. 追蹤識別碼不由 pipeline 指定。
6. devkit 提供本機實作：接受 span、不對外匯出，使含 tracing 的 pipeline 能在 IDE 中執行。

## 驗收條件

- 宣告了 trace 角色的 pipeline 在未綁定時正常執行，提交 span 不產生錯誤或延遲。
- pipeline 使用此契約，靜態分析仍判 safe。
- 屬性值型別不合法時的處理方式明確且不使 run 失敗（捨棄並可被觀察，具體由實作者在不違反約束下決定並記錄）。
- core 與 runner 不新增對 OpenTelemetry 的依賴；既有模組邊界測試通過。
- devkit 本機實作可在 IDE 中執行含 tracing 的 pipeline，不連外。
- 既有 pipeline 與測試行為不變。

## 實作記錄

- 契約在 core 的 `TraceExport.kt`：`SpanRecord`、`SpanStatus`、`TraceExportAccessor.submit`；取得方式 `context.accessors.traceExport(name)`；型別常數 `ResourceTypes.TRACE_EXPORT`（`trace-export`），已加入封閉集合。
- 屬性值不合法（`null`、其他物件、巢狀或混型別的清單）：run 側的存取端在提交前捨棄該屬性，其餘照常送出，`submit` 回傳捨棄的個數，因此可被觀察，run 不失敗。整數統一成 `Long`、浮點數統一成 `Double`。
- 角色已宣告為此型別但 host 未提供：取得存取端不失敗，提交為空操作且不呼叫 host。未宣告、只宣告名稱或型別不符仍與其他型別一樣被拒絕。host 回覆失敗或丟出例外時，span 遺失、pipeline 不受影響。
- 邊界操作 `trace.submit` 的引數見 [05](../../pipeline-engine/05-ipc.md)；起訖時間以 `java.time.Instant` 傳遞。
- devkit 本機實作 `LocalTraceExport`：不需在 `RUNLINE_RESOURCES` 定義角色，接受 span、不對外匯出，run 結束時在主控台印出提交個數。

## 架構約束

- 邊界只傳 JDK 內建型別（[05](../../pipeline-engine/05-ipc.md)）。
- 契約是「完成後提交」，不提供開啟與結束兩段式。
- 測試的外部系統用真實或簡易真實實作，不用 Stub 或 Mock。
