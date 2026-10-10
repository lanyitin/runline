# WI-1006 頁面擷取

本文回答：頁面擷取要達成什麼行為。狀態：已核可（2026-10-09）。

目標：對每篇候選用瀏覽器擷取封面圖與可見文字，產出可讀清單（worklist），結果記入資料庫。行為參考：來源的 `CAPTURE.md`、`page-capture.mjs`、`extract.mjs`、`profiles/`、`capture-all.sh`。工作儲存庫：`runline-digest-rss`。

## 驗收條件

1. 站點 profile 路由與來源一致：站點專屬（網址或網域）→ 播放器（頁面結構偵測）→ 通用，優先序由低到高；現有五個 profile（generic、onejav、missav、plyr、141ppv）行為等價。新增站點只需新增一個 profile 定義，不改路由。
2. 封面：先取元素截圖，再嘗試下載原圖（成功則原圖為主），都取不到時才用整頁截圖；整頁截圖的文章不進入 worklist。
3. 文字：先截圖再隱藏頁面雜訊再取文字（順序有意義）；含超過 400 字的元素不被當雜訊隱藏；字數上限與來源一致。
4. 下載連結：只在 profile 指定的內容範圍內找 magnet 或 torrent；torrent 連結在頁面開著時於瀏覽器 session 內取得並驗證為合法 torrent，轉成 magnet；取不到時保留原連結並附原因。
5. 5xx 視為失敗；失敗、過薄（少於 40 字，可設定）與要求的欄位缺漏者不進 worklist，單篇失敗不中止整個 run。
6. 每篇的結果寫入 CAPTURE：profile 與匹配方式、HTTP 狀態、文字與字數、封面種類與來源網址與影像、下載連結與解析結果、錯誤、耗時。影像與文字不經檔案傳遞（記憶體或資料庫）。
7. 以本機 HTTP 服務提供的真實測試頁（對應來源的 fixture：播放器、通用、僅 og:image、torrent 列表含誘餌連結）驗證，誘餌連結不被選中。
8. 同一次 run 內單一瀏覽器工作階段；run 結束或取消時結束 Chrome。
9. 每個 run 開始時，清理先前被強制終止所遺留、屬於本 pipeline 的 Chrome 行程與專屬暫存目錄（判定依據是專屬使用者資料目錄，不影響其他 Chrome）；以模擬強制終止遺留行程的方式驗證，並驗證不會誤殺不相干的 Chrome。
10. 檢查頁的辨識不只看 HTTP 狀態：以回應狀態為 5xx 呈現的挑戰頁若能由頁面特徵確認為檢查頁，同樣視為失敗；不能確認時維持以狀態碼判定，並把判定依據記入 CAPTURE。
11. 硬體渲染的啟動檢查：部署以 `CHROME_REQUIRE_HARDWARE_RENDERING` 提供「要求硬體渲染」的設定（[WI-1001](WI-1001-engine-browser-deployment.md)、`deploy/browser.md`）。該設定啟用時，run 在開始擷取之前以 pipeline 自己啟動的 Chrome 讀出 WebGL 渲染器；為軟體渲染（例如 llvmpipe、SwiftShader）或讀不到時，run 以明確原因失敗，不開始擷取、不標記；未啟用時只在 log 與 RUN 記錄渲染器字串，不影響執行。以「渲染器回報為軟體渲染」的環境驗證兩種設定下的行為。

## 架構約束

- 依 WI-1000 的結論；不使用內含 Node 的函式庫。
- 擷取內容視為不受信任輸入。
- Chrome 的使用者資料目錄是瀏覽器行程自己的暫存，不視為 pipeline 的寫入（ADR-022）。
