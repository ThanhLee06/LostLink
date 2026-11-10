package com.example.lostlink.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ImageUploadBytesTest {
    @Test fun acceptsAnImageExactlyAtTheLimit() {
        val bytes = ByteArray(100) { it.toByte() }
        assertArrayEquals(bytes, readImageUploadBytes(ByteArrayInputStream(bytes), 100))
    }

    @Test fun stopsReadingAnOversizedStreamAfterLimitPlusOneByte() {
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                consumed += len
                return len
            }
        }
        assertThrows(IllegalArgumentException::class.java) { readImageUploadBytes(input, 100) }
        assertEquals(101, consumed)
    }
}
