package dev.agentmeter

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Some agents append one zstd frame per log line, so a file is many frames back to back.
 * All frames are decoded. If the tail is damaged or half-written, decoding stops there and the text is cut
 * after the last complete line, so the next scan picks it up once it is finished.
 */
object ZstdFrames {
    fun decodeText(data: ByteArray): String {
        val out = ByteArrayOutputStream()
        var complete = true
        try {
            ZstdInputStream(ByteArrayInputStream(data)).use { it.transferTo(out) }
        } catch (e: Exception) {
            complete = false
        }
        val bytes = out.toByteArray()
        val end = if (complete) bytes.size else bytes.lastIndexOf('\n'.code.toByte()) + 1
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
