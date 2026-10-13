# WI-1442 文件對齊：WI-1432 至 WI-1440 的行為寫進現行文件

本文回答：WI-1432 至 WI-1440 完成後，哪些現行文件需要改成描述新行為，以及完成的判準。狀態：已核可（2026-10-13）。相依：WI-1432 至 WI-1437、WI-1436、WI-1440 實作完成；WI-1441 完成後對應條目再補。儲存庫：R（runline，文件）與 T（runline-transcribe，README）。

## 行為與驗收條件

文件一律描述現況（現在式、宣告式），不寫流水帳或更新敘事；同一事實只寫一處，其他處以連結引用。參數的名稱與預設值以程式實際宣告為準（對照各參數宣告與其文件測試），不以本文為準。

1. **入口文件**（`README.md`）：
   - 關鍵決策表新增「補掃」（[ADR-044](../adr/ADR-044-asr-coverage-sweep.md)）與「降噪後 VAD」（[ADR-046](../adr/ADR-046-denoise-before-vad.md)）兩列，各一句摘要加連結；ADR 索引範圍涵蓋到 ADR-046，並標明 ADR-045 已被 ADR-046 取代。
   - 參數表新增：`summaryMaxTokens`、`modelCallTimeoutSeconds`、`polishTokenMargin`、`translateTokenMargin`、`sweepEnabled`、`sweepMinGapSeconds`、`sweepWindowSeconds`、`sweepMinChars`、`denoise`、`denoiseAttenLimitDb`；每個參數的語意與預設值只寫在這裡（參數表為單一寫處）。
   - 處理階段圖與說明：補掃屬於轉錄階段；降噪開啟時多一個降噪階段（共 13 個），關閉時為 12 個。
2. **runbook**（`runbook.md`）：
   - 參數：連到入口文件的參數表，不重複。
   - 失敗與排錯：`TRUNCATED`、`TOTAL_TIMEOUT`、`PARAMETER_LOCKED` 的意義與處置；補掃降級（WI-1441）的處置；`NO_SPEECH` 在補掃開啟時發生於 `transcription` 階段（補掃關閉時仍在 `segmentation`）；降噪工具缺少或失敗時的 `CONFIGURATION`。
   - 查詢：Console 的 `summary`（整支影片與各組摘要）、`frames`、`sweep-filtered` view；`entries` 新增 `source` 與 `time_estimated`；`degradations` 新增補掃窗口；`runs.file` 與 `run` 的 drill 目標；手動查詢 `group_summary`、`sweep_window` 與 `exchange` 的 `stage=sweep`。
   - log：`SWEEP PLAN`／`SWEEP PROGRESS`／`SWEEP FILTERED`／`SWEEP RETRY`／`SWEEP FAILED`、RUN SUMMARY 新增的補掃與降噪欄位。
   - 部署：降噪 wrapper 與固定版本的 deep-filter 的安裝位置、兩個環境變數、雜湊與版本；Linux x86_64 靜態預編譯檔。
   - 已驗證與未驗證清單：新增補掃、降噪、各項生成量上限與逾時、各組摘要與新 view 只對 Fake 伺服器或固定資料驗證；真實 Lemonade 與 arr run 的驗證項目列為待做（見 ADR 與各工作項）。
   - ASR 在 temperature 0 下仍有少量跨批次不穩定，驗收看總量而非單段文字。
3. **資料模型**（`06-data-model.md`）：
   - 實體：`SEGMENT_B` 新增 `source`、`time_estimated`；新增 `SWEEP_WINDOW`、`GROUP_SUMMARY`；`RUN` 新增降噪是否啟用與工具描述；`EXCHANGE` 的 `endpoint` 語意、`error` 含 `TOTAL_TIMEOUT`、`target` 含 `window`、`stage` 含 `sweep`；ER 圖同步。
   - 遷移段：版本 4 至 9 各自新增的欄位與表，舊 run 的預設語意（條目視為來源 VAD、時間非估計；無降噪）。
   - 保留段落：`sweep_window` 與 `group_summary` 隨 run 一併清除。
4. **IPC 與部署**（`05-ipc.md`、`04-deployment.md`）：新增降噪工具契約（輸入 48 kHz 單聲道 WAV、輸出降噪音訊、退出碼、不連網）；輸出長度的說法為「不比輸入長，最多短 100 毫秒」，pipeline 尾端補零回原長度（見第 6 項）；部署文件記錄 wrapper 與二進位檔路徑、環境變數、版本與雜湊。
5. **ADR-038 本文**：決策 3「字幕條目 ＝ VAD-B 段」改為「字幕條目來自 VAD-B 段或補掃窗口」，與 ADR-044 一致。
6. **ADR-046 與 WI-1440 措辭修正**（已核可文件的小修訂，本項核可即視為同意）：
   - 降噪輸出與輸入的長度關係改為「不比輸入長，最多短 100 毫秒」；工具必須使用延遲補償，補償後與輸入的對齊延遲為 0；衰減上限為 0 時不做降噪（原樣輸出）。
   - ADR-046 記載 macOS arm64 預編譯檔的版本與雜湊（`4601e7f4…`，完整值取自實際驗證紀錄），以及輸出相對輸入落後約 487 個樣本（48 kHz）的實測。
   - 驗收條件 7 的固定資料位置與取得方式（儲存庫外、manifest 記錄來源與雜湊）寫入 WI-1440；基準為 silero 0.50／0.35、37 段、涵蓋 20.5%。
7. **舊文件的過時敘述**：WI-1422 的「已知限制」中已被後續項目解決的條目、WI-1433 與 WI-1434 工作項內仍指向暫定行為的備註，改成現況；T 儲存庫 README 中與現況不符的「待確認」字句（約第 191 行附近）改成現況。
8. **總覽**（`work-items/README.md`）：WI-1432 至 WI-1441 的狀態欄與已完成項的註記與實際一致；移除「文件待補」字樣。
9. **一致性檢查**：入口文件參數表的名稱與預設值逐項對照程式宣告與文件測試；各文件之間的連結皆可到達；每個事實只有一處正文；沒有「補充」「更新」「後來」之類的敘事段落；不貼程式碼。

## 架構約束

- 只改文件，不改應用程式碼與測試。
- 單一事實來源與漸進式披露：入口文件短，細節在各自文件。
- 以現行版本描述；被取代的決策只在 ADR 狀態欄標明。
- 本項涉及已核可的 stable 文件（入口、runbook、資料模型、IPC、部署、ADR-038、ADR-046、WI-1440、WI-1422、WI-1433、WI-1434）：只改上列範圍，不改驗收條件與架構約束的實質內容，第 6 項例外已明列。
