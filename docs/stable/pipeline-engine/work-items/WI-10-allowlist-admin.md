# WI-10 管理員白名單設定

目標：Engine 管理員可以檢視與維護 import 白名單，變更立即影響所有 pipeline 的 safe／unsafe 判定；並提供 Engine 與開發入口共用的預設白名單。

狀態：已核可（2026-10-05）。相依：WI-05、WI-06、WI-21、WI-24。

## 行為與驗收條件
- 管理員可新增、查詢、修改、刪除白名單條目；條目為套件名稱（可標記為「僅此套件」，排除子套件）或完整類別名稱（[ADR-014](../adr/ADR-014-class-level-allow-list-entries.md)）。API 與回應明確區分兩種條目，不依賴名稱猜測。
- 非管理員不能讀寫白名單（讀取權限範圍依一般授權規則；[ADR-012](../adr/ADR-012-api-authentication.md)）。
- 新增條目時驗證名稱格式，重複或互相涵蓋的條目（例如類別條目已被同名套件條目涵蓋）給出清楚提示。
- 每次變更產生新的白名單版本，並記錄變更者與時間。
- 變更與其觸發的重判在同一交易完成：變更成功即代表所有既有 pipeline 定義已用新版本重判；失敗則白名單維持原狀。
- 變更前可預覽影響：列出會從 safe 變 unsafe、或從 unsafe 變 safe 的 pipeline 定義。
- 重判後，原本允許以 unsafe 執行的設定保持不變；因重判而新變成 unsafe 的定義，其「允許以 unsafe 執行」為不允許。
- 重判不影響進行中的 run。
- 每個 pipeline 的判定結果顯示所用白名單版本。
- 首次啟動時，白名單的初始內容來自專案提供的預設白名單，並可由組態覆寫；之後以管理 API 的內容為準。
- 管理員可手動觸發對所有既有定義的重判（使用目前的白名單與目前的分析規則），使分析規則更新後既有判定能被更新；手動重判同樣可預覽影響，並遵守重判的各項規則。
- 變更有 log 與 trace，判定結果變動數量有 metric。

### 預設白名單
- 專案提供一份預設白名單，內容依 [ADR-013](../adr/ADR-013-io-sensitive-members.md) 與 ADR-014 的原則，如下表。預設內容與其版本標示只有單一來源，由 analyzer 模組提供，Engine 與開發入口（devkit）共用同一份。

| 條目 | 種類 | 備註 |
|---|---|---|
| `java.lang` | 套件，僅此套件 | 排除 `java.lang.foreign`、`reflect`、`module`、`classfile`、`management`、`instrument` |
| `java.lang.invoke`、`java.lang.annotation`、`java.lang.ref`、`java.lang.runtime`、`java.lang.constant` | 套件 | `java.lang.invoke` 與 `runtime` 為 Kotlin 與 Java 編譯產物（lambda、字串串接、record、pattern switch）所必需 |
| `java.util` | 套件，僅此套件 | 排除 `java.util.zip`、`jar`、`logging`、`prefs`、`spi` |
| `java.util.concurrent`、`java.util.function`、`java.util.regex`、`java.util.stream`、`java.util.random` | 套件 | 含各自的子套件 |
| `java.time`、`java.math`、`java.text` | 套件 | 無 IO 子套件 |
| `java.nio.charset` | 套件 | 純編碼；Kotlin 的字串轉位元組內嵌後會參照 |
| `kotlin` | 套件，僅此套件 | 排除 `kotlin.io` 與 `kotlin.io.path`（含檔案與主控台之外的檔案擴充） |
| `kotlin.annotation`、`collections`、`comparisons`、`concurrent`、`contracts`、`coroutines`、`enums`、`experimental`、`internal`、`jdk7`、`jvm`、`math`、`properties`、`random`、`ranges`、`reflect`、`sequences`、`streams`、`system`、`text`、`time`、`uuid` | 套件 | 逐一列入，未來新增的子套件不自動受信任 |
| `org.jetbrains.annotations` | 套件 | Kotlin 編譯的每個類別都帶註解 |
| `java.io.PrintStream` | 類別 | 標準輸出（印出文字）；其以檔名開檔的建構仍受成員層級規則約束 |
| `kotlin.io.ConsoleKt` | 類別 | 主控台讀取；只含主控台函式 |
| `kotlin.io.CloseableKt`、`java.io.Closeable` | 類別 | 資源關閉（`use`）；只涉及關閉，不提供開檔或網路能力（[WI-25](WI-25-allow-list-format-and-default-refinement.md) 新增，預設白名單版本 `default-2`） |

- 預設白名單不含 `java.io`、`java.nio.file`、`java.nio.channels`、`java.net`、`java.sql`、`java.security` 等套件；是否加入由管理員決定。不放行直接以 JDK 讀取標準輸入的類別（`InputStream`、`Reader`、`BufferedReader` 等）：典型 Kotlin pipeline 使用主控台讀取函式即可。
- 實作須以真實編譯的樣本驗證上表：典型 Kotlin pipeline（循序步驟、使用 context、使用基礎集合與字串處理、印出文字與讀取標準輸入）為 safe；直接使用檔案、網路或啟動行程的 pipeline 為 unsafe；移除表中任一必要條目會使對應的樣本被判 unsafe。與上表有出入時，列出差異與依據並停下來回報，由架構決定。
- Engine 以預設白名單作為首次啟動的初始內容；開發入口以它作為預設值，使用者可用環境變數覆寫。
- 開發入口顯示判定時，標明所用的是預設值還是使用者覆寫的內容，並說明這不代表 Engine 現行的白名單版本；顯示來源、版本與條目數量，條目全文可在要求時列出。Engine 的白名單經管理員修改後，兩者可能不同。
- 專案內任何程式或說明不得再各自維護另一份預設白名單；開發入口與外部測試專案（[WI-17](WI-17-external-test-project.md)）改用單一來源。

## 架構約束
[ADR-002](../adr/ADR-002-context-and-unsafe.md)：節點在白名單即受信任、不再展開；[ADR-014](../adr/ADR-014-class-level-allow-list-entries.md)：條目為套件或完整類別；[ADR-013](../adr/ADR-013-io-sensitive-members.md)：IO 敏感成員在成員層級判定、不受白名單豁免。重判使用 WI-05、WI-21 與 WI-24 的分析（analyzer 模組），不得另寫判定邏輯。管理 API 沿用既有認證機制（[ADR-012](../adr/ADR-012-api-authentication.md)）。

白名單文字格式的統一由 [WI-25](WI-25-allow-list-format-and-default-refinement.md) 處理。
