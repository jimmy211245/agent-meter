package dev.agentmeter.importers

/**
 * One field of a protobuf message read without a schema. [varint] holds the value of varint, fixed32 and
 * fixed64 fields (little-endian bits for the fixed ones); [bytes] the payload of length-delimited fields.
 */
class PbField(val number: Int, val wireType: Int, val varint: Long = 0, val bytes: ByteArray? = null)

/**
 * Minimal protobuf wire-format reader (varint, fixed64, length-delimited, fixed32), enough to pull numbers
 * out of messages whose schema is not shipped. Unknown fields are simply not asked for.
 */
object Protobuf {
    /** Null if [data] is not a well-formed message (truncated field, bad wire type, field number 0). */
    fun decode(data: ByteArray): List<PbField>? {
        val out = ArrayList<PbField>()
        var i = 0
        while (i < data.size) {
            val (key, afterKey) = readVarint(data, i) ?: return null
            i = afterKey
            val number = (key ushr 3).toInt()
            if (number <= 0 || key ushr 3 > Int.MAX_VALUE) return null
            when (val wire = (key and 7).toInt()) {
                0 -> {
                    val (v, next) = readVarint(data, i) ?: return null
                    out += PbField(number, wire, varint = v)
                    i = next
                }
                1 -> {
                    if (i + 8 > data.size) return null
                    out += PbField(number, wire, varint = littleEndian(data, i, 8))
                    i += 8
                }
                5 -> {
                    if (i + 4 > data.size) return null
                    out += PbField(number, wire, varint = littleEndian(data, i, 4))
                    i += 4
                }
                2 -> {
                    val (len, next) = readVarint(data, i) ?: return null
                    if (len < 0 || len > data.size - next) return null
                    out += PbField(number, wire, bytes = data.copyOfRange(next, next + len.toInt()))
                    i = next + len.toInt()
                }
                else -> return null // groups (3, 4) and invalid types are not used by the data we read
            }
        }
        return out
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift < 70) {
            val x = b[i++].toInt()
            result = result or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    private fun littleEndian(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (k in n - 1 downTo 0) v = (v shl 8) or (b[at + k].toLong() and 0xff)
        return v
    }
}

/** First varint with this field number, or 0 (proto3 default) if absent. */
fun List<PbField>.varint(number: Int): Long = firstOrNull { it.number == number && it.wireType != 2 }?.varint ?: 0L

/** First embedded message with this field number. */
fun List<PbField>.message(number: Int): List<PbField>? =
    firstOrNull { it.number == number && it.wireType == 2 }?.bytes?.let { Protobuf.decode(it) }

fun List<PbField>.string(number: Int): String? =
    firstOrNull { it.number == number && it.wireType == 2 }?.bytes?.toString(Charsets.UTF_8)

/** First length-delimited value anywhere in the message tree that starts with [prefix] (depth-first). */
fun List<PbField>.findString(prefix: String, depth: Int = 0): String? {
    for (f in this) {
        val b = f.bytes ?: continue
        val s = b.toString(Charsets.UTF_8)
        if (s.startsWith(prefix)) return s
        if (depth < 6) Protobuf.decode(b)?.takeIf { it.isNotEmpty() }?.findString(prefix, depth + 1)?.let { return it }
    }
    return null
}
