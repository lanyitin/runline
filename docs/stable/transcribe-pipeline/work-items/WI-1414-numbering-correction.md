# WI-1414 編號更正

狀態：已核可（2026-10-11），未開始。儲存庫：R。相依：WI-1400 驗收通過。本文回答：WI-1400 實作過程中，引用舊編號（ADR-034、WI-1300）之處要如何更正。

## 背景

ADR-034 與 WI-1300 原用於本專案，但這兩個編號屬於 pipeline-views。決策改編為 ADR-036，工作項改編為 WI-1400。

## 驗收條件

1. runline 儲存庫中，凡指 webhook 呼叫端提供參數的決策與工作項引用（原 ADR-034、WI-1300），一律改為 ADR-036、WI-1400，並指向 `docs/stable/transcribe-pipeline/` 下的新路徑。範圍包括：README、`docs/stable/pipeline-engine/` 下的 `05-ipc.md`、`06-data-model.md`、`08-api.md`、ADR-005，以及 Engine 的程式註解、測試說明、設定檔註解與遷移檔註解。
2. pipeline-views 的 ADR-034、ADR-035 與 WI-1300 至 1305 的引用一律不動。
3. 更正後全專案搜尋：所有指 webhook 參數的「ADR-034」與「WI-1300」皆已不存在；所有指 views 的引用完好。
4. 只改文字，不改任何行為；`ApiDocumentationTest` 與 trigger 相關測試通過，`ktfmtCheck` 對本項觸及的檔案通過。
5. 不處理 WI-1400 驗收之外的任何功能問題。

## 架構約束

純文字更正；不 commit；不處理進行中的 pipeline-views 檔案本身。
