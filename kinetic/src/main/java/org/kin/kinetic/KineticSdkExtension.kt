package org.kin.kinetic

import android.util.Base64
import android.util.Log
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import com.solana.core.Transaction as SolanaTransaction
import com.solana.core.VersionedTransaction
import com.solana.core.VersionedMessage
import com.solana.core.MessageV0
import com.solana.core.TransactionInstruction
import com.solana.core.AccountMeta
import com.solana.core.AddressLookupTableAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.generated.api.model.ConfirmationStatus
import org.kin.kinetic.generated.api.model.Transaction as KineticTransaction
import java.util.concurrent.TimeUnit

/**
 * Extension functions for KineticSdk to support Jupiter swaps and other advanced functionality
 * UPDATED: Now uses proper VersionedTransaction and VersionedMessage classes for ALT support
 */

/**
 * Submit a pre-built serialized transaction to the Solana network as LEGACY format
 * This is how we set asLegacy = true for Jupiter transactions
 *
 * @param serializedTransaction Base64 encoded transaction
 * @param owner Keypair to sign the transaction (if not already signed)
 * @param commitment Transaction commitment level
 * @param addSignature Whether to add the owner's signature to the transaction
 * @return Transaction result from Kinetic API
 */
suspend fun KineticSdk.submitSerializedTransaction(
    serializedTransaction: String,
    owner: Keypair,
    commitment: Commitment? = null,
    addSignature: Boolean = true
): KineticTransaction {
    // Decode the serialized transaction
    val transactionBytes = Base64.decode(serializedTransaction, Base64.DEFAULT)

    // If we need to add a signature
    val transaction = if (addSignature) {
        try {
            // Deserialize, sign, and re-serialize
            val tx = SolanaTransaction.from(transactionBytes)

            // Check if the transaction already has the owner's signature
            val ownerPubKey = owner.solanaPublicKey
            val alreadySigned = tx.signatures.any { it.publicKey.toBase58() == ownerPubKey.toBase58() }

            if (!alreadySigned) {
                // Add signature if not already present
                tx.partialSign(owner.solana)
            }

            // Re-serialize the transaction
            val reSerializedTx = tx.serialize(SerializeConfig(requireAllSignatures = false, verifySignatures = false))
            Base64.encodeToString(reSerializedTx, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w("KineticExt", "Error processing transaction with signature: ${e.message}")
            serializedTransaction
        }
    } else {
        serializedTransaction
    }

    // ✅ FIXED: Use the public API method with asLegacy = true
    return this.submitPreBuiltTransaction(
        serializedTransaction = transaction,
        owner = owner,
        commitment = commitment,
        asLegacy = true,  // SET asLegacy = true for legacy transactions
        isVersioned = false
    )
}

/**
 * Submit a versioned transaction directly to Kinetic
 * This sets asLegacy = false and isVersioned = true
 *
 * @param serializedTransaction Base64 encoded transaction (v0 format)
 * @param owner Keypair of the wallet
 * @param commitment Transaction commitment level
 * @param addressLookupTableAccounts Optional lookup table addresses
 * @return Transaction result with signature
 */
suspend fun KineticSdk.submitVersionedTransaction(
    serializedTransaction: String,
    owner: Keypair,
    commitment: Commitment? = null,
    addressLookupTableAccounts: List<String>? = null
): KineticTransaction {
    Log.d("KineticVersioned", "Submitting versioned transaction")

    // ✅ FIXED: Use the public API method with isVersioned = true
    return this.submitPreBuiltTransaction(
        serializedTransaction = serializedTransaction,
        owner = owner,
        commitment = commitment,
        asLegacy = false,  // SET asLegacy = false for versioned transactions
        isVersioned = true,  // SET isVersioned = true for versioned transactions
        addressLookupTableAccounts = addressLookupTableAccounts
    )
}

/**
 * UPDATED: Enhanced executeJupiterSwap with proper VersionedTransaction support
 * Now uses real VersionedMessage and VersionedTransaction classes for ALT compression
 *
 * @param fromToken Source token mint address
 * @param toToken Destination token mint address  
 * @param amount Amount to swap in decimals (e.g. "10.5")
 * @param slippagePercent Maximum allowed slippage in percent (e.g. "1.0" for 1%)
 * @param owner Keypair of the wallet initiating the swap
 * @param commitment Transaction commitment level
 * @param useLegacyTransaction Whether to use legacy transactions (default: false for better compression)
 * @param simplifyRoutes Whether to simplify routes for better success rate
 * @param maxRouteHops Maximum number of route hops
 * @return Transaction result with signature
 */
suspend fun KineticSdk.executeJupiterSwap(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment? = null,
    useLegacyTransaction: Boolean = false,  // Default to versioned for better compression
    simplifyRoutes: Boolean = true,
    maxRouteHops: Int = 2
): KineticTransaction {
    val tag = "KineticJupiterV2"
    Log.d(tag, "=== JUPITER SWAP WITH PROPER VERSIONED TRANSACTION SUPPORT ===")
    Log.d(tag, "Starting Jupiter swap: $fromToken -> $toToken, amount=$amount, slippage=$slippagePercent%")
    Log.d(tag, "Using ${if (useLegacyTransaction) "LEGACY" else "VERSIONED with ALT compression"} transactions")

    return try {
        if (useLegacyTransaction) {
            // Use legacy approach for compatibility
            executeJupiterSwapLegacy(fromToken, toToken, amount, slippagePercent, owner, commitment, simplifyRoutes, maxRouteHops)
        } else {
            // Use proper versioned transaction approach with ALT support
            executeJupiterSwapVersioned(fromToken, toToken, amount, slippagePercent, owner, commitment, simplifyRoutes, maxRouteHops)
        }
    } catch (e: Exception) {
        Log.e(tag, "Jupiter swap failed: ${e.message}", e)
        throw e
    }
}

/**
 * Execute Jupiter swap using proper VersionedTransaction with address lookup tables
 * This approach can handle much larger transactions by compressing addresses via ALTs
 */
private suspend fun KineticSdk.executeJupiterSwapVersioned(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment?,
    simplifyRoutes: Boolean,
    maxRouteHops: Int
): KineticTransaction = withContext(Dispatchers.IO) {
    val tag = "JupiterVersionedV2"
    Log.d(tag, "=== BUILDING PROPER VERSIONED TRANSACTION WITH ALT SUPPORT ===")

    // Step 1: Get Jupiter quote and instructions using correct API
    val jupiterData = getJupiterSwapInstructions(
        fromToken, toToken, amount, slippagePercent, owner, simplifyRoutes, maxRouteHops
    )

    Log.d(tag, "Got Jupiter instructions response")
    Log.d(tag, "- Compute budget instructions: ${jupiterData.computeBudgetInstructions.size}")
    Log.d(tag, "- Setup instructions: ${jupiterData.setupInstructions.size}")
    Log.d(tag, "- Swap instruction: ✓")
    Log.d(tag, "- Cleanup instruction: ${if (jupiterData.cleanupInstruction != null) "✓" else "None"}")
    Log.d(tag, "- ALT addresses: ${jupiterData.addressLookupTableAddresses.size}")

    // Step 2: Build all transaction instructions in order
    val allInstructions = mutableListOf<TransactionInstruction>()
    
    // Add compute budget instructions
    jupiterData.computeBudgetInstructions.forEach { instruction ->
        allInstructions.add(convertJupiterInstructionToTransactionInstruction(instruction))
    }
    
    // Add setup instructions
    jupiterData.setupInstructions.forEach { instruction ->
        allInstructions.add(convertJupiterInstructionToTransactionInstruction(instruction))
    }
    
    // Add main swap instruction
    allInstructions.add(convertJupiterInstructionToTransactionInstruction(jupiterData.swapInstruction))
    
    // Add cleanup instruction if present
    jupiterData.cleanupInstruction?.let { instruction ->
        allInstructions.add(convertJupiterInstructionToTransactionInstruction(instruction))
    }

    Log.d(tag, "Built ${allInstructions.size} total instructions")

    // Step 3: Get recent blockhash
    val blockhashResponse = this@executeJupiterSwapVersioned.internal.getBlockhash()
    val recentBlockhash = blockhashResponse.blockhash
    
    // Step 4: Build VersionedTransaction
    val feePayer = PublicKey(this@executeJupiterSwapVersioned.getFeePayer(owner))
    val ownerPublicKey = PublicKey(owner.publicKey)
    
    Log.d(tag, "Fee payer: ${feePayer.toBase58().take(8)}...")
    Log.d(tag, "Owner: ${ownerPublicKey.toBase58().take(8)}...")
    Log.d(tag, "Recent blockhash: ${recentBlockhash.take(8)}...")

    // Step 5: Create VersionedTransaction using MessageV0
    val messageV0 = MessageV0.builder()
        .setPayerKey(feePayer)
        .setRecentBlockhash(recentBlockhash)
        .addInstructions(allInstructions)
        // Add ALT addresses for compression
        .setAddressLookupTableAccounts(
            jupiterData.addressLookupTableAddresses.map { address ->
                AddressLookupTableAccount(
                    key = PublicKey(address),
                    // ALT addresses will be resolved during transaction processing
                    addresses = emptyList()
                )
            }
        )
        .build()

    val versionedMessage = VersionedMessage.of(messageV0)
    val versionedTransaction = VersionedTransaction(versionedMessage)
    
    Log.d(tag, "VersionedTransaction created with ${jupiterData.addressLookupTableAddresses.size} ALTs")

    // Step 6: Sign the transaction
    versionedTransaction.sign(listOf(owner.solana))
    Log.d(tag, "Transaction signed")

    // Step 7: Serialize and submit
    val serializedTransaction = versionedTransaction.serialize()
    val base64Transaction = Base64.encodeToString(serializedTransaction, Base64.NO_WRAP)
    
    Log.d(tag, "Transaction serialized (${base64Transaction.length} chars)")
    Log.d(tag, "Submitting versioned transaction to Kinetic...")

    // Submit via Kinetic with proper versioned flags
    this@executeJupiterSwapVersioned.submitPreBuiltTransaction(
        serializedTransaction = base64Transaction,
        owner = owner,
        commitment = commitment,
        asLegacy = false,
        isVersioned = true,
        addressLookupTableAccounts = jupiterData.addressLookupTableAddresses
    )
}

/**
 * Fallback legacy Jupiter swap implementation
 * Uses traditional Transaction class - kept for compatibility
 */
private suspend fun KineticSdk.executeJupiterSwapLegacy(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    commitment: Commitment?,
    simplifyRoutes: Boolean,
    maxRouteHops: Int
): KineticTransaction = withContext(Dispatchers.IO) {
    val tag = "JupiterLegacy"
    Log.d(tag, "Using legacy Transaction approach (fallback)")

    // Get the complete transaction from Jupiter /swap endpoint
    val swapTransaction = this@executeJupiterSwapLegacy.getJupiterSwapTransaction(
        fromToken = fromToken,
        toToken = toToken,
        amount = amount,
        slippagePercent = slippagePercent,
        owner = owner,
        useLegacyTransaction = true,
        simplifyRoutes = simplifyRoutes,
        maxRouteHops = maxRouteHops
    )

    // Submit as legacy transaction
    this@executeJupiterSwapLegacy.submitSerializedTransaction(
        swapTransaction, owner, commitment, addSignature = true
    )
}

/**
 * Data classes for Jupiter swap-instructions API response
 */
private data class JupiterSwapInstructionsResponse(
    val computeBudgetInstructions: List<JupiterInstruction>,
    val setupInstructions: List<JupiterInstruction>,
    val swapInstruction: JupiterInstruction,
    val cleanupInstruction: JupiterInstruction?,
    val addressLookupTableAddresses: List<String>
)

/**
 * Jupiter instruction model matching the API response
 */
private data class JupiterInstruction(
    val programId: String,
    val accounts: List<JupiterAccountMeta>,
    val data: String
)

/**
 * Jupiter account metadata matching the API response
 */
private data class JupiterAccountMeta(
    val pubkey: String,
    val isSigner: Boolean,
    val isWritable: Boolean
)

/**
 * Get Jupiter swap instructions using the correct /swap-instructions endpoint
 */
private suspend fun KineticSdk.getJupiterSwapInstructions(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    simplifyRoutes: Boolean,
    maxRouteHops: Int
): JupiterSwapInstructionsResponse = withContext(Dispatchers.IO) {
    val tag = "JupiterSwapInstructions"
    
    // Create HTTP client
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Step 1: Get quote first
    val quote = getJupiterQuote(fromToken, toToken, amount, slippagePercent, simplifyRoutes, maxRouteHops)
    
    // Step 2: Get fee payer
    val feePayer = this@getJupiterSwapInstructions.getFeePayer(owner)
    
    // Step 3: Build request for swap instructions
    val swapInstructionsRequest = JSONObject().apply {
        put("userPublicKey", owner.publicKey)
        put("payer", feePayer)
        put("wrapAndUnwrapSol", true)
        put("useSharedAccounts", true)  // Enable for versioned transactions
        put("dynamicComputeUnitLimit", true)
        put("skipUserAccountsRpcCalls", false)
        put("asLegacyTransaction", false)  // We want versioned transaction
        put("quoteResponse", JSONObject(quote))
        
        // Add prioritization fee
        val prioritizationFee = JSONObject().apply {
            val priorityLevel = JSONObject().apply {
                put("maxLamports", 10000)
                put("priorityLevel", "medium")
            }
            put("priorityLevelWithMaxLamports", priorityLevel)
        }
        put("prioritizationFeeLamports", prioritizationFee)
    }

    Log.d(tag, "Requesting swap instructions from Jupiter...")
    Log.d(tag, "Request URL: https://lite-api.jup.ag/swap/v1/swap-instructions")

    val requestBody = swapInstructionsRequest.toString().toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder()
        .url("https://lite-api.jup.ag/swap/v1/swap-instructions")
        .post(requestBody)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .build()

    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Failed to get Jupiter swap instructions: ${response.code} - ${response.message}")
        }

        val responseBody = response.body?.string() ?:
            throw Exception("Empty response from Jupiter swap instructions API")

        Log.d(tag, "Swap instructions response received")
        Log.d(tag, "Response: $responseBody")

        // Parse the response
        val responseJson = JSONObject(responseBody)
        
        // Parse compute budget instructions
        val computeBudgetInstructions = mutableListOf<JupiterInstruction>()
        if (responseJson.has("computeBudgetInstructions")) {
            val array = responseJson.getJSONArray("computeBudgetInstructions")
            for (i in 0 until array.length()) {
                computeBudgetInstructions.add(parseJupiterInstruction(array.getJSONObject(i)))
            }
        }
        
        // Parse setup instructions
        val setupInstructions = mutableListOf<JupiterInstruction>()
        if (responseJson.has("setupInstructions")) {
            val array = responseJson.getJSONArray("setupInstructions")
            for (i in 0 until array.length()) {
                setupInstructions.add(parseJupiterInstruction(array.getJSONObject(i)))
            }
        }
        
        // Parse main swap instruction
        val swapInstruction = parseJupiterInstruction(responseJson.getJSONObject("swapInstruction"))
        
        // Parse cleanup instruction (optional)
        val cleanupInstruction = if (responseJson.has("cleanupInstruction")) {
            parseJupiterInstruction(responseJson.getJSONObject("cleanupInstruction"))
        } else null
        
        // Parse address lookup table addresses
        val addressLookupTableAddresses = mutableListOf<String>()
        if (responseJson.has("addressLookupTableAddresses")) {
            val array = responseJson.getJSONArray("addressLookupTableAddresses")
            for (i in 0 until array.length()) {
                addressLookupTableAddresses.add(array.getString(i))
            }
        }

        Log.d(tag, "Parsed instructions successfully:")
        Log.d(tag, "- Compute budget: ${computeBudgetInstructions.size}")
        Log.d(tag, "- Setup: ${setupInstructions.size}")
        Log.d(tag, "- Swap: 1")
        Log.d(tag, "- Cleanup: ${if (cleanupInstruction != null) 1 else 0}")
        Log.d(tag, "- ALT addresses: ${addressLookupTableAddresses.size}")

        JupiterSwapInstructionsResponse(
            computeBudgetInstructions = computeBudgetInstructions,
            setupInstructions = setupInstructions,
            swapInstruction = swapInstruction,
            cleanupInstruction = cleanupInstruction,
            addressLookupTableAddresses = addressLookupTableAddresses
        )
    }
}

