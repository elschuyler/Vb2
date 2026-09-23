package com.example.foundation.utils

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Zero-heap, low-latency binary dictionary buffer wrapper.
 * Supports direct [ByteBuffer] (from memory-mapped files via [java.nio.channels.FileChannel])
 * or in-memory [ByteArray] backed instances.
 */
class ByteArrayDictBuffer private constructor(
    private val byteBuffer: ByteBuffer?,
    private val byteArray: ByteArray?,
    private val baseOffset: Int,
    val capacity: Int
) {
    var position: Int = 0
        set(value) {
            field = value.coerceIn(0, limit)
            byteBuffer?.position(baseOffset + field)
        }

    var limit: Int = capacity
        set(value) {
            field = value.coerceIn(0, capacity)
            if (position > field) {
                position = field
            }
        }

    constructor(buffer: ByteBuffer) : this(
        byteBuffer = buffer.duplicate(),
        byteArray = null,
        baseOffset = buffer.position(),
        capacity = buffer.remaining()
    )

    constructor(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) : this(
        byteBuffer = null,
        byteArray = bytes,
        baseOffset = offset,
        capacity = length
    )

    fun hasRemaining(): Boolean = position < limit

    fun remaining(): Int = (limit - position).coerceAtLeast(0)

    fun seek(newPosition: Int) {
        position = newPosition
    }

    fun rewind() {
        position = 0
    }

    /**
     * Reads a signed byte (-128..127) and advances position.
     */
    fun readByte(): Int {
        checkBounds(1)
        val value = if (byteBuffer != null) {
            byteBuffer.get().toInt()
        } else {
            byteArray!![baseOffset + position].toInt()
        }
        position++
        return value
    }

    /**
     * Reads an unsigned byte (0..255) and advances position.
     */
    fun readUnsignedByte(): Int {
        return readByte() and 0xFF
    }

    /**
     * Reads a 16-bit big-endian signed short and advances position by 2.
     */
    fun readShort(): Int {
        checkBounds(2)
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val s = (b0 shl 8) or b1
        return if (s >= 0x8000) s - 0x10000 else s
    }

    /**
     * Reads a 16-bit big-endian unsigned short (0..65535) and advances position by 2.
     */
    fun readUnsignedShort(): Int {
        checkBounds(2)
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        return (b0 shl 8) or b1
    }

    /**
     * Reads a 24-bit big-endian unsigned integer (0..16777215) and advances position by 3.
     * Standard node pointer representation in AOSP / HeliBoard binary dictionary format.
     */
    fun readInt24(): Int {
        checkBounds(3)
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    /**
     * Reads a 32-bit big-endian signed integer and advances position by 4.
     */
    fun readInt(): Int {
        checkBounds(4)
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /**
     * Reads a fixed-length UTF-8 string.
     */
    fun readString(length: Int): String {
        if (length <= 0) return ""
        checkBounds(length)
        val bytes = ByteArray(length)
        if (byteBuffer != null) {
            byteBuffer.get(bytes)
        } else {
            System.arraycopy(byteArray!!, baseOffset + position, bytes, 0, length)
        }
        position += length
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Reads a null-terminated UTF-8 string from the current position.
     */
    fun readNullTerminatedString(): String {
        val startPos = position
        while (hasRemaining()) {
            val b = readByte()
            if (b == 0) {
                val len = position - startPos - 1
                val bytes = ByteArray(len)
                if (byteBuffer != null) {
                    val currentPos = byteBuffer.position()
                    byteBuffer.position(baseOffset + startPos)
                    byteBuffer.get(bytes)
                    byteBuffer.position(currentPos)
                } else {
                    System.arraycopy(byteArray!!, baseOffset + startPos, bytes, 0, len)
                }
                return String(bytes, StandardCharsets.UTF_8)
            }
        }
        return ""
    }

    /**
     * Returns the byte at an absolute [index] without changing position.
     */
    operator fun get(index: Int): Byte {
        if (index !in 0 until limit) {
            throw IndexOutOfBoundsException("Index $index out of bounds for limit $limit")
        }
        return if (byteBuffer != null) {
            byteBuffer.get(baseOffset + index)
        } else {
            byteArray!![baseOffset + index]
        }
    }

    /**
     * Creates an isolated sub-view of this buffer starting at [offset] with size [length].
     */
    fun slice(offset: Int, length: Int): ByteArrayDictBuffer {
        val safeOffset = offset.coerceIn(0, capacity)
        val safeLength = length.coerceIn(0, capacity - safeOffset)
        return if (byteBuffer != null) {
            val dup = byteBuffer.duplicate()
            dup.position(baseOffset + safeOffset)
            dup.limit(baseOffset + safeOffset + safeLength)
            ByteArrayDictBuffer(dup)
        } else {
            ByteArrayDictBuffer(byteArray!!, baseOffset + safeOffset, safeLength)
        }
    }

    private fun checkBounds(bytesNeeded: Int) {
        if (position + bytesNeeded > limit) {
            throw IndexOutOfBoundsException("Cannot read $bytesNeeded bytes at position $position with limit $limit")
        }
    }
}
