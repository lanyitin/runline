# WI-56 檢查時間的精度一致

本文回答：資源檢查回傳的時間，為何要與之後讀回的 `lastCheck` 相同，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-43。

## 背景

`POST /api/v1/resources/{name}/check` 回應中的 `checkedAt` 精確到奈秒，資料庫保存的是四捨五入後的微秒；之後 `GET` 讀到的 `lastCheck.checkedAt` 與檢查回應的字串不同。在時鐘有奈秒精度的 Linux 上，Engine 自己的 `ResourceCheckApiTest`、`ResourceCheckerTest` 因此失敗；Console 的契約測試只能以「同一毫秒內」比對。

## 行為與驗收條件

- 同一次檢查中，檢查回應的 `checkedAt` 與之後 `GET /api/v1/resources/{name}`、`GET /api/v1/resources` 中 `lastCheck.checkedAt` 的字串完全相同。在時鐘有奈秒精度的平台上，以真實 PostgreSQL（Testcontainers）驗證。
- 時間的精度與格式在 08-api 中記載一次，其他提到 `checkedAt` 的段落以連結引用。
- Engine 中所有「回應當下回傳、之後從資料庫讀回」的時間欄位都盤點過，有相同問題者一併處理，並在回報中列出盤點結果。
- 原本因精度失敗的 `ResourceCheckApiTest` 與 `ResourceCheckerTest` 通過；不得以放寬斷言達成。
- Console 契約測試改回以字串相等比對，並在 Fake 與真實 Engine 上通過。

## 架構約束

- 不改變 `checkedAt` 的欄位名稱與意義，也不改變 API 的其他行為。
- 測試不使用 Stub 或 Mock，資料庫用真實容器。
