# 開發環境需求

本文回答：在本機建置與執行測試需要什麼前置條件，以及 Docker 由 colima 提供時如何設定。狀態：已核可（2026-10-04）。建議併入專案 README 的開發環境段落。

## 前置條件

| 項目 | 說明 |
|---|---|
| JDK | 專案基準為 JDK 25。Gradle toolchain 會自動下載，不需手動安裝 |
| Node | 前端（Console）建置用，只存在於建置期，不進入執行環境或發佈產物。版本固定在專案根目錄的 `.node-version`（單一來源，精確版本，目前為 Node 24 LTS 線）；devcontainer 與建置檢查都讀這個檔案，變更版本只改這一處 |
| Docker 相容的容器執行環境 | 資料庫測試使用 Testcontainers 啟動真實 PostgreSQL，沒有容器環境時這類測試無法執行，也不會以替身取代 |

`./gradlew check` 以 root（開發容器的預設）或一般使用者執行都應通過；檔案權限相關的測試在 root 下也會被驗證（測試在放棄了略過檔案權限的能力的執行緒上執行）。Console 的真實瀏覽器腳本（`npm run e2e`）不屬於 `check`，它的前置條件（瀏覽器、金鑰庫項目、Fake 服務行程、全新資料庫）只寫在 [console/e2e/README.md](../../console/e2e/README.md)。

## Node 工具鏈

- devcontainer：`.devcontainer/Dockerfile` 依 `.node-version` 從 nodejs.org 安裝該版本（以官方 SHASUMS256 驗證），重建後即可直接使用 `node` 與 `npm`。
- 非 devcontainer 的本機：以任何方式（官方安裝程式、版本管理工具或系統套件管理員）安裝與 `.node-version` 完全相同的 Node 版本，並讓執行 Gradle 的 shell 的 `PATH` 找得到 `node`。
- 驗證：`node --version` 應等於 `.node-version` 的內容；若使用版本管理工具，請確認它解析出的版本等於 `.node-version`。

## 使用 colima 時的設定

colima 的 socket 不在 Testcontainers 預設尋找的位置，需要在執行測試的 shell 或 IDE 執行組態中設定兩個環境變數。

| 環境變數 | 內容 |
|---|---|
| `DOCKER_HOST` | colima 的 Docker socket，格式為 `unix://` 加上 socket 的絕對路徑；位置在使用者家目錄的 colima 預設 profile 目錄下。缺少 `unix://` 前綴時，Docker CLI 可能仍可使用，但 Testcontainers 找不到 Docker |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` | 固定設為 `/var/run/docker.sock`。它描述容器內部看到的 socket 路徑，不是主機上的 colima 路徑 |

## 環境變數的管理

- 設定只屬於個人環境，不寫入專案的程式或組態檔。
- 可由 shell 設定檔、IDE 執行組態，或其他環境管理工具提供；無論來源為何，必須讓執行 Gradle 的程序繼承到。
- 環境變數未生效時，資料庫測試失敗於 Testcontainers 尋找 Docker 的階段，與專案程式碼無關。

## 待確認問題

- 是否在 devcontainer 內提供統一的容器執行環境，使本機不再需要個別設定。影響：開發環境說明可簡化。
