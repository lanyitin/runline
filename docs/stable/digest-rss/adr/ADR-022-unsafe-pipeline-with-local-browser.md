# ADR-022 digest-rss 以 unsafe pipeline 在 Engine 主機上驅動本機 Chrome

狀態：已核可（2026-10-08）。回答：頁面擷取放在哪裡、為什麼接受 unsafe。

## 背景

來源流程需要有畫面的真實 Chrome（headless 會得到看似成功的檢查頁）。context 的行程能力是「等結束再回傳」，網路能力是原始 socket，兩者都不能承載長駐瀏覽器與 HTTP 呼叫。

## 決策

- pipeline 在 Engine 主機直接啟動 Chrome，以 DevTools 協定控制，並自帶 HTTP 用戶端呼叫外部服務。
- 此 pipeline 判為 unsafe；管理員對該版本明確允許。pipeline 為薄 jar，只使用 JDK、core 與 Kotlin 標準函式庫，不帶第三方函式庫，因此不需要新增白名單條目；日後若引入第三方函式庫，其白名單條目是管理員的信任決定（ADR-002、ADR-014）。
- LLM、資料庫與憑證檔仍經 context 與資源存取端。
- run 取消、逾時或結束時，pipeline 必須結束自己啟動的 Chrome 行程。
- Chrome 需要磁碟上的使用者資料目錄。它是瀏覽器行程自己的暫存：每個 run 專屬、位於系統暫存區（容器內為 tmpfs 或可寫目錄）、run 結束時刪除；不屬於「pipeline 不寫檔」的範圍。
- Engine 被強制終止時可能遺留 Chrome；pipeline 在每個 run 開始時清理屬於本 pipeline 且已失去擁有者的 Chrome 行程與暫存目錄，判定依據是行程的專屬使用者資料目錄，不影響其他 Chrome。
- Chrome 以 `--ignore-gpu-blocklist` 啟動；渲染器要求見 [WI-1000](../work-items/WI-1000-browser-feasibility.md)。

## 取捨

- 得到：單一部署單元、沿用 Engine 的排程、取消與 log。
- 失去：這條 pipeline 不受 context 限制、不可錄製；Engine 主機需安裝 Chrome 與顯示。

## 後果

- 部署需求見 [04](../04-deployment.md)；可行性關卡見 [WI-1000](../work-items/WI-1000-browser-feasibility.md)。
- 若日後要讓此 pipeline 回到 safe，改採獨立擷取服務（[03](../03-options.md) 的方案 B），屬新決策。
