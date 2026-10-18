# #1：Wiki 是 stable 的唯讀映像

本文回答：Forgejo Wiki 與 `docs/stable/` 的關係，以及為何採單向同步。狀態：提案中。Issue：#1。

## 背景

stable 是核可後的單一事實來源。Wiki 提供較易瀏覽的入口，但若可直接編輯，會出現兩份內容分歧。

## 決策

- Wiki 內容完全由 `docs/stable/` 產生，單向同步，不接受 Wiki 上的直接修改。
- 同步由 `main` 上 `docs/stable/` 的變動觸發，Wiki 的差異以 stable 為準，被覆蓋即視為正確。
- 保留 stable 的主題階層；首頁目錄依主題列出各入口文件。
- `docs/draft/` 不進 Wiki。

## 取捨

- 優點：單一事實來源不變，審查流程（draft→PR→stable）不受影響。
- 缺點：Wiki 無法快速修錯字，須走 stable 的修訂流程；階層對應與連結改寫需要額外規則。
- 替代方案：把 ADR 攤平放到 Wiki（原規範做法）。不採用，因 stable 內有多個主題含同名檔案，攤平會衝突。

## 後果

- 任何文件修訂都從 draft 開始，Wiki 只反映已核可版本。
- 同步失敗時 Wiki 暫時落後，不影響 stable 的正確性。
