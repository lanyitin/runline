# WI-60 上傳的閒置判定以服務端實際收下的進度計時

本文回答：上傳期間的閒置逾時如何判定、誤差上限在哪裡記載，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-53。決策見[ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 4「閒置逾時」。

## 背景

上傳的閒置逾時以「HTTP 用戶端取走資料」計時。作業系統的送出緩衝會先收下一大段資料（實測最大 4 MiB），之後服務端雖然持續讀取，Engine 卻判定為 `IDLE_TIMEOUT`，違反「持續有進度的上傳不逾時」。`OpenAiBindingMultipartTest`「a slow upload that keeps going…」在開發容器中因此每次失敗（[WI-57](WI-57-test-environment-robustness.md)「實作結果」）。

## 行為與驗收條件

- 服務端以低速持續讀取上傳內容時，即使總時間遠超過 `idleMs`，只要兩次讀取之間的間隔小於 `idleMs`，上傳就不會因 `IDLE_TIMEOUT` 失敗。
  - 以自製 Fake 服務驗證。
  - 須在作業系統送出緩衝很大的環境（例如本開發容器）中通過。
  - 須涵蓋多部分表單與二進位本文兩種上傳。
- 服務端停止讀取超過 `idleMs` 時，上傳仍以 `IDLE_TIMEOUT` 失敗；從服務端停止讀取到判定逾時，所需時間不超過 `idleMs`，加上把固定緩衝上限內的資料送完的時間。
- 送出緩衝的上限是 Engine 內部固定值，記載於 08-api；只作用於資源連線，不改變 JVM 中其他 HTTP 用戶端。若無法只作用於資源連線，停下回報。
- 大檔上傳的吞吐沒有不合理的下降：以同一環境量測修正前後的上傳時間，並在回報中列出結果。
- `OpenAiBindingMultipartTest`「a slow upload that keeps going…」通過，斷言不放寬。
- 08-api 記載送出緩衝上限；[WI-53](WI-53-openai-compatible-multipart-and-binary.md)「實作結果」中「上傳的閒置逾時」的描述同步改寫。

## 架構約束

- 不改變 `idleMs` 的設定方式、預設值與錯誤類別，也不改變串流回應的閒置語意。
- 不新增管理員或 pipeline 可調整的設定。
- 不得以全域系統屬性改變整個 JVM 的 HTTP 行為。
- 測試不使用 Stub 或 Mock。
