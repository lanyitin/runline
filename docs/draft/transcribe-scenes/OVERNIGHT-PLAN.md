# 夜間自動完成計畫（WI-1482、WI-1481、部署、開啟 trigger）

本文回答：使用者離開期間，架構師要依什麼順序與規則完成開發與部署。狀態：執行中（2026-10-16）。

## 順序

1. WI-1482（tdd-coder）→ 架構師唯讀驗收 → 合併到 T 的 main（本機，不 push）。
2. WI-1481（tdd-coder）→ 驗收 → 合併。
3. 兩項都合併後，homelab-deployer 部署：上傳新版 jar、重新允許 unsafe、檢查分析器差異、資源綁定沿用、trigger 的 contentHash 改到新版本。
4. 煙測通過後，開啟 transcribe 的 trigger，排程每 90 分鐘一次。

## 使用者的指示

- 所有送給 LLM 的 prompt 一律使用預設值：run 與 trigger 的參數都不覆寫任何 system prompt 參數。
- 模型參數沿用前一次成功的 run（DB run 43）：
  - 轉錄模型 `Qwen3-ASR-1.7B-GGUF-Q8_0`；
  - 對齊模型 `Qwen3-ForcedAligner-0.6B`；
  - LLM `Gemma4-26B-A4B-Uncensored-HauhauCS-Balanced-Q2_K_P`。
- 第二個轉錄模型使用 WI-1481 的預設（`Kotoba-Whisper-v2.0`），Lemonade 主機上已保留該模型。
- 開啟 trigger 前須煙測成功；分析器差異超出預期、或煙測失敗時，不開 trigger，保持停用並留下紀錄。

## 進度與待處理

- WI-1482：T 的分支 `wi-1482-remove-character-table`（02d3d3e）已驗收；端到端測試在 tdd-coder 的環境有既有失敗，改過的端到端測試未能執行，以部署後的煙測補驗。
- 待使用者核可：[ADR-076](adr/ADR-076-report-without-character-table.md)（報告不顯示角色表，已隨 WI-1482 實作）。
- 待處理：stable runbook 仍描述舊流程（VAD-A、verify），對齊屬於 WI-1460 的 R 部分，不在本次夜間範圍。

- WI-1481：已驗收並合併到 T 的 main（ce27e71，本機）；jar 為 `/tmp/runline-deploy/runline-transcribe-ce27e71.jar`（941972 bytes，sha256 `2a3d9fc9551a9e126f4c9b43c830bf131713627d207545ec21cbc0931c2e3cbd`）。條件 12、13 待實機煙測。
- 部署：homelab-deployer 的第一個唯讀探查被權限分類器以「Production Deploy」擋下，部署尚未開始。homelab 沒有任何變動：無備份、無上傳、trigger 維持停用並指向舊版。
- 續行條件：使用者放行部署動作後，重新派 homelab-deployer，內容同本文「順序」第 3、4 步與「使用者的指示」；Engine 的 API 位於 `https://runline.app.lan/api/v1/`，部署細節在 `docs/stable/transcribe-pipeline/runbook.md` 第 13 節；上傳與動 trigger 需要 admin token（fnox 的 `TF_VAR_runline_api_tokens` 內 console-admin）。部署前必須備份 `transcribe.db`（schema 15→16）。

## 邊界

- 合併只在本機，不 push 到任何遠端（GitHub 不得 push）。
- 衝突與架構約束被破壞時停下，不自行繞過。
- 設計決策先寫 ADR 到 `docs/draft/`，事後列給使用者。
