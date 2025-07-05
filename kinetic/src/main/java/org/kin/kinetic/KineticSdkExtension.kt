package org.kin.kinetic

import android.util.Base64
import android.util.Log
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import com.solana.core.Transaction as SolanaTransaction
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
 * Enhanced with comprehensive asLegacy and versioned transaction support
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
 * Enhanced executeJupiterSwap with explicit format control
 * Updated to use the new asLegacy/isVersioned system
 *
 * @param fromToken Source token mint address
 * @param toToken Destination token mint address  
 * @param amount Amount to swap in decimals (e.g. "10.5")
 * @param slippagePercent Maximum allowed slippage in percent (e.g. "1.0" for 1%)
 * @param owner Keypair of the wallet initiating the swap
 * @param commitment Transaction commitment level
 * @param useLegacyTransaction Whether to use legacy transactions (default: true to fix current issues)
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
    useLegacyTransaction: Boolean = true,  // Default to true to fix current Jupiter issues
    simplifyRoutes: Boolean = true,
    maxRouteHops: Int = 2
): KineticTransaction {
    val tag = "KineticSwap"
    Log.d(tag, "Starting Jupiter swap: $fromToken -> $toToken, amount=$amount, slippage=$slippagePercent%")
    Log.d(tag, "Using ${if (useLegacyTransaction) "LEGACY (asLegacy=true)" else "VERSIONED (isVersioned=true)"} transactions")

    val swapTransaction = this.getJupiterSwapTransaction(
        fromToken = fromToken,
        toToken = toToken,
        amount = amount,
        slippagePercent = slippagePercent,
        owner = owner,
        useLegacyTransaction = useLegacyTransaction,
        simplifyRoutes = simplifyRoutes,
        maxRouteHops = maxRouteHops
    )

    // Submit with the appropriate method to set the correct flags
    return if (useLegacyTransaction) {
        Log.d(tag, "Submitting via submitSerializedTransaction (sets asLegacy=true)")
        submitSerializedTransaction(swapTransaction, owner, commitment, addSignature = true)
    } else {
        Log.d(tag, "Submitting via submitVersionedTransaction (sets isVersioned=true)")
        submitVersionedTransaction(swapTransaction, owner, commitment, null)
    }
}

/**
 * Helper method to get Jupiter swap transaction from API
 * Extracted for reuse across different submission methods
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
    val quoteUrl = "https://api.jup.ag/swap/v1/quote" +
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
        .url("https://api.jup.ag/swap/v1/swap")
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