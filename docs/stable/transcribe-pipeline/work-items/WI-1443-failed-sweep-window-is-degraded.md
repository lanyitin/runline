# WI-1443 補掃窗口失敗計入降級

本文回答：有補掃窗口轉錄失敗的 run，結果應如何標示。狀態：已核可（2026-10-13）。依據 [WI-1441](WI-1441-sweep-window-failure-degrades.md)。相依：WI-1441。儲存庫：T（runline-transcribe）。

## 背景

WI-1441 讓補掃窗口失敗時略過、計數並可查，但 RUN SUMMARY 的 `degraded=` 與 outcome 不含失敗窗口，run 仍顯示為完全成功。失敗窗口代表可能漏掉了語音，使用者決定應計入降級。

## 行為與驗收條件

1. 有任一補掃窗口失敗的 run，其降級計數包含失敗窗口，outcome 為 `success-degraded`（沿用現有降級語意）；沒有失敗窗口時 outcome 與以前相同。
2. RUN SUMMARY 與轉錄階段結果的降級資訊能看出失敗窗口數（沿用 `sweepFailed`，不重複新增欄位）；既有欄位名稱與值不變。
3. Console 的 `degradations` view 對失敗窗口已有列；`runs` 等顯示 outcome 的 view 在有失敗窗口時顯示降級成功。
4. 取消、失敗的 run 的 outcome 不受影響；cron 掃描對「成功」「降級成功」的處理與現況一致（降級成功仍算處理完成，不重試）。
5. 驗證：以 Fake OpenAI 伺服器涵蓋：單一失敗窗口、多個失敗窗口、無失敗窗口、補掃關閉、窗口失敗且 VAD 條目另有降級項目時計數相加。
6. README（T 儲存庫）與測試中關於 `degraded` 與 outcome 的字句同步；runbook 與資料模型的對齊由架構師處理。

## 架構約束

- 只改降級計數與 outcome 的判定；不改失敗處理、不改結構（若需要結構調整，取下一個可用的結構版本）。
- 薄 jar、嚴格 TDD、不使用 Stub 或 Mock。
