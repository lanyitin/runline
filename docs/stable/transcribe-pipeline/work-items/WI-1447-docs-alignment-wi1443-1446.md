# WI-1447 文件對齊：WI-1443 至 WI-1446 的行為與先前遺留的文件落差

本文回答：prompt 參數（WI-1445）、Console 長文字預設值（WI-1446）、失敗補掃窗口計入降級（WI-1443）與降噪工具交付（WI-1444）完成後，哪些現行文件需要改，以及完成的判準。狀態：已核可（2026-10-13）。相依：WI-1443 至 WI-1446 已實作。儲存庫：R（runline，文件）與 T（runline-transcribe，README）。承接 [WI-1442](WI-1442-docs-alignment-wi1432-1440.md) 的做法。

## 行為與驗收條件

文件一律描述現況（現在式、宣告式），不寫流水帳；同一事實只寫一處，其他處以連結引用。參數名稱與預設值以程式實際宣告為準，不以本文為準。

1. **入口文件參數表**（`README.md`，單一寫處）：新增六個 prompt 參數（`asrSystemPrompt`、`polishSystemPrompt`、`verifySystemPrompt`、`summaryGroupSystemPrompt`、`summaryVideoSystemPrompt`、`translateSystemPrompt`）。內容：各自替換哪個階段的 system prompt 全文；預設值為現行 prompt（ASR 為空）；留空或僅含空白時用預設值；上限 8000 字元，超過在 `intake` 以 `CONFIGURATION` 失敗；程式解析所需的輸出標記不可刪；ASR 參數僅作用於 chat 型端點，且含 WI-1445 背景所記的實驗警語（預設應保持空）。參數總數同步更新。
2. **Runbook**：
   - 如何覆寫 prompt（手動 run 於建立 run 頁面；固定於 trigger），以及如何在 Console 看預設值全文。
   - `intake` 的 `PROMPTS` log 行（預設或自訂與字數）的讀法。
   - `transcriptions` 端點下 ASR prompt 被忽略的 log 說明與處置。
   - 故障排查：自訂 prompt 使輸出格式不符時的降級現象與處置。
   - 參數數量（目前寫「45 個」）與引用的 T README 一致。
   - `deploy/transcribe.md` 若列有參數或操作，同步補上。
3. **資料模型**（`06-data-model.md`）：ASR 的 `exchange` 請求文字在設定了 system prompt 時含 `[system]…[user]` 前綴；`run_parameter` 因 prompt 參數單列值可達數 KB 的說明（不是問題，只是容量估算）。
4. **IPC**（`05-ipc.md`）：請求語意表加入 ASR 請求可帶 system 訊息（僅 chat 端點、預設不帶）；補掃請求的語意與 `max_tokens` 現況與程式一致。
5. **WI-1443 的呈現**：RUN SUMMARY 括號內的各階段計數不含轉錄，失敗的補掃窗口使 `degraded` 加一並使 `OUTCOME` 為 `success-degraded`；runbook 對應表與入口文件一致。
6. **WI-1446 的 Console 描述**：建立 run 與 trigger 表單對含換行或超過 80 字元的預設值顯示全文、提供「以預設值填入」、不操作不送出；寫在 Console 相關文件一處，其他處連結。
7. **先前遺留的落差**（逐項核對程式現況後改寫，已一致者不改）：
   - [WI-1440](WI-1440-denoise-before-vad.md) 驗收第 1 項的「模型路徑」說法。
   - [WI-1441](WI-1441-sweep-window-failure-degrades.md) 驗收第 3 項的措辭。
   - `07-nfr-risks.md` 的容量估算式：納入補掃與降噪的耗時。
   - T README 開頭的狀態敘述改為現況描述。
   - 參數語意的單一寫處：入口文件參數表為準，T README 只列名稱、預設值與連結；兩處若重複語意，保留入口文件。
8. **判準**：對照程式宣告的參數清單，文件中的參數名稱、預設值、上限無缺漏或矛盾（以搜尋逐一核對）；所有內部連結有效；文件內沒有「後來」「更新」「原本」之類敘事；只改文件，不改應用程式碼與測試。

## 架構約束

- 不改變任何已核可的決策與驗收條件；發現文件與程式不一致時，以程式實際行為為準，行為本身有疑義則回報架構師，不自行改程式。
- 不複製細節：入口文件只放一句摘要加連結。
