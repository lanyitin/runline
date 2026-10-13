# 資料模型

本文回答：有哪些主要實體、如何關聯、資料如何保存。使用 PostgreSQL。

## 實體關係

```mermaid
erDiagram
  ARTIFACT_CONTENT ||--o{ PIPELINE_ARTIFACT : "is the jar of"
  PIPELINE_ARTIFACT ||--o{ PIPELINE_DEFINITION : contains
  PIPELINE_DEFINITION ||--o{ TRIGGER : "is bound by"
  PIPELINE_DEFINITION ||--o{ RUN : "has"
  PIPELINE_DEFINITION }o..o{ SHARED_RESOURCE : "declares roles, same name by default"
  RESOURCE_BINDING }o..o| SHARED_RESOURCE : "role bound to, by name"
  RESOURCE_BINDING }o..o{ PIPELINE_DEFINITION : "of the uploader's pipeline name"
  TRIGGER ||--o{ TRIGGER_FIRING : "records"
  TRIGGER_FIRING }o--o| RUN : "created"
  TRIGGER ||--o{ RUN : "fires"
  RUN ||--o{ RUN_LOG_ENTRY : "emits"
  ALLOWLIST_ENTRY }o--o{ PIPELINE_DEFINITION : "evaluated against"
  OIDC_USER ||--o{ USER_SESSION : "signs in"
```

Pipeline 定義與共享資源之間以名稱鬆散關聯（虛線）：定義宣告資源角色（名稱），沒有綁定時角色就是同名資源；資源綁定（[ADR-025](../digest-rss/adr/ADR-025-resource-roles-and-binding.md)）以 pipeline 名稱和角色名稱為鍵、以資源名稱指向資源，資料庫都不以外鍵連結。

## 實體說明

