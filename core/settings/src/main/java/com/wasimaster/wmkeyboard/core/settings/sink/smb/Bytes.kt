package com.wasimaster.wmkeyboard.core.settings.sink.smb

import java.io.ByteArrayOutputStream

/**
 * A little-endian byte writer, which is every integer SMB and NTLM have.
 * Positions are absolute so a length or an offset can be patched in after
 * the thing it describes has been written.
 */
internal class LeWriter(initial: Int = 128) {
    private val out = object : ByteArrayOutputStream(initial) {
        fun raw(): ByteArray = buf
    }

    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = apply {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }

    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }

    fun bytes(b: ByteArray) = apply { out.write(b, 0, b.size) }

    fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }

    /** Pads with zeros to a multiple of [n]. */
    fun align(n: Int) = apply { while (size % n != 0) out.write(0) }

    fun putU16(at: Int, v: Int) {
        val b = out.raw()
        b[at] = v.toByte()
        b[at + 1] = (v ushr 8).toByte()
    }

    fun putU32(at: Int, v: Int) {
        val b = out.raw()
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)

internal fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

internal fun ByteArray.slice(at: Int, len: Int): ByteArray = copyOfRange(at, at + len)

internal fun ByteArray.putU64(at: Int, v: Long) {
    for (i in 0 until 8) this[at + i] = (v ushr (8 * i)).toByte()
}

internal fun ByteArray.putU32(at: Int, v: Long) {
    for (i in 0 until 4) this[at + i] = (v ushr (8 * i)).toByte()
}

internal fun utf16(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)

internal fun fromUtf16(b: ByteArray, at: Int, len: Int): String = String(b, at, len, Charsets.UTF_16LE)

/** Windows FILETIME: 100 ns ticks since 1601. */
internal object FileTime {
    private const val EPOCH_DIFF_MS = 11_644_473_600_000L

    fun toMillis(ticks: Long): Long = if (ticks <= 0L) 0L else ticks / 10_000L - EPOCH_DIFF_MS

    fun fromMillis(ms: Long): Long = (ms + EPOCH_DIFF_MS) * 10_000L
}
