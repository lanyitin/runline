# WI-48 `jdbc-pool` 型別資源（PostgreSQL）

本文回答：管理員如何把一個資料庫定義為共享的連線池資源，pipeline 如何以窄 SQL 存取使用它，以及如何在不改契約的前提下擴充到其他 JDBC 資料庫。狀態：已核可（2026-10-06）；已實作（2026-10-07，見「實作結果」；只對真實 PostgreSQL 驗證，沒有對其他資料庫驗證）。相依：WI-41、WI-43（含檢查端點）、WI-46（別名解析機制已於該項驗證）。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 4 點「`jdbc-pool`」。

## 背景

連線池由 Engine 持有，密碼經金鑰庫別名取得，pipeline 只拿到窄 SQL 存取，因此不參照資料庫套件、維持 safe。首版只有 PostgreSQL，資料庫差異集中在 Engine 內建的資料庫設定檔。

## 行為與驗收條件

**資源設定**
- 非機密欄位：資料庫種類（首版僅 `postgresql`）、主機、埠、資料庫名、使用者名稱、每 run 連線額度（預設 1）、逾時、額外連線屬性；機密：密碼的金鑰庫別名（選填）。不接受自由格式連線字串。不合規回 422 `invalid_resource`，`problem` 至少區分：資料庫種類不支援、連線屬性不在允許清單、欄位缺漏或不合規。
- 連線位址由 Engine 依資料庫設定檔以結構化欄位組成；額外屬性只接受設定檔允許清單內的項目，清單不含機密類屬性，也不含可載入類別或寫入檔案的項目（以此類屬性被拒絕驗證）。

**資料庫設定檔機制**
- 設定檔集中該資料庫的所有差異：驅動、連線位址組成規則、允許的額外連線屬性清單、健康查詢、錯誤分類、驅動型別到 JDK 型別的對應。首版只有 PostgreSQL。
- 新增資料庫不改資源模型、API 欄位結構與存取端契約：以僅存在於測試原始碼的第二份設定檔證明機制可依「資料庫種類」選用不同設定檔，而不需修改上述三者。
- 驅動隨 Engine 發佈、由 Engine 的 class loader 載入，不由 pipeline jar 或管理員上傳；驅動納入可重現建置（[WI-32](WI-32-reproducible-release-build.md)）與相依漏洞檢查。Pipeline jar 內夾帶驅動時，存取端仍使用 Engine 的驅動（以真實測試 jar 驗證）；run 的執行期目錄不含驅動（沿用打包驗證）。

**連線池與容量**
- 連線池大小由 Engine 推導為「容量 × 每 run 連線額度」，不能獨立設定。容量 N 的資源，N 個 run 同時持有且各用滿額度時全部成功、沒有等待（以真實 PostgreSQL 觀察連線數驗證）。
- 連線池在第一次需要時建立；實體連不上時，取得容量不被阻擋，使用存取端的操作失敗並回傳錯誤類別。
- 修改設定或重載金鑰庫後，已持有者繼續使用其世代的連線池，之後取得的 run 使用新世代；舊世代在持有者全部結束後關閉（從資料庫端觀察連線數與使用的帳號驗證）。
- Run 終止或強制釋放時，連線歸還、未完成的交易回滾、進行中的查詢被取消（同時是 WI-43 通用存取端「進行中的長時間操作被取消」的驗證）；其他 run 取得後看不到前者的交易狀態。

**存取端**
- 提供查詢（回傳結果列）、更新（回傳影響筆數）與顯式交易（開始、提交、回滾）；參數與結果列只用 JDK 的集合與基本型別，資料庫特有型別以文字或位元組呈現；型別對應由設定檔定義並在 08 或資源文件記載。不暴露 JDBC 型別，也不暴露可取出底層驅動物件的途徑；使用存取端的 pipeline 仍為 safe（真實測試 jar）。
- SQL 文字原樣交給資料庫，Engine 不解析或限制；以 PostgreSQL 方言特有的語法驗證其原樣執行。權限由資料庫帳號決定：以真實 PostgreSQL 建立最小權限角色，驗證 pipeline 只具備該角色的權限。
- 回傳給 pipeline 的錯誤只含錯誤類別（連線失敗、驗證失敗、逾時、SQL 錯誤等，由設定檔分類），SQL 錯誤可附標準 SQLState 代碼，不含連線字串與驅動訊息原文；原文寫入 Engine log，以 `errorId` 對應。

