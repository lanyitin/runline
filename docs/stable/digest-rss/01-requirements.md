# 需求摘要

本文回答：digest-rss 移植要達成什麼、前提是什麼、哪些仍待確認。

## 目標與角色

- 使用者：管理員（綁定 trigger、允許 unsafe、設定資源與白名單）、讀摘要並彙整資料的人（單一使用者，直接查詢 SQLite）。
- 成功：由 cron 觸發的一次 run，行為與現行 `rss-digest.sh` 等價——同樣的輸入（FreshRSS 未讀、頁面、模型回答）得到同樣的判定、同樣的摘要結構、同樣的標已讀範圍。

## 行為需求（摘要，細節在各工作項）

1. 取得 FreshRSS 未讀（上限 cap 篇），解析為候選清單，排除隔離中的文章。
2. 先確認模型服務有回應，否則不開瀏覽器，且不標已讀。
3. 以站點 profile 擷取每篇的封面圖與可見文字，過薄或失敗者不進入判定。
4. 每篇每個條件一次模型呼叫（條件規則為系統提示，附頁面文字與封面圖）；判定映射、否決規則、引文驗證與模型身分比對與現行一致。
5. 保留的文章啟動下載（有 magnet 或 torrent 走 rqbit，否則走 streamexa），盡力而為，不影響摘要與標已讀。
6. 產生 digest：主題摘要（模型）加事實表（程式計算），以位元組串接組成；檢查其由本次 run 的事實組成。
7. 只在標已讀開關為開、且有摘要時，才把「得出判定」的文章標為已讀；無 url 者不標。
8. 一再無法判定的文章連續 3 次後隔離 7 天，隔離期間不標已讀。

## 非功能需求

- 全程 Kotlin；pipeline 以單一 jar 上傳；不引入 Node 或 shell 腳本於執行路徑。
- 同一時間只有一個模型生成；長時間 run 可被取消，取消時瀏覽器行程一併結束。
- 標已讀不可逆：閘門在程式內，不依賴提示或旗標的字面值以外的任何東西。

## 已確認的前提

- 瀏覽器擷取採方案 A：pipeline 自行啟動 Chrome（因而為 unsafe）。
- pipeline 為薄 jar，只使用 JDK、core 與 Kotlin 標準函式庫，不帶第三方函式庫，不需要白名單條目；不新增資源型別。
- pipeline 的所有寫入改存 SQLite，以便彙整；至少要能回答：每次 run 抓到哪些未讀、每篇的圖文分析結果、哪些文章被派給 streamexa 或 rqbit。其他值得收集的資料見 [06](06-data-model.md)。
- 只移植正式流程；輔助工具不移植。
- 行為等價是目標；pi 側舊檔由使用者自行移除。
- 三個 MCP 服務的位址與 token 放在 `file` 資源內的憑證檔（[ADR-023](adr/ADR-023-service-credentials.md)）。
- Engine 為 `jdbc-pool` 新增 `sqlite` 資料庫種類（[ADR-024](adr/ADR-024-sqlite-run-store.md)）。
- 頁面全文、封面影像、模型對話與其他資料一併存入資料庫；摘要只從資料庫讀取，不再有檔案。
- pipeline 是獨立於 runline 儲存庫的外部專案 `runline-digest-rss`（與 runline 同層的目錄），以 Gradle composite build 取得 devkit 等模組（同 [WI-17](../pipeline-engine/work-items/WI-17-external-test-project.md) 的做法）。
- 判斷條件以 id 選擇（現有 `creampie-busty`），規則檔（條件與 rubric）隨 pipeline jar 發佈；不支援每次 run 輸入自由文字條件。
- Engine 主機是 Linux，以虛擬顯示執行 Chrome（與 pi-env 現行部署相同）。該環境的瀏覽器指紋尚未驗證，由 WI-1000 作為關卡。

## 假設

- 排程由 Engine cron trigger 提供，取代 `digest-schedule` 的 interval／cap／criterion 設定；這些改為 trigger 參數。
- 原始資料（頁面全文、封面影像、模型對話）預設保留 30 天，可由參數調整；彙整欄位長期保留。

## 待確認問題

無。
