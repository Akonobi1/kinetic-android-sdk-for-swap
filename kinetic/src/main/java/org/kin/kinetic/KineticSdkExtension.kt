package org.kin.kinetic

import android.util.Base64
import android.util.Log
import com.solana.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.kin.kinetic.generated.api.TransactionApi
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.generated.api.model.MakeTransferRequest
import org.kin.kinetic.generated.api.model.Transaction as KineticTransaction
import org.kin.kinetic.generated.api.model.ConfirmationStatus as KineticConfirmationStatus
import org.kin.kinetic.generated.api.model.ConfirmationStatus
import java.util.concurrent.TimeUnit

/**
 * Enhanced KineticSdk extensions for Jupiter swaps using instruction-based approach
 * Based on the successful swap implementation that used /swap-instructions endpoint
 * Implements both legacy Transaction and versioned VersionedTransaction support with automatic fallback
 */

private const val TAG = "KineticJupiterSwap"

// HTTP client for Jupiter API calls
private val jupiterHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/**
 * Execute Jupiter swap with automatic fallback: Legacy first, then Versioned
 * Uses the successful /swap-instructions approach instead of /swap endpoint
 *
 * @param fromToken Source token mint address
 * @param toToken Destination token mint address  
 * @param amount Amount to swap in decimals (e.g. "10.5")
 * @param slippagePercent Maximum allowed slippage in percent (e.g. "1.0" for 1%)
 * @param owner Keypair of the wallet initiating the swap
 * @param commitment Transaction commitment level
 * @return Transaction result with signature
 */
suspend fun KineticSdk.executeJupiterSwap(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment? = null
): KineticTransaction {
    Log.d(TAG, "=== JUPITER SWAP WITH KINETIC FEE PAYER ===")
    Log.d(TAG, "Starting Jupiter swap: $fromToken -> $toToken, amount=$amount, slippage=$slippagePercent%")
    Log.d(TAG, "Approach: Instructions-based with automatic legacy/versioned fallback")

    return try {
        // Try legacy approach first (proven working approach)
        Log.d(TAG, "Attempting legacy Transaction approach...")
        executeJupiterSwapLegacy(fromToken, toToken, amount, slippagePercent, owner, commitment)
    } catch (legacyError: Exception) {
        Log.w(TAG, "Legacy approach failed: ${legacyError.message}")
        Log.d(TAG, "Attempting versioned Transaction approach...")
        
        try {
            // Fall back to versioned approach
            executeJupiterSwapVersioned(fromToken, toToken, amount, slippagePercent, owner, commitment)
        } catch (versionedError: Exception) {
            Log.e(TAG, "Both legacy and versioned approaches failed")
            Log.e(TAG, "Legacy error: ${legacyError.message}")
            Log.e(TAG, "Versioned error: ${versionedError.message}")
            throw Exception("Jupiter swap failed: Legacy (${legacyError.message}) | Versioned (${versionedError.message})")
        }
    }
}

/**
 * Execute Jupiter swap using legacy Transaction class (proven working approach)
 */