/**
 * Get Jupiter quote for the swap instructions request
 */
private suspend fun KineticSdk.getJupiterQuote(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    simplifyRoutes: Boolean,
    maxRouteHops: Int
): String = withContext(Dispatchers.IO) {
    val tag = "JupiterQuote"
    
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Convert amount to smallest units
    val inputDecimals = if (fromToken.equals("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", ignoreCase = true)) 5 else 6
    val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, inputDecimals.toDouble())
    val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

    val quoteUrl = "https://quote-api.jup.ag/v6/quote" +
            "?inputMint=$fromToken" +
            "&outputMint=$toToken" +
            "&amount=${amountInSmallestUnits.toLong()}" +
            "&slippageBps=$slippageBps" +
            "&restrictIntermediateTokens=true" +
            (if (simplifyRoutes) "&onlyDirectRoutes=true" else "") +
            "&maxAccounts=${maxRouteHops * 5}" +
            "&maxRoutes=5"

    Log.d(tag, "Getting quote: $quoteUrl")
    
    val request = Request.Builder().url(quoteUrl).get().build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Failed to get Jupiter quote: ${response.message}")
        }

        val quoteJson = response.body?.string() ?:
            throw Exception("Empty response from Jupiter quote API")

        Log.d(tag, "Quote received successfully")
        quoteJson
    }
}

