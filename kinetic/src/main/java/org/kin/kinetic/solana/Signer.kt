package org.kin.kinetic.solana

interface Signer {
    val publicKey: ByteArray
    val ownerLength: Number
    val signatureLength: Number
    suspend fun signPayload(payload: ByteArray): ByteArray
}