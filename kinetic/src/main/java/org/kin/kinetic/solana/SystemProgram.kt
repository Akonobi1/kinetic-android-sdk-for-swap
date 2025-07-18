package org.kin.kinetic.solana

//import com.funkatronics.encoders.Encoder
import com.solana.networking.serialization.format.BorshEncoder

import kotlinx.serialization.builtins.ByteArraySerializer
import kotlin.jvm.JvmStatic

object SystemProgram : Program {
    @JvmStatic
    val PROGRAM_ID = SolanaPublicKey.from("11111111111111111111111111111111")

    private const val PROGRAM_INDEX_CREATE_ACCOUNT = 0
    private const val PROGRAM_INDEX_TRANSFER = 2

    @JvmStatic
    fun transfer(
        fromPublicKey: SolanaPublicKey,
        toPublickKey: SolanaPublicKey,
        lamports: Long
    ): TransactionInstruction =
        TransactionInstruction(PROGRAM_ID,
            listOf(
                AccountMeta(fromPublicKey, true, true),
                AccountMeta(toPublickKey, false, true)
            ),
            BorshEncoder().apply {
                encodeInt(PROGRAM_INDEX_TRANSFER)
                encodeLong(lamports)
            }.borshEncodedBytes
        )

    @JvmStatic
    fun createAccount(
        fromPublicKey: SolanaPublicKey,
        newAccountPublickey: SolanaPublicKey,
        lamports: Long,
        space: Long,
        programId: SolanaPublicKey
    ): TransactionInstruction =
        TransactionInstruction(PROGRAM_ID,
            listOf(
                AccountMeta(fromPublicKey, true, true),
                AccountMeta(newAccountPublickey, true, true)
            ),
            BorshEncoder().apply {
                encodeInt(PROGRAM_INDEX_CREATE_ACCOUNT)
                encodeLong(lamports)
                encodeLong(space)
                encodeSerializableValue(ByteArraySerializer(), programId.bytes)
            }.borshEncodedBytes
        )

    override val programId = PROGRAM_ID
}