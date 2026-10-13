# ADR-041 Engine 映像可以任意 uid:gid 執行，帳號由部署者指定

狀態：已核可（2026-10-12）。補充 [ADR-026](ADR-026-homelab-arr-deployment.md)；與既有的 Docker 部署設計（Engine 帳號固定為 uid 10001）有衝突之處：[04-deployment-docker](../../pipeline-engine/04-deployment-docker.md)、`deploy/` 的 compose 疊加檔與 `deploy/browser.md`、`deploy/transcribe.md` 對 uid 10001 的敘述，以本文為準並由 WI-1103 同步修訂。回答：Engine 容器如何以影片目錄的擁有者（arr 上是 1000:1000）執行，才能在影片旁寫字幕檔。

## 背景

arr 上的 `/data/media/movies` 全部歸 1000:1000（檔案 0644、目錄 0755），所有寫入者（rqbit-linker、streamexa、Jellyfin）都是 1000:1000。映像的帳號寫死 uid 10001：以 10001 寫不進這個目錄；映像內的 `HOME`、run 根目錄歸 10001，以其他 uid 執行會寫不進去；init 容器把 shared、resources 寫死 chown 為 10001:10001；keystore 與 secrets 的擁有者也假設 10001。

## 選項

| 選項 | 優點 | 缺點 | 結論 |
|---|---|---|---|
| A. 映像支援以任意 uid:gid 執行；部署者用 `user:` 指定，init 容器的 chown 目標由 `.env` 指定（預設 10001:10001） | 與 arr 慣例一致（rqbit、streamexa、Jellyfin 皆 1000:1000）；預設行為不變；一個映像服務所有部署 | 映像內所有可寫位置要與 uid 脫鉤 | 採用 |
| B. 以補充群組加入影片目錄的群組並讓群組可寫 | 不改映像 | 所有寫入者 umask 022、目錄 0755，新目錄群組不可寫，須改每個寫入者的 umask 並設 setgid | 否決 |
| C. 對 uid 10001 設 ACL | 不改映像 | 依賴 default ACL 繼承，hardlink 與 rename 不保證；arr 無先例 | 否決 |
| D. 為 arr 另建 uid 1000 的映像 | 簡單 | 每個 uid 一個映像，違反「同一產物用於各環境」 | 否決 |

## 決策

- 映像不再假設執行時的 uid：`HOME`、暫存位置、run 根目錄、Chrome 設定檔、Xvfb 的目錄，對任意 uid 都可寫，且不依賴 `/etc/passwd` 有該 uid 的項目；Java 的 `user.home` 與 SQLite 原生程式庫解開位置同樣如此。
- 帳號 `runline`（uid 10001）仍存在，作為預設；不指定 `user:` 時行為與現在完全相同。
- 映像內唯讀的部分（`/app`、`/opt/runline`）維持 root 擁有、其他帳號唯讀。
- init 容器的 chown 目標改由 `.env` 的 uid:gid 指定，預設 10001:10001；keystore、secrets 的擁有者說明與範例改為「Engine 執行的 uid」。
- 渲染節點的 `group_add` 維持不變。
- 部署文件明列：收件目錄的擁有者必須等於 Engine 執行的 uid，或目錄對該 uid 可寫。

## 取捨

- 得到：Engine 可以直接在既有的 1000:1000 檔案樹內寫字幕檔，其他服務（Jellyfin 的字幕外掛、rqbit-linker）對檔案的操作不受影響。
- 失去：映像的可寫位置多了一層「與 uid 脫鉤」的要求，需要測試覆蓋多種 uid；world-writable 的暫存位置只存在容器內，無額外外部暴露。

## 後果

- 由 [WI-1103](../work-items/WI-1103-image-arbitrary-uid.md) 實作並驗證；完成後 homelab-deployer 才能把 arr 的 compose 改為 `user: "1000:1000"`。
- 預設部署（uid 10001）的驗證須全部保持通過。
- arr 的 init 容器與 secrets 權限由 homelab 基礎設施儲存庫的 `modules/arr-runline` 產生，不使用 runline 的 `compose.registry.yaml`；該處的 uid 由 homelab-deployer 調整。
