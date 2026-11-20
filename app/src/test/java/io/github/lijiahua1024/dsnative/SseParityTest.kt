package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.api.SseReader
import org.junit.Test
import java.io.File

// Feeds every tools/sse-samples/*.sse through the app's own SseReader and
// writes the decoded (event,data) sequence to $SSE_OUT as JSON, for
// byte-level comparison with tools/parse_ref.mjs output (tools/sse_parity.sh).
class SseParityTest {
    @Test
    fun decodeAllSamples() {
        val dir = listOf(File("../tools/sse-samples"), File("tools/sse-samples"))
            .first { it.isDirectory }
        fun esc(s: String) = buildString {
            append('"')
            for (c in s) append(when (c) {
                '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"
                '\t' -> "\\t"; '\b' -> "\\b"; '' -> "\\f"
                else -> if (c < ' ') "\\u%04x".format(c.code) else c.toString()
            })
            append('"')
        }
        val out = StringBuilder("[")
        val files = dir.listFiles { _, n -> n.endsWith(".sse") }!!.sortedBy { it.name }
        files.forEachIndexed { i, f ->
            val reader = SseReader(f.bufferedReader())
            val events = StringBuilder("[")
            var n = 0
            while (true) {
                val ev = reader.nextEvent() ?: break
                if (n++ > 0) events.append(',')
                events.append("{\"event\":").append(esc(ev.first))
                    .append(",\"data\":").append(esc(ev.second)).append('}')
            }
            events.append(']')
            if (i > 0) out.append(',')
            out.append("{\"sample\":").append(esc(f.name))
                .append(",\"events\":").append(events).append('}')
        }
        out.append(']')
        File(System.getenv("SSE_OUT") ?: "/tmp/native-parse.json").writeText(out.toString())
    }
}
