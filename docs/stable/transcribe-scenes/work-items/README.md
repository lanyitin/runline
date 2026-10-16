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
| [WI-1461](WI-1461-asr-generation-cap.md) | T | 每個轉錄請求帶生成上限，截斷依規則處理；量測工具套用同樣的上限 | WI-1453 | 已完成（2026-10-14） |
| [WI-1462](WI-1462-gateway-alignment-endpoint.md) | G | llm-gateway 以 GPU 提供對齊模型，經 OpenAI 相容的音訊轉錄端點；與 Lemonade、d1 輪流佔用 GPU | 無 | 已完成並在實機驗證（2026-10-14）；條件 9 已量測：切換約 4 至 5 秒、每片段中位數 0.07 秒、對齊期間 GPU 總佔用峰值 2,519 MB |
| [WI-1451](WI-1451-feasibility-forced-alignment.md) | T | 可行性關卡：經 gateway 以 GPU 對齊 30 秒片段的耗時與失敗率；時間精度延後到成品字幕核對 | WI-1461、WI-1462 | 關卡通過（2026-10-14）：一小時推估約 1 分鐘，對齊失敗 0；時間精度待成品字幕核對 |
| [WI-1452](WI-1452-feasibility-long-chunks-and-segments.md) | T | 可行性關卡：30 秒片段的請求大小與耗時；場景片段附圖的潤飾成本；場景偵測的門檻；轉錄品質與換人正確率延後到成品字幕核對 | 無（轉錄的量測依賴 WI-1461） | 關卡通過（2026-10-14）：請求大小、成本、場景偵測皆符合；預設值見 [ADR-058](../adr/ADR-058-measured-defaults.md)；轉錄品質與換人正確率待成品字幕核對 |
| [WI-1454](WI-1454-aligner-in-image.md) | R、T | 對齊工具與模型檔進入 `transcribe` 系列映像與主機部署 | WI-1451 | 作廢（2026-10-14），由 ADR-056 與 WI-1462 取代 |
| [WI-1455](WI-1455-long-chunks-alignment-entries.md) | T | 長片段切分、轉錄、經 gateway 對齊、組成條目；移除 VAD-A／B、補掃與輸出時合併 | WI-1451、WI-1452、WI-1453、WI-1461、WI-1462 | 已完成（2026-10-15），T 的 55bb8a1；舊表暫時仍寫（橋接），至 WI-1456 起逐項替換 |
| [WI-1456](WI-1456-scenes-and-segments.md) | T | 場景偵測與場景片段、條目歸屬 | WI-1455 | 已完成（2026-10-15），T 的 fa94d03；舊表仍寫，由 WI-1457 至 WI-1460 替換讀取者後停寫 |
| [WI-1457](WI-1457-polish-with-turns-and-dash.md) | T | 潤飾附畫面與換人資料；SRT 依參數畫破折號；刪除核對階段 | WI-1456 | 已完成（2026-10-15），T 的 faa767d；移除 verify* 與 maxFramesPerRequest 參數，新增 framesPerSegment、subtitleDialogueDash |
| [WI-1458](WI-1458-three-layer-summary.md) | T | 片段、場景、全片三層摘要與角色表 | WI-1457 | 已完成（2026-10-15），T 的 da8fb20；移除 summaryGroupSystemPrompt，新增 summarySegmentSystemPrompt、summarySceneSystemPrompt；group_summary 暫時橋接寫入至 WI-1460 |
| [WI-1459](WI-1459-translation-with-context.md) | T | 翻譯附角色表、場景摘要與前文，保留行與換人 | WI-1458 | 已完成（2026-10-15），T 的 93eedb8；移除 contextBatchEntries、contextSeconds、contextMaxEntries；舊表橋接寫入至 WI-1460 |
| [WI-1460](WI-1460-views-and-docs-alignment.md) | T、R | Console view 改讀新資料；runbook、資料模型、入口文件對齊 | WI-1459 | 已核可（2026-10-14），T 的部分已完成（2026-10-15，T 的 55a664a）；R 的對齊稿在 draft（07-nfr-risks、01 第 56 行），待核可 |

實機測試後追加：

