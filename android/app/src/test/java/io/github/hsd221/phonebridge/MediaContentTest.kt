package io.github.hsd221.phonebridge

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MediaContentTest {
    @Test fun onlyCanonicalExternalImageAndVideoIdsAreAccepted() {
        assertEquals("images", parseMediaUri("content://media/external/images/media/1")!!.kind)
        assertEquals("videos", parseMediaUri("content://media/external/video/media/42")!!.kind)
        assertEquals(Long.MAX_VALUE, parseMediaUri("content://media/external/video/media/${Long.MAX_VALUE}")!!.id)
        for (uri in listOf("file:///sdcard/Pictures/test.png", "https://example.invalid/p.png",
            "content://other/external/images/media/1", "content://media/internal/images/media/1",
            "content://media/external_primary/images/media/1", "content://media/external/images/media/0",
            "content://media/external/images/media/01", "content://media/external/images/media/-1",
            "content://media/external/images/media/+1", "content://media/external/images/media/9223372036854775808",
            "content://media/external/images/media/1?x=1", "content://media/external/images/media/1#x",
            "content://media/external/images/media/%31", "content://media/external/images%2fmedia/1",
            "content://media/external/images/media/1/", "content://media/external/videos/media/1",
            "content://user@media/external/images/media/1", "content://media:80/external/images/media/1",
            "content://media/external/images/media/1\n")) assertNull(uri, parseMediaUri(uri))
    }

    @Test fun mimeMustBelongToTheSelectedCollection() {
        for (mime in listOf("image/png", "image/jpeg", "image/heic", "image/avif", "IMAGE/PNG")) {
            assertTrue(isMediaMimeType("images", mime))
            assertFalse(isMediaMimeType("videos", mime))
        }
        for (mime in listOf("video/mp4", "video/webm")) {
            assertTrue(isMediaMimeType("videos", mime))
            assertFalse(isMediaMimeType("images", mime))
        }
        for (mime in listOf("text/plain", "application/octet-stream", "audio/mpeg", "image/", "image/png; x=1", "image/png\n", "")) {
            assertFalse(isMediaMimeType("images", mime))
            assertFalse(isMediaMimeType("videos", mime))
        }
    }

    @Test fun exactLimitAndEmptyContentWorkButOneExtraByteNeverReturnsPartialSuccess() {
        val data = ByteArray(32768) { (it % 251).toByte() }
        assertArrayEquals(data, readMediaBytes(ByteArrayInputStream(data), data.size) {})
        assertArrayEquals(byteArrayOf(), readMediaBytes(ByteArrayInputStream(byteArrayOf()), 1) {})
        val extra = assertThrows(ToolFailure::class.java) { readMediaBytes(ByteArrayInputStream(data), data.size - 1) {} }
        assertEquals("MEDIA_TOO_LARGE", extra.code)
        for (size in listOf(0, -1, 4194305)) {
            assertEquals("INVALID_ARGUMENT", assertThrows(ToolFailure::class.java) {
                readMediaBytes(ByteArrayInputStream(byteArrayOf()), size) {}
            }.code)
        }
    }

    @Test fun unknownLengthStreamIsNeverReadBeyondLimitPlusOne() {
        var bytesRead = 0
        val endless = object : InputStream() {
            override fun read(): Int { bytesRead++; return 42 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(42, offset, offset + length); bytesRead += length; return length
            }
        }
        assertEquals("MEDIA_TOO_LARGE", assertThrows(ToolFailure::class.java) {
            readMediaBytes(endless, 7) {}
        }.code)
        assertEquals(8, bytesRead)
    }

    @Test fun accessChecksCanCancelBetweenChunksAndAfterEndOfStream() {
        val cancellation = IllegalStateException("synthetic access withdrawal")
        var checks = 0
        assertSame(cancellation, assertThrows(IllegalStateException::class.java) {
            readMediaBytes(ByteArrayInputStream(ByteArray(32768)), 32768) {
                if (++checks == 2) throw cancellation
            }
        })
        var reachedEof = false
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it == -1) reachedEof = true
            }
        }
        assertSame(cancellation, assertThrows(IllegalStateException::class.java) {
            readMediaBytes(source, 10) { if (reachedEof) throw cancellation }
        })
        assertTrue(reachedEof)
    }
}
