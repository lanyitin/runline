# transcribe-scenes 工作項總覽

狀態：已核可（2026-10-14）。本文回答：要交給 tdd-coder 的工作有哪些、先後順序為何。tdd-coder 只需讀本頁、被派到的那一項，以及入口 [README](../README.md)。編號接續 transcribe（WI-1451 起）。一次派一項；相依項完成並驗收前不派下一項。

儲存庫：
- 「R」為 runline；
- 「T」為 runline-transcribe；
- 「G」為 llm-gateway（`/Users/lanyitin/Projects/Personal/llm-gateway`）。

真實硬體（arr、Lemonade 主機）上的量測由使用者或 homelab-deployer 執行，tdd-coder 提供量測工具與步驟。

| 項目 | 儲存庫 | 目標 | 相依 | 狀態 |
|---|---|---|---|---|
| [WI-1453](WI-1453-thinking-policy-and-early-stop.md) | T | thinking 取樣政策、分開的逾時、截斷不拆批、及早止損、推理與 5xx 的紀錄 | 無 | 已完成（2026-10-14）；runbook 的部分併入 WI-1460 |
| [WI-1461](WI-1461-asr-generation-cap.md) | T | 每個轉錄請求帶生成上限，截斷依規則處理；量測工具套用同樣的上限 | WI-1453 | 已核可（2026-10-14），派工中 |
| [WI-1462](WI-1462-gateway-alignment-endpoint.md) | G | llm-gateway 以 GPU 提供對齊模型，經 OpenAI 相容的音訊轉錄端點；與 Lemonade、d1 輪流佔用 GPU | 無 | 已完成並在實機驗證核心路徑（2026-10-14）；條件 9 的效能紀錄待量測 |
| [WI-1451](WI-1451-feasibility-forced-alignment.md) | T | 可行性關卡：經 gateway 以 GPU 對齊 30 秒片段的耗時與失敗率；時間精度延後到成品字幕核對 | WI-1461、WI-1462 | 關卡通過（2026-10-14）：一小時推估約 1 分鐘，對齊失敗 0；時間精度待成品字幕核對 |
| [WI-1452](WI-1452-feasibility-long-chunks-and-segments.md) | T | 可行性關卡：30 秒片段的請求大小與耗時；場景片段附圖的潤飾成本；場景偵測的門檻；轉錄品質與換人正確率延後到成品字幕核對 | 無（轉錄的量測依賴 WI-1461） | 量測已完成（2026-10-14），成本符合；待使用者提供 `maxRequestBytes` 與判定場景偵測後通過 |
| [WI-1454](WI-1454-aligner-in-image.md) | R、T | 對齊工具與模型檔進入 `transcribe` 系列映像與主機部署 | WI-1451 | 作廢（2026-10-14），由 ADR-056 與 WI-1462 取代 |
| [WI-1455](WI-1455-long-chunks-alignment-entries.md) | T | 長片段切分、轉錄、經 gateway 對齊、組成條目；移除 VAD-A／B、補掃與輸出時合併 | WI-1451、WI-1452、WI-1453、WI-1461、WI-1462 | 已核可（2026-10-14），待派工 |
| [WI-1456](WI-1456-scenes-and-segments.md) | T | 場景偵測與場景片段、條目歸屬 | WI-1455 | 已核可（2026-10-14），待派工 |
| [WI-1457](WI-1457-polish-with-turns-and-dash.md) | T | 潤飾附畫面與換人資料；SRT 依參數畫破折號；刪除核對階段 | WI-1456 | 已核可（2026-10-14），待派工 |
| [WI-1458](WI-1458-three-layer-summary.md) | T | 片段、場景、全片三層摘要與角色表 | WI-1457 | 已核可（2026-10-14），待派工 |
| [WI-1459](WI-1459-translation-with-context.md) | T | 翻譯附角色表、場景摘要與前文，保留行與換人 | WI-1458 | 已核可（2026-10-14），待派工 |
| [WI-1460](WI-1460-views-and-docs-alignment.md) | T、R | Console view 改讀新資料；runbook、資料模型、入口文件對齊 | WI-1459 | 已核可（2026-10-14），待派工 |

量測報告（提案中）：[WI-1451](../../../draft/transcribe-scenes/WI-1451-forced-alignment-report.md)、[WI-1452](../../../draft/transcribe-scenes/WI-1452-long-chunks-and-segments-report.md)。

派工順序：
1. WI-1461、WI-1462、WI-1451 的工具修訂：已完成。
2. WI-1451 與 WI-1452 的實機量測：已完成；WI-1451 關卡通過，WI-1452 待 `maxRequestBytes` 與場景偵測的判定。
3. 兩個關卡通過後，Engine 資源的上游改指 gateway（[04](../04-deployment.md)），再派 WI-1455 及其後各項。

關卡不通過時停下來，由架構決定，不繼續後續項目。