/**
 * Convert Jupiter instruction to Solana TransactionInstruction
 */
private fun convertJupiterInstructionToTransactionInstruction(jupiterInstruction: JupiterInstruction): TransactionInstruction {
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
        Log.w("JupiterInstruction", "Failed to decode instruction data: ${e.message}")
        jupiterInstruction.data.toByteArray()
    }

    return TransactionInstruction(programId, accounts, data)
}

/**
 * Parse a Jupiter instruction from JSON object
 */
private fun parseJupiterInstruction(instructionJson: JSONObject): JupiterInstruction {
    val programId = instructionJson.getString("programId")
    val data = instructionJson.getString("data")
    
    val accounts = mutableListOf<JupiterAccountMeta>()
    val accountsArray = instructionJson.getJSONArray("accounts")
    for (i in 0 until accountsArray.length()) {
        val accountObj = accountsArray.getJSONObject(i)
        accounts.add(
            JupiterAccountMeta(
                pubkey = accountObj.getString("pubkey"),
                isSigner = accountObj.getBoolean("isSigner"),
                isWritable = accountObj.getBoolean("isWritable")
            )
        )
    }
    
    return JupiterInstruction(programId, accounts, data)
}

/**
 * Helper method to get Jupiter swap transaction from API (legacy approach)
 */
