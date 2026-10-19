# #13：transcribe 與 digest-rss 文件拆到各自的專案儲存庫

本文回答：拆分的範圍與規則。狀態：提案中。Issue：#13。

## 背景

- `docs/stable/` 目前含 11 個主題，其中 `transcribe-pipeline`、`transcribe-scenes`、`digest-rss` 描述的是外部專案 `runline-transcribe`、`runline-digest-rss` 的設計，共約 190 個檔案；`docs/draft/transcribe-scenes` 有 4 個檔案。
- 兩個專案是 runline 的外部專案，各有自己的 Forgejo 儲存庫，文件卻在 runline，需要在兩個儲存庫之間來回對照。
- 量測到的耦合：
  - 其餘主題指向這三個主題的連結約 40 個（主要是 `pipeline-engine` 指向 `digest-rss` 31 個、指向 `transcribe-pipeline` 4 個）。
  - 這三個主題指向其餘主題的連結約 35 個，另有約 30 個指向 runline 的程式與部署檔。
  - 這三個主題之間：`transcribe-scenes` 與 `transcribe-pipeline` 互相連結約 26 個，`transcribe-pipeline` 指向 `digest-rss` 2 個。
  - 約 18 個非文件檔（部署腳本、compose、環境範例、一處程式註解）以路徑引用這三個主題的文件。
  - `runline-digest-rss` 的測試讀取 `../runline/docs/stable/digest-rss/runbook.md` 來比對手冊內容。

## 決策

**搬移範圍。**

| 主題 | 去處 |
|---|---|
| `docs/stable/transcribe-pipeline`、`docs/stable/transcribe-scenes`、`docs/draft/transcribe-scenes` | `runline-transcribe` 的 `docs/stable/`、`docs/draft/` 下同名目錄 |
| `docs/stable/digest-rss` | `runline-digest-rss` 的 `docs/stable/digest-rss` |
| 其餘主題 | 留在 runline |

主題內部的結構與檔名不變。搬完並驗收後，runline 移除原檔，不留副本，維持單一事實來源。git 歷史留在 runline，不移轉。

**連結規則。**

| 連結類型 | 處理 |
|---|---|
| 同一儲存庫內的文件 | 相對連結 |
| 跨儲存庫的文件或程式檔 | 指向對方儲存庫 `main` 上該檔案的網址（`forgejo.dev.lan` 上的儲存庫路徑） |
| 原本以「兄弟目錄」相對路徑指向另一個專案（例如 `../../../../runline-transcribe/README.md`） | 同一儲存庫時改為相對連結，否則改為網址 |
| 外部網址、頁內錨點 | 不變 |

**程式與部署檔的引用。** runline 內以路徑引用搬走文件的非文件檔，改成指向新儲存庫的位置或網址；`runline-digest-rss` 讀取手冊的測試改讀自己儲存庫內的新位置。

**編號規則。** 既有 WI-、ADR- 編號凍結並隨文件搬走，不改名。新的 ADR 與工作項用該文件所在儲存庫自己的 Forgejo Issue 編號，寫作 `#<編號>`；跨儲存庫引用寫作 `<儲存庫名>#<編號>`。

**流程與 agent。** `runline-transcribe`、`runline-digest-rss` 沿用 runline 的 draft／stable 分工與立項查重流程（Issue 編號各儲存庫獨立）。`high-level-architect`、`tdd-coder` 的定義、`CLAUDE.md`、`.claude/settings.json` 原樣複製，唯一調整是設定中以儲存庫名稱限定的項目（建立標籤的權限）改為該儲存庫的名稱。不複製 `.claude/settings.local.json`，那是使用者的本機設定。

**實作順序。** 兩個接收專案互不相依，可並行；runline 的清理等兩者都驗收並由使用者合併後才做。

## 取捨

- 不留副本：避免兩份分歧，代價是 runline 的 Wiki 映像在清理後不再含這三個主題（下一次同步完整重新產生時消失），以及約 40 個跨主題連結變成跨儲存庫網址。
- 只改位置與連結：降低搬移引入內容錯誤的風險，也讓驗收可以用「除連結外內容一致」來檢查。
- 原樣複製 agent：快、行為與 runline 一致；代價是定義內容描述 runline 的模組結構，不完全貼合兩個專案。
- 不移轉歷史：簡單，歷史仍可在 runline 查到；代價是新儲存庫的歷史從搬移那一刻開始。

## 後果

- 每個專案的設計文件與程式在同一儲存庫，審查與發版一起進行。
- 跨儲存庫連結依賴 `main` 上的路徑，被搬動時會失效；各專案自己的文件變動須注意。
- runline 的 Wiki 映像只涵蓋留在 runline 的主題；兩個新儲存庫是否也要映像另案處理。
