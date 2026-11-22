package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.ArchiveBackupException
import io.github.lijiahua1024.dsnative.data.FileAttachment
import io.github.lijiahua1024.dsnative.data.Message
import io.github.lijiahua1024.dsnative.data.ReplyBlock
import io.github.lijiahua1024.dsnative.data.ReplyStatus
import io.github.lijiahua1024.dsnative.data.Session
import io.github.lijiahua1024.dsnative.data.detectImageMimeType
import io.github.lijiahua1024.dsnative.data.mergeArchiveSession
import io.github.lijiahua1024.dsnative.data.readArchiveZipFile
import io.github.lijiahua1024.dsnative.data.visibleMessages
import io.github.lijiahua1024.dsnative.data.writeSessionsArchive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HistoryArchiveTest {

    @Test
    fun exportAndZipFileImportRoundTripsBranchesToolsAndImages() {
        val tempRoot = Files.createTempDirectory("archive-rt-").toFile()
        try {
            val imgFile = File(tempRoot, "sample.png")
            val pngBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D,
            )
            imgFile.writeBytes(pngBytes)

            val readLinkUse = ReplyBlock.Tool(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "tu_rl")
                put("kind", "read_link")
                put("name", "deepseek_read_url")
                putJsonObject("input") { put("action", "read_link"); put("url", "https://example.org/r") }
            })
            val readLinkRes = ReplyBlock.Tool(buildJsonObject {
                put("type", "web_fetch_tool_result")
                put("tool_use_id", "tu_rl")
                put("kind", "read_link")
                put("signature", "sig_123")
                putJsonObject("content") {
                    put("type", "web_fetch_result")
                    put("url", "https://example.org/r")
                    putJsonObject("content") { put("title", "ExampleDoc") }
                }
            })
            val searchUse = ReplyBlock.Tool(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "tu_s")
                put("kind", "deep_search")
                put("name", "deepseek_web_search")
                putJsonObject("input") {
                    put("action", "search")
                    putJsonArray("queries") { add("kotlin") }
                }
            })
            val searchRes = ReplyBlock.Tool(buildJsonObject {
                put("type", "web_search_tool_result")
                put("tool_use_id", "tu_s")
                put("kind", "deep_search")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "web_search_result")
                        put("url", "https://kotlinlang.org")
                        put("title", "Kotlin")
                        put("snippet", "Kotlin site")
                    })
                }
            })
            val openUse = ReplyBlock.Tool(buildJsonObject {
                put("type", "server_tool_use")
                put("id", "tu_o")
                put("kind", "deep_search")
                putJsonObject("input") {
                    put("action", "open_page")
                    put("url", "https://kotlinlang.org")
                }
            })
            val openRes = ReplyBlock.Tool(buildJsonObject {
                put("type", "web_fetch_tool_result")
                put("tool_use_id", "tu_o")
                put("kind", "deep_search")
                putJsonObject("content") {
                    put("type", "web_fetch_result")
                    put("url", "https://kotlinlang.org")
                    putJsonObject("content") { put("title", "Kotlin") }
                }
            })

            // Branching session: u1 -> a1 (active), and sibling branch u1 -> a2
            val u1 = Message.User(
                id = "u1",
                content = "Check photo",
                files = listOf(
                    FileAttachment(
                        id = "att1",
                        name = "sample.png",
                        size = pngBytes.size.toLong(),
                        isImage = true,
                        mimeType = "image/png",
                        localPath = imgFile.absolutePath,
                    ),
                ),
                createdAt = 1_700_000_000_000L,
                parentId = null,
                selectedChildId = "a1",
            )
            val a1 = Message.Assistant(
                id = "a1",
                createdAt = 1_700_000_001_000L,
                thinking = true,
                search = true,
                status = ReplyStatus.DONE,
                blocks = listOf(
                    ReplyBlock.Thinking("hmm", elapsedSeconds = 5L),
                    searchUse, searchRes, openUse, openRes, readLinkUse, readLinkRes,
                    ReplyBlock.Text("branch one answer"),
                ),
                parentId = "u1",
            )
            val a2 = Message.Assistant(
                id = "a2",
                createdAt = 1_700_000_002_000L,
                status = ReplyStatus.DONE,
                blocks = listOf(ReplyBlock.Text("alt branch")),
                parentId = "u1",
            )
            val session = Session(
                id = "sess-1",
                title = "Branches & Tools",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_005_000L,
                messages = listOf(u1, a1, a2),
                rootMessageId = "u1",
            )

            val zipFile = File(tempRoot, "export.zip")
            zipFile.outputStream().use { out ->
                writeSessionsArchive(listOf(session), setOf("sess-1"), out)
            }

            val attachesOut = File(tempRoot, "extracted")
            val parsed = readArchiveZipFile(zipFile, attachesOut)
            assertEquals(1, parsed.sessions.size)
            assertTrue("sess-1" in parsed.pinnedIds)
            val restored = parsed.sessions.first()
            assertEquals(3, restored.messages.size) // Both branches preserved!
            val vis = visibleMessages(restored)
            assertEquals(2, vis.size)
            val rUser = vis[0] as Message.User
            assertEquals("image/png", rUser.files[0].mimeType)
            assertNotNull(rUser.files[0].localPath)
            assertTrue(File(rUser.files[0].localPath!!).exists())

            val rAst = vis[1] as Message.Assistant
            val thinking = rAst.blocks.filterIsInstance<ReplyBlock.Thinking>().first()
            assertEquals(5L, thinking.elapsedSeconds)
            assertTrue(rAst.blocks.any { it is ReplyBlock.Tool })
            assertEquals("branch one answer", rAst.blocks.filterIsInstance<ReplyBlock.Text>().first().text)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun zipFileReadsStoredEntriesWithBit3DataDescriptorAndRejectsNewerVersion() {
        val tempRoot = Files.createTempDirectory("archive-stored-ext-").toFile()
        try {
            val entries = linkedMapOf(
                "manifest.json" to """{"export_format_version":3}""".toByteArray(),
                "chat_session_index.json" to "[]".toByteArray(),
            )
            val storedExtZip = File(tempRoot, "stored-ext.zip")
            storedExtZip.writeBytes(buildStoredWithDataDescriptorZip(entries))
            val parsed = readArchiveZipFile(storedExtZip, File(tempRoot, "att"))
            assertTrue(parsed.sessions.isEmpty())

            // Version > 3 should report backupNewerVersion
            val newerZip = File(tempRoot, "newer.zip")
            ZipOutputStream(newerZip.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"export_format_version":4}""".toByteArray())
                zip.closeEntry()
            }
            try {
                readArchiveZipFile(newerZip, File(tempRoot, "att2"))
                fail("Expected ArchiveBackupException")
            } catch (e: ArchiveBackupException) {
                assertEquals("backupNewerVersion", e.errorKey)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun mergeArchiveSessionKeepsLocalMetadataUpgradesIncompleteAndGraftsBranches() {
        val local = Session(
            id = "s1",
            title = "Topic",
            createdAt = 1000L,
            updatedAt = 2000L,
            rootMessageId = "loc-u1",
            messages = listOf(
                Message.User(id = "loc-u1", content = "q1", createdAt = 1000L, selectedChildId = "loc-a1"),
                Message.Assistant(
                    id = "loc-a1", createdAt = 1100L, status = ReplyStatus.STOPPED,
                    model = "deepseek-flash", latestReminder = "rem",
                    blocks = listOf(ReplyBlock.Text("partial")), parentId = "loc-u1",
                ),
            ),
        )
        val incoming = Session(
            id = "s1",
            title = "Topic",
            createdAt = 1000L,
            updatedAt = 3000L,
            rootMessageId = "1",
            messages = listOf(
                Message.User(id = "1", content = "q1", createdAt = 1000L, selectedChildId = "2"),
                Message.Assistant(
                    id = "2", createdAt = 1100L, status = ReplyStatus.DONE,
                    blocks = listOf(ReplyBlock.Text("partial complete answer")), parentId = "1", selectedChildId = "3",
                ),
                Message.User(id = "3", content = "q2", createdAt = 2500L, parentId = "2", selectedChildId = "4"),
                Message.Assistant(
                    id = "4", createdAt = 2600L, status = ReplyStatus.DONE,
                    blocks = listOf(ReplyBlock.Text("a2")), parentId = "3",
                ),
                Message.Assistant(
                    id = "5", createdAt = 1500L, status = ReplyStatus.DONE,
                    blocks = listOf(ReplyBlock.Text("alt reply")), parentId = "1",
                ),
            ),
        )
        val res = mergeArchiveSession(local, incoming, createId = { "gen-${System.nanoTime()}" })
        assertTrue(res.changed)
        assertEquals(3, res.addedMessages) // q2, a2, alt reply
        assertEquals(1, res.updatedMessages) // loc-a1 upgraded partial -> full DONE
        val vis = visibleMessages(res.session)
        assertEquals(listOf("loc-u1", "loc-a1"), vis.take(2).map { it.id })
        assertEquals("deepseek-flash", (vis[1] as Message.Assistant).model)
        assertEquals("rem", (vis[1] as Message.Assistant).latestReminder)
        assertEquals("partial complete answer", (vis[1] as Message.Assistant).blocks.filterIsInstance<ReplyBlock.Text>().first().text)
        assertEquals(4, vis.size) // Switched to deeper q2 -> a2 leaf
        assertEquals(5, res.session.messages.size) // Sibling branch 5 also retained!
        assertEquals("image/webp", detectImageMimeType("RIFFxxxxWEBP".toByteArray()))
    }

    /** Constructs minimal STORED ZIP with bit-3 Data Descriptor (f7e2f86 edge case). */
    private fun buildStoredWithDataDescriptorZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val centralDir = ArrayList<ByteArray>()
        var offset = 0
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value.toInt()
            val localHeader = ByteBuffer.allocate(30 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            localHeader.putInt(0x04034B50)
            localHeader.putShort(20)
            localHeader.putShort(0x0008) // bit 3 Data Descriptor!
            localHeader.putShort(0) // STORED!
            localHeader.putShort(0)
            localHeader.putShort(0)
            localHeader.putInt(0)
            localHeader.putInt(0)
            localHeader.putInt(0)
            localHeader.putShort(nameBytes.size.toShort())
            localHeader.putShort(0)
            localHeader.put(nameBytes)
            out.write(localHeader.array())
            out.write(data)
            val desc = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            desc.putInt(0x08074B50)
            desc.putInt(crc)
            desc.putInt(data.size)
            desc.putInt(data.size)
            out.write(desc.array())

            val cd = ByteBuffer.allocate(46 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            cd.putInt(0x02014B50)
            cd.putShort(20)
            cd.putShort(20)
            cd.putShort(0x0008)
            cd.putShort(0)
            cd.putShort(0)
            cd.putShort(0)
            cd.putInt(crc)
            cd.putInt(data.size)
            cd.putInt(data.size)
            cd.putShort(nameBytes.size.toShort())
            cd.putShort(0)
            cd.putShort(0)
            cd.putShort(0)
            cd.putShort(0)
            cd.putInt(0)
            cd.putInt(offset)
            cd.put(nameBytes)
            centralDir.add(cd.array())

            offset += localHeader.capacity() + data.size + 16
        }
        val cdOffset = offset
        var cdSize = 0
        for (c in centralDir) {
            out.write(c)
            cdSize += c.size
        }
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054B50)
        eocd.putShort(0)
        eocd.putShort(0)
        eocd.putShort(entries.size.toShort())
        eocd.putShort(entries.size.toShort())
        eocd.putInt(cdSize)
        eocd.putInt(cdOffset)
        eocd.putShort(0)
        out.write(eocd.array())
        return out.toByteArray()
    }
}
