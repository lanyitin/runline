# WI-01 Pipeline 撰寫契約

目標：開發人員能用 Kotlin 宣告一個 pipeline 及其 metadata，並透過 context 做 IO。

## 行為與驗收條件
- 作者可宣告 pipeline 名稱與參數；宣告為 Jenkins 風格的程式化流程（循序步驟為主）。
- Metadata 以宣告式存在編譯產物內；不執行 pipeline 邏輯即可讀出。
- Metadata 可宣告使用哪些檔案範圍（pipeline 共享目錄、run 私有目錄）及各自唯讀或可寫；未宣告的範圍不可用。
- Metadata 對網路連線與外部行程執行分別表達「允許範圍」或「不限制」；未提供視同未限制。
- Context 的檔案操作只接受相對於上述兩種目錄的位置；相對路徑或符號連結跳出範圍的嘗試被拒絕，並產生清楚的錯誤。
- Metadata 可宣告 pipeline 可能使用的共享資源名稱；宣告與否不影響 safe／unsafe 判定。
- Context 提供對應各 IO 類別的操作；網路與外部行程最寬鬆時不阻擋。共享資源由初始化階段處理，context 不提供取得資源的操作。
- 範圍外的存取（超出 metadata）被拒絕，並產生清楚、可歸屬到該 pipeline 的錯誤。
- 契約文件化，含範例 pipeline（範例屬於 tdd-coder 產出）。

## 架構約束
- 不依賴 Ktor、Engine 或資料庫。
- Metadata 不含 trigger（[ADR-005](../adr/ADR-005-trigger-binding.md)）。
- 契約需能穿過 class loader 邊界而不外洩 Engine 型別。