| 項目 | 儲存庫 | 目標 | 相依 | 狀態 |
|---|---|---|---|---|
| [WI-1463](WI-1463-truncation-degrades-chunk.md) | T | 轉錄片段被截斷時降級，不重試、不計入及早止損；全部失敗才讓 run 失敗 | WI-1455、WI-1456 | 已完成（2026-10-15），T 的 161838b；已核可 |
| [WI-1464](WI-1464-times-out-of-model-answers.md) | T | 潤飾與翻譯的請求與回答不帶條目時間，由 pipeline 依序號補回；失敗 run 的降級數顯示修正 | WI-1457、WI-1459 | 已完成（2026-10-15），T 的 53e3466；已核可 |
| [WI-1465](WI-1465-repetition-penalties.md) | T | 轉錄截斷後帶重複懲罰重試一次；摘要可選的懲罰參數 | WI-1463、WI-1464 | 已完成（2026-10-15），T 的 0ef37ac；已核可 |
| [WI-1466](WI-1466-low-information-transcript.md) | T | 轉錄文字幾乎全是重複語助詞時視同沒有文字：不產出條目、不參與來源語言、全部如此時 run 以 NO_TRANSCRIPT 失敗；降級括號納入轉錄 | WI-1463、WI-1465 | 已完成（2026-10-15），T 的 c730302；新增參數 asrLowInformationRatio（預設 0.75）；相關範圍 676 個測試通過；已核可 |
| [WI-1467](WI-1467-resume-run-from-stage.md) | T | 失敗或中斷的 run 以新 run 從任一階段續跑：起點之前沿用來源 run，起點階段按單位延續，之後重算；參數可覆寫 | WI-1455 至 WI-1465 | 已完成（2026-10-15），T 的 6cbf0a2、53fe0db、c9ba617；全套測試因使用者決定中斷而未跑完，相關範圍 119 個測試通過；已核可 |
| [WI-1468](WI-1468-answer-contract-per-segment.md) | T | 潤飾、摘要、翻譯的回答違反契約：重試附原因、翻譯行數規則依換人標記放寬、未翻譯獨立成因、保留合格條目、及早止損以片段計數 | WI-1459、WI-1464、WI-1465 | 已完成（2026-10-15），T 的 aed32ca；相關範圍 779 個測試，除既有的 DenoiseRecordsTest 一項失敗外通過；已核可 |
| [WI-1469](WI-1469-test-suite-speed.md) | T | 縮短測試套件執行時間：先量測，去掉人為等待、共用啟動、類別層級並行、量測工具與平台相依測試分組；不降低檢查力 | 無 | 部分完成（2026-10-15），T 的 2d1388c、92b8b80、ba0cdeb、ecd9747：共用樣本、降噪基準跳過、並行與 measurementTest 分組；全套時間目標未驗證；已核可 |
| [WI-1470](WI-1470-resume-from-degraded-run.md) | T | success-degraded 的 run 也可當續跑來源：降級的單位視為未完成而重做；續跑只送要變更的參數，不繼承來源 run 的值 | WI-1467、WI-1468 | 已完成（2026-10-15），T 的 4d6df56；相關範圍 682 個測試，除既有的 DenoiseRecordsTest 一項失敗外通過；已核可 |
| [WI-1471](WI-1471-any-chinese-and-context-check.md) | T | 翻譯接受任何中文字形（取消簡體字檢查）；語境重複檢查不誤判與前文譯文相同的短句 | WI-1468 | 已完成（2026-10-15），T 的 10de292；相關範圍 489 個測試通過（唯一的 SummaryRunTest 失敗由 WI-1466 的測試資料引起，已在 T 的 618d016 修正）；已核可 |
| [WI-1472](WI-1472-recheck-carried-units.md) | T | 續跑時以現行規則重新檢查被沿用的譯文（含假名、行數），不通過的條目重做 | WI-1470 | 已完成（2026-10-15），T 的 709032a；相關範圍測試全數通過；已核可 |
| [WI-1473](WI-1473-lenient-translate-polish-answers.md) | T | 翻譯與潤飾的回答依序號寬鬆解析，取不到才以條目降級；翻譯的換人標記由 pipeline 補回 | WI-1468、WI-1471、WI-1472 | 已完成（2026-10-15），T 的 9eb335a；相關範圍 443 個測試通過（最後一次修正後未重跑整組）；截斷仍重試，留給 WI-1475；已核可 |
| [WI-1474](WI-1474-lenient-summary-answers.md) | T | 摘要與角色表的回答寬鬆解析：標記尋找、語言碼對應、角色逐列採用、超長截斷 | WI-1458、WI-1468 | 已完成（2026-10-15），T 的 1e8792e，合併於 8dcf823；相關範圍 416 個測試通過；已核可 |
| [WI-1475](WI-1475-retry-and-early-stop-policy.md) | T | 重試只留給服務錯誤與空手而回（每片段 1 次，不附原因）；及早止損以片段計數；角色表不為描述編譯名 | WI-1468 | 已完成（2026-10-15），T 的 774ce76，合併於 832c4f8；`modelRetries` 預設改為 1（轉錄與對齊共用此參數）；已核可 |
| [WI-1476](WI-1476-cancelled-run-records-its-end.md) | T | 被取消的 run 記錄結束狀態並可當續跑來源；取消盡快中止進行中的呼叫 | WI-1467 | 已完成（2026-10-15），T 的 dd354fb；取消、逾時、中斷的 run 記錄結束並寫入 `result_note = STOPPED`，被中止的階段列標為 `aborted`；取消的反應原本即時（假服務量到 3 至 7 毫秒，真實硬體未量測）；相關範圍 232 個測試，除既有的 DenoiseRecordsTest 一項失敗外通過；已核可 |
| [WI-1477](WI-1477-retry-untranslated-entries.md) | T | 翻譯中未翻譯的條目重試一次，只重送那些條目；與 WI-1475 對「未翻譯不重試」的敘述衝突時以本項為準 | WI-1473、WI-1475 | 已完成（2026-10-15），T 的 0975535，合併於 832c4f8；`UntranslatedRetryRunTest` 11 個與 `TranslateRunTest`、`ResumeFromDegradedRunTest`、`ThinJarTest` 通過；已核可 |
| [WI-1478](WI-1478-html-run-report.md) | T | 每個 run 結束時產生 HTML 報告（參數 `htmlReport`，預設開）：播放器形式、三階段字幕、場景背景色與場景摘要 | WI-1459 | 已完成（2026-10-15），T 的 000f1a2，合併於 74bd40d；影片旁寫出 `影片檔名.report.html`；在無頭 Chrome 驗證影片同步與背景色變換，聲音與其他瀏覽器未檢查；已核可 |
| [WI-1479](WI-1479-measure-feedback-time.md) | T | 量測一次工作回饋的完整成本（編譯、依賴、每個類別的耗時、並行利用率、端到端單價），不改測試 | 無 | 已完成（2026-10-16），T 的 0e25a20；報告在 T 的 `docs/test-feedback-report.md`；暖快取全套 7.5 分鐘、冷快取 10 至 12 分鐘，端到端占 87.5%，一次端到端執行的 47% 是 VAD 子行程；已核可 |
| [WI-1480](WI-1480-rebalance-tests.md) | T | 依量測重配測試層：行為規則下沉到純邏輯與行程內整合層，端到端縮到少量，放寬測試替身；目標日常相關測試 ≤ 3 分鐘、全套 ≤ 10 分鐘 | WI-1479 | 已完成（2026-10-16），T 的 f38e37d；全套暖快取 5 分 39 秒至 5 分 54 秒、冷快取 7 分 17 秒，純邏輯 2.5 秒，單一工作項（以翻譯為例）24 秒；連續 3 次結果一致；類別順序的隨機未做（建置為 JUnit 4 加 category 過濾，無此機制）；已核可 |

量測報告（提案中）：[WI-1451](../../../draft/transcribe-scenes/WI-1451-forced-alignment-report.md)、[WI-1452](../../../draft/transcribe-scenes/WI-1452-long-chunks-and-segments-report.md)。

派工順序：
1. WI-1461、WI-1462、WI-1451 的工具修訂：已完成。
2. WI-1451 與 WI-1452 的實機量測：已完成，兩個關卡通過。
3. WI-1455 及其後各項依相依順序派工；WI-1455 的版本上線前，Engine 資源的上游改指 gateway（[04](../04-deployment.md)）。

關卡不通過時停下來，由架構決定，不繼續後續項目。
