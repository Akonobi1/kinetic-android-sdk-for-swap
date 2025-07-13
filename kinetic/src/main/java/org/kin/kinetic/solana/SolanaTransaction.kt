package org.kin.kinetic.solana

//package com.gftzhu.kinnected.solana

import org.kin.kinetic.solana.ByteStringSerializer
import org.kin.kinetic.solana.TransactionFormat
import kotlinx.serialization.*

object SignatureSerializer : ByteStringSerializer(SolanaTransaction.SIGNATURE_LENGTH_BYTES)

@Serializable
data class SolanaTransaction(
    val signatures: List<@Serializable(with = SignatureSerializer::class) ByteArray>,
    @Serializable(with = MessageSerializer::class) val message: Message
) {

    constructor(message: Message):
            this(List(message.signatureCount.toInt()) { ByteArray(SIGNATURE_LENGTH_BYTES) }, message)

    companion object {
        const val SIGNATURE_LENGTH_BYTES = 64
        fun from(bytes: ByteArray) = TransactionFormat.decodeFromByteArray(kotlinx.serialization.serializer<Transaction>(), bytes)
    }

    fun serialize(): ByteArray = TransactionFormat.encodeToByteArray(kotlinx.serialization.serializer<Transaction>(), this)
}