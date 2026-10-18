# ADR-081 摘要的預設提示採用經量測的結構

本文回答：場景層與片段層摘要的預設 system prompt 應是什麼樣子，以及量測所用的提示與程式預設的差異如何處理。狀態：已核可（2026-10-18）。更正 [ADR-078](ADR-078-segment-summary-wording.md) 的背景、[量測報告](../summary-leak-experiment-report.md)「原因」中關於片段提示的敘述；補充 [ADR-080](ADR-080-summary-degradation-conditions.md) 決策 3 的前提（提示要求四個欄位）。

## 背景

- run 38 的 system prompt 全部是使用者建立 run 時提供的覆寫值，與程式的預設不同。原文見[參考文件](../run38-summary-prompts-reference.md)。
- 場景層覆寫值要求四個標籤固定的欄位（`Setting:`、`People:`、`Progression:`、`Ending:`），上限 1500 字元；程式的預設場景提示沒有欄位，只要求標記與散文。
- 片段層覆寫值：一到三句、500 字元以內；提到「A scene is cut into such segments」「so a reader can picture the scene」「write about the scene」；要求最後一句寫片段的最後一刻。程式的預設片段提示沒有 "scene" 一詞，限制為 6 句、1500 字元。
- 量測（[報告](../summary-leak-experiment-report.md)）逐字使用覆寫值。因此：
  - 預填、截斷與外洩的結論對這兩份提示成立；
  - 片段摘要中的「The scene ends with …」可能來自覆寫提示中的 "scene" 與「最後一句寫最後一刻」；ADR-078 背景所說「提示沒有 scene 一詞，模型自行選用」只對程式預設成立；
  - [ADR-080](ADR-080-summary-degradation-conditions.md) 決策 3 與 [ADR-079](ADR-079-truncated-summary-is-kept-and-flagged.md) 的「四個欄位」只在提示要求欄位時成立。

## 決策

1. **場景層預設提示採用覆寫值的結構**：四個標籤固定、順序固定、各占一行的欄位，總長不超過 1500 字元；第一行為 `SUMMARY:`；涵蓋整個場景的要求、命名規則與規則段、形狀範例都依[參考文件](../run38-summary-prompts-reference.md)。
2. **片段層預設提示採用覆寫值的內容**：一到三句、500 字元以內；最後一句寫片段的最後一刻；看最後一張圖；命名與規則段，依參考文件。其中提到 "scene" 之處（單位的說明、「讓讀者想像場面」、「寫場景」）改寫為不含該詞的說法，並依 [ADR-078](ADR-078-segment-summary-wording.md) 決策 1 加入用詞規定。
3. **影片語言與說話語言**：預設提示不寫死「Japanese」，也不要求說明演員說的語言，沿用現行預設的規定（對白不限語言）。
4. **其他提示不變**：全片層、潤飾、翻譯的預設提示不變；覆寫值仍可使用，標記不得刪去（沿用 [ADR-054](ADR-054-scene-summaries-and-translation-context.md)）。
5. **欄位的處理**：因預設場景提示要求欄位，[ADR-080](ADR-080-summary-degradation-conditions.md) 決策 3 的裁剪與 [ADR-079](ADR-079-truncated-summary-is-kept-and-flagged.md) 決策 2 的「四欄是否完整」在預設提示下適用。提示不要求欄位時（覆寫值），裁剪不適用，被截斷與否只依結束原因判斷。

## 取捨

- 得到：預設與經量測的提示一致，量測結論直接適用於預設；欄位固定使保存與裁剪的邊界明確；片段摘要不再被提示引導寫出 "scene"。
- 失去：
  - 預設提示變長，每次請求多數百個 token；
  - 片段摘要縮為一到三句，涵蓋度可能不如現行預設的六句；量測中的片段摘要即為此長度；
  - 已有覆寫值的使用者不受影響，其覆寫值內的 "scene" 用詞仍需自行修改；
  - 預設提示改變後，舊 run 與新 run 的摘要風格不同。

## 後果

- 工作項：[WI-1488](../work-items/WI-1488-default-summary-prompts.md)。
- [WI-1484](../work-items/WI-1484-segment-summary-wording.md) 加入的用詞規定併入 WI-1488 的片段提示，不單獨保留。
- [WI-1486](../work-items/WI-1486-scene-summary-field-block-only.md) 的相依增加 WI-1488。
- 文件更正：ADR-078 與量測報告的狀態欄註明相關敘述由本決策更正。
- 實機驗證：預設提示更新後，由 homelab-deployer 以新片段提示重新產生場景 30 的片段摘要（影片在 container `arr`），再跑場景層摘要，確認外洩與涵蓋度。

## 待確認問題

無。預設提示不寫死「Japanese」、不要求說明說話語言（決策 3），以及片段摘要縮為一到三句、500 字元（決策 2），已由使用者於 2026-10-18 核可。
