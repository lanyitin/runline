# WI-1102 建置輸入以雜湊固定

狀態：已核可（2026-10-11）。回答：要達成什麼行為，使 CI 與映像檔建置不再依賴可移動的標籤。依據 [ADR-029](../adr/ADR-029-pin-build-inputs-by-hash.md)。

**工作的儲存庫：** runline（`.forgejo/`、`deploy/`）。

## 目標

工作流程的所有 action 與 `deploy/` 內所有容器映像都以內容雜湊引用；建置在輸入不變時可重現；更新雜湊是有文件的人工步驟。

## 範圍

- 內：`.forgejo/workflows/` 內每個 action 的引用；`deploy/docker/` 的 Dockerfile 與各 Compose 範本中引用的每個容器映像（Engine 基底映像、PostgreSQL）；`deploy/publish-image` 對基底映像的架構檢查；`deploy/browser.md` 的更新步驟說明。
- 外：Chrome 安裝套件的完整性（維持以版本號固定，不驗證內容雜湊，見 [ADR-029](../adr/ADR-029-pin-build-inputs-by-hash.md)）；自動更新工具；arr 側的 Terraform 與 compose（由 homelab-deployer 在 homelab 儲存庫處理，其映像是否同樣固定另議）。

## 驗收條件

1. 工作流程內沒有任何以標籤或分支名稱引用的 action；每個都是完整提交識別，並有註解標明版本。
2. `deploy/` 內沒有任何只以標籤引用的容器映像；每個都帶多平台索引的內容雜湊，並有註解標明對應標籤。
3. 每個雜湊只在一處定義；沒有多處各寫一份。
4. `deploy/publish-image` 的架構檢查使用的是實際建置所用的那個固定映像，而不是浮動標籤；雜湊與架構不符時不建置、不推送。
5. 以固定後的輸入實際建置一次 `linux/amd64` 瀏覽器版映像，並通過 WI-1100 的映像檔驗證（非 root、不含密鑰、`run-runtime/` 與 `engine.jar` 分離、瀏覽器探測可執行）。
6. 在 Compose 範本中以固定後的 PostgreSQL 映像啟動，Engine 就緒。
7. 有一個自動檢查：掃描上述位置，發現任何未帶雜湊的 action 或容器映像就失敗。此檢查能在本機執行，並對一個故意放入標籤引用的情形確實失敗。
8. `deploy/browser.md` 說明如何更新雜湊（取得新雜湊、確認來源、更新引用、重新驗證）。
9. Engine 與 Console 的行為、映像檔 tag 規則與 `deploy/publish-image` 的其他規則不變。

## 架構約束

- 單一事實來源：每個雜湊只定義一處。
- 12-Factor：不新增執行期設定項；固定的是建置輸入。
- 不改 Engine 行為；不改 WI-1100、WI-1101 已核可的建置與推送規則。
- 工作流程仍不接觸 arr、不觸發上線。

## 待確認問題

- 雜湊是否由 tdd-coder 取得？取得新雜湊需要連到各上游；若實作環境無法連線，tdd-coder 停下來回報，由使用者提供，不自行編造。

## 阻擋與前提

- 相依 WI-1100、WI-1101（皆已實作並提交）。