**檢查、使用量與可觀測**
- 密碼別名的解析沿用 WI-46 驗證過的機制，以 `jdbc-pool` 重新驗證：狀態 `not_set`／`found`／`missing`、`GET /api/v1/secrets` 與重載回應中的引用者、重載後新取得的 run 使用新密碼（見上方連線池世代）；密碼值不出現在任何輸出。
- 檢查（WI-43）驗證連得上並完成設定檔的健康查詢，同時驗證密碼別名存在；失敗類別至少區分連線失敗、驗證失敗、逾時、別名缺失。
- 資源的查詢回傳型別專屬的使用量（使用中連線數），供 Console 顯示；寫入 08-api。
- Metric：使用中連線數、取得失敗數、查詢耗時；標籤只用資源名稱與型別。Trace 的 span 不記錄 SQL 文字，log 不記錄 SQL 與參數。
- `network` 與資源主機相同時的上傳警告沿用 WI-46 的機制並涵蓋此型別。

**開發入口與驗證**
- 本機實作以本機組態與本機環境提供的密碼提供同契約，與 Engine 以同一組行為測試驗證存取端行為與錯誤類別。
- 自動測試使用真實 PostgreSQL（Testcontainers），密碼別名使用真實 PKCS12 金鑰庫。
- 08-api 同步更新（`type=jdbc-pool` 的設定欄位、`invalid_resource` 的新增 `problem`、使用量欄位）；`ApiDocumentationTest` 通過。

- 資料庫的 TLS 信任與 mTLS 用戶端憑證不在本項範圍，由 [WI-52](WI-52-tls-trust-and-mtls.md) 加入；本項的額外連線屬性允許清單不含 TLS 相關項目，且不得提供任何關閉主機名稱或憑證驗證的途徑。

## 架構約束

- 容量是 run 級持有（ADR-007），連線池內沒有等待，保留無死結性質；不引入請求級或交易級的取得與釋放。
- JDBC 型別暴露、放置驅動 jar 載入、其他資料庫的設定檔都不在本項範圍；新增資料庫需發佈新版 Engine。
- 營運上建議資源使用最小權限的獨立資料庫帳號，不使用 Engine 自己的帳號；此建議寫入部署文件。
- 測試使用真實 PostgreSQL（Testcontainers），不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-07）

程式：契約在 `core`（`Accessors.jdbcPool(name)` 回傳 `JdbcAccessor`：`query`、`update`、`begin`、`commit`、`rollback`；`JdbcRows`；`ResourceFailure` 新增 `SQL_ERROR` 與 `TRANSACTION_STATE`；`ResourceAccessException.sqlState`）；主機側在 `accessors/src/main/kotlin/jdbc/`（`JdbcProfile` 與 `PostgresProfile`、`JdbcSettings`、`JdbcConnectionPool`、`JdbcPools`（世代）、`JdbcBinding`、`JdbcProbe`、`JdbcConnector`）；Engine 在 `engine/.../resource/`（`JdbcPoolBehavior`、`JdbcTelemetry`）；開發入口在 `LocalResources`。驅動 `org.postgresql:postgresql` 是 `accessors` 的 `implementation` 相依（版本在 version catalog），由 Engine 的 class loader 以 `Driver().connect` 直接載入，不經 `DriverManager`。

實作時的決定（超出條文之處，供審閱）：

