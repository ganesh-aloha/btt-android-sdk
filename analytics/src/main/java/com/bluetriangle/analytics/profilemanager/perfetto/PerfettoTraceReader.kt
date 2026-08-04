package com.bluetriangle.analytics.profilemanager.perfetto

/**
 * Minimal, dependency-free reader for the protobuf wire format used by Perfetto traces.
 *
 * Only the wire types the trace actually uses are supported: varint (0), 64-bit (1) and
 * length-delimited (2). 32-bit (5) is skipped.
 */
internal class PerfettoTraceReader(
    private val buffer: ByteArray,
    private var pos: Int,
    private val end: Int,
) {
    constructor(buffer: ByteArray) : this(buffer, 0, buffer.size)

    val hasMore: Boolean get() = pos < end

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_64BIT = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_32BIT = 5
    }

    class Field(
        val number: Int,
        val wireType: Int,
        /** Scalar value for varint / 64-bit / 32-bit fields. Meaningless for length-delimited. */
        val value: Long,
        val payloadStart: Int,
        val payloadEnd: Int,
    )

    fun readField(): Field {
        val tag = readVarint()
        val number = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        return when (wireType) {
            WIRE_VARINT -> Field(number, wireType, readVarint(), 0, 0)
            WIRE_64BIT -> Field(number, wireType, readFixed64(), 0, 0)
            WIRE_LENGTH_DELIMITED -> {
                val len = readVarint().toInt()
                val s = pos
                pos += len
                if (pos > end) throw PerfettoParseException("length-delimited field overruns buffer")
                Field(number, wireType, len.toLong(), s, pos)
            }
            WIRE_32BIT -> {
                pos += 4
                Field(number, wireType, 0, 0, 0)
            }
            else -> throw PerfettoParseException("unsupported wire type $wireType at $pos")
        }
    }

    fun stringOf(field: Field): String =
        String(buffer, field.payloadStart, field.payloadEnd - field.payloadStart, Charsets.UTF_8)

    /** Reads the next varint at the current cursor (used for packed repeated fields). */
    fun readVarintValue(): Long = readVarint()

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw PerfettoParseException("varint overruns buffer")
            val b = buffer[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw PerfettoParseException("varint too long")
        }
        return result
    }

    private fun readFixed64(): Long {
        if (pos + 8 > end) throw PerfettoParseException("fixed64 overruns buffer")
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((buffer[pos++].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }
}

internal class PerfettoParseException(message: String) : Exception(message)
