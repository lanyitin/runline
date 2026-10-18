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

Whisper 二次轉錄與移除角色表（2026-10-16 核可）：

| 項目 | 儲存庫 | 目標 | 相依 | 狀態 |
|---|---|---|---|---|
| [WI-1481](WI-1481-whisper-second-pass.md) | T | 降級或語言不符的片段，以第二個轉錄模型在固定語言下二次轉錄；預設開啟、預設模型 Kotoba-Whisper（日語），run 語言由首次轉錄的書寫系統決定；採用規則與幻覺句處理 | WI-1463、WI-1466、WI-1467 | 已完成，T 的 4b66430，合併於 ce27e71；驗收條件尚未由架構師逐條重跑；決策見 [ADR-073](../adr/ADR-073-whisper-second-pass.md)、[ADR-075](../adr/ADR-075-second-pass-default-on-with-kotoba.md) |
| [WI-1482](WI-1482-remove-character-table.md) | T、R | 移除角色表與全片摘要的出場人物；翻譯不附譯名 | WI-1474、WI-1475 | 已完成，T 的 02d3d3e，合併於 8358a56；驗收條件尚未由架構師逐條重跑；決策見 [ADR-074](../adr/ADR-074-remove-character-table.md) |

兩項沒有相依，可並行（各自在獨立的 worktree 與分支）。兩項都改 T 的摘要與轉錄附近的程式，合併時預期有衝突，由使用者決定合併順序。

摘要外洩與降級（2026-10-18 核可；量測見[報告](../summary-leak-experiment-report.md)）：

| 項目 | 儲存庫 | 目標 | 相依 | 狀態 |
|---|---|---|---|---|
| [WI-1483](WI-1483-scene-summary-answer-opening.md) | T | thinking 關閉時，場景層摘要請求的預填接上第一行標記；解析接受標記來自預填 | 無 | 已完成（2026-10-18），T 的 be5c6ff 合併；摘要相關測試 92 個通過；決策見 [ADR-077](../adr/ADR-077-scene-summary-answer-opening.md) |
| [WI-1484](WI-1484-segment-summary-wording.md) | T | 片段摘要的提示規定稱呼自己為「片段」，不使用「場景」 | 無 | 已完成（2026-10-18），T 的 dfc1052 合併；用詞規定併入 WI-1488 的片段提示；決策見 [ADR-078](../adr/ADR-078-segment-summary-wording.md) |
| [WI-1485](WI-1485-no-input-degradation.md) | T | 沒有素材的片段與場景不呼叫模型，直接降級並記下原因 | 無 | 已完成（2026-10-18），T 的 f212813 合併；日常套件 966 個測試，僅既有的 DenoiseRecordsTest 與偶發的 SchemaMigratorTest 失敗；新增遷移 V17；後續補上全片摘要沒有輸入的缺少原因（遷移 V18、T 的 f5115e1）與 SchemaMigrator 競爭修正；決策見 [ADR-080](../adr/ADR-080-summary-degradation-conditions.md) 決策 1、2、5、6 |
| [WI-1488](WI-1488-default-summary-prompts.md) | T | 場景層預設提示改為四個欄位、片段層預設提示改為一到三句且不含「scene」（內容依[參考文件](../run38-summary-prompts-reference.md)） | WI-1483、WI-1484、WI-1485 | 已完成（2026-10-18），T 的 9ed4aa4 合併；決策見 [ADR-081](../adr/ADR-081-default-summary-prompts-adopt-measured-structure.md) |
| [WI-1486](WI-1486-scene-summary-field-block-only.md) | T | 欄位固定的層保存時只取欄位區塊，裁掉 `Ending` 之後的內容 | WI-1483、WI-1488 | 已完成（2026-10-18），T 的 5caa6ee，合併於 24ce7a5；日常測試通過（既有的 DenoiseRecordsTest 除外），端到端與真實服務未跑；Ending 後沒有空行就接修稿的回答不裁剪，待真實 run 的資料；決策見 [ADR-080](../adr/ADR-080-summary-degradation-conditions.md) 決策 3、4 |
| [WI-1487](WI-1487-truncated-summary-flag.md) | T | 被截斷的摘要記錄為被截斷，並在下一層的輸入中標明 | WI-1486 | 已完成（2026-10-18），T 的 f172e67，合併於 1abf001；schema 版本 19；日常測試通過（既有的 DenoiseRecordsTest 除外），端到端與真實服務未跑；06-data-model 的對齊待辦；決策見 [ADR-079](../adr/ADR-079-truncated-summary-is-kept-and-flagged.md) |
| [WI-1489](WI-1489-entry-one-line.md) | T | 潤飾與翻譯不標換人、不分行，條目是一行文字；SRT 與報告不畫對話破折號，移除 `subtitleDialogueDash` | 無 | 已完成（2026-10-18），T 的 2bcb0b0，合併於 fbb9d09；實作時誤用 WI-1483／ADR-077 的編號，合併時改為本項；日常測試 1012 個通過，端到端與真實服務未跑；決策見 [ADR-083](../adr/ADR-083-entry-is-one-line.md) |

WI-1483 至 WI-1489 都已完成並合併進 T 的 main。

WI-1483 與 WI-1484 驗收並提交後，由 homelab-deployer 在 arr 的 Lemonade 上對 run 38 的素材做重放量測（條件見報告）：場景層看場景 30、18 與壓力場景，預期外洩為 0、回答開頭為格式；片段層看帶圖片的摘要不含「scene」的比例。量測前由架構師向使用者確認；量測結果不是使用者的核可。

待確認問題：[ADR-080](../adr/ADR-080-summary-degradation-conditions.md) 的待確認問題 2（沒有素材的片段占比）待真實 run 的資料；問題 1 已由 [ADR-082](../adr/ADR-082-truncated-with-no-complete-field-is-kept.md) 解答。

量測報告（提案中）：[WI-1451](../../../draft/transcribe-scenes/WI-1451-forced-alignment-report.md)、[WI-1452](../../../draft/transcribe-scenes/WI-1452-long-chunks-and-segments-report.md)。

派工順序：
1. WI-1461、WI-1462、WI-1451 的工具修訂：已完成。
2. WI-1451 與 WI-1452 的實機量測：已完成，兩個關卡通過。
3. WI-1455 及其後各項依相依順序派工；WI-1455 的版本上線前，Engine 資源的上游改指 gateway（[04](../04-deployment.md)）。

關卡不通過時停下來，由架構決定，不繼續後續項目。
