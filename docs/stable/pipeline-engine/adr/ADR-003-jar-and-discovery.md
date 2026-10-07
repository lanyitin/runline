# ADR-003 Jar 發佈、宣告式 metadata 與探索

狀態：已核可（2026-10-03）；版本的識別方式由 [ADR-020](ADR-020-per-uploader-artifact-versions.md) 修訂為內容雜湊加上傳者。回答：pipeline 如何發佈與被找到。

## 背景
Pipeline 以 jar 發佈，Engine 要以反射或類似機制找出可執行的 pipeline，並在上傳時就取得 metadata 與安全判定。

## 決策
- Jar 透過上傳 API 進入 Engine，內容不可變，以內容雜湊辨識版本。
- Pipeline 與其 metadata 以宣告式方式存在 jar 內，讀取 metadata 不需要執行 pipeline 邏輯。
- 探索發生在上傳時；結果寫入資料庫，執行期不重新探索。
- 探索與分析不得觸發 pipeline 的靜態初始化或業務邏輯。
- 只有 jar 內明確宣告為 pipeline 的入口才可被執行。

## 取捨
- 得到：上傳即回饋、管理員可事先審核、執行期穩定。
- 失去：metadata 表達力受限於宣告式格式，無法依執行期條件動態決定。

## 後果
- 作者需遵守 core 模組的宣告契約。
- 宣告格式的具體選擇由 tdd-coder 在工作項限制下決定。
