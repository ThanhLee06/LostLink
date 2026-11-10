package com.example.lostlink.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Do not trust a content provider's SIZE metadata or allocate an unbounded image. */
internal fun readImageUploadBytes(input: InputStream, limit: Int = CloudinaryRepository.MAX_FILE_SIZE_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - total))
        if (count < 0) break
        if (count == 0) {
            val next = input.read()
            if (next < 0) break
            total++
            require(total <= limit) { "Ảnh vượt quá giới hạn 5 MB." }
            output.write(next)
        } else {
            total += count
            require(total <= limit) { "Ảnh vượt quá giới hạn 5 MB." }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