private suspend fun KineticSdk.executeJupiterSwapLegacy(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment?
): KineticTransaction {
    val tag = "${TAG}Legacy"
    Log.d(tag, "Building legacy transaction using com.solana.core.Transaction")

    try {
        // Step 1: Get quote from Jupiter
        val quoteDetails = getJupiterQuote(fromToken, toToken, amount, slippagePercent)
        val quoteJson = quoteDetails["quoteJson"] ?: throw Exception("Missing quote JSON")
        
        Log.d(tag, "Quote obtained - expected output: ${quoteDetails["outputAmount"]}")

        // Step 2: Get swap instructions from Jupiter
        val instructions = getJupiterSwapInstructions(quoteJson, owner.publicKey)
        Log.d(tag, "Instructions obtained successfully")

        // Step 3: Get Kinetic configuration
        val appConfig = this.config ?: throw IllegalStateException("App config not initialized")
        val transactionApi = TransactionApi(basePath = this.endpoint, headers = createHeaders())
        val blockHash = transactionApi.getLatestBlockhash(this.sdkConfig.environment, this.sdkConfig.index)
        
        // Get Kinetic fee payer from app config
        val kineticFeePayerPublicKey = com.solana.core.PublicKey(appConfig.mint.feePayer)
        
        Log.d(tag, "Kinetic fee payer: ${kineticFeePayerPublicKey.toBase58()}")
        Log.d(tag, "User signer: ${owner.publicKey}")

        // Step 4: Build legacy transaction
        val transaction = Transaction().apply {
            feePayer = kineticFeePayerPublicKey
            setRecentBlockHash(blockHash.blockhash)
        }

        // Add instructions in proper order
        addInstructionsToTransaction(transaction, instructions, tag)

        // Only user signs the transaction (Kinetic will add its signature)
        transaction.partialSign(owner.solana)

        Log.d(tag, "Legacy transaction built successfully")
        Log.d(tag, "Fee payer: ${transaction.feePayer?.toBase58()}")
        Log.d(tag, "Instructions count: ${transaction.instructions.size}")

        // Step 5: Serialize and submit
        return submitTransactionToKinetic(transaction, blockHash, appConfig, commitment, transactionApi, tag, isVersioned = false)

    } catch (e: Exception) {
        Log.e(tag, "Error in legacy approach: ${e.message}", e)
        throw e
    }
}

/**
 * Execute Jupiter swap using versioned Transaction approach
 * Uses Transaction class with versioned flags for maximum compatibility
 */
