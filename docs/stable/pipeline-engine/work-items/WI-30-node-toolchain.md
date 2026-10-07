# WI-30 開發環境的 Node 工具鏈

本文回答：開發環境如何取得固定版本的 Node，供前端建置使用。狀態：已核可（2026-10-05）。相依：無。決策見 [ADR-015](../adr/ADR-015-console-frontend.md)。

## 背景

前端建置（Vite）需要 Node。devcontainer 目前只有 JDK 25 與 PostgreSQL，沒有 Node。專案沒有 CI，所有驗證都在本機（或 devcontainer）進行。

## 行為與驗收條件

- Node 版本在單一處固定（版本來源由本項決定並在 README 說明），devcontainer 與建置檢查都使用同一個版本；變更版本只需改一處。
- devcontainer 重建後可直接執行 Node 與其套件管理器；`./gradlew` 的前端建置（WI-31）不需手動安裝任何東西。
- 非 devcontainer 的本機環境以使用者自選的方式取得相同版本；缺少 Node 或版本不符時，前端建置以清楚的訊息失敗（指出需要的版本與取得方式），而不是出現難以診斷的錯誤。
- JDK 25 與 PostgreSQL 的既有設定、環境變數與埠轉送不受影響；既有建置與測試照常通過。
- README 說明如何取得 Node、版本固定在哪裡、如何驗證。

## 架構約束

- Node 只是建置期工具，不得進入執行環境或部署產物（[ADR-015](../adr/ADR-015-console-frontend.md)）。
- 不新增 CI；驗證在 devcontainer 內以真實指令完成（回報執行的指令與輸出）。
- 只修改 `.devcontainer/` 與 README；不修改應用程式碼。
