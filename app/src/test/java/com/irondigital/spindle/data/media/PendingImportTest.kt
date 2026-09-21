package com.irondigital.spindle.data.media

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PendingImportTest {
    @Test fun `read failure after partial output removes the pending destination`() {
        var removed = false
        var published = false
        val bytes = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            completePendingImport({ "pending" }, {
                val input = object : InputStream() {
                    var read = false
                    override fun read(): Int {
                        if (read) throw IOException("Provider disconnected")
                        read = true
                        return 42
                    }
                }
                input.copyTo(bytes, 1)
            }, { published = true; true }, { removed = true })
        }
        assertEquals(1, bytes.size())
        assertTrue(removed)
        assertFalse(published)
    }

    @Test fun `publication failure and cancellation both clean up`() {
        var removed = 0
        assertThrows(IOException::class.java) {
            completePendingImport({ 1 }, { 10L }, { false }, { removed++ })
        }
        assertThrows(CancellationException::class.java) {
            completePendingImport({ 2 }, { throw CancellationException() }, { true }, { removed++ })
        }
        assertEquals(2, removed)
    }

    @Test fun `successful publication keeps the imported file`() {
        val result = completePendingImport({ "audio" }, { 10L }, { true }, { fail("Deleted published file") })
        assertEquals("audio", result)
    }
}
