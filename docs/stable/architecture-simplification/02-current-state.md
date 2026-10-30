# 現況觀察（審閱發現）

本文回答：現有架構哪些地方造成不必要的複雜度與低內聚，哪些複雜度是必要的而應保留。行號只用於指出現況。

## 模組與規模

`settings.gradle.kts:22-27` 有 6 個 Gradle 模組，另有 `console`（前端，非 Gradle 模組）。主程式規模約略如下：

| 模組 | 主程式行數 | 責任（現況） | 依賴（主程式） |
|---|---|---|---|
| core | 約 1,400 | 撰寫契約，**加上**限制 context、錄製、存取端轉接、反射讀取 metadata | 無 |
| runner | 約 1,100 | 隔離 class loader 執行、目錄、資源主機介面 | core |
| accessors | 約 5,100 | 各資源型別的實體、設定解析、探測、存取端綁定 | core、runner |
| analyzer | 約 900 | 靜態分析、白名單、位元組層級讀取 metadata（含一份自己的 metadata 模型） | 無 |
| devkit | 約 1,100 | 開發入口、本機資源組裝、判定與提案輸出 | core、runner、analyzer、accessors |
| engine | 約 13,600 | 服務端全部功能 | core、runner、analyzer、accessors |

## 發現一：資源型別的知識分散在四個模組

同一個型別（例如 `jdbc-pool`）的規則分布如下：

| 位置 | 擁有的型別知識 |
|---|---|
| core | 存取端契約與 run 側轉接（`core/src/main/kotlin/Accessors.kt`、`HostAccessors.kt`，合計約 800 行） |
| accessors | 設定解析與驗證、實體、探測、綁定（`accessors/src/main/kotlin/jdbc/`、`openai/`） |
| engine | 每型別一個「行為」：驗證入口、正規化、描述、檢查、存取端建立、金鑰庫串接（`engine/src/main/kotlin/resource/ResourceBehavior.kt:107`、`JdbcPoolBehavior.kt`、`OpenAiCompatibleBehavior.kt`、`FileBehavior.kt`），以及每型別的可觀測（`JdbcTelemetry.kt`、`OpenAiTelemetry.kt`、`TlsTelemetry.kt`） |
| devkit | 依型別分支組裝本機資源（`devkit/src/main/kotlin/LocalResources.kt:110-124`），自行讀取本機組態並呼叫 accessors 的設定解析 |

影響：改一條設定規則要同時理解 accessors 與 engine 的行為檔；devkit 與 engine 各自把設定接到實體，兩邊的「同一份設定得到同一個結果」只靠紀律維持。Engine 匯入 accessors 的型別專屬類別多達數十處（`TlsAliases`、`JdbcPools`、`JdbcProfiles`、`OpenAiObserver` 等）。

## 發現二：engine 內部功能套件循環依賴

engine 依功能分了 13 個套件，但套件之間有循環：

| 循環 | 原因（現況） |
|---|---|
| resource ↔ run | run 擁有資源閘門介面與 run 的可觀測，resource 實作與使用它們；run 又使用 resource 的可用性與問題型別 |
| resource ↔ secret | resource 使用金鑰庫查詢；secret 為了回答「哪些資源使用此別名」反向讀取資源儲存 |
| artifact ↔ resource | resource 使用 artifact 的定義紀錄；artifact 的 API 模型使用 resource 的警告文件型別 |
| auth ↔ artifact | 共用的錯誤回應型別位於 artifact |
| config → artifact、auth、secret | 組態解析直接建出各功能的型別，組態成為所有功能的匯合點（`engine/src/main/kotlin/config/EngineConfig.kt`，約 480 行） |

另外，每個功能的 HTTP 路由放在 engine 根套件（`AllowListRoutes.kt`、`ResourceRoutes.kt`、`RunRoutes.kt`、`TriggerRoutes.kt` 等），與功能本身分離；全部組裝集中在 `DI.kt`（約 360 行）。一個功能的完整樣貌要跨根套件、功能套件、組態與組裝四處讀。

影響：無法單獨理解或修改一個功能；循環使「誰是上游」不明確，新程式碼很容易再加一條反向依賴。

## 發現三：core 混入執行期實作，擴大受信任面

core 是 pipeline 撰寫契約，也是靜態分析中「受信任的葉節點」（[ADR-002](../../stable/pipeline-engine/adr/ADR-002-context-and-unsafe.md)）：pipeline 參照 core 的任何公開類別都不會被判 unsafe。現況 core 公開了只有 runner 需要的執行期類別：`RestrictedContext`（`core/src/main/kotlin/RestrictedContext.kt:9`）、`RecordingContext`（`RecordingContext.kt:13`）、`IoRecorder`（`IoRecorder.kt:24`）、`PipelineMetadataReader`（`PipelineMetadataReader.kt:4`）。儲存庫內在 core 之外只有 runner 主程式使用它們。

影響：撰寫契約與執行期實作混在一起，降低 core 的內聚；受信任面比撰寫所需更大，pipeline 可以自行建構限制或錄製 context 而不被判定察覺。

## 發現四：metadata 模型重複兩份

core（`core/src/main/kotlin/Metadata.kt:36`）與 analyzer（`analyzer/src/main/kotlin/PipelineMetadata.kt:19`）各有一份 pipeline metadata 模型，預設值語意靠對照測試保持一致（[WI-19](../../stable/pipeline-engine/work-items/WI-19-unified-metadata-reading.md)）。重複的根因是 WI-16 規定 analyzer 主程式不得依賴 core；當時的理由是讓 analyzer 不隨 run 載入、並保持純函式，這兩點與「是否依賴 core 的模型」無關：core 本身只依賴 JDK 與 Kotlin 標準函式庫。

影響：metadata 新增一個欄位要改兩份模型、兩份讀取、一組對照測試；devkit 同時接觸兩種同名模型。

## 發現五：既有未結事項累積

- stable 文件有約 20 個待確認問題，多數已被實作做出選擇但文件未改寫；工作項總覽無法看出哪些已完成。
- [WI-57](../../stable/pipeline-engine/work-items/WI-57-test-environment-robustness.md) 的重新驗收受阻：測試在 `/tmp` 留下暫存目錄，每次全量 `check` 約 1.6 GiB，累積後磁碟已滿。[WI-51](../../stable/pipeline-engine/work-items/WI-51-secret-non-leak-verification.md) 整體驗證未執行。
- 19 份工作項含事件敘述段落；WI-44、WI-45 只剩指向；`docs/draft/resource-type-catalog/` 是晉升後未移除的舊稿。
- 根目錄 `README.md` 約 330 行，組態表、部署與發佈流程與 stable 文件重複。

結案方式見 [00](00-open-items-closure.md)。

## 必要的複雜度（保留）

- `engine.jar` 與 `run-runtime/` 分開打包、每個 run 獨立 class loader（[ADR-001](../../stable/pipeline-engine/adr/ADR-001-isolated-classloader.md)）：安全隔離的核心，不簡化。
- analyzer 獨立於 runner：分析程式不應進入每個 run 的 class loader（[WI-16](../../stable/pipeline-engine/work-items/WI-16-analyzer-module.md)）。
- accessors 獨立於 engine：devkit 必須在沒有 Engine 的情況下使用同一份資源實作。
- 型別集合封閉（[ADR-019](../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md)）：集中型別知識不等於開放外掛。
