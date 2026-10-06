# WI-00 清理範本並建立模組邊界

目標：移除與本案無關的範本內容，讓後續工作從乾淨的基礎出發。

## 行為與驗收條件
- 範本的 Leaderboard、City、Greeting、UserSession 與 Koog 範例不再存在；Koog 的去留若有疑慮先回報。
- 建置與既有的資料庫連線測試仍通過。
- 保留：PostgreSQL、OpenTelemetry、認證、WebSocket、Swagger、監控、DI。
- 模組邊界反映三個關注點：pipeline 撰寫契約（core）、Runner、Engine。core 與 Runner 不得依賴 Ktor 或 Engine；Engine 依賴 Runner 與 core。
- 所有 JVM 模組的 toolchain 升級到 JDK 25；devcontainer 映像同步升級。建置、既有測試與本機執行在 JDK 25 下通過。Kotlin、Ktor、Gradle 與相依函式庫有不相容時，回報後交由架構決定，不自行降級 JDK。
- `client` 與 `web` 模組若無用途，列出建議後交由使用者決定，不自行刪除。

## 架構約束
core 與 Runner 必須能獨立於 Engine 在 IDE 中使用。
