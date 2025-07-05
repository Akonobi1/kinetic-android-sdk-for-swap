package org.kin.kinetic.jupiter

import android.util.Log
import android.util.Base64
import com.solana.core.*
import org.kin.kinetic.Keypair
import org.kin.kinetic.KinBinaryMemo

/**
 * FIXED: Transaction modifier that works with standard Solana SDK (com.solana.core.*)
 * Now properly integrates with Kinetic SDK's Transaction class
 */
class JupiterTransactionModifier {
    companion object {
        private const val TAG = "JupiterTxModifier"

        /**
         * Build standard Transaction with Jupiter instructions
         * Uses com.solana.core.Transaction to match Kinetic SDK
         */
        fun buildJupiterTransaction(
            jupiterInstructions: JupiterInstructionsResponse,
            owner: Keypair,
            feePayer: PublicKey,
            appIndex: Int,
            blockhash: String,
            includeMemo: Boolean = false
        ): Transaction {
            Log.d(TAG, "Building standard Transaction with Jupiter instructions")
            Log.d(TAG, "Fee Payer: ${feePayer.toBase58().take(8)}...")
            Log.d(TAG, "Owner: ${owner.publicKey.take(8)}...")
            Log.d(TAG, "Jupiter instructions: ${getInstructionCount(jupiterInstructions)}")

            val instructions = mutableListOf<TransactionInstruction>()
            val ownerPublicKey = PublicKey(owner.publicKey)

            // Add Kin memo if requested
            if (includeMemo) {
                instructions.add(
                    createKinMemoInstruction(
                        appIndex,
                        KinBinaryMemo.TransactionType.P2P
                    )
                )
                Log.d(TAG, "Added Kin memo instruction")
            }

            // Add all Jupiter instructions in order
            jupiterInstructions.computeBudgetInstructions.forEach { instruction ->
                instructions.add(convertJupiterInstruction(instruction))
            }
            Log.d(TAG, "Added ${jupiterInstructions.computeBudgetInstructions.size} compute budget instructions")

            jupiterInstructions.setupInstructions.forEach { instruction ->
                instructions.add(convertJupiterInstruction(instruction))
            }
            Log.d(TAG, "Added ${jupiterInstructions.setupInstructions.size} setup instructions")

            jupiterInstructions.tokenLedgerInstruction?.let { instruction ->
                instructions.add(convertJupiterInstruction(instruction))
                Log.d(TAG, "Added token ledger instruction")
            }

            // Main swap instruction
            instructions.add(convertJupiterInstruction(jupiterInstructions.swapInstruction))
            Log.d(TAG, "Added main swap instruction")

            jupiterInstructions.cleanupInstruction?.let { instruction ->
                instructions.add(convertJupiterInstruction(instruction))
                Log.d(TAG, "Added cleanup instruction")
            }

            Log.d(TAG, "Total instructions: ${instructions.size}")

            // Create standard Transaction using Kinetic SDK pattern
            val transaction = Transaction()

            // Set signatures first (following Kinetic SDK pattern)
            transaction.signatures = mutableListOf<SignaturePubkeyPair>(
                SignaturePubkeyPair(null, ownerPublicKey)
            )

            // Set fee payer
            transaction.feePayer = feePayer

            // Add all instructions
            transaction.add(*instructions.toTypedArray())

            // Set recent blockhash
            transaction.setRecentBlockHash(blockhash)

            Log.d(TAG, "Transaction built successfully")
            Log.d(TAG, "  - Instructions: ${instructions.size}")
            Log.d(TAG, "  - Signers: ${transaction.signatures.size}")
            Log.d(TAG, "  - Fee Payer: ${feePayer.toBase58().take(8)}...")

            return transaction
        }

        /**
         * Create Kin memo instruction locally (since SDK version is internal)
         */
        private fun createKinMemoInstruction(appIndex: Int, type: KinBinaryMemo.TransactionType): TransactionInstruction {
            // Build the Kin memo
            val kinMemo = KinBinaryMemo.Builder(appIndex)
                .setTransferType(type)
                .build()

            // Encode the memo data
            val encodedMemo = Base64.encodeToString(kinMemo.encode(), Base64.DEFAULT).toByteArray()

            // Memo v1 program ID
            val MEMO_V1_PROGRAM_ID = PublicKey("Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo")

            return TransactionInstruction(
                MEMO_V1_PROGRAM_ID,
                emptyList<AccountMeta>(), // Memo instructions don't require any accounts
                encodedMemo
            )
        }

        /**
         * Convert Jupiter instruction to standard TransactionInstruction
         */
        private fun convertJupiterInstruction(jupiterInstruction: JupiterInstruction): TransactionInstruction {
            val programId = PublicKey(jupiterInstruction.programId)
            val accounts = jupiterInstruction.accounts.map { accountMeta ->
                AccountMeta(
                    PublicKey(accountMeta.pubkey),
                    accountMeta.isSigner,
                    accountMeta.isWritable
                )
            }

            val data = try {
                Base64.decode(jupiterInstruction.data, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode instruction data: ${e.message}")
                jupiterInstruction.data.toByteArray()
            }

            return TransactionInstruction(
                programId,
                accounts,
                data
            )
        }

        /**
         * Sign the transaction with owner's keypair
         */
        fun signTransaction(transaction: Transaction, owner: Keypair): Transaction {
            try {
                // Partial sign with owner (following Kinetic SDK pattern)
                transaction.partialSign(owner.solana)
                Log.d(TAG, "Transaction signed by owner: ${owner.publicKey.take(8)}...")
                return transaction
            } catch (e: Exception) {
                Log.e(TAG, "Error signing transaction: ${e.message}", e)
                throw e
            }
        }

        /**
         * Create complete signed transaction from Jupiter instructions
         */
        fun createSignedJupiterTransaction(
            jupiterInstructions: JupiterInstructionsResponse,
            owner: Keypair,
            feePayer: PublicKey,
            appIndex: Int,
            blockhash: String,
            includeMemo: Boolean = false
        ): Transaction {
            val transaction = buildJupiterTransaction(
                jupiterInstructions,
                owner,
                feePayer,
                appIndex,
                blockhash,
                includeMemo
            )

            return signTransaction(transaction, owner)
        }

        /**
         * Count total Jupiter instructions
         */
        private fun getInstructionCount(jupiterInstructions: JupiterInstructionsResponse): Int {
            return jupiterInstructions.computeBudgetInstructions.size +
                    jupiterInstructions.setupInstructions.size +
                    (if (jupiterInstructions.tokenLedgerInstruction != null) 1 else 0) +
                    1 + // swap instruction
                    (if (jupiterInstructions.cleanupInstruction != null) 1 else 0)
        }

        /**
         * Analyze transaction for debugging
         */
        fun analyzeTransaction(transaction: Transaction): Map<String, Any> {
            val analysis = mutableMapOf<String, Any>()

            analysis["instructionCount"] = transaction.instructions.size
            analysis["signerCount"] = transaction.signatures.size
            analysis["feePayer"] = transaction.feePayer?.toBase58()?.take(8) + "..."
            analysis["recentBlockhash"] = transaction.recentBlockhash ?: "not set"

            val programIds = transaction.instructions.map {
                it.programId.toBase58().take(8) + "..."
            }.distinct()
            analysis["uniquePrograms"] = programIds

            Log.d(TAG, "Transaction Analysis: $analysis")
            return analysis
        }
    }
}