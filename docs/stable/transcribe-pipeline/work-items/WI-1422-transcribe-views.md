# WI-1422 transcribe 的 Console view：run、階段、降級、逐條文字與結果

本文回答：transcribe 在 Console 的「資料」區要能回答哪些問題。狀態：已核可（2026-10-12）。相依：WI-1420；pipeline view 機制（WI-1300 至 WI-1305，已完成）。儲存庫：runline-transcribe（若需 runline 文件對照，另回報）。

## 背景

transcribe 的資料在 SQLite（run、run_parameter、stage、segment、entry_text、entry_frame、exchange、result），目前只能以資料庫工具查（runbook 第 9 節）。pipeline view 機制（[pipeline-views](../../pipeline-views/README.md)）已讓 pipeline 宣告唯讀 view，由 Console 的 pipeline 詳情頁以表格呈現。

## 可用的機制限制（設計依據）

只有表格、參數篩選、鑽取；儲存格為純文字（長文字會換行，但換行字元不保留）；沒有圖表、明細頁、下載與連結；查詢為 SQLite 語法，使用具名佔位符，30 秒內完成；時間欄須輸出帶 UTC 偏移的值；狀態欄的值需宣告語氣。

## 行為與驗收條件

宣告以下 view，掛在 `database` 角色；標題、欄位標籤與說明為繁體中文，與 digest-rss 一致。

1. **run 清單（`runs`）**：每次 run 一列，依開始時間由新到舊：影片檔（相對路徑）、開始、結束、結果（`success`、`success-degraded`、`failed`、`stopped`、`running`；未結束者顯示 `running`）、結果說明（失敗分類）、降級數、來源語言、字幕檔狀態（`written`、`not_written`）。結果與字幕檔狀態為狀態欄並宣告語氣（失敗負面、降級與 `not_written` 與 `running` 中性、成功與 `written` 正面）。可用結果篩選。影片檔欄鑽取到「某次 run 的階段」。
2. **某次 run 的階段（`run-stages`）**：參數 `run`（必填）。每階段一列，依順序：階段名稱、狀態（`done`、`failed`）、輸入、輸出、失敗、降級筆數、耗時、失敗原因。只呈現資料庫有紀錄的階段（`segmentation` 至 `export`；其餘階段只在 log，於 view 的說明中註明）。
3. **降級與失敗的模型呼叫（`degradations`）**：參數 `run`（必填）。列出 `outcome` 為 `fallback` 或 `failed` 的呼叫：階段、對象（組、條目、整個 run）、對象序號、對應的起訖時間（時:分:秒文字）、結果、模型、耗時。讓使用者知道哪一段字幕退回了上一階段的文字。
4. **逐條文字（`entries`）**：參數 `run`（必填）與選填的 `show`（固定選項：`all`、`changed`；`changed` 只列任一階段文字與前一階段不同的條目）。每個字幕條目一列，依序號：序號、起訖時間（文字）、轉錄、潤飾、核對、翻譯；翻譯為繁體中文字幕的最終文字。分頁沿用 Engine 的 50 列預設。
5. **結果（`result`）**：參數 `run`（必填）。單列：來源語言、影片摘要、來源語言字幕的條目數、繁體中文字幕的條目數、寫出的字幕檔（相對路徑，兩個檔以固定順序列在同一欄，以換行以外的分隔字元分隔）。摘要缺少時顯示為空，不報錯。
6. **記錄寫出的檔案**：`export` 階段（WI-1420）寫出檔案時，在資料庫記錄每個檔的相對路徑、語系與是否成功；這是前向、冪等、於 run 開始時套用並記錄結構版本的遷移。第 1、5 項依此回答，不得由檔案系統推測。
7. 查詢只讀；不選取 `exchange.prompt`、`exchange.response`（含完整文章與圖片說明，體積大）與任何機密；`run_parameter` 不在 view 中。
8. 以 pipeline 自己建立的結構與有代表性的資料驗證每個 view 的列：涵蓋 `success`、`success-degraded`、`failed`、`running`、降級的組與條目、摘要缺少、字幕檔已寫出與未寫出；另以一個含數千條目的 run 驗證 `entries` 與 `runs` 的查詢在 30 秒內完成，必要時新增索引（遷移規則同第 6 項）並說明理由。
9. 實機驗證：jar 上傳到真實 Engine，上傳驗證通過；每個 view 實際讀取至少一次，資料與資料庫相符。環境不允許的部分明說未驗證。
10. README 說明這些 view 取代哪些手動查詢；runbook 位於 runline 儲存庫，需要的修改回報，由後續文件工作項處理。

## 架構約束

- 原因類別、結果與字幕檔狀態的值在宣告中穩定，之後改名視為破壞性變更。
- 所有時間欄輸出帶 UTC 偏移；時間區間以文字欄呈現（秒數轉為時:分:秒），不使用時間欄。
- 查詢遵守 [ADR-034](../../pipeline-views/adr/ADR-034-view-parameter-binding.md)（具名佔位符、無位置標記、無 PostgreSQL 專屬運算子）。
- 薄 jar 約束不變；測試不使用 Stub 或 Mock；嚴格 TDD。

## 已知限制

- 不提供字幕檔下載與播放；檔案在影片旁邊（[ADR-039](../adr/ADR-039-subtitle-files-beside-video.md)）。
- 不顯示模型提示與回答原文（`exchange.prompt`、`exchange.response`）；以資料庫工具查詢。
- 摘要的段落換行在表格中不保留，所以 `summary` view 依段落拆成多列（[WI-1433](WI-1433-console-summary-and-frames.md)、[WI-1435](WI-1435-group-summaries.md)）；畫面時間點與畫面觀察由 `frames` view 顯示（WI-1433）。
