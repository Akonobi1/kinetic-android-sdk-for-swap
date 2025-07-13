package org.kin.kinetic.solana

//import org.bouncycastle.crypto.signers.Ed25519Signer

import org.kin.kinetic.solana.SolanaTransaction

abstract class SolanaSigner : Ed25519Signer() {
    abstract fun signMessage(message: ByteArray): ByteArray
    abstract fun signTransaction(transaction: ByteArray): ByteArray
    abstract suspend fun signAndSendTransaction(transaction: SolanaTransaction): Result<String>
}