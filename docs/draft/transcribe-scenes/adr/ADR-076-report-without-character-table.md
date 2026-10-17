# ADR-076 HTML 報告不再顯示角色表

本文回答：移除角色表之後，HTML 報告的可展開區塊顯示什麼。狀態：提案中（2026-10-16）。修訂 [ADR-071](../../../stable/transcribe-scenes/adr/ADR-071-html-run-report.md) 決策 6 與資料描述中的「全片摘要與角色表」，以及 [WI-1478](../../../stable/transcribe-scenes/work-items/WI-1478-html-run-report.md) 驗收條件 7、8 中的「角色表」「角色名」；其餘不變。

## 背景

[ADR-074](../../../stable/transcribe-scenes/adr/ADR-074-remove-character-table.md) 移除角色表，新 run 不再寫入。ADR-071 與 WI-1478 仍寫報告含角色表。

## 決策

1. 報告可展開的區塊只顯示全片摘要，標題為「全片摘要」。
2. 報告不讀取角色表資料；舊 run 若重新產生報告，同樣不顯示角色表（Console 的 characters view 仍可查舊資料）。
3. 不可信內容的規則（純文字顯示）不變，適用於字幕與摘要。

## 取捨

- 得到：報告與資料模型一致，新舊 run 的報告外觀相同。
- 失去：舊 run 的報告看不到角色表；需要時從 Console 查看。

## 後果

- 實作已隨 WI-1482 完成（T 的報告資料、報告頁面與標題）。
- 核可後，ADR-071 與 WI-1478 的對應敘述改為本決策的內容。
