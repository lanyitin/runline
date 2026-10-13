# WI-1440 VAD 前以 DeepFilterNet3 降噪

本文回答：pipeline 在 VAD 之前如何降噪，以及降噪工具要達成什麼行為。狀態：已核可（2026-10-13）。依據 [ADR-046](../adr/ADR-046-denoise-before-vad.md)。相依：WI-1434、WI-1435。儲存庫：T（runline-transcribe）。取代 WI-1438、WI-1439。

## 行為與驗收條件

1. **參數**：開關 `denoise`（預設開啟）、降噪衰減上限（預設 24 dB）、降噪工具的命令（部署層設定，環境變數 `TRANSCRIBE_DENOISE_COMMAND`，比照 silero 工具的命令）。模型內建於 deep-filter 執行檔，沒有模型路徑；執行檔的路徑是降噪工具自己的設定（`RUNLINE_DENOISE_BINARY`，由包裝腳本讀取，pipeline 不讀）。名稱由實作者提案、架構師核可，寫入入口文件與 runbook（單一寫處）。
2. **降噪工具契約**：獨立行程；輸入 48 kHz 單聲道 WAV，輸出降噪音訊，長度不比輸入長、最多短 100 毫秒，必須使用延遲補償，衰減上限為 0 時原樣輸出；pipeline 轉為 16 kHz 單聲道供 VAD 使用，並於尾端補零回原長度；診斷走標準錯誤；成功結束碼 0，失敗非 0 並說明原因；同一輸入同一結果；不連網；僅 CPU；版本與模型雜湊固定並記錄於專案文件，缺少或不符時失敗。
3. **音訊流向**：降噪輸出只供 VAD；分段後的切片與 ASR 取自原始音訊；條目時間與原始影片對齊（降噪不得引入時間位移，驗收：降噪輸出與輸入的相關性延遲為 0，以實驗相同的檢查方式）。
4. **關閉時**：`denoise=false` 的 run，行為與現況完全相同，不呼叫降噪工具。
5. **失敗**：開啟時降噪工具未設定、不存在或執行失敗，run 以 `CONFIGURATION` 失敗並指出原因，不靜默略過。
6. **紀錄與 log**：RUN SUMMARY 與分段 log 增加降噪是否啟用、衰減上限、處理耗時；資料庫的 run 層級記錄工具版本與是否啟用（使用下一個可用的結構版本）。
7. **驗證**：以 arr 影片 NTRH-002-C.mp4 的 `orig48.wav` 與 homelab-deployer 實驗得到的降噪輸出與 silero 區間作為固定輸入資料；驗證降噪後的區間與離線實驗（衰減上限 24 dB、silero 0.5／0.35）一致：37 段、涵蓋 20.5%（基準為 silero 0.50／0.35）。涵蓋：工具缺少、衰減上限邊界值、`denoise=false`、非 48 kHz 輸入（由 pipeline 擷取保證）。固定資料放在儲存庫之外的 `~/fixtures/dfn/`，不進任何 git 儲存庫；其中的 `MANIFEST.md` 記錄來源（影片、Engine 映像、ffmpeg 與 deep-filter 的版本與雜湊、產生指令）與每個檔案的 sha256。測試以環境變數 `TRANSCRIBE_DFN_FIXTURES` 指向該目錄；未設定時依賴固定資料的測試失敗並說明，不略過。取得方式：依 `MANIFEST.md` 在 arr 的 Engine 容器內重新產生，由 homelab-deployer 執行。
8. **部署**：產出可在 arr 的 Engine 容器內執行的單位；掛載路徑與命令名稱由實作者提案，與 `runline-vad` 不衝突；部署由 homelab-deployer 在核可後執行。
9. 對真實 Engine 的驗證由 arr 上的下一次 run 完成。

## 架構約束

- 不改變條目時間不變式（ADR-038 決策 3、4）、不存放音訊。
- 降噪工具是獨立行程，不進 pipeline jar。
- 薄 jar、嚴格 TDD、不使用 Stub 或 Mock。
- 降噪工具使用 ADR-046 記載的 Linux x86_64 靜態預編譯檔，版本與雜湊依該 ADR。
