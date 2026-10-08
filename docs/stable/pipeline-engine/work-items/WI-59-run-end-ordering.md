# WI-59 Run 被觀察為已結束時，資源已釋放

本文回答：Run 結束時，存取端失效、資源釋放與結束記錄三者的先後，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-09、WI-43。決策見[ADR-007](../adr/ADR-007-shared-resources.md)「Run 終止時」一點。

## 背景

現行 Run 先被記錄為已結束，之後才停用存取端並釋放容量。在負載下，這段時間差使 `OpenAiResourceRunTest` 的三個取消測試、`ResourceRunIntegrationTest`「two runs…」、`EngineAccessorBehaviorTest`「once the run is over…」失敗（[WI-57](WI-57-test-environment-robustness.md)「實作結果」）。

## 行為與驗收條件

- 對每一種終止方式（成功、失敗、取消、逾時、被強制釋放後結束），只要能透過 API（`GET /api/v1/runs/{runId}`）、Console 或 metric 觀察到 run 已結束：
  - 資源的持有者清單已不含該 run；
  - 該 run 的存取端呼叫一律失敗；
  - 等待中的 run 已可取得被釋放的容量。
- 以「在結束流程的各步驟之間刻意加入延遲」的方式證明順序成立：加入延遲後，上述觀察仍成立。延遲只能透過測試支援注入，不得留在產品行為中。
- 存取端失效或釋放本身失敗或卡住時，run 仍會被記錄為已結束，並有 log 與 metric 記錄釋放失敗；以真實資源型別（至少 `jdbc-pool` 與 `openai-compatible` 其中之一）驗證。
- 原本因這段時間差而失敗的 5 個測試，在全量並行下通過，且沒有放寬斷言或加入等待釋放的邏輯。

## 架構約束

- 不改變 run 狀態的種類、API 欄位與強制釋放的語意。
- 跨 run 共享的狀態仍由 Engine 持有（ADR-007）。
- 測試不使用 Stub 或 Mock；外部系統使用真實容器或自製 Fake。