private suspend fun KineticSdk.getJupiterSwapTransaction(
    fromToken: String,
    toToken: String,
    amount: String,
    slippagePercent: String,
    owner: Keypair,
    useLegacyTransaction: Boolean,
    simplifyRoutes: Boolean = true,
    maxRouteHops: Int = 2
): String = withContext(Dispatchers.IO) {
    val tag = "JupiterAPI"
    
    // Create HTTP client with appropriate timeouts
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Define mintFeePayer from Kinetic SDK configuration
    val mintFeePayer = this@getJupiterSwapTransaction.getFeePayer(owner)
    Log.d(tag, "Using fee payer: ${mintFeePayer.take(8)}... (${
        if (mintFeePayer == owner.publicKey) "owner pays" else "Kinetic pays"
    })")

    // Convert amount to smallest units (based on decimal places)
    val inputDecimals = if (fromToken.equals("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", ignoreCase = true)) 5 else 6
    val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, inputDecimals.toDouble())

    // Convert slippage from percent to basis points
    val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

    // Step 1: Get a quote from Jupiter
    val quoteUrl = "https://quote-api.jup.ag/v6/quote" +
            "?inputMint=$fromToken" +
            "&outputMint=$toToken" +
            "&amount=${amountInSmallestUnits.toLong()}" +
            "&slippageBps=$slippageBps" +
            "&restrictIntermediateTokens=true" +
            (if (simplifyRoutes) "&onlyDirectRoutes=true" else "") +
            "&maxAccounts=${maxRouteHops * 5}" +
            "&maxRoutes=5" +
            "&asLegacyTransaction=$useLegacyTransaction"

    Log.d(tag, "Getting Jupiter quote: $quoteUrl")
    val quoteRequest = Request.Builder().url(quoteUrl).get().build()
    val quoteResponse = httpClient.newCall(quoteRequest).execute()

    if (!quoteResponse.isSuccessful) {
        throw Exception("Failed to get Jupiter quote: ${quoteResponse.message}")
    }

    val quoteJson = quoteResponse.body?.string() ?:
        throw Exception("Empty response from Jupiter quote API")

    Log.d(tag, "Jupiter quote response received")

    // Step 2: Get swap transaction from Jupiter
    val swapRequest = JSONObject().apply {
        put("quoteResponse", JSONObject(quoteJson))
        put("userPublicKey", owner.publicKey)
        put("payer", mintFeePayer)  // ✅ Fee payer for SOL transaction fees (Kinetic pays when configured)
        put("asLegacyTransaction", useLegacyTransaction)
        put("useSharedAccounts", !useLegacyTransaction)
        put("dynamicComputeUnitLimit", true)
        put("skipUserAccountsCheck", false)
    }

    Log.d(tag, "Requesting Jupiter swap transaction (asLegacyTransaction=$useLegacyTransaction)")

    val requestBody = swapRequest.toString().toRequestBody("application/json".toMediaTypeOrNull())
    val swapApiRequest = Request.Builder()
        .url("https://quote-api.jup.ag/v6/swap")
        .post(requestBody)
        .header("Content-Type", "application/json")
        .build()

    val swapResponse = httpClient.newCall(swapApiRequest).execute()

    if (!swapResponse.isSuccessful) {
        throw Exception("Failed to get Jupiter swap transaction: ${swapResponse.message}")
    }

    val swapResponseBody = swapResponse.body?.string() ?:
        throw Exception("Empty response from Jupiter swap API")

    Log.d(tag, "Jupiter swap transaction received")

    // Parse the swap response to get the transaction
    val swapResult = JSONObject(swapResponseBody)
    return@withContext swapResult.getString("swapTransaction")
}

