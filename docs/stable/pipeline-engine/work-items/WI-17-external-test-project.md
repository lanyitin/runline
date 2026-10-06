# WI-17 外部測試專案 runline-test

本文回答：如何建立一個獨立於 runline 儲存庫的 pipeline 專案，用來實測 devkit 的 IDE 執行與除錯。狀態：已核可（2026-10-04）。相依：WI-03 的實作（已完成）。

## 背景

[WI-03](WI-03-runner-dev-entry.md) 的驗收需要使用者在 IntelliJ IDEA 實測中斷點、單步與檢視變數。實測必須在一個外部的 pipeline 專案中進行，才能反映開發人員的實際使用情境。該專案是與 runline 儲存庫同層的 `runline-test` 目錄，已由使用者以 IntelliJ 建立為空白專案（只有 IDE 產生的檔案與空的原始碼目錄）。

## 目標

`runline-test` 是一個外部 pipeline 專案：能取得 devkit 及其相依、含可編譯的範例 pipeline、可打包成 jar，並提供 IDE 的標準執行／除錯設定。

## 行為與驗收條件

- 專案以 Gradle composite build 指向本機的 runline 儲存庫來取得 devkit、core、runner 與 analyzer；不要求先發佈。取得方式、版本與路徑的設定只在專案內單一處管理。
- 專案建置與執行使用 JDK 25，與 runline 儲存庫一致。
- 專案含範例 pipeline，涵蓋下列情境，每個都能編譯並打包成 jar：
  - safe：循序步驟、使用至少一個宣告的檔案範圍、網路與行程限制為明確的允許範圍。
  - 輔助類別：pipeline 呼叫位於同一專案、不同類別中的輔助邏輯，可在其中設中斷點。
  - unsafe：包含 JVM 結束呼叫，使判定顯示相應的原因與說明。
  - 參數：至少一個必填與一個含預設值的參數。
- 提供 IDE 的標準執行／除錯設定，照 [devkit 操作指南](../../devkit-guide.md)的步驟即可啟動；設定包含執行前的打包步驟。
- 以 devkit 實際執行每個範例 pipeline，結果符合預期：safe 的 pipeline 成功並顯示 safe 判定；unsafe 的 pipeline 顯示 unsafe 與原因；缺少必填參數時明確失敗並回報。
- 專案內有說明文件，涵蓋如何建置、如何執行範例，以及 IDE 實測需要做的準備。
- 專案不依賴 Engine、Ktor、OpenTelemetry 或資料庫。

## 架構約束

- 不修改 runline 儲存庫內任何模組的行為。需要 runline 儲存庫配合調整（例如發佈設定、模組輸出）時，停下來回報，不自行修改。
- 專案是消費端：只透過 devkit 的公開入口使用 runline，不複製 runline 的程式碼。
- 專案內不得放入敏感資訊或寫死的個人路徑；composite build 的路徑相對於專案位置設定。
- 本專案與 runline 儲存庫是不同的版本庫，不自行 git commit，除非使用者要求。

## 待確認問題

- 指南是否需要依實測結果更新（例如 IDE 設定畫面與實際不符之處）。影響：[devkit 操作指南](../../devkit-guide.md)的內容，由使用者實測後回報，另行修訂。
