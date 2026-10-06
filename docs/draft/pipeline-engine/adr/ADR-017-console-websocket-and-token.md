# ADR-017 Console 的 log 即時顯示與 Bearer token 的瀏覽器保存

狀態：提案中（草案，待核可後晉升到 stable）。不修改 [ADR-012](../../../stable/pipeline-engine/adr/ADR-012-api-authentication.md) 的認證模型。回答：瀏覽器無法在 WebSocket 設定標頭時，Console 如何顯示 run 的即時 log；SPA 如何保存 Bearer token。

## 背景

`GET /api/v1/runs/{runId}/log/stream` 是 WebSocket，升級請求需要 `Authorization: Bearer` 標頭（[08-api](../../../stable/pipeline-engine/08-api.md)）。瀏覽器的 WebSocket API 不能設定自訂標頭。約束：

- ADR-012：token 不得出現在 log、trace 或錯誤訊息；Bearer 契約不得被破壞；token 為設定檔提供的長效靜態密鑰，沒有自助撤銷，輪替需重啟。
- Engine 啟用 OpenTelemetry 的 Ktor 檢測，請求網址通常會進入 trace 屬性；反向代理與瀏覽器也會記錄網址。因此「網址中的任何長效機密」都有洩漏路徑。
- v1 單實例，單一團隊使用；run 的 log 已有可用的分頁查詢 `GET /api/v1/runs/{runId}/log?after=`，其回傳的 `last` 可作為下一次的游標。

## 選項（即時 log）

| 選項 | 說明 | 安全性 | 複雜度 | 結論 |
|---|---|---|---|---|
| A. 一次性短效 ticket | 先用 Bearer 呼叫新端點換取綁定該 run 與呼叫者、約 30 秒、僅能用一次的 ticket，再以網址參數連 WebSocket | 網址上的 ticket 用過即失效，即使進入 log／trace／歷史紀錄也無用；token 本身從不離開標頭 | 高：新端點、新的伺服器狀態（簽發、過期、單次使用、清理）、WebSocket 驗證多一條路徑；多實例時需共享狀態；08-api 與測試擴充 | 保留為升級路徑 |
| B. 把 Bearer token 放在網址參數 | 升級請求帶 `?token=` | 差：長效 token 進入存取日誌、OTel trace、代理日誌、瀏覽器歷史、Referer；直接牴觸 ADR-012 的「token 不得出現在 log、trace」 | 低 | 否決 |
| C. 改用 HTTP 輪詢 `log?after=` | Console 以游標定期查詢；run 結束且游標追上時停止 | 最佳：完全沿用 Bearer 標頭，沒有新的認證路徑與新增攻擊面 | 低：沒有伺服器端變更；前端需處理間隔、分頁與停止條件 | 採用（v1）|
| D. 以 `Sec-WebSocket-Protocol` 夾帶 token | 瀏覽器可設定子協定，伺服器從中取 token | 中：不進網址，但濫用協定欄位，代理或日誌可能記錄該標頭；需伺服器回應該子協定 | 中：需特殊握手處理，並偏離 ADR-012 的標頭規則 | 否決 |
| E. Cookie 工作階段 | 登入後以 Cookie 驗證 WebSocket | 引入 CSRF 與工作階段狀態 | 高：新的認證模型 | 否決：改變 ADR-012 |

輪詢的代價：每個正在檢視進行中 run 的分頁約每秒一次查詢（單一 run 以序號索引查詢，成本低）；v1 預期使用者數少。延遲約一個輪詢間隔（1 至 2 秒）。

## 決策（即時 log）

- Console v1 以輪詢 `GET /api/v1/runs/{runId}/log?after=` 顯示 log，不使用 WebSocket。08-api 現有的 WebSocket 端點維持不變，供非瀏覽器客戶端使用；契約不變。
- 輪詢行為（需求層級）：
  - 只在 run 未結束且分頁可見時輪詢；分頁隱藏時暫停，回到前景時從游標繼續。
  - run 進入終止狀態且游標已追上最後一筆後停止；run 狀態用現有的 run 查詢取得。
  - 連續失敗時退避重試；遇到 401 即視為登入失效並回到登入畫面；404 視為 run 已被清理並明確顯示。
  - 顯示的 log 為追加式，序號連續；重複或遺漏以序號校正。
