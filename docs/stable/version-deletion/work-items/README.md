# 版本刪除工作項總覽

本文回答：版本刪除要交給 tdd-coder 的工作有哪些、先後順序為何。tdd-coder 讀本頁加被派到的那一項。狀態：已核可（2026-10-13）。入口：[README](../README.md)。

| 項目 | 目標 | 相依 | 狀態 |
|---|---|---|---|
| [WI-59](WI-59-engine-delete-impact-and-force.md) | Engine：影響預覽端點與確認後連同 run 刪除，文件同步 | 無 | 已完成（2026-10-13），54b76e2；提交含 Engine 的影響預覽與刪除測試，驗收條件尚未由架構師逐條重跑 |
| [WI-60](WI-60-console-delete-confirm.md) | Console：先預覽再確認的刪除對話框 | WI-59 | 已完成（2026-10-13），54b76e2；提交含 Console 對話框測試與 e2e，驗收條件尚未由架構師逐條重跑 |

## 全域架構約束

- Bearer API 的既有行為只在 [05-ipc](../05-ipc.md) 所列處改變，其餘不變（[ADR-012](../../pipeline-engine/adr/ADR-012-api-authentication.md)）。
- Console 只用同源的 `/api/v1`（[ADR-015](../../pipeline-engine/adr/ADR-015-console-frontend.md)）。
- 決策依據：[ADR-047](../adr/ADR-047-delete-version-with-runs.md)。
- 測試的外部系統使用真實容器或自製的 Fake，不使用 Stub 或 Mock；遵循 TDD；專案沒有 CI，驗證皆可在本機完成。
