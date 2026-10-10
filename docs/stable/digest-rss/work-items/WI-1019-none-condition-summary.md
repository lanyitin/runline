# WI-1019 `condition=none` 時的摘要

本文回答：`condition=none` 時摘要如何取得模型 id，以及手冊要隨之完成什麼。狀態：已核可（2026-10-09）。工作儲存庫：`runline-digest-rss`（手冊在 runline 的 `docs/draft/digest-rss/runbook.md`）。

背景：WI-1018 落實後，`condition=none` 不使用 `readerModel`，因此摘要沒有模型 id 而失敗，digest 只有事實。修訂後 `none` 時 `readerModel` 選填：有給就用於摘要，沒給就只產出事實的 digest。

## 驗收條件

1. `condition=none` 且提供 `readerModel`：有保留文章時摘要照常以該模型產生（thinking 關、模型身分比對、串流，與條件判定時的摘要完全相同）；沒有保留文章時不呼叫模型。`condition=none` 本身仍不呼叫讀者、不做模型可用性檢查。
2. `condition=none` 且未提供 `readerModel`：不呼叫模型，digest 只有事實，`summary_status` 記錄為未產生並說明原因（不是失敗），run 成功。
3. `readerModel` 一旦提供就套用格式規則（非空、不含控制字元、最長 100 字元），即使 `condition=none` 也不合法即呼叫錯誤；`condition` 不是 `none` 時維持必填。
4. `llm` 角色在 Engine 上的綁定要求不變（`condition=none` 仍須綁定，因為 Engine 以宣告為準）。
5. README 的參數表、判定與摘要說明與本項一致；WI-1002、WI-1007 的條文以 stable 為準。
6. 上線手冊（`docs/draft/digest-rss/runbook.md`）的「`condition=none` 摘要現況，待確認」改為本項的行為，並移除「待確認」標示；手冊其餘內容與 README 再核對一次，特別是參數表、危險組合警告與排錯對照。
7. 既有測試以新預期更新並說明，不放寬斷言；端到端測試（`endToEndTest`）涵蓋 `condition=none` 加 `readerModel` 的一次 run；`./gradlew ktfmtCheck test` 與 `endToEndTest` 通過。

## 架構約束

- 不改變其他行為；不新增第三方函式庫；不修改 runline 的程式與 docs/stable。
