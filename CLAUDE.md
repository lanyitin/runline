# runline

Kotlin 撰寫的 pipeline 執行引擎：Kotlin + Ktor 3.x、JDK 25、PostgreSQL、OpenTelemetry，Gradle 多模組，開發環境見 `.devcontainer/`。

## 模組

Gradle 模組為 `core`、`runner`、`accessors`、`analyzer`、`devkit`、`engine`（以 `settings.gradle.kts` 為準）；`console` 是 Svelte 前端，由 engine 的建置打包，不是 Gradle 模組。各模組的責任與依賴方向以 `docs/stable/pipeline-engine/02-current-state.md` 為唯一來源。

## 文件

- `docs/stable/`：已核可、現行有效的決策依據。
- `docs/draft/`：提案，不能當成事實引用。

## 核可

使用者在對話中明確確認即為核可，不經外部審查系統。確認的對象是對話中列出的文件或工作項。

## 童子軍原則（所有 agent 適用）

- 每次變更都讓碰過的地方比之前乾淨。
- 不留下待確認問題、受阻的驗收、暫存檔、過時文件、TODO 或「之後再處理」的註記。
- 發現而無法當場解決的問題，立即回報，由架構師轉為工作項或在對話中結案，不以文字註記留置。
