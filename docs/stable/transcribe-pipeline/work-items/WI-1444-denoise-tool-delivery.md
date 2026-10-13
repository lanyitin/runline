# WI-1444 降噪工具隨部署交付

本文回答：降噪 wrapper 與固定版本的 deep-filter 如何進入 Engine 容器，以及部署前檢查如何確認。狀態：已核可（2026-10-13）。依據 [ADR-046](../adr/ADR-046-denoise-before-vad.md) 與 [WI-1440](WI-1440-denoise-before-vad.md) 第 8 項。相依：WI-1440。儲存庫：R（runline，`deploy/`）；wrapper 原始檔現在在 T。

## 背景

v0.0.9 映像沒有降噪 wrapper 與 deep-filter，`deploy/` 沒有相關安裝步驟，`runline-transcribe-check` 也不檢查。降噪預設開啟，工具缺少時 run 以 `CONFIGURATION` 失敗。現有慣例：版本與雜湊集中定義在 `runline-transcribe-fetch`；Docker 映像的命令放 `/usr/local/bin`，systemd 部署放 `/opt/runline/bin`；VAD 的 jar 與模型由部署層提供。Engine 容器的環境為 bash 5、mawk（非 gawk）、uutils sha256sum（非 GNU coreutils）。

## 行為與驗收條件

1. **交付**：Docker 映像內有降噪 wrapper（命令名 `runline-denoise`，位於 `PATH`）與固定版本的 deep-filter（Linux x86_64 靜態檔，版本與雜湊依 ADR-046），wrapper 能找到該執行檔；systemd 部署方式同樣可用（比照現有慣例的路徑）。
2. **版本固定**：版本與 sha256 與其他外部工具一樣集中定義在 `runline-transcribe-fetch`；下載後驗證雜湊，不符則失敗，不留下半成品；映像建置時取得，執行時不連網。
3. **單一來源**：wrapper 只有一份原始檔；實作者提案放在哪個儲存庫、另一邊如何取用（T 的測試需要它，R 的映像需要它），避免兩份複本漂移。
4. **相容性**：wrapper 在 Engine 容器的實際環境（mawk、uutils sha256sum）上通過 T 儲存庫既有的降噪測試的同等檢查；若 wrapper 使用了 GNU 專屬行為，改成可攜寫法。在映像內以真實 deep-filter 對一個短音訊跑過一次並確認輸出長度與延遲補償行為。
5. **部署前檢查**：`runline-transcribe-check` 檢查降噪工具是否存在、可執行、雜湊與版本符合；缺少時指出原因與處置。`denoise` 關閉時該檢查可略過（或只警告），行為由實作者提案、架構師核可。
6. **文件**：`deploy/transcribe.md` 與 runbook 第 2.4 節描述交付方式（單一寫處，其餘連結）；`04-deployment.md` 的版本與雜湊表保持一致。
7. **驗證**：映像建置成功並可在本機執行檢查；對真實 Engine 容器的驗證由 homelab-deployer 部署後完成。

## 架構約束

- 降噪工具是獨立行程，不進 pipeline jar；不連網（建置時取得除外）。
- 不改動 pipeline 對工具的契約（命令與環境變數見 WI-1440）。
- 只改部署檔案與其文件；不改 Engine 與 pipeline 程式。
- 映像與 compose 檔案的撰寫由 tdd-coder 完成；部署由 homelab-deployer 在核可後執行。
