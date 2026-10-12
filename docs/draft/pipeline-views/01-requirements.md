# 需求摘要

本文回答：為什麼要做 pipeline view、做到什麼程度、有哪些假設與未決事項。

## 目標與使用者

- 開發人員與管理員在 Console 看到某個 pipeline 自己累積的資料，不必離開 Console 用資料庫工具查詢。
- 第一個情境（digest-rss）：目前為止下載了哪些文章；哪些沒有下載，原因是什麼。

## 範圍內

- 通用機制：pipeline 宣告唯讀 view，Engine 執行，Console 以表格顯示。
- 篩選參數，以及從某列鑽取到另一個 view 的連結（例如從 run 到該次 run 的文章）。
- digest-rss 的第一組 view（見 [WI-1303](work-items/WI-1303-digest-rss-views.md)）。

## 範圍外（第一版）

- 圖表、儀表板版面、明細頁（判定引文、模型對話）、影像與封面。
- pipeline 自帶前端程式碼。
- 匯出檔案。
- 以多語系提供 view 標題與欄位名稱（單一語言的文字原樣顯示）。
- 寫入或操作（重試下載等）；view 一律唯讀。

## 非功能需求

- 唯讀且不得影響正在執行的 run（不阻塞寫入、不占用 run 的資源容量）。
- 查詢有逾時、列數與回應大小上限。
- pipeline 資料含不可信內容（RSS 標題等），一律當文字顯示。
- 不洩漏他人上傳的 pipeline 的存在與資料（沿用 ADR-020、ADR-025 的隔離）。

## 假設

- 開發人員已能把自己的 pipeline 綁到任何既有資源，也能在自己的 run 中對該資源執行任意讀寫；view 不新增能力，只新增一條唯讀的讀取路徑。
- digest-rss 的 SQLite 已透過 `jdbc-pool` 資源綁定（ADR-024、ADR-025）。

## 待確認問題

1. 列出「沒下載的原因」時，「保留但頁面與 feed 都沒有 torrent／magnet」是否已能由現有紀錄與「下載失敗」區分？影響 [WI-1303](work-items/WI-1303-digest-rss-views.md) 是否要擴充 pipeline 的紀錄。該項會先驗證再回報。
2. view 結果是否需要提供「連結」欄（例如下載連結）？第一版預設不提供：連結可能帶金鑰，digest-rss 已刻意不選取該欄。