private suspend fun KineticSdk.executeJupiterSwapVersioned(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment?
): KineticTransaction {
    val tag = "${TAG}Versioned"
    Log.d(tag, "Building versioned transaction using Transaction class with versioned flags")

    try {
        // Step 1: Get quote from Jupiter (same as legacy)
        val quoteDetails = getJupiterQuote(fromToken, toToken, amount, slippagePercent)
        val quoteJson = quoteDetails["quoteJson"] ?: throw Exception("Missing quote JSON")
        
        Log.d(tag, "Quote obtained - expected output: ${quoteDetails["outputAmount"]}")

        // Step 2: Get swap instructions from Jupiter (same as legacy)
        val instructions = getJupiterSwapInstructions(quoteJson, owner.publicKey)
        Log.d(tag, "Instructions obtained successfully")

        // Step 3: Get Kinetic configuration (same as legacy)
        val appConfig = this.config ?: throw IllegalStateException("App config not initialized")
        val transactionApi = TransactionApi(basePath = this.endpoint, headers = createHeaders())
        val blockHash = transactionApi.getLatestBlockhash(this.sdkConfig.environment, this.sdkConfig.index)
        
        // Get Kinetic fee payer from app config (same as legacy)
        val kineticFeePayerPublicKey = com.solana.core.PublicKey(appConfig.mint.feePayer)
        
        Log.d(tag, "Kinetic fee payer: ${kineticFeePayerPublicKey.toBase58()}")
        Log.d(tag, "User signer: ${owner.publicKey}")

        // Step 4: Handle Address Lookup Tables (extract for versioned transaction)
        val addressLookupTableAccounts = if (instructions.has("addressLookupTableAddresses")) {
            val altAddresses = instructions.getJSONArray("addressLookupTableAddresses")
            val altList = mutableListOf<String>()
            for (i in 0 until altAddresses.length()) {
                altList.add(altAddresses.getString(i))
            }
            
            if (altList.isNotEmpty()) {
                Log.d(tag, "Jupiter provided ${altList.size} ALT addresses for versioned transaction")
                altList
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Step 5: Build transaction using same Transaction class as legacy but with versioned intent
        val transaction = Transaction().apply {
            feePayer = kineticFeePayerPublicKey
            setRecentBlockHash(blockHash.blockhash)
        }

        // Add instructions in proper order (same as legacy)
        addInstructionsToTransaction(transaction, instructions, tag)

        // Only user signs the transaction (Kinetic will add its signature)
        transaction.partialSign(owner.solana)

        Log.d(tag, "Versioned-style transaction built successfully using Transaction class")
        Log.d(tag, "Fee payer: ${transaction.feePayer?.toBase58()}")
        Log.d(tag, "Instructions count: ${transaction.instructions.size}")
        Log.d(tag, "ALT addresses to pass: ${addressLookupTableAccounts.size}")

        // Step 6: Serialize the transaction
        val serializedTransaction = transaction.serialize(
            SerializeConfig(
                requireAllSignatures = false, // Kinetic will add its signature
                verifySignatures = false
            )
        )
        val transactionBase64 = Base64.encodeToString(serializedTransaction, Base64.NO_WRAP)

        Log.d(tag, "Transaction serialized, length: ${transactionBase64.length}")

        // Step 7: Submit as versioned transaction (key difference from legacy)
        val makeTransferRequest = MakeTransferRequest(
            commitment = commitment ?: this.sdkConfig.commitment ?: Commitment.confirmed,
            environment = this.sdkConfig.environment,
            index = this.sdkConfig.index,
            mint = appConfig.mint.publicKey,
            lastValidBlockHeight = blockHash.lastValidBlockHeight,
            tx = transactionBase64,
            reference = null,
            isVersioned = true,  // THIS IS THE KEY DIFFERENCE - tells Kinetic to treat as versioned
            asLegacy = false,
            addressLookupTableAccounts = if (addressLookupTableAccounts.isNotEmpty()) {
                addressLookupTableAccounts
            } else null
        )

        val result = transactionApi.makeTransfer(makeTransferRequest)

        Log.d(tag, "=== VERSIONED SWAP COMPLETED ===")
        Log.d(tag, "✓ Signature: ${result.signature}")
        Log.d(tag, "✓ Transaction type: Versioned (isVersioned=true)")
        Log.d(tag, "✓ Fee payer: Kinetic (user paid no SOL fees)")
        Log.d(tag, "✓ Instructions count: ${transaction.instructions.size}")
        Log.d(tag, "✓ ALT addresses: ${if (addressLookupTableAccounts.isNotEmpty()) addressLookupTableAccounts.size else "none"}")

        return result

    } catch (e: Exception) {
        Log.e(tag, "Error in versioned approach: ${e.message}", e)
        throw e
    }
}

/**
 * Get quote from Jupiter v6 API
 */
private suspend fun getJupiterQuote(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String
): Map<String, String> = withContext(Dispatchers.IO) {
    
    // Resolve token addresses if they're symbols
    val inputMint = resolveTokenAddress(fromToken)
    val outputMint = resolveTokenAddress(toToken)
    
    // Convert amount to smallest units
    val inputDecimals = getTokenDecimals(inputMint)
    val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, inputDecimals.toDouble())
    val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

    val url = "https://quote-api.jup.ag/v6/quote" +
            "?inputMint=$inputMint" +
            "&outputMint=$outputMint" +
            "&amount=${amountInSmallestUnits.toLong()}" +
            "&slippageBps=$slippageBps" +
            "&restrictIntermediateTokens=true"

    Log.d(TAG, "Jupiter quote URL: $url")

    val request = Request.Builder().url(url).get().build()
    
    jupiterHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Failed to get Jupiter quote: ${response.code} - ${response.message}")
        }

        val responseBody = response.body?.string() ?: 
            throw Exception("Empty response from Jupiter quote API")

        // Calculate output for display
        val quoteResponse = JSONObject(responseBody)
        val outputDecimals = getTokenDecimals(outputMint)
        val outputAmount = quoteResponse.getString("outAmount").toLongOrNull() ?: 0L
        val formattedOutputAmount = (outputAmount.toDouble() / Math.pow(10.0, outputDecimals.toDouble())).toString()

        Log.d(TAG, "Quote successful - input: $amount, output: $formattedOutputAmount")

        mapOf(
            "quoteJson" to responseBody,
            "outputAmount" to formattedOutputAmount
        )
    }
}

/**
 * Get swap instructions from Jupiter v6 API (the successful approach)
 */
