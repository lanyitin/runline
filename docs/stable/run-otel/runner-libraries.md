# Runner 提供的函式庫

本文回答：pipeline 作者不必自己打包哪些依賴、版本為何。這是 Runner 提供函式庫的唯一公告處；run 執行期目錄的實際內容必須與本表一致（驗證見 [#43](work-items/43-run-provides-opentelemetry.md)）。狀態：已作廢（2026-10-27），被 #46 取代。

| 函式庫 | 版本 | 用途 |
|---|---|---|
| runline core | 隨 Runner | pipeline 撰寫契約 |
| Kotlin 標準函式庫 | 隨 Runner | 語言執行期 |
| OpenTelemetry API、SDK、OTLP 匯出 | 1.63.0 | trace 建立與匯出 |
| OpenTelemetry 語意慣例 | 1.42.0 | 屬性名稱常數 |

規則：

- 本表的版本是 run 執行期目錄的版本，不等於 Engine 自用的 OpenTelemetry 版本；兩者可以不同。
- pipeline 針對本表版本（或相容的較舊次版本）編譯即可，不需要也不應夾帶這些函式庫。
- 夾帶時以本表為準，並收到警告。
- 變更本表的版本視為 Runner 的依賴升級，須同步更新本文。
