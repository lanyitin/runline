# transcribe-scenes 工作項總覽

狀態：已核可（2026-10-14）。本文回答：要交給 tdd-coder 的工作有哪些、先後順序為何。tdd-coder 只需讀本頁、被派到的那一項，以及入口 [README](../README.md)。編號接續 transcribe（WI-1451 起）。一次派一項；相依項完成並驗收前不派下一項。

儲存庫：「R」為 runline，「T」為 runline-transcribe。真實硬體（arr、Lemonade）上的量測由使用者或 homelab-deployer 執行，tdd-coder 提供量測工具與步驟。

| 項目 | 儲存庫 | 目標 | 相依 | 狀態 |
|---|---|---|---|---|
| [WI-1451](WI-1451-feasibility-forced-alignment.md) | T、R | 可行性關卡：Qwen3-ForcedAligner 在 Engine 映像的 CPU 上對齊 30 秒片段的精度、耗時與映像大小 | 無 | 已核可（2026-10-14），待派工 |
| [WI-1452](WI-1452-feasibility-long-chunks-and-segments.md) | T | 可行性關卡：30 秒片段的轉錄品質與請求大小；場景片段附圖的潤飾成本；換人標記的正確率 | 無 | 已核可（2026-10-14），待派工 |
| [WI-1453](WI-1453-thinking-policy-and-early-stop.md) | T | thinking 取樣政策、分開的逾時、截斷不拆批、及早止損、推理與 5xx 的紀錄 | 無 | 已核可（2026-10-14），待派工 |
| [WI-1454](WI-1454-aligner-in-image.md) | R、T | 對齊工具與模型檔進入 `transcribe` 系列映像與主機部署 | WI-1451 | 已核可（2026-10-14），待派工 |
| [WI-1455](WI-1455-long-chunks-alignment-entries.md) | T | 長片段切分、轉錄、對齊、組成條目；移除 VAD-A／B、補掃與輸出時合併 | WI-1452、WI-1453、WI-1454 | 已核可（2026-10-14），待派工 |
| [WI-1456](WI-1456-scenes-and-segments.md) | T | 場景偵測與場景片段、條目歸屬 | WI-1455 | 已核可（2026-10-14），待派工 |
| [WI-1457](WI-1457-polish-with-turns-and-dash.md) | T | 潤飾附畫面與換人資料；SRT 依參數畫破折號；刪除核對階段 | WI-1456 | 已核可（2026-10-14），待派工 |
| [WI-1458](WI-1458-three-layer-summary.md) | T | 片段、場景、全片三層摘要與角色表 | WI-1457 | 已核可（2026-10-14），待派工 |
| [WI-1459](WI-1459-translation-with-context.md) | T | 翻譯附角色表、場景摘要與前文，保留行與換人 | WI-1458 | 已核可（2026-10-14），待派工 |
| [WI-1460](WI-1460-views-and-docs-alignment.md) | T、R | Console view 改讀新資料；runbook、資料模型、入口文件對齊 | WI-1459 | 已核可（2026-10-14），待派工 |

派工順序：WI-1453 可先行，因為與其他項目無相依，且立即減少長時間空轉。兩個關卡（WI-1451、WI-1452）也可先行。關卡不通過時停下來，由架構決定，不繼續後續項目。
