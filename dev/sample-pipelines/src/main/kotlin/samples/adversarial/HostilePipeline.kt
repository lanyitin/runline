package samples.adversarial

import dev.lawlan.runline.core.AccessLimit
import dev.lawlan.runline.core.Param
import dev.lawlan.runline.core.Pipeline
import dev.lawlan.runline.core.PipelineContext
import dev.lawlan.runline.core.PipelineDefinition

/**
 * For the security check of the Console (WI-37), never for a demo: every string that a pipeline can
 * hand to the Console (its parameters, network entries, log lines, the failure) carries markup, a
 * script or a control sequence. The Console must show all of it as plain text. Each fragment has a
 * marker: `pwn` in an id, `window.__xss` in a handler, and the host `evil.invalid` in anything that
 * would make the browser fetch it.
 */
@PipelineDefinition(
    name = "adversarial-hostile",
    parameters =
        [
            Param(
                "<i id=\"pwn-param\">p</i>",
                required = false,
                default = "<script>window.__xss='default'</script>",
            ),
            Param("plain", required = false, default = "x"),
        ],
    network = AccessLimit(allow = ["<b id=\"pwn-host\">evil.invalid</b>"]),
    processes = AccessLimit(allow = []),
)
class HostilePipeline : Pipeline {
  override fun run(context: PipelineContext) {
    println("<script>window.__xss='log'</script>")
    println(
        "<img id=\"pwn-log\" src=\"http://evil.invalid/log.png\" onerror=\"window.__xss='log-img'\">"
    )
    println("</div><div id=\"pwn-break\">closed the line</div>")
    println("\u001b[31mred? \u001b[1;5mblink\u001b[0m \u001b[2J\u001b[H cleared?")
    println("\u001b]8;;http://evil.invalid/osc\u0007osc-link\u001b]8;;\u0007 \u001b]0;title\u0007")
    println(
        "javascript:window.__xss='url' http://evil.invalid/autolink <a href=\"http://evil.invalid/a\">a</a>"
    )
    System.err.println("<svg id=\"pwn-stderr\" onload=\"window.__xss='stderr'\"></svg>")
    context.parameters.forEach { (key, value) -> println("param $key=$value") }
    throw IllegalStateException(
        "<img id=\"pwn-failure\" src=\"http://evil.invalid/failure.png\" onerror=\"window.__xss='failure'\">" +
            " \u001b[31mfailed\u001b[0m"
    )
  }
}
