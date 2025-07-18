package org.kin.kinetic.solana

import org.kin.kinetic.solana.SolanaPublicKey
import org.kin.kinetic.solana.SolanaPublicKeySerializer
import org.kin.kinetic.solana.TransactionFormat
import org.kin.kinetic.solana.Instruction  // ADDED: Explicit import for the @Serializable Instruction
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.kin.kinetic.solana.SolanaTransaction
import org.kin.kinetic.solana.TransactionInstruction
import kotlin.experimental.and
import kotlin.experimental.or

typealias Blockhash = SolanaPublicKey
val Blockhash.blockhash get() = this.bytes

sealed class Message {

    abstract val signatureCount: UByte
    abstract val readOnlyAccounts: UByte
    abstract val readOnlyNonSigners: UByte
    abstract val accounts: List<SolanaPublicKey>
    abstract val blockhash: Blockhash
    abstract val instructions: List<Instruction>

    companion object {
        fun from(bytes: ByteArray) = TransactionFormat.decodeFromByteArray(MessageSerializer, bytes)
    }

    fun serialize(): ByteArray = TransactionFormat.encodeToByteArray(MessageSerializer, this)

    data class Builder(
        val instructions: MutableList<TransactionInstruction> = mutableListOf(),
        var blockhash: Blockhash? = null
    ) {

        fun addInstruction(instruction: TransactionInstruction) = apply {
            instructions.add(instruction)
        }

        fun setRecentBlockhash(blockhash: String) = setRecentBlockhash(Blockhash.from(blockhash))
        fun setRecentBlockhash(blockhash: Blockhash) = apply {
            this.blockhash = blockhash
        }

        fun build(): Message {
            check(blockhash != null)
            val writableSigners = mutableSetOf<SolanaPublicKey>()
            val readOnlySigners = mutableSetOf<SolanaPublicKey>()
            val writableNonSigners = mutableSetOf<SolanaPublicKey>()
            val readOnlyNonSigners = mutableSetOf<SolanaPublicKey>()
            instructions.forEach { instruction ->
                instruction.accounts.forEach { account ->
                    if (account.isSigner) {
                        if (account.isWritable) writableSigners.add(account.publicKey)
                        else readOnlySigners.add(account.publicKey)
                    } else {
                        if (account.isWritable) writableNonSigners.add(account.publicKey)
                        else readOnlyNonSigners.add(account.publicKey)
                    }
                }
                readOnlyNonSigners.add(instruction.programId)
            }

            val signers = writableSigners + readOnlySigners
            val accounts = signers + writableNonSigners + readOnlyNonSigners
            val compiledInstructions = instructions.map { instruction ->
                Instruction(
                    accounts.indexOf(instruction.programId).toUByte(),
                    instruction.accounts.map {
                        accounts.indexOf(it.publicKey).toByte()
                    }.toByteArray(),
                    instruction.data
                )
            }

            return LegacyMessage(
                signers.size.toUByte(),
                readOnlySigners.size.toUByte(),
                readOnlyNonSigners.size.toUByte(),
                accounts.toList(),
                blockhash!!,
                compiledInstructions
            )
        }
    }
}

@Serializable
data class LegacyMessage(
    override val signatureCount: UByte,
    override val readOnlyAccounts: UByte,
    override val readOnlyNonSigners: UByte,
    override val accounts: List<SolanaPublicKey>,
    override val blockhash: Blockhash,
    override val instructions: List<Instruction>
) : Message()

@Serializable
data class VersionedMessage(
    @Transient val version: Byte = 0,
    override val signatureCount: UByte,
    override val readOnlyAccounts: UByte,
    override val readOnlyNonSigners: UByte,
    override val accounts: List<SolanaPublicKey>,
    override val blockhash: Blockhash,
    override val instructions: List<Instruction>,
    val addressTableLookups: List<AddressTableLookup>
) : Message()

@Serializable
data class AddressTableLookup(
    val account: SolanaPublicKey,
    val writableIndexes: List<UByte>,
    val readOnlyIndexes: List<UByte>
)

object MessageSerializer : KSerializer<Message> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.solana.transaction.Message")

    override fun deserialize(decoder: Decoder): Message {
        val firstByte = decoder.decodeByte()
        val version = if (firstByte.toInt() and 0x80 == 0) -1 else firstByte and 0x7f
        val signatureCount = if (version >= 0) decoder.decodeByte().toUByte() else firstByte.toUByte()
        val readOnlyAccounts = decoder.decodeByte().toUByte()
        val readOnlyNonSigners = decoder.decodeByte().toUByte()
        val accounts = decoder.decodeSerializableValue(ListSerializer(SolanaPublicKeySerializer))
        val blockhash = Blockhash(decoder.decodeSerializableValue(SolanaPublicKeySerializer).bytes)
        val instructions = decoder.decodeSerializableValue(ListSerializer(serializer<Instruction>()))
        return if (version >= 0)
            VersionedMessage(
                version,
                signatureCount, readOnlyAccounts, readOnlyNonSigners,
                accounts, blockhash, instructions,
                decoder.decodeSerializableValue(ListSerializer(serializer<AddressTableLookup>()))
            )
        else
            LegacyMessage(
                signatureCount, readOnlyAccounts, readOnlyNonSigners,
                accounts, blockhash, instructions,
            )
    }

    override fun serialize(encoder: Encoder, value: Message) {
        if (value is VersionedMessage) encoder.encodeByte(0x80.toByte() or value.version)
        when (value) {
            is LegacyMessage -> encoder.encodeSerializableValue(serializer<LegacyMessage>(), value)
            is VersionedMessage -> encoder.encodeSerializableValue(serializer<VersionedMessage>(), value)
        }
    }
}

fun Message.toUnsignedTransaction(): SolanaTransaction = SolanaTransaction(this)