/**
 * Get the appropriate fee payer for transactions
 * Uses Kinetic SDK configuration when available, falls back to owner
 *
 * @param owner The wallet owner as fallback
 * @return Fee payer public key address
 */
fun KineticSdk.getFeePayer(owner: Keypair): String {
    return try {
        // Try to get fee payer from app config (preferred - Kinetic pays fees)
        this.config?.mint?.feePayer 
            ?: this.config?.mints?.firstOrNull()?.feePayer
            ?: owner.publicKey // Fallback to owner as fee payer
    } catch (e: Exception) {
        Log.w("KineticExt", "Could not get fee payer from config, using owner: ${e.message}")
        owner.publicKey // Safe fallback
    }
}

/**
 * Check if a transaction has been confirmed
 *
 * @param signature Transaction signature to check
 * @param commitment Commitment level to check
 * @return true if transaction is confirmed or finalized
 */
suspend fun KineticSdk.isTransactionConfirmed(
    signature: String,
    commitment: Commitment? = null
): Boolean {
    return try {
        val response = this.getTransaction(signature, commitment)
        val status = response.status.confirmationStatus

        status == ConfirmationStatus.confirmed ||
                status == ConfirmationStatus.finalized
    } catch (e: Exception) {
        // If there's an error checking, return false
        false
    }
}

/**
 * Wait for a transaction to be confirmed, with timeout
 *
 * @param signature Transaction signature to check
 * @param commitment Commitment level to check
 * @param maxAttempts Maximum number of attempts to check
 * @param delayMs Delay between attempts in milliseconds
 * @return true if transaction is confirmed within the timeout
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

        kotlinx.coroutines.delay(delayMs)
        attempts++
    }

    return false
}