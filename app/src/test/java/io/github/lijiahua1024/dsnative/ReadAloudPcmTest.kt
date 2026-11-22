package io.github.lijiahua1024.dsnative

import io.github.lijiahua1024.dsnative.data.PcmChunkAligner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ReadAloudPcmTest {

    @Test
    fun alignerKeepsEveryWriteEvenAndPreservesByteOrderAcrossOddChunks() {
        val source = ByteArray(256) { it.toByte() }
        val chunkSizes = intArrayOf(1, 2, 3, 5, 1, 4, 7, 13, 19, 1, 200)
        val emitted = ByteArrayOutputStream()
        val aligner = PcmChunkAligner()

        var pos = 0
        for (size in chunkSizes) {
            val end = minOf(source.size, pos + size)
            val chunk = source.copyOfRange(pos, end)
            aligner.align(chunk, chunk.size) { buf, len ->
                assertTrue("Expected even length, got $len", len % 2 == 0)
                emitted.write(buf, 0, len)
            }
            pos = end
        }

        val out = emitted.toByteArray()
        assertEquals(256, out.size)
        assertArrayEquals(source, out)
    }

    @Test
    fun singleByteChunkDefersUntilSecondByteArrives() {
        val aligner = PcmChunkAligner()
        val writes = mutableListOf<ByteArray>()

        aligner.align(byteArrayOf(0x11), 1) { b, l -> writes += b.copyOfRange(0, l) }
        assertTrue(writes.isEmpty())

        aligner.align(byteArrayOf(0x22, 0x33), 2) { b, l -> writes += b.copyOfRange(0, l) }
        assertEquals(1, writes.size)
        assertArrayEquals(byteArrayOf(0x11, 0x22), writes[0])

        aligner.align(byteArrayOf(0x44), 1) { b, l -> writes += b.copyOfRange(0, l) }
        assertEquals(2, writes.size)
        assertArrayEquals(byteArrayOf(0x33, 0x44), writes[1])
    }
}
