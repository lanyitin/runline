# WI-35 Console 的開發人員功能頁

本文回答：開發人員在 Console 內如何查看 pipeline、上傳 jar、建立與查看 run 及其 log。狀態：已核可（2026-10-05）。相依：WI-34。決策見 [ADR-017](../adr/ADR-017-console-websocket-and-token.md)，API 見 [08-api](../08-api.md)。

## 行為與驗收條件

- **總覽**：顯示目前進行中與最近的 run 與重點數字（依呼叫者的可見範圍）。
- **Pipelines**：列出 definition（`GET /api/v1/definitions`），顯示名稱、所屬版本（contentHash）、上傳者與時間、判定（SAFE／UNSAFE）與 `allowListVersion`；詳細頁顯示 metadata（參數、檔案、網路、行程、資源）、`reasons[]`（各 `kind` 的內容與 `path[]`）、`warnings[]`、`limitations`。
- **上傳**：選擇 jar 上傳（`POST /api/v1/artifacts`）；顯示 201（新版本）與 200（相同內容已存在）的差異；413 與 422 的各錯誤代碼以多語系訊息呈現，指出 `message` 中的項目名稱等具體資訊（以純文字顯示）；上傳中可見進度與可取消。
- **建立 run**：依 pipeline 宣告的參數產生表單；`invalid_parameters` 的 `problems[]` 對應到欄位；`unsafe_not_allowed`、`resources_unavailable`、`definition_not_found` 以清楚的訊息說明（`problems[]` 逐項呈現）。
- **Runs**：列表（新的在前，可依 pipeline 篩選，limit 預設與上限依契約），狀態徽章涵蓋所有 `state`；詳細頁顯示欄位、參數、`failure`（`type`、`message`、`trace`，純文字）、`unsafeExecution`、時間；取消 run（200／202／409 各有對應的呈現）。
- **Log**：依 [ADR-017](../adr/ADR-017-console-websocket-and-token.md) 以 `GET /api/v1/runs/{runId}/log?after=` 輪詢，不使用 WebSocket：
  - 只在 run 未結束且分頁可見時輪詢；隱藏時暫停，回到前景從游標繼續；run 終止且游標追上後停止。
  - 連續失敗退避重試；401 回到登入；404 明確顯示 run 或 log 已被清理。
  - 顯示追加式、序號連續；重複或遺漏以序號校正；`STDOUT`／`STDERR` 可區分；log 保留期限已過而 `entries` 為空時有說明。
  - 延遲預期為 1 至 2 秒。
- 可見範圍：開發人員只看到自己的資料；Console 不自行過濾（依 API 回應），404 與「不存在」不可區分的語意被保留（不洩漏他人資料的存在）。
- 所有 pipeline 提供的字串（名稱、參數、log、失敗訊息、堆疊、`reasons` 的 `detail`）一律以 WI-33 的純文字機制顯示。
- 所有文字有 zh-TW 與 en；日期、數字依語系格式化。

**驗收方式**
- 使用真實的打包後 Engine、真實 PostgreSQL（Testcontainers）、真實編譯的測試 pipeline jar 與真實瀏覽器引擎走完整流程：上傳 → 建立 run → 觀察狀態與 log 輪詢 → 取消。log 輪詢的暫停、退避與停止條件以真實行為驗證（時間由可控制的時鐘或明確的等待條件提供，不以隨意睡眠換取通過）。不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）。
- 前端單元測試涵蓋游標與去重邏輯、錯誤代碼對應。

## 架構約束

- 只使用既有 API，不新增或修改端點；不使用 WebSocket 端點。
- 不新增 CI；前端測試納入 `check`。