| 實體 | 內容 | 備註 |
|---|---|---|
| Artifact Content | jar 位元組、內容雜湊、大小 | 以內容雜湊為鍵，同一內容只存一份；不可變；最後一個參照它的版本被刪除時，在同一交易內移除（[ADR-020](adr/ADR-020-per-uploader-artifact-versions.md)） |
| Pipeline Artifact（版本） | 內容雜湊（參照 Artifact Content）、上傳者、上傳時間 | 不可變；（內容雜湊，上傳者）唯一，同一內容可有多個版本、每位上傳者最多一個；definition、trigger、run 仍以版本的代理鍵參照 |
| Pipeline Definition | 名稱、所屬 artifact、宣告的 metadata（含宣告的資源角色、型別與選填的用途，以及選填的 view 宣告，格式見 [08-api](08-api.md) 的「view 宣告」）、safe／unsafe 判定、原因與依賴路徑、判定時使用的白名單版本（文字；白名單由資料庫管理之前儲存的判定為 `config`）、是否允許以 unsafe 執行及設定者與設定時間 | 同名 pipeline 隨 artifact 版本並存；同一 artifact 內，pipeline 名稱與類別名稱各自唯一；判定與 unsafe 設定逐版本獨立保存，不繼承、不共享（同一內容的不同上傳者各有一份），預設不允許 |
| Trigger | 管理員指定的唯一名稱、類型（cron／webhook）、目標 pipeline 定義、固定參數（原樣保存）、呼叫端提供的參數名稱清單（只有 webhook 可有，[ADR-036](../transcribe-pipeline/adr/ADR-036-webhook-caller-parameters.md)）、啟用旗標、建立者與修改者及時間；cron：五欄表達式與時區；webhook：密鑰雜湊與最近輪替時間 | 由管理員維護；名稱用於網址與 run 的來源名稱；只存密鑰的雜湊，不存明文；cron 與 webhook 欄位互斥 |
| Trigger Firing | 所屬 trigger、觸發時間、cron 的排程時間或 webhook 的 delivery 識別碼、結果（待處理、已建立 run、被拒絕、失敗、中斷）、原因與說明、所建立的 run | 同一 trigger 內 delivery 識別碼唯一、排程時間唯一，由資料庫保證；只記錄 cron 觸發與已通過驗證的 webhook 呼叫；run 被清理時此紀錄保留並清除其 run 參照 |
| Run | 狀態、觸發來源（手動時為呼叫者名稱，trigger 時為該 trigger）、參數、起訖時間、結果與失敗原因、是否為 unsafe 執行及當時的設定、設定者與時間 | 狀態含：排隊、等待資源、初始化、執行中、逾時未結束、成功、失敗、取消、中斷、逾時。「逾時」是 run 本體逾時後以失敗結束的終態；等待資源逾時屬於「失敗」，失敗原因註明等待資源逾時 |
| Run Log Entry | 所屬 run、序號、時間、輸出串流、內容 | 序號在 run 內從 1 起遞增 |
| Allowlist Entry | 種類（套件或類別，各有欄位區分）、名稱、是否「僅此套件」（只用於套件）、建立者與時間、最後修改者與時間 | 同一種類內名稱唯一；整份白名單有版本，版本另存一張歷史表（版本號、變更者與時間、動作、說明、該版本重判了多少定義與其中多少變成 unsafe／safe）；版本從 1 起，每次條目變更加 1；變更後立即重判 |
| Resource Binding | 上傳者、pipeline 名稱、角色名稱、資源名稱、設定者與設定時間 | 由開發人員（自己的）或管理員（任何上傳者的）維護；（上傳者，pipeline 名稱，角色名稱）唯一；以（上傳者，pipeline 名稱）為單位，同一位上傳者的各版本共用，不同上傳者彼此獨立、互不可見（[ADR-020](adr/ADR-020-per-uploader-artifact-versions.md)）；沒有列的角色解析到同名資源，所以既有資料不需要任何列；資源之後被刪除時列保留（run 被拒絕為 `unknown`）；不再被任何版本宣告的角色，其列保留到被移除（[ADR-025](../digest-rss/adr/ADR-025-resource-roles-and-binding.md)，遷移 `V10__resource_binding.sql`） |
| OIDC User | 識別碼、provider 的 issuer 與 subject、runline 內的名稱、顯示名稱、建立時間、最近登入時間 | 登入過的人（[ADR-029](../oidc-login/adr/ADR-029-oidc-login-with-server-session.md)，遷移 `V11__oidc_login.sql`）；（issuer，subject）唯一；名稱唯一，首次登入時取 `preferred_username`，之後不變，擁有權資料（上傳者、綁定等）仍以名稱保存、不引用此表；名稱不得等於 `API_TOKENS` 的名稱（由 Engine 在登入與啟動時檢查，token 不在資料庫）；不保存密碼、email、token、群組；不因 provider 改名或移出群組而刪除；停用狀態（`disabled`）與最近一次停用／啟用的操作者與時間（`status_changed_by`／`status_changed_at`，`V13__revalidation_and_user_disable.sql`）：被停用者的工作階段在同一交易內刪除，且不能登入，登入時以對使用者列的鎖與停用互斥，並行下不會留下工作階段 |
| User Session | 識別碼的 SHA-256、所屬使用者、登入當下的角色、CSRF token、加密的 refresh token（必有，`V12__session_refresh_token_required.sql`；沒有 refresh token 的登入不成立）、建立／最近使用／最近成功驗證時間、到期時間 | 識別碼只在使用者的 cookie，資料庫只有雜湊；refresh token 以 AES-256-GCM 加密並綁定該工作階段，金鑰不在資料庫；有效 = 未到到期時間 且 距最近使用未超過閒置時間（閒置時間是組態，不存），過期即無效、不依賴清理；登出刪除。另有管理用的代號 `public_id`（不是識別碼，不能登入）與最近一次向 provider 驗證失敗的嘗試時間 `last_attempt_at`（`V13`；失敗的驗證每個間隔最多一次，寬限從最近一次成功起算）；重新驗證的結果（角色、新的 refresh token、驗證時間）寫入此列，同一工作階段的檢查以資料庫的 advisory lock 輪流進行 |
| Login Attempt | state、nonce、PKCE 驗證碼、返回路徑、建立時間 | 短效（10 分鐘）、單次使用：消耗（讀取並刪除）是一個語句，同一 state 只成功一次；逾時的視為不存在 |
| Shared Resource | 名稱、型別（`counter`、`file`、`jdbc-pool`、`openai-compatible`，建立後不可改）、容量、啟用旗標、型別專屬的非機密設定、機密別名、最近一次實體檢查的結果與時間、建立者與時間、修改者與時間 | 由管理員維護；名稱為識別；機密值不入庫，只存金鑰庫別名（[ADR-019](adr/ADR-019-typed-shared-resources.md)）；設定或別名被修改時，最近一次檢查結果清除；持有者與等待者是執行期狀態，存於 Engine 記憶體，不入庫 |

## 一致性與交易邊界

