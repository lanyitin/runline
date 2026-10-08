# WI-58 `responses.create` 標為有狀態

本文回答：型別目錄中 `responses.create` 的「有狀態」標示要如何調整，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-55。決策見 [ADR-021](../adr/ADR-021-resource-type-catalog-endpoint.md)；「有狀態」的意義見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 13。

## 背景

WI-55 把會建立、取消或刪除服務端保存之物的條目標為有狀態，但沒有標 `responses.create`。OpenAI 相容服務預設會保存建立的回應，使用者決定將它標為有狀態（會改變服務端保存的資料）。

## 行為與驗收條件

- `GET /api/v1/resource-types` 中 `responses.create` 的有狀態標示為真；其他條目的標示不變。
- `responses.create` 仍不預設啟用；「有狀態者不預設啟用」的既有測試仍然通過。
- Console 的端點選項在 `responses.create` 旁顯示「會變更服務端保存的資料」；Fake Engine 的目錄同步，契約測試在 Fake 與真實 Engine 上通過，且兩者回應相等。
- 08-api 與 [WI-55](WI-55-resource-type-catalog-endpoint.md)「實作結果」中「有狀態的範圍」的描述同步改寫。

## 架構約束

- 只改這一個條目的標示，不改變任何條目的啟用規則、路徑與請求驗證。
- 型別描述仍是唯一事實來源（ADR-021 決策 2）。
