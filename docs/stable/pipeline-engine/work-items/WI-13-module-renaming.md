# WI-13 模組命名調整為 core、runner、engine

本文回答：模組如何命名與劃分，使其對應 pipeline 撰寫契約、Runner 與 Engine 三個關注點。狀態：已核可（2026-10-03）。相依 WI-00（已完成），排在 WI-01 之前。

## 背景

WI-00 建立了 `core`（OpenTelemetry 輔助）、`runner`、`sdk`、`server` 四個模組。使用者決定模組為 `core`、`runner`、`engine` 三個，各自對應一個關注點。

## 目標模組

| 模組 | 責任 | 來源 |
|---|---|---|
| `core` | Pipeline 撰寫契約，即現行的 `sdk` | 現行 `sdk` 改名 |
| `runner` | Runner | 維持 |
| `engine` | 服務端，含 HTTP、WebSocket、Swagger、監控、資料庫與 OpenTelemetry 輔助 | 現行 `server` 改名，並併入現行 `core` 的 OpenTelemetry 輔助 |

## 行為與驗收條件

- 專案只剩上述三個模組，現行 `sdk`、`server` 名稱與舊 `core` 不再存在。
- 依賴方向：`engine` 依賴 `runner` 與 `core`；`runner` 依賴 `core`；`core` 與 `runner` 不依賴 Ktor、OpenTelemetry 輔助或 `engine`。
- 既有的邊界測試持續存在並通過，檢查的對象隨新名稱調整，保證 `core` 與 `runner` 不含 Ktor 與 Engine。
- 建置、既有測試與本機執行在 JDK 25 下通過；`/openapi` 行為不變。
- 專案文件與說明中的模組名稱同步更新；`docs/stable/` 不由本項修改，若其中模組用詞需調整，回報後由架構師另開 draft。

## 架構約束

- 只做命名、搬移與依賴調整，不改變任何行為。
- `core` 與 `runner` 必須能獨立於 `engine` 在 IDE 中使用。
- 套件與內部結構由 tdd-coder 決定。