- 上傳：內容（若尚未存在）、上傳者的版本與其下所有 definition 在同一交易內寫入，要嘛全成功要嘛全失敗。同一上傳者重複上傳相同內容由（內容雜湊，上傳者）的唯一性保證冪等，並行下仍只有一個版本。
- 內容的寫入與版本的刪除以內容雜湊為鍵取同一把交易層級 advisory lock：刪除最後一個版本時移除內容，與並行的上傳互斥，不會出現版本指向已不存在的內容（外鍵以「拒絕刪除」參照內容，作為第二道保證）。
- Run 建立與狀態轉換各為獨立交易，狀態只能單向前進。
- Webhook 去重：同一 trigger 內 delivery 識別碼唯一，由資料庫的唯一性保證，並行重送下仍只建立一個 run。
- Cron 去重：同一 trigger 內同一排程時間唯一，同樣由資料庫保證。
- 白名單變更與其觸發的重判在同一交易內完成：交易持有白名單的互斥鎖（資料庫的交易層級 advisory lock），逐一讀取已儲存的 jar、用 analyzer 重新分析並更新判定；任何失敗都使條目、版本與判定維持原狀。手動重判（不改條目、不產生新版本）同樣如此。
- 上傳寫入判定時以同一把鎖的共享模式檢查：判定所用的版本必須仍是目前版本，否則不寫入、以新版本重新判定。因此變更不會漏掉正在上傳的定義。預覽不持有鎖，在唯讀、一致的快照上進行。
- 登入：使用者的建立（或找到）與工作階段的建立在同一交易內；同一人（issuer，subject）的並行登入以交易層級 advisory lock 串行，不產生重複使用者；名稱的唯一性由資料庫保證，並行下不同的人搶同一名稱時只有一方成功，另一方回名稱衝突且不留下任何資料。
- 共享資源的定義變更（含刪除）為獨立交易；pipeline 可在資源定義之前上傳，此時上傳只產生警告，不影響判定。刪除只在沒有持有者與等待者時成立；已宣告該資源的定義與 trigger（含綁定到它的角色）不阻擋刪除（以名稱鬆散關聯），綁定的列保留。綁定的設定與解除各為獨立交易。

## 生命週期

- Run 與 log 依保留期限清理，期限由組態決定（[WI-20](work-items/WI-20-run-retention.md)）；run 被清理時其 log 一併移除。
- 觸發紀錄依保留期限清理（[WI-20](work-items/WI-20-run-retention.md)）。webhook 的觸發紀錄同時是去重視窗，其保留期限不得短於去重視窗；刪除 trigger 時其觸發紀錄一併刪除。
- 過期的工作階段與逾時的登入暫存由同一個保留清理移除（批次、間隔與不重疊的原則相同，見 README 的保留清理）；它們在清理之前就已無效。登入過的使用者不自動刪除。
- 被任何 trigger 或進行中 run 引用的版本不得刪除（他人的同內容版本被引用不阻擋這個版本被刪除）。終態的 run 不阻擋：管理員確認其數量後，刪除在單一交易內依序移除該版本 definition 的 run 與 log、清除觸發紀錄對這些 run 的參照，再刪除版本與（最後一個參照者的）內容；同一版本的建立 run 與刪除互斥（對 definition 列的鎖）。契約見 [08-api](08-api.md)，決策見 [ADR-047](../version-deletion/adr/ADR-047-delete-version-with-runs.md)。所有參照 definition 的資料表（trigger、run 等）仍必須以「拒絕刪除」的方式參照，作為第二道保證：連帶刪除由刪除流程明確完成，不由資料庫連鎖。共享資源以名稱參照，不屬於此類。
- 既有 artifact 於遷移時各自成為其內容雜湊的唯一版本，jar 位元組移到以內容雜湊為鍵的單一儲存（版本的代理鍵不變，definition、trigger、run 的參照不變）。
- 既有資源於遷移時全部成為 `counter` 型別；金鑰庫檔案不在資料庫，是部署的組態產物，備份與資料庫分開（[ADR-019](adr/ADR-019-typed-shared-resources.md)）。
- Artifact 儲存先放資料庫，以交易一致性與 12-Factor 無狀態為優先；體積成為問題時再評估改放物件儲存。

## 遷移策略

Schema 變更以版本化、單向前進的遷移進行。遷移是獨立的一次性步驟，使用與應用相同的程式碼與設定；Engine 啟動時只檢查 schema 是否為最新，不自行遷移，未遷移則啟動失敗。遷移檔由 tdd-coder 撰寫。
