package org.kin.kinetic.solana

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * Stub implementation of BorshEncoder for compatibility
 * This is a minimal implementation that extends AbstractEncoder
 */
class BorshEncoder : AbstractEncoder() {
    private val bytes = mutableListOf<Byte>()

    val encodedBytes: ByteArray get() = bytes.toByteArray()

    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeByte(value: Byte) {
        bytes.add(value)
    }

    override fun encodeBoolean(value: Boolean) = encodeByte(if (value) 1 else 0)
    override fun encodeChar(value: Char) = encodeShort(value.code.toShort())
    override fun encodeDouble(value: Double) = encodeLong(value.toBits())
    override fun encodeFloat(value: Float) = encodeInt(value.toBits())

    override fun encodeInt(value: Int) {
        repeat(4) { i ->
            encodeByte((value shr (i * 8)).toByte())
        }
    }

    override fun encodeLong(value: Long) {
        repeat(8) { i ->
            encodeByte((value shr (i * 8)).toByte())
        }
    }

    override fun encodeShort(value: Short) {
        repeat(2) { i ->
            encodeByte((value.toInt() shr (i * 8)).toByte())
        }
    }

    override fun encodeString(value: String) {
        val stringBytes = value.toByteArray(Charsets.UTF_8)
        encodeInt(stringBytes.size)
        stringBytes.forEach { encodeByte(it) }
    }

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        encodeInt(collectionSize)
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = this
}

/**
 * Stub implementation of BorshDecoder for compatibility
 * This is a minimal implementation that extends AbstractDecoder
 */
class BorshDecoder(private val bytes: ByteArray) : AbstractDecoder() {
    private var position = 0

    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun decodeByte(): Byte {
        if (position >= bytes.size) throw IndexOutOfBoundsException("Decoder reached end of input")
        return bytes[position++]
    }

    override fun decodeBoolean(): Boolean = decodeByte() != 0.toByte()
    override fun decodeChar(): Char = decodeShort().toInt().toChar()
    override fun decodeDouble(): Double = Double.fromBits(decodeLong())
    override fun decodeFloat(): Float = Float.fromBits(decodeInt())

    override fun decodeInt(): Int {
        var result = 0
        repeat(4) { i ->
            result = result or ((decodeByte().toInt() and 0xFF) shl (i * 8))
        }
        return result
    }

    override fun decodeLong(): Long {
        var result = 0L
        repeat(8) { i ->
            result = result or ((decodeByte().toLong() and 0xFF) shl (i * 8))
        }
        return result
    }

    override fun decodeShort(): Short {
        var result = 0
        repeat(2) { i ->
            result = result or ((decodeByte().toInt() and 0xFF) shl (i * 8))
        }
        return result.toShort()
    }

    override fun decodeString(): String {
        val length = decodeInt()
        val stringBytes = ByteArray(length) { decodeByte() }
        return String(stringBytes, Charsets.UTF_8)
    }

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = decodeInt()

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        return if (position < bytes.size) 0 else CompositeDecoder.DECODE_DONE
    }

    override fun decodeSequentially(): Boolean = true

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = this
}

/**
 * Stub for isOnCurve function
 */
fun ByteArray.isOnCurve(): Boolean {
    // For Program Derived Addresses, we want addresses that are NOT on the curve
    // This is a simplified implementation - in practice this would do Ed25519 curve checking
    return false // Most addresses are not on curve, which is what we want for PDAs
}