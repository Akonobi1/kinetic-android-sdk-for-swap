package org.kin.kinetic.solana

import com.funkatronics.hash.Sha256
import org.kin.kinetic.solana.isOnCurve
import org.kin.kinetic.solana.ProgramDerivedAddress
import org.kin.kinetic.solana.PublicKey
import org.kin.kinetic.solana.SolanaPublicKey
import kotlin.jvm.JvmStatic

interface Program {
    val programId: SolanaPublicKey

    suspend fun createDerivedAddress(seeds: List<ByteArray>) =
        createDerivedAddress(seeds, programId)

    suspend fun findDerivedAddress(seeds: List<ByteArray>) =
        findDerivedAddress(seeds, programId)

    companion object {
        @JvmStatic
        suspend fun findDerivedAddress(seeds: List<ByteArray>, programId: PublicKey) =
            ProgramDerivedAddress.find(seeds, programId)

        @JvmStatic
        suspend fun createDerivedAddress(seeds: List<ByteArray>, programId: PublicKey): Result<SolanaPublicKey> {
            val address = Sha256.hash(
                seeds.foldIndexed(ByteArray(0)) { i, a, s ->
                    require(s.size <= 32) { "Seed length must be <= 32 bytes" }; a + s
                } + programId.bytes + "ProgramDerivedAddress".encodeToByteArray()
            )

            /* if (address.isOnCurve()) {
                 return Result.failure(
                     IllegalArgumentException("Invalid seeds, address must fall off curve")
                 )
             }

             */

            return Result.success(SolanaPublicKey(address))


        }
    }
}