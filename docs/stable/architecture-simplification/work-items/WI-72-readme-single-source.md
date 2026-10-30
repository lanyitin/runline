# WI-72 README 收斂為入門，細節以 stable 文件為單一來源；最終驗證

本文回答：根目錄 README 應保留什麼、重複的細節如何收斂，以及重整結束時的最終驗證，如何驗收。狀態：已核可（2026-10-30）。相依：WI-69。

## 背景

根目錄 `README.md` 約 330 行，保留 Ktor 專案產生器的範本段落，並重複了組態表、上傳限制、關閉流程、部署與發佈流程等 stable 文件已有的內容（[04](../../../stable/pipeline-engine/04-deployment.md)、[08](../../../stable/pipeline-engine/08-api.md)、[devkit 指南](../../../stable/devkit-guide.md)、[dev-environment](../../../stable/dev-environment.md)）。同一件事寫兩處，修改時容易只改一處。

## 行為與驗收條件

**README**
- README 只含：專案是什麼（一段）、模組與各自責任（與重整後的現況一致）、建置與測試的常用指令、本機試用的入口、以及指向 stable 文件與 `deploy/`、`release/`、`dev/` 說明的連結。
- 範本產生器的段落與已不適用的功能清單移除。
- 組態變數、上傳限制、錯誤與關閉、部署步驟、建置資訊與可重現建置、發佈檢查清單等細節，每一項只存在於一處。README 原有而 stable 文件或 `deploy/README.md`、`release/` 說明中沒有的事實，先在回報中列出由架構師決定歸屬，不得遺失。
- README 中的每個連結都指向存在的檔案。

**最終驗證**
- 依 WI-57 的全量驗證指令在開發容器以 root 與一般使用者各執行一次，全部通過，且符合 WI-71 的暫存資料條件；回報各模組的測試數，與階段 B 開始前（WI-51 驗收時）的數量對照，不減少。
- 依賴方向檢查涵蓋 [ADR-023](../adr/ADR-023-engine-feature-slices.md) 與 [ADR-025](../adr/ADR-025-analyzer-shares-core-metadata.md) 的全部規則並通過。
- 符合[總覽全域約束](README.md#全域約束適用本總覽所有項目)的全部條件。

## 架構約束

- 不改變任何指令、組態鍵或部署檔的行為；只改說明文件的位置與內容組織。
- `docs/stable/` 的內容由架構師維護：需要搬入 stable 的事實，以回報列出，不由本項直接寫入。
