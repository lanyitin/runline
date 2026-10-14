# WI-1448 潤飾與翻譯以批次送出並附唯讀的前後文

本文回答：潤飾與翻譯如何改為批次送出、附上前後文，以及如何驗收。狀態：已完成（2026-10-13）。相依：無。決策見 [ADR-049](../adr/ADR-049-batches-with-read-only-context.md)。儲存庫：T（runline-transcribe）。

## 背景

潤飾與翻譯目前以 VAD-A 組為單位送出；run 7 的 160 組中有 158 組只有一個條目，模型看不到任何前後文（數據見 [ADR-049](../adr/ADR-049-batches-with-read-only-context.md) 背景）。

## 行為與驗收條件

1. **參數**：新增 `contextBatchEntries`（預設 20）、`contextSeconds`（預設 120）、`contextMaxEntries`（預設 30），潤飾與翻譯共用。前兩者為正整數；`contextSeconds` 為大於零的數字。`contextMaxEntries` 可為 0，表示不附語境。不合法時在 intake 以 `CONFIGURATION` 失敗並指出參數名稱。
2. **批次**：潤飾與翻譯各自依條目順序，把有語音的條目切成連續的批次，每批最多 `contextBatchEntries` 條。批次與 VAD-A 組無關。無語音的條目不進批次也不進語境；全部無語音時不送出任何請求。批次逐一循序送出（並行 1）。
3. **語境的選取**：前文是批次第一條之前的有語音條目，從最近的往前取，直到超過 `contextMaxEntries` 條，或條目終點早於批次第一條起點減 `contextSeconds`。後文對稱：從批次最後一條之後往後取。
4. **語境的文字**：
   - 潤飾：前文為已潤飾的文字（降級者為轉錄文字），後文為轉錄文字。
   - 翻譯：前後文的來源文字都是核對後文字（核對降級者為潤飾文字）。前文的每個條目另附其譯文（翻譯降級者為其保留的文字）；後文只有來源文字。
5. **user 訊息**：依序三段，標題固定。
   - 第一段 `Context before (for understanding only; do not return it):`，其後每個條目一行 `[H:MM:SS] 文字`。翻譯的前文在該行之下多一行 `  -> 譯文`。
   - 第二段為要處理的 SRT，序號從 1 起算，是批次內的序號；潤飾的標題沿用現有寫法，翻譯為 `SRT to translate:`。
   - 第三段 `Context after (for understanding only; do not return it):`，格式同第一段。
   - 沒有語境的段落寫 `(none)`。
   - 翻譯不再附 `Summary of the whole video` 段落。
6. **預設 system prompt**：潤飾與翻譯的預設 prompt 改寫：說明前後文只供理解、不得輸出、不得把語境的字搬進條目；潤飾的說明不再提到「VAD 組」。T README 的引文與程式字串一致（沿用現有的文件測試）。WI-1445 的參數說明提醒：自訂 prompt 也要處理語境段落。
7. **驗證、拆開與退回**：回答的驗證規則不變，只核對批次的條目。重試用盡時：
   - 批次多於一條：對半拆開（前半取 ⌈n/2⌉ 條），兩半依序各自當成批次處理，重試次數完整，語境依新範圍重新選取。
   - 只剩一條：退回前一階段的文字並記錄降級。

   生成上限依批次 SRT 計算（沿用 WI-1437）。翻譯在來源語言已是繁體中文時不送出的規則不變，判斷單位改為批次。
8. **紀錄**：
   - `exchange.target` 對潤飾與翻譯記 `batch`，`target_seq` 為該批（含拆開後的半批）第一個條目的序號；請求文字含語境。
   - log 的 RETRY 與 DEGRADED 行寫成 `batch (entries <first> to <last>)`。
   - 拆開時寫一行 `SPLIT batch (entries <first> to <last>) into <first> to <mid> and <mid+1> to <last> after <n> tries: <原因>`。
   - STAGE 與 RESULT 的計數由 `groups` 改為 `batches`，並新增 `splits`。
   - T README 的「潤飾」「翻譯」「紀錄資料庫」各節隨之更新。
9. **驗證**（Fake OpenAI 伺服器）：
   - 批次切法：條數上限、無語音條目被跳過、跨越 VAD-A 組。
   - 語境：秒數與條數兩種上限、開頭與結尾的 `(none)`、`contextMaxEntries=0`。
   - 潤飾的前文取已潤飾的文字（含前一批降級的情況）；翻譯的前後文取核對後文字，前文附譯文（含降級條目保留的文字），後文不附。
   - 翻譯請求不含摘要。
   - 回答含語境行時被拒並重試。
   - 拆開：重試用盡後對半拆開；兩半各自成功；其中一半再拆；拆到單一條目仍失敗時只降級該條，其他條目保留新文字；後半的前文含前半剛完成的結果；`splits` 計數與 SPLIT 行正確。
   - 請求並行數最多 1；`exchange` 與 log 的批次編號正確。
   - 全部預設值下，條目的時間與數量和改動前相同。
10. 對真實 Lemonade 的效果，於 WI-1450 一併比較。

## 架構約束

- 不改變條目時間與數量的不變式，不改變核對階段。
- 不新增資料表或欄位；舊 run 的紀錄不改寫。
- 每個預設值與預設 prompt 只在程式內定義一處。
- 薄 jar、嚴格 TDD、不使用 Stub 或 Mock。
