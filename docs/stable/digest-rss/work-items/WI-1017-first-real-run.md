# WI-1017 真實環境的首次執行

本文回答：digest-rss 在真實環境第一次執行要驗證什麼、順序為何、誰執行。狀態：已核可（2026-10-09）。

背景：前面各項都以 Fake 與本機真實 Chrome 驗證；真實 FreshRSS、Lemonade、rqbit、streamexa、目標站點與目標主機（amd64、Intel iGPU）尚未驗證。這些只有使用者能存取，因此本項由使用者執行；tdd-coder 只在使用者回傳結果後，依結果修正缺陷。

## 驗收條件（依序執行，前一步不通過不做下一步）

1. 只讀的契約檢查（不改變任何外部狀態）：`realFreshRssTest`、`realDownloadsTest`、`realReaderTest` 的契約部分、`realSitesTest`。結果記錄：FreshRSS 缺 token 的錯誤形狀與清單欄位（尤其 enclosure）、伺服器對超過 100 的 limit 的行為、rqbit 與 streamexa 的工具名稱與欄位、Lemonade 串流事件是否帶 `model`、`models.list` 是否可用、各站點 profile 的選擇器是否仍有效。
2. 摘要 thinking 關的預填在真實模型上有效：對真實模型送一次摘要請求，確認沒有推理內容；無效時停下來由架構決定。
3. 32 個標註案例對真實模型跑 `realReaderTest`，輸出逐案差異與一致率，使用者判斷是否接受（本專案的目標是行為等價，不是提升準確度）。
4. 目標主機的環境：依 `deploy/browser.md` 的「在目標主機上要做的驗證」記錄渲染器、取消測試與 SQLite 連續多次 run。
5. 在目標主機以 `markRead=off`、小的 `cap`（例如 3）、明確的 `condition` 執行一次完整 run：確認擷取放行（記錄渲染器字串與各站點結果）、判定、下載派發（可先以測試用的 rqbit／streamexa 目標）、digest 寫入，且 FreshRSS 中沒有任何文章被標已讀。結果不如預期（例如站點回傳檢查頁）時停下來由架構決定。
6. 以彙整查詢（WI-1011 手冊的範例）核對這次 run 的紀錄與實際發生的事一致。
7. 使用者決定是否開啟 `markRead=on`；開啟後第一次執行仍用小的 `cap`，事後核對 FreshRSS 與資料庫的標記一致。

## 架構約束

- 第一次真實執行必須 `markRead=off`；`markRead=on` 前必須完成第 5、6 步。
- 任何步驟發現缺陷，記錄原因後由架構決定是修正、調整規格或回報；不在真實帳號上試錯。
