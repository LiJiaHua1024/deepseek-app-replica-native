package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.api.ApiError
import io.github.lijiahua1024.dsnative.api.ApiErrorKind
import io.github.lijiahua1024.dsnative.api.buildCreateMessageRequest
import io.github.lijiahua1024.dsnative.data.CHECKPOINT_LIMIT
import io.github.lijiahua1024.dsnative.data.ContextCheckpoint
import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.contextBudget
import io.github.lijiahua1024.dsnative.data.prepareHistoryContext
import io.github.lijiahua1024.dsnative.data.readOfficeText
import io.github.lijiahua1024.dsnative.data.readPlainTextAttachment
import io.github.lijiahua1024.dsnative.data.saveContextCheckpoint
import io.github.lijiahua1024.dsnative.data.textPrefixLength
import io.github.lijiahua1024.dsnative.data.textTail
import io.github.lijiahua1024.dsnative.data.validateSummaryResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContextCompactionTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun validSummary(body: String = "关键事实：$bodyTag") = """
        ## 目标与约束
        ${body}

        ## 已确认的进展
        已完成第一阶段

        ## 待解决问题
        无

        ## 关键资料
        参考记录
    """.trimIndent()

    companion object {
        private const val bodyTag = "ctx"
    }

    @Test
    fun summaryValidationRequiresEndTurnAndAllFourSections() {
        val ok = validSummary()
        assertNotNull(validateSummaryResponse(ok, "end_turn"))
        assertNull(validateSummaryResponse(ok, "max_tokens"))
        assertNull(validateSummaryResponse("## 目标与约束\n只有一节", "end_turn"))
    }

    @Test
    fun checkpointSaveBoundsToLimitAndReplacesExistingReplyId() {
        var list = emptyList<ContextCheckpoint>()
        for (i in 1..20) {
            list = saveContextCheckpoint(list, ContextCheckpoint("r$i", "s$i"))
        }
        assertEquals(CHECKPOINT_LIMIT, list.size)
        assertEquals("r5", list.first().replyId)
        list = saveContextCheckpoint(list, ContextCheckpoint("r20", "s20-updated", offset = 12))
        assertEquals("s20-updated", list.last().summary)
        assertEquals(12, list.last().offset)
    }

    @Test
    fun prefixAndTailRespectNewlinesAndBudgets() {
        val text = "line1 alpha\nline2 beta\nline3 gamma\nline4 delta"
        val len = textPrefixLength(text, budget = 10)
        assertTrue(len in 1..text.length)
        val tail = textTail(text, budget = 8)
        assertTrue(tail.isNotEmpty() && text.endsWith(tail))
    }

    @Test
    fun rollingCompactionSummarizesOlderHistoryOnOverflowAndInjectsSummary() = runBlocking {
        val history = mutableListOf<Message>()
        for (i in 1..6) {
            history.add(Message.User(id = "u$i", content = "question $i " + "x".repeat(400), createdAt = i * 2L))
            history.add(
                Message.Assistant(
                    id = "a$i",
                    createdAt = i * 2L + 1,
                    status = ReplyStatus.DONE,
                    latestReminder = "rem",
                    blocks = listOf(ReplyBlock.Text("answer $i " + "y".repeat(400))),
                )
            )
        }
        val checkpoints = mutableListOf<ContextCheckpoint>()
        var summarizeCalls = 0
        val res = prepareHistoryContext(
            history = history,
            checkpoints = emptyList(),
            budget = 750,
            recentBudget = 320,
            summarize = { req ->
                summarizeCalls++
                if (req.excerpts.joinToString("\n").length > 900) {
                    throw ApiError(ApiErrorKind.HTTP, "context_window_exceeded", status = 400, code = "context_length_exceeded")
                }
                validSummary("批次$summarizeCalls")
            },
            saveCheckpoint = { cp -> checkpoints.add(cp) },
            batchBudgetInit = 1200,
        )
        assertTrue("Expected at least one summarization call", summarizeCalls >= 1)
        assertTrue("Expected checkpoints saved", checkpoints.isNotEmpty())
        assertNotNull(res.summary)
        assertTrue(res.history.size < history.size)
        assertTrue(res.history.isNotEmpty())

        // Verify summary is inserted into first user message right before user text block, while preserving file block & cache_control
        val q = Message.User(
            id = "u7",
            content = "latest q",
            files = listOf(FileAttachment("f1", serverId = "file-1", name = "a.png", size = 10, isImage = true)),
            createdAt = 99L,
        )
        val built = buildCreateMessageRequest(
            history = res.history,
            question = q,
            latestReminder = "rem2",
            thinking = false,
            search = false,
            requestTitle = false,
            contextSummary = res.summary,
        )
        val msgs = Json.parseToJsonElement(built.bodyJson).jsonObject["messages"]!!.jsonArray
        val firstUserContent = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            .jsonObject["content"]!!.jsonArray
        assertTrue(firstUserContent[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("较早的对话摘要（历史资料）"))
        val b = contextBudget(393_216)
        assertTrue(b.input in 500_000..700_000)
    }

    @Test
    fun extractsPlainTextAndOfficeDocxAndXlsx() {
        val txtFile = File(tmp.root, "notes.txt").apply { writeText("Hello\nWorld") }
        assertEquals("Hello\nWorld", readPlainTextAttachment(txtFile, "notes.txt", isImage = false))

        val docxFile = File(tmp.root, "sample.docx")
        ZipOutputStream(docxFile.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""".trimIndent(),
            )
            add(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>First Paragraph</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>ColA</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>ColB</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  </w:body>
                </w:document>""".trimIndent(),
            )
        }
        val docText = readOfficeText("docx", docxFile)
        assertEquals("First Paragraph\nColA\tColB", docText)

        val xlsxFile = File(tmp.root, "sheet.xlsx")
        ZipOutputStream(xlsxFile.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent(),
            )
            add(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""".trimIndent(),
            )
            add(
                "xl/sharedStrings.xml",
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Item</t></si></sst>""",
            )
            add(
                "xl/workbook.xml",
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId2"/></sheets></workbook>""",
            )
            add(
                "xl/worksheets/sheet1.xml",
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42</v></c></row></sheetData></worksheet>""",
            )
        }
        val xlsxText = readOfficeText("xlsx", xlsxFile)
        assertTrue(xlsxText != null && xlsxText.contains("[工作表 Data 第 1 行]"))
        assertTrue(xlsxText!!.contains("Item") && xlsxText.contains("42"))
    }
}
