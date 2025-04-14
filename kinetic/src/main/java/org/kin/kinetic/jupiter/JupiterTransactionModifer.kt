package org.kin.kinetic.jupiter

import android.util.Base64
import android.util.Log
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import com.solana.core.Transaction
import org.json.JSONArray
import org.json.JSONObject
import org.kin.kinetic.Keypair
import org.kin.kinetic.helpers.generateKinMemoInstruction
import java.util.concurrent.atomic.AtomicInteger

/**
 * Utility class for modifying Jupiter transactions to work with Kinetic's fee model
 */
class JupiterTransactionModifier {
    companion object {
        private const val TAG = "JupiterTxModifier"

        /**
         * Extract transaction details from Jupiter's serialized transaction
         *
         * @param serializedTransaction Base64 encoded transaction from Jupiter
         * @return Parsed transaction details
         */
        fun parseJupiterTransaction(serializedTransaction: String): JupiterTransactionDetails {
            val transactionBytes = Base64.decode(serializedTransaction, Base64.DEFAULT)
            val transaction = Transaction.from(transactionBytes)

            Log.d(TAG, "Parsing Jupiter transaction with ${transaction.instructions.size} instructions")

            return JupiterTransactionDetails(
                transaction = transaction,
                instructions = transaction.instructions,
                signers = transaction.signatures.map { it.publicKey }.toSet(),
                recentBlockhash = transaction.recentBlockhash
            )
        }

        /**
         * Modify a transaction to use Kinetic's fee payer model
         *
         * @param jupiterDetails The original Jupiter transaction details
         * @param owner The wallet owner keypair
         * @param feePayer The fee payer public key from Kinetic
         * @param appIndex Kinetic app index
         * @param blockhash Fresh blockhash for the transaction
         * @param includeMemo Whether to include a Kin memo (for Kin swaps)
         * @return A new transaction with Kinetic's fee model
         */
        fun createKineticTransaction(
            jupiterDetails: JupiterTransactionDetails,
            owner: Keypair,
            feePayer: PublicKey,
            appIndex: Int,
            blockhash: String,
            includeMemo: Boolean = false
        ): Transaction {
            // Create a new transaction with Kinetic's fee payer
            val kineticTransaction = Transaction().apply {
                this.feePayer = feePayer
                this.recentBlockhash = blockhash

                // Add owner's public key to the signers
                this.signatures.add(com.solana.core.SignaturePubkeyPair(null, owner.solanaPublicKey))

                // Add Kin memo if needed (for Kin transfers)
                if (includeMemo) {
                    this.add(generateKinMemoInstruction(appIndex, org.kin.kinetic.KinBinaryMemo.TransactionType.P2P))
                }

                // Add all instructions from Jupiter's transaction
                jupiterDetails.instructions.forEach {
                    this.add(it)
                }
            }

            // Sign the transaction with the owner's keypair
            kineticTransaction.partialSign(owner.solana)

            return kineticTransaction
        }

        /**
         * Analyze a Jupiter swap transaction and extract key information
         * (like token amounts, accounts involved)
         *
         * @param transaction The transaction to analyze
         * @return SwapAnalysis with extracted information
         */
        fun analyzeSwapTransaction(transaction: Transaction): SwapAnalysis {
            val tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
            val systemProgram = "11111111111111111111111111111111"

            val userAccounts = mutableSetOf<String>()
            val tokenAccounts = mutableSetOf<String>()
            val programIds = mutableSetOf<String>()
            val transferInstructions = AtomicInteger(0)

            transaction.instructions.forEach { instruction ->
                val programId = instruction.programId.toBase58()
                programIds.add(programId)

                // Count token transfers
                if (programId == tokenProgram) {
                    transferInstructions.incrementAndGet()
                }

                // Collect account information
                instruction.keys.forEach { meta ->
                    val pubkey = meta.publicKey.toBase58()
                    if (meta.isSigner) {
                        userAccounts.add(pubkey)
                    }
                    if (programId == tokenProgram) {
                        tokenAccounts.add(pubkey)
                    }
                }
            }

            return SwapAnalysis(
                tokenTransferCount = transferInstructions.get(),
                programIds = programIds.toList(),
                userAccounts = userAccounts.toList(),
                tokenAccounts = tokenAccounts.toList(),
                isComplexSwap = programIds.size > 3 || transferInstructions.get() > 2
            )
        }
    }

    /**
     * Details of a Jupiter transaction
     */
    data class JupiterTransactionDetails(
        val transaction: Transaction,
        val instructions: List<com.solana.core.TransactionInstruction>,
        val signers: Set<PublicKey>,
        val recentBlockhash: String
    )

    /**
     * Analysis of a swap transaction
     */
    data class SwapAnalysis(
        val tokenTransferCount: Int,
        val programIds: List<String>,
        val userAccounts: List<String>,
        val tokenAccounts: List<String>,
        val isComplexSwap: Boolean
    )
}