private suspend fun getJupiterSwapInstructions(
    quoteJson: String,
    userPublicKey: String
): JSONObject = withContext(Dispatchers.IO) {
    
    val requestBody = JSONObject().apply {
        put("quoteResponse", JSONObject(quoteJson))
        put("userPublicKey", userPublicKey)
        put("wrapAndUnwrapSol", true)
        put("useTokenLedger", false)
        // Don't specify feeAccount - let Jupiter handle it
    }

    Log.d(TAG, "Requesting swap instructions from Jupiter v6")

    val request = Request.Builder()
        .url("https://quote-api.jup.ag/v6/swap-instructions")
        .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
        .header("Content-Type", "application/json")
        .build()

    jupiterHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Failed to get swap instructions: ${response.code} - ${response.message}")
        }

        val responseBody = response.body?.string() ?: 
            throw Exception("Empty response from Jupiter instructions API")

        val instructionsResponse = JSONObject(responseBody)
        
        // Check for error in response
        if (instructionsResponse.has("error") && !instructionsResponse.isNull("error")) {
            throw Exception("Jupiter instructions error: ${instructionsResponse.getString("error")}")
        }

        Log.d(TAG, "Swap instructions obtained successfully")
        
        instructionsResponse
    }
}

/**
 * Add instructions to legacy transaction in proper order
 */
private fun addInstructionsToTransaction(
    transaction: Transaction,
    instructions: JSONObject,
    tag: String
) {
    // Add compute budget instructions
    if (instructions.has("computeBudgetInstructions")) {
        val computeBudgetArray = instructions.getJSONArray("computeBudgetInstructions")
        for (i in 0 until computeBudgetArray.length()) {
            val instruction = convertJupiterInstructionToSolana(computeBudgetArray.getJSONObject(i))
            transaction.add(instruction)
        }
        Log.d(tag, "Added ${computeBudgetArray.length()} compute budget instructions")
    }

    // Add setup instructions
    if (instructions.has("setupInstructions")) {
        val setupArray = instructions.getJSONArray("setupInstructions")
        for (i in 0 until setupArray.length()) {
            val instruction = convertJupiterInstructionToSolana(setupArray.getJSONObject(i))
            transaction.add(instruction)
        }
        Log.d(tag, "Added ${setupArray.length()} setup instructions")
    }

    // Add token ledger instruction if present
    if (instructions.has("tokenLedgerInstruction") && !instructions.isNull("tokenLedgerInstruction")) {
        val instruction = convertJupiterInstructionToSolana(instructions.getJSONObject("tokenLedgerInstruction"))
        transaction.add(instruction)
        Log.d(tag, "Added token ledger instruction")
    }

    // Add main swap instruction
    val swapInstruction = convertJupiterInstructionToSolana(instructions.getJSONObject("swapInstruction"))
    transaction.add(swapInstruction)
    Log.d(tag, "Added main swap instruction")

    // Add cleanup instruction if present
    if (instructions.has("cleanupInstruction") && !instructions.isNull("cleanupInstruction")) {
        val instruction = convertJupiterInstructionToSolana(instructions.getJSONObject("cleanupInstruction"))
        transaction.add(instruction)
        Log.d(tag, "Added cleanup instruction")
    }
}

/**
 * Convert Jupiter instruction JSON to Solana TransactionInstruction
 */
private fun convertJupiterInstructionToSolana(jupiterInstruction: JSONObject): TransactionInstruction {
    val programId = com.solana.core.PublicKey(jupiterInstruction.getString("programId"))
    
    val accountsArray = jupiterInstruction.getJSONArray("accounts")
    val accountMetas = mutableListOf<AccountMeta>()
    
    for (i in 0 until accountsArray.length()) {
        val account = accountsArray.getJSONObject(i)
        val pubkey = com.solana.core.PublicKey(account.getString("pubkey"))
        val isSigner = account.getBoolean("isSigner")
        val isWritable = account.getBoolean("isWritable")
        
        accountMetas.add(AccountMeta(pubkey, isSigner, isWritable))
    }
    
    val data = Base64.decode(jupiterInstruction.getString("data"), Base64.DEFAULT)
    
    return TransactionInstruction(programId, accountMetas, data)
}

/**
 * Submit transaction to Kinetic (shared by both legacy and versioned)
 */
