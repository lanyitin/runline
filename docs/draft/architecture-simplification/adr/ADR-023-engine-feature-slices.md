# ADR-023 Engine 以功能切片組織，功能之間單向依賴並由建置檢查

狀態：提案中（2026-10-30）。回答：engine 模組內部如何劃分責任、功能之間允許哪些依賴、如何防止退化。

## 背景

engine 依功能分了套件，但路由集中在根套件、組態集中解析、組裝集中在一處，且功能之間有 5 組循環依賴（[02](../02-current-state.md) 發現二）。理解或修改一個功能需要跨四處閱讀。

## 決策

1. **功能切片**：每個功能（artifact、allowlist、run、trigger、resource、secret、retention、health、info、console）擁有自己的 HTTP 路由、組態段、服務、儲存與可觀測。根套件只保留行程入口、啟動與關閉流程、以及把各功能接起來的組裝。
2. **三層**：入口層（行程入口、組裝、啟動與關閉）→ 功能層 → 共用層（API 共用型別如錯誤回應與身分、認證與授權、資料庫連線與遷移、組態讀取的基礎、可觀測的基礎）。共用層不依賴任何功能；功能不依賴入口層。
3. **功能之間的允許方向**（未列出者一律不允許）：

| 功能 | 可依賴的功能 |
|---|---|
| trigger | run、artifact |
| run | resource、artifact、secret |
| resource | secret、artifact |
| allowlist | artifact |
| retention | run、trigger |
| info | allowlist |
| artifact、secret、health、console | 無 |

4. **反向需求以上游提供查詢或由入口層組合**：例如「哪些資源使用某機密別名」由 resource 回答，secret 不讀資源儲存；共用的錯誤回應與文件型別放在共用層。
5. **自動檢查**：建置的 `check` 驗證上表與三層規則，以及 Gradle 模組之間的允許方向（依[入口文件](../README.md)的目標模組關係：core 不依賴任何專案模組；analyzer 只依賴 core，見 [ADR-025](ADR-025-analyzer-shares-core-metadata.md)；accessors 只依賴 core 與 runner）。違規使建置失敗並指出違規的來源與目標。檢查不新增 CI（專案沒有 CI）。

## 取捨

- 得到：一個功能在一個套件內讀完；依賴方向明確，新增反向依賴會立即失敗。
- 付出：一次性的搬移；部分型別需移到共用層或改由上游提供查詢。
- 不做：拆成多個 Gradle 模組（[03](../03-options.md) 選項 C）。

## 後果

- 對外行為不變；API 文件與路由一致性的既有測試維持。
- 方向表是唯一來源；要新增方向，先修訂本 ADR，再改檢查規則。
