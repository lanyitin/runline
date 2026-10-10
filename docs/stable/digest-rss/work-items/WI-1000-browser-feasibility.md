# WI-1000 瀏覽器可行性關卡

本文回答：瀏覽器擷取在什麼條件下算可行。狀態：已核可（2026-10-08）。

目標：證明純 Kotlin／JVM 能以 DevTools 協定驅動有畫面的真實 Chrome，達到現行擷取的效果。這是關卡：不通過就停下來，由架構決定（改為獨立擷取服務或調整範圍），不繼續後續項目。工作儲存庫：`runline-digest-rss`。

## 驗收條件

1. 從 JVM 啟動有畫面的真實 Chrome（非 headless），開頁、等待、對指定元素截圖、取得可見文字，並在頁面開著時以該 session 下載一個資源。
2. 指紋：自動化標記不外露（`navigator.webdriver` 為 false、無自動化相關的視窗屬性），且 WebGL 渲染器依環境判定如下。量測時 Chrome 必須帶 `--ignore-gpu-blocklist`，否則 WebGL 不存在。
   - 開發機（實體顯示）：渲染器不是軟體渲染。
   - 目標主機（amd64 Linux，Intel iGPU 以 `/dev/dri` 直通，容器內以虛擬顯示執行）：
     - 渲染器為硬體 GPU：通過。
     - 渲染器為 llvmpipe 或 SwiftShader（軟體渲染）：條件通過，前提是第 3 條的三類站點在該環境各重複至少 5 次都放行，並把渲染器字串記入報告。
     - 沒有 WebGL：不通過。
   - 開發機上的 Linux 容器（Rosetta、無 GPU）只作為補充資料，不能取代目標主機的判定。
3. 對現行會遇到的站點類型（需 Cloudflare clearance 者、一般播放器頁面、torrent 列表頁）各至少一個實測頁，能取得真實內容而非檢查頁；軟體渲染環境下每類至少重複 5 次。
4. 回應為 5xx 時視為失敗，不當作頁面。
5. 呼叫端取消或逾時時，Chrome 行程在限定時間內結束，不留遺留行程；重複 50 次無累積。開發機與 Linux 容器各驗證一次。
6. 不使用 Playwright for Java、Selenium 或任何內含 Node 的函式庫。
7. 產出一份 draft 報告（放 runline 的 `docs/draft/digest-rss/`）：量測內容、結果、剩餘風險；報告列出在目標主機上重跑量測的步驟（掛載 `/dev/dri`、以 root 或加入裝置所屬群組執行、必要旗標、輸出如何回傳）。
8. 目標主機不另做專案內的量測，以下列依據代替（使用者決定，2026-10-08）：pi-env 現行部署在該主機上已量測過指紋與站點放行；本專案在 Linux 容器（Rosetta、軟體渲染 llvmpipe）下三類站點 15/15 放行。本項據此判定為「條件通過」。殘餘風險是 pi-env 的量測使用的是不同的瀏覽器驅動程式，不能直接證明本專案的 DevTools 實作在目標主機上同樣放行；此風險由 [WI-1011](WI-1011-end-to-end-and-runbook.md) 在真實環境的首次執行（標記關閉）驗證，結果不如預期時停下來由架構決定。

## 架構約束

- 只驗證可行性，不建立完整 pipeline；成果以後續項目可重用的方式留下，但不預設其內部設計。
- 不放寬 ADR-022 的範圍。
- Chrome 的使用者資料目錄是瀏覽器行程自己的暫存，每個 run 專屬，結束時刪除；不屬於 pipeline 的寫入（[ADR-022](../adr/ADR-022-unsafe-pipeline-with-local-browser.md)、ADR-024）。