- 保留升級路徑：若實測發現延遲或查詢量成為問題，另開 ADR 採用 ticket 方案（選項 A）。屆時的設計約束：ticket 須綁定 run 與呼叫者、單次使用、短效（秒級）、不得與 Bearer token 互換使用，且簽發端點為 Bearer（developer）、遵守與既有 run 查詢相同的可見範圍。
- 明確禁止：不得把 Bearer token 放進任何網址（含查詢參數與 fragment）。

## 選項與決策（token 的瀏覽器保存）

需求：同一瀏覽器的所有分頁共用同一個登入工作階段（任一分頁登入，其他分頁不需重登；任一分頁登出，所有分頁一併登出）。

| 選項 | 跨分頁共用 | 風險 | 使用體驗 |
|---|---|---|---|
| localStorage | 原生共用；以儲存事件通知登出 | token 長期留在磁碟，瀏覽器重啟後仍在；任何時間點的任何 XSS（即使沒有其他分頁開著）都能讀走並離線使用；磁碟鑑識與同機其他程式可取得；而 token 是長效、無法單獨撤銷的密鑰 | 最方便，重啟瀏覽器也不用重登 |
| sessionStorage 加分頁間同步（BroadcastChannel）| 新分頁啟動時向已登入的同源分頁請求 token；登入、登出與 401 失效都廣播 | token 只存在於分頁存活期間，不落在持久儲存；XSS 仍可讀取 token（本分頁的儲存，或在其他分頁存活時向頻道請求）| 同一瀏覽器工作階段內免重登；所有分頁關閉後需重新登入 |
| 只存在記憶體加分頁間同步 | 同上，但重新整理的分頁需重新向其他分頁請求；若僅此一分頁則重新整理即登出 | 磁碟與工作階段儲存皆無 | 單一分頁重新整理會登出，不符合使用習慣 |

分頁間同步的機制為同源限定：BroadcastChannel 只在同源的分頁間傳遞，其他網站讀不到。

- 決策（推薦，待使用者確認）：sessionStorage 加 BroadcastChannel 同步。
  - 新分頁啟動時，若自身沒有 token，向同源分頁詢問；有登入的分頁回覆 token，新分頁存入自己的 sessionStorage。逾時沒有回覆即顯示登入畫面。
  - 登入、登出與「收到 401 而清除 token」都廣播；其他分頁收到後同步狀態（登出傳播到所有分頁，回到登入畫面並清除本分頁的 token）。
  - 「登出」清除所有分頁的 token；不提供「記住我」，不使用 localStorage。
  - 所有分頁關閉後工作階段結束，需要重新輸入 token（不跨瀏覽器重啟）。
- 與 localStorage 相比，多出來的 XSS 風險評估：
  - localStorage 增加的風險：任何 XSS 在任何時間都能取得 token，不需要使用者已登入或有分頁開著；token 持續留在磁碟；瀏覽器關閉後仍有效。
  - 推薦方案的殘留風險：XSS 在使用者登入期間仍可取得 token（含向頻道請求）；這是兩個方案共有的風險，只靠 XSS 緩解降低。
  - 推薦方案為新增的複雜度付出的代價：同步握手與競態處理（兩個分頁同時啟動、頻道無人回覆、登出與詢問同時發生），需有行為層級的驗收。
  - 若使用者要求「瀏覽器重啟後仍保持登入」，則只能用 localStorage，並須明確接受 token 持久化於磁碟的風險；本 ADR 不推薦。