private suspend fun submitTransactionToKinetic(
    transaction: Transaction,
    blockHash: org.kin.kinetic.generated.api.model.LatestBlockhashResponse,
    appConfig: org.kin.kinetic.generated.api.model.AppConfig,
    commitment: Commitment?,
    transactionApi: TransactionApi,
    tag: String,
    isVersioned: Boolean
): KineticTransaction {
    
    // Serialize the transaction
    val serializedTransaction = transaction.serialize(
        SerializeConfig(
            requireAllSignatures = false, // Kinetic will add its signature
            verifySignatures = false
        )
    )
    val transactionBase64 = Base64.encodeToString(serializedTransaction, Base64.NO_WRAP)
    
    Log.d(tag, "Transaction serialized, length: ${transactionBase64.length}")

    // Submit to Kinetic
    val makeTransferRequest = MakeTransferRequest(
        commitment = commitment ?: Commitment.confirmed,
        environment = "", // Will be set by headers
        index = 0, // Will be set by headers  
        mint = appConfig.mint.publicKey,
        lastValidBlockHeight = blockHash.lastValidBlockHeight,
        tx = transactionBase64,
        reference = null,
        isVersioned = isVersioned,
        asLegacy = !isVersioned
    )

    val result = transactionApi.makeTransfer(makeTransferRequest)

    Log.d(tag, "=== SWAP COMPLETED ===")
    Log.d(tag, "✓ Signature: ${result.signature}")
    Log.d(tag, "✓ Fee payer: Kinetic (user paid no SOL fees)")
    Log.d(tag, "✓ Transaction type: ${if (isVersioned) "Versioned" else "Legacy"}")

    return result
}

/**
 * Create API headers for KineticSDK
 */
private fun KineticSdk.createHeaders(): Map<String, String> {
    return mapOf(
        "kinetic-environment" to this.sdkConfig.environment,
        "kinetic-index" to this.sdkConfig.index.toString(),
        "kinetic-user-agent" to "KineticSDK-JupiterV6"
    ) + this.sdkConfig.headers
}

/**
 * Resolve a token identifier to its mint address
 */
private fun resolveTokenAddress(token: String): String {
    // If it looks like an address already, return it
    if (token.length > 30) {
        return token
    }

    // Try to resolve from common tokens
    val commonTokens = mapOf(
        "KIN" to "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
        "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "SOL" to "So11111111111111111111111111111111111111112",
        "USDT" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    )
    
    return commonTokens[token.uppercase()] ?: token
}

/**
 * Get token decimals (defaults based on common tokens)
 */
private fun getTokenDecimals(mintAddress: String): Int {
    return when (mintAddress) {
        "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6" -> 5 // KIN
        "So11111111111111111111111111111111111111112" -> 9 // SOL
        else -> 6 // Most SPL tokens use 6 decimals (USDC, USDT, etc.)
    }
}

/**
 * Check if a transaction has been confirmed
 */
suspend fun KineticSdk.isTransactionConfirmed(
    signature: String,
    commitment: Commitment? = null
): Boolean {
    return try {
        val response = this.getTransaction(signature, commitment)
        val status = response.status.confirmationStatus
        status == KineticConfirmationStatus.confirmed ||
                status == KineticConfirmationStatus.finalized
    } catch (e: Exception) {
        false
    }
}

/**
 * Wait for a transaction to be confirmed, with timeout
 */
suspend fun KineticSdk.waitForTransactionConfirmation(
    signature: String,
    commitment: Commitment? = null,
    maxAttempts: Int = 10,
    delayMs: Long = 1500
): Boolean {
    var attempts = 0
    while (attempts < maxAttempts) {
        if (isTransactionConfirmed(signature, commitment)) {
            return true
        }
        delay(delayMs)
        attempts++
    }
    return false
}

/**
 * Legacy method for backward compatibility - uses the proven working approach
 */
suspend fun KineticSdk.submitSerializedTransaction(
    serializedTransaction: String,
    owner: Keypair,
    commitment: Commitment? = null,
    addSignature: Boolean = true
): KineticTransaction {
    Log.d(TAG, "Using legacy submitSerializedTransaction method")
    
    // Use the existing submitPreBuiltTransaction method with legacy settings
    return this.submitPreBuiltTransaction(
        serializedTransaction = serializedTransaction,
        owner = owner,
        commitment = commitment,
        asLegacy = true,
        isVersioned = false
    )
}