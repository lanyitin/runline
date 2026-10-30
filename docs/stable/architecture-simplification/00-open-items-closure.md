# 既有未結事項結案清單

本文回答：stable 文件與既有工作項中有哪些未結事項，各自如何結案。依童子軍原則，架構重整開始前先結清這些事項，讓重整從乾淨的基線出發。狀態：已核可（2026-10-30）。

## 一、文件中的待確認問題

結案方式一律是「依現行實作定案」：實作已經做出選擇，文件把問題改寫為決定。本節已全部完成（2026-10-30），各文件改以「已定案的選擇」記載。

| 來源 | 問題 | 結案 |
|---|---|---|
| [dev-environment](../../stable/dev-environment.md) | devcontainer 是否提供統一的容器執行環境 | 不採用；現行說明即為定案 |
| [01-requirements](../../stable/pipeline-engine/01-requirements.md) Q1 | 是否新增管理員設定的唯讀目錄範圍 | 不新增；檔案範圍只有兩種（[ADR-009](../../stable/pipeline-engine/adr/ADR-009-file-scopes.md)） |
| 同 Q2 | 共享目錄是否跨版本沿用 | 沿用（現行實作） |
| 同 Q3 | 是否提供「同一 pipeline 同時只一個 run」設定 | 不提供；需要者用共享資源 |
| 同 Q4 | 磁碟上限是否為單一組態值 | 是（`RUNLINE_WORKSPACE_MAX_BYTES`） |
| [ADR-012](../../stable/pipeline-engine/adr/ADR-012-api-authentication.md) | 開發人員可否查詢他人的 pipeline 與 run | 不可；管理員可查全部（現行） |
| 同 | token 輪替方式 | 重啟生效（現行） |
| [ADR-019](../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md) | 逾時與大小上限的數值 | 決策 14 的預設值即為定案，管理員可逐資源調整 |
| 同 | 目標服務對各端點的實際支援 | 不屬於 Engine 的驗證範圍；管理員逐條啟用端點即承擔此判斷，文件改寫為此語意 |
| 同 | 請求級限流 | 不做；吞吐不足時另提 ADR |
| [ADR-021](../../stable/pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md) | 讀取權限 | 管理員專屬（現行） |
| 同 | 是否公開設定預設值 | 不公開（現行） |
| [WI-05](../../stable/pipeline-engine/work-items/WI-05-safety-analyzer.md) | pipeline 自身類別的範圍、無法解析的類別、無效壓縮檔 | 三項皆依現行實作定案（上傳端已拒絕無效壓縮檔） |
| [WI-06](../../stable/pipeline-engine/work-items/WI-06-upload-and-discovery.md) | 過渡白名單預設內容 | 已由預設白名單取代（[WI-25](../../stable/pipeline-engine/work-items/WI-25-allow-list-format-and-default-refinement.md)）；問題移除 |
| [WI-14](../../stable/pipeline-engine/work-items/WI-14-kmfmt-formatting.md) | ktfmt 的取得方式、是否納入 `check`、規則 | 依現行實作定案（`ktfmtCheck` 已存在） |
| [WI-16](../../stable/pipeline-engine/work-items/WI-16-analyzer-module.md) | 判定訊息是否全部改繁體中文 | 不改；限度說明繁中、其餘英文為定案 |
| [WI-17](../../stable/pipeline-engine/work-items/WI-17-external-test-project.md) | 指南是否依實測更新 | 結案；日後實測發現差異時直接以新需求處理 |
| [WI-25](../../stable/pipeline-engine/work-items/WI-25-allow-list-format-and-default-refinement.md) | 是否提供「套用新的預設白名單」操作 | 不提供；以既有 API 逐一新增（現行），並保留於 07 的已知限制 |

本提案自身的決定見 [01](01-requirements.md)「決定」。

## 二、未完成的工作

| 項目 | 現況 | 結案方式 |
|---|---|---|
| 測試遺留暫存目錄 | 每次全量 `check` 在 `/tmp` 留下約 1.6 GiB，累積後使測試因磁碟已滿失敗 | 新工作項 [WI-71](work-items/WI-71-test-temp-hygiene.md) |
| [WI-57](../../stable/pipeline-engine/work-items/WI-57-test-environment-robustness.md) 重新驗收 | 受阻於磁碟空間 | WI-71 完成後重新派工驗收 |
| [WI-51](../../stable/pipeline-engine/work-items/WI-51-secret-non-leak-verification.md) 整體驗證 | 相依項皆已實作，本身未執行 | WI-57 驗收後派工 |

## 三、文件衛生（架構師執行）

本節已全部完成（2026-10-30）。事件敘述段落中仍有效的限制已移到 [07「已知限制」](../pipeline-engine/07-nfr-risks.md) 與 [ADR-019「已定案的選擇」](../pipeline-engine/adr/ADR-019-typed-shared-resources.md)；全量驗證指令移到 [WI-57](../pipeline-engine/work-items/WI-57-test-environment-robustness.md) 的驗收條件。

| 項目 | 結案方式 |
|---|---|
| 工作項總覽無法看出哪些已完成；狀態列寫「皆待使用者確認」 | 總覽加狀態欄（未開始、已完成、已併入），依 git 歷史與各項實作結果逐項標示；無法判定者當場在對話中確認。狀態列改為已核可 |
| 19 份工作項含「實作結果」「重新驗收」等事件敘述段落，違反文件風格 | 改寫為現況描述：保留驗收條件與架構約束，敘述段落移除（歷史由 git 保存）；其中仍有效的事實（例如已知限制）移到對應的 07 或 08 |
| WI-44、WI-45 僅剩「已併入」指向 | 檔案移除，總覽保留一行說明 |
| stable 的 02 模組表缺 accessors；入口索引寫「ADR-001 至 020」 | 修正 |
| `docs/draft/resource-type-catalog/` 是晉升後未移除的舊稿 | 移除 |
| 本提案晉升後的 draft 原檔 | 移除 |