- 必須同時成立的 XSS 緩解（沒有它們，任何保存方式都無法保護 token，因此屬於本決策的一部分）：
  - 嚴格的同源內容安全政策，不載入第三方腳本、不允許內嵌腳本（見 [ADR-015](ADR-015-console-frontend.md)）。
  - pipeline 名稱、參數、log 行、失敗訊息與堆疊、jar 內的字串都視為不可信內容，一律以純文字渲染，不得當成標記解讀；log 若需要彩色或連結，在受控的白名單轉換後再顯示。
  - 不使用會把字串當成標記插入的前端機制處理上述內容。
  - token 不寫入前端的 log、錯誤回報與任何可持久化的狀態（路由、URL、歷史）。
- 分頁間同步的頻道只傳遞登入狀態事件與 token 本身，限同源；不傳遞其他資料。
- Token 驗證只透過 `GET /api/v1/system`（[ADR-016](ADR-016-engine-build-info-endpoint.md)）；收到 401 一律清除 token 並回到登入畫面。
- Console 不將 token 傳給 Engine 以外的任何位置；傳輸機密性依賴 TLS（ADR-012 交由部署層），Console 在非 HTTPS（本機開發除外）下登入時顯示警告。

## 取捨

| 項目 | 優點 | 缺點 |
|---|---|---|
| 輪詢取代 WebSocket | 零伺服器端變更、零新認證路徑、不洩漏 token、行為易測 | 非逐行即時（秒級延遲）；進行中 run 的重複查詢；WebSocket 端點在 Console 內閒置 |
| 保留 ticket 為升級路徑 | 日後可補即時性，且不須修改 ADR-012 | 需再開 ADR，屆時要處理單實例以外的狀態共享 |
| sessionStorage 加分頁間同步 | 所有分頁共用登入且登出一併傳播；token 不長期留在磁碟 | 不防 XSS；需維護同步握手；瀏覽器完全關閉後需重新登入 |

## 後果

- 本決策不改變 API 契約；08-api 與測試不需因 Console 而改動 WebSocket 端點。
- Console 的 log 檢視需要支援游標分頁與終止條件，行為驗收由工作項定義。
- 因長效 token 不能撤銷，XSS 緩解是 Console 的上線條件，不是後續優化。
- 若日後採用 ticket，ADR-012 的「免認證清單」不需變動（ticket 端點為 Bearer，WebSocket 升級驗證 ticket 屬於 Bearer 契約的受控延伸，須另行在該 ADR 明列）。

## 對既有文件的後續修改清單

- 08-api.md：`GET /api/v1/runs/{runId}/log/stream` 一節補一句：瀏覽器無法設定標頭，Console 不使用此端點（以 `log?after=` 輪詢）；契約不變。
- 04-deployment.md：「外部連線」或新增小節說明：Console 使 Bearer token 進入瀏覽器，TLS 終止於入口為必要條件。
- 07-nfr-risks.md：新增風險列：「瀏覽器內 token 外洩（XSS）」（處置：嚴格 CSP、不可信內容以純文字渲染、sessionStorage 加分頁間同步、token 輪替需重啟）；「log 輪詢的查詢量」（處置：僅可見分頁輪詢、單一 run 序號索引；超出時採 ticket 方案）；「token 不可單獨撤銷」（既有，ADR-012，提醒 Console 放大其影響）。
- 07「安全」節：補充 Console 的 XSS 與 token 保存原則，連結本 ADR。
- README（入口）：決策表新增一列。

## 待確認問題

- 已確認：log 以輪詢顯示，1 至 2 秒延遲可接受。
- 待使用者確認：跨分頁共用登入採「sessionStorage 加 BroadcastChannel 同步」（推薦），還是 localStorage（需承擔 token 持久化於磁碟與任何時間點 XSS 皆可讀取的風險）。「瀏覽器完全關閉後需重新登入」是否可接受；若不可接受，只有 localStorage 或改採可撤銷的 token（ADR-012 的替換 provider 路徑）。
- 待使用者確認（尚未回覆）：管理員 token 是否與開發人員 token 分開，並定期輪替。建議：管理員另設 token 並定期輪替（輪替需重啟，見 ADR-012）；影響：維運方式。