- **run 持有連線到結束**：run 在第一次需要時從連線池取得連線（最多 `connectionsPerRun` 條），持有到 run 結束、被取消或被強制釋放，才經清理歸還；因此一個 run 的 session 狀態（`SET`、暫存表）在 run 內持續，且與 WI 所說「run 終止時連線歸還」一致。連線池大小是容量乘以每 run 連線數，沒有等待。
- **歸還前清理，無法證明乾淨就關閉**（安全審查項目）：回滾、`DISCARD ALL`（PostgreSQL 設定檔的 `resetStatements`）、再套用啟動語句（時區 UTC、`ApplicationName`、`currentSchema`，因為 `DISCARD ALL` 把它們還原成伺服器的值），並驗證連線開著、在 autocommit、答得出來；任一步失敗、被取消中途的連線與被切斷的連線都關閉。`JdbcProfiles` 拒絕沒有重設語句又沒有明說 `resetNotNeeded` 的設定檔。
- **取消**：run 的取消是中斷其執行緒，而驅動的 socket 讀取不理會中斷，所以語句在存取端的工作執行緒上執行，run 的執行緒等它；被中斷時在資料庫端取消語句並切斷連線（取消請求有 2 秒上限，`cancelSignalTimeout` 也是 2 秒，網路斷掉時強制釋放也在有限時間內返回）。
- **呼叫只讀 `sql` 與 `parameters`**：帶其他成員、其他型別的參數、不認得的操作都是 `INVALID_ARGUMENT`（host 側檢查，不依賴 run 內的存取端）；`BoundResources.call` 對形狀不對的請求回答失敗而不是丟例外。
- **`readOnly` 不在允許清單**：語句可以自己改回，會變成只是看起來有保護；唯一的保護是資料庫帳號的權限。允許清單：`ApplicationName`、`currentSchema`、`tcpKeepAlive`。
- **錯誤文字**：`ResourceOperationFailure` 新增 `sqlState` 與 `withErrorId`（jdbc 的每個失敗都有 errorId）；給 log 的 cause 是新的 `JdbcFailureCause`，`SQL_ERROR` 只含類別、SQLState 與例外種類（資料庫訊息可能含語句片段、名稱與值，WI 說「log 不記錄 SQL 與參數」），連線、登入與逾時類含驅動訊息並去除密碼。這與條文「原文寫入 log」有取捨：`SQL_ERROR` 的原文不寫。
- **用量**：`usage` 為 `{"activeConnections": n}`（`UsageDoc` 改為只含型別有的成員）；`concurrencyLimit` 對 `jdbc-pool` 是連線池大小。
- **本機實作**：開發入口以 `jdbc-pool` 設定檔與環境變數密碼提供同契約；`DevkitBoundaryTest` 原本禁止任何資料庫驅動，改為只禁止 Engine 自己的持久化（Flyway 等），因為本機實作需要設定檔的驅動。
- **Console**：沒有新增錯誤碼，`consoleApiDocCheck` 不需要新的翻譯（新增的是 `problem` 值 `unsupported_database`、`property_not_allowed`，Console 目前不翻譯 `problem`）。

驗證：真實 PostgreSQL（Testcontainers）涵蓋型別對應、方言語法、交易、錯誤類別、逾時、列數與大小上限、連線額度與併發、世代、強制釋放與取消、清理、不外洩、檢查與可觀測；Engine 與開發入口跑同一組 `JdbcBehaviorSuite`；「第二份設定檔」在測試原始碼中是以 `PostgresProfile` 為底、規則不同的設定檔（`JdbcSettingsTest`），機制以資料庫種類選用。

**尚未驗證**：真實的非 PostgreSQL 資料庫（沒有第二個真實資料庫的設定檔）；TLS 到資料庫（WI-52；PostgreSQL 驅動預設的 `sslmode=prefer` 不驗證憑證，管理員無法改，也無法關閉）；相依漏洞檢查（專案目前沒有這項檢查）；可重現建置只因驅動在既有的 Engine 相依之內而沿用（未另行量測）；對遠端網路中斷的語意只以會凍結的 TCP 轉送器模擬。
