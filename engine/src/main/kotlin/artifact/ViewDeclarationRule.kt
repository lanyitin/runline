package dev.lawlan.runline.engine.artifact

import dev.lawlan.runline.accessors.jdbc.SingleStatements
import dev.lawlan.runline.accessors.jdbc.SqlSyntax
import dev.lawlan.runline.core.ResourceTypes
import dev.lawlan.runline.core.Tone
import dev.lawlan.runline.core.ViewColumnType
import dev.lawlan.runline.core.ViewParamType

/** One thing wrong with a declared view: which view, and what. */
data class ViewProblem(val viewId: String, val detail: String)

/**
 * Judges the views a pipeline declares (ADR-033), reading only the stored declaration: nothing is
 * run and nothing is stored here. All problems of all views are returned, so an author can fix them
 * in one go.
 */
class ViewDeclarationRule {
  fun problems(metadata: MetadataDoc): List<ViewProblem> {
    val views = metadata.views
    val byId = views.groupBy { it.id }
    return views.flatMap { view ->
      val first = byId.getValue(view.id).first() === view
      buildList {
        if (first && byId.getValue(view.id).size > 1) {
          add(problem(view, "識別「${view.id}」重複，同一個 pipeline 內 view 的識別必須唯一"))
        }
        addAll(roleProblems(view, metadata))
        addAll(duplicateNames(view))
        addAll(referenceProblems(view))
        addAll(typeProblems(view))
        addAll(sortProblems(view))
        addAll(drillProblems(view, byId))
      }
    }
  }

  private fun problem(view: ViewDoc, detail: String) = ViewProblem(view.id, detail)

  private fun roleProblems(view: ViewDoc, metadata: MetadataDoc): List<ViewProblem> {
    val role = view.resource
    return when {
      role !in metadata.resources -> listOf(problem(view, "掛載的資源角色「$role」沒有宣告"))
      metadata.resourceTypes[role] != ResourceTypes.JDBC_POOL ->
          listOf(
              problem(
                  view,
                  "掛載的資源角色「$role」的型別不是資料庫（${ResourceTypes.JDBC_POOL}）：" +
                      (metadata.resourceTypes[role]?.let { "宣告的型別是「$it」" } ?: "沒有宣告型別"),
              )
          )
      else -> emptyList()
    }
  }

  private fun duplicateNames(view: ViewDoc): List<ViewProblem> =
      view.parameters.duplicates { it.name }.map { problem(view, "參數名稱「$it」重複") } +
          view.columns.duplicates { it.name }.map { problem(view, "欄位名稱「$it」重複") }

  /**
   * The query refers to its parameters by name (ADR-034): each name is a declared parameter, each
   * declared parameter is named, and there is no `?`. The database a view will read is only known
   * when it is bound, so the text is read as each dialect there is: a name counts when any reading
   * finds it, and a text that cannot be read to its end is left to the read.
   */
  private fun referenceProblems(view: ViewDoc): List<ViewProblem> {
    val readings = SqlSyntax.KNOWN.mapNotNull { SingleStatements.references(view.query, it) }
    if (readings.isEmpty()) return emptyList()
    val referred = readings.flatMap { it.names }.distinct()
    val declared = view.parameters.map { it.name }.toSet()
    return buildList {
      referred.filter { it !in declared }.forEach { add(problem(view, "查詢的佔位符「:$it」指向沒有宣告的參數")) }
      view.parameters
          .map { it.name }
          .distinct()
          .filter { it !in referred }
          .forEach { add(problem(view, "宣告的參數「$it」沒有被查詢引用（以「:$it」引用）")) }
      if (readings.any { it.positional }) {
        add(problem(view, "查詢使用了位置參數標記「?」，請改用具名佔位符（冒號加參數名稱）"))
      }
    }
  }

  private fun typeProblems(view: ViewDoc): List<ViewProblem> {
    val parameterTypes = ViewParamType.entries.map { it.name }
    val columnTypes = ViewColumnType.entries.map { it.name }
    val tones = Tone.entries.map { it.name }
    return view.parameters.flatMap { p ->
      buildList {
        if (p.type !in parameterTypes) {
          add(problem(view, "參數「${p.name}」的型別「${p.type}」不在允許的集合 $parameterTypes"))
        }
        if (p.type == ViewParamType.CHOICE.name && p.options.isEmpty()) {
          add(problem(view, "固定選項的參數「${p.name}」沒有列出任何選項"))
        }
      }
    } +
        view.columns.flatMap { c ->
          buildList {
            if (c.type !in columnTypes) {
              add(problem(view, "欄位「${c.name}」的型別「${c.type}」不在允許的集合 $columnTypes"))
            }
            c.tones
                .filter { it.tone !in tones }
                .forEach {
                  add(problem(view, "欄位「${c.name}」的狀態值「${it.value}」的語氣「${it.tone}」不在允許的集合 $tones"))
                }
          }
        }
  }

  private fun sortProblems(view: ViewDoc): List<ViewProblem> {
    val columns = view.columns.map { it.name }.toSet()
    return view.sort
        .filter { it.column !in columns }
        .map { problem(view, "預設排序的欄位「${it.column}」不是這個 view 的欄位") }
  }

  private fun drillProblems(view: ViewDoc, byId: Map<String, List<ViewDoc>>): List<ViewProblem> {
    val columns = view.columns.map { it.name }.toSet()
    return view.columns
        .mapNotNull { c -> c.drill?.let { c to it } }
        .flatMap { (column, drill) ->
          val target = byId[drill.view]?.first()
          buildList {
            if (drill.from !in columns) {
              add(problem(view, "欄位「${column.name}」的鑽取取值的欄位「${drill.from}」不是這個 view 的欄位"))
            }
            when {
              target == null -> add(problem(view, "欄位「${column.name}」的鑽取目標 view「${drill.view}」不存在"))
              target.parameters.none { it.name == drill.parameter } ->
                  add(
                      problem(
                          view,
                          "欄位「${column.name}」的鑽取要帶入的參數「${drill.parameter}」不是 view「${drill.view}」的參數",
                      )
                  )
            }
          }
        }
  }

  private fun <T> List<T>.duplicates(key: (T) -> String): List<String> =
      groupBy(key).filterValues { it.size > 1 }.keys.toList()
}
