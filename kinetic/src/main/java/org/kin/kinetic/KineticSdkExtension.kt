package org.kin.kinetic

import android.util.Base64
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
import org.kin.kinetic.generated.api.TransactionApi
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.generated.api.model.ConfirmationStatus
import org.kin.kinetic.generated.api.model.MakeTransferRequest
import org.kin.kinetic.generated.api.model.Transaction as KineticTransaction
import java.util.concurrent.TimeUnit

/**
 * Extension functions for KineticSdk to support Jupiter swaps and other advanced functionality
 */

/**
 * Submit a pre-built serialized transaction to the Solana network
 * Useful for submitting transactions constructed by external services like Jupiter
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
    val appConfig = this.config ?: throw IllegalStateException("App config not initialized")

    // Create transaction API client
    val transactionApi = TransactionApi(
        basePath = this.endpoint,
        headers = createHeaders()
    )

    // Get blockhash using the transaction API
    val blockHash = transactionApi.getLatestBlockhash(
        this@submitSerializedTransaction.sdkConfig.environment,
        this@submitSerializedTransaction.sdkConfig.index
    )

    // Decode the serialized transaction
    val transactionBytes = Base64.decode(serializedTransaction, Base64.DEFAULT)

    // If we need to add a signature
    val transaction = if (addSignature) {
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
    } else {
        // Use as is
        serializedTransaction
    }

    // Create the transaction request
    val makeTransferRequest = MakeTransferRequest(
        commitment = commitment ?: this.sdkConfig.commitment ?: Commitment.confirmed,
        environment = this.sdkConfig.environment,
        index = this.sdkConfig.index,
        mint = appConfig.mint.publicKey,
        lastValidBlockHeight = blockHash.lastValidBlockHeight,
        tx = transaction,
        reference = null
    )

    // Submit the transaction (already running in a suspend function)
    return transactionApi.makeTransfer(makeTransferRequest)
}

/**
 * Execute a Jupiter swap transaction
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
    // Create HTTP client with appropriate timeouts
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
            "&restrictIntermediateTokens=true"

    val quoteRequest = Request.Builder()
        .url(quoteUrl)
        .get()
        .build()

    val quoteResponse = withContext(Dispatchers.IO) {
        httpClient.newCall(quoteRequest).execute()
    }

    if (!quoteResponse.isSuccessful) {
        throw Exception("Failed to get Jupiter quote: ${quoteResponse.message}")
    }

    val quoteJson = quoteResponse.body?.string() ?:
    throw Exception("Empty response from Jupiter quote API")

    // Step 2: Get swap transaction from Jupiter
    val swapRequest = JSONObject().apply {
        put("quoteResponse", JSONObject(quoteJson))
        put("userPublicKey", owner.publicKey)
        put("asLegacyTransaction", true)
        put("dynamicComputeUnitLimit", true)
    }

    val requestBody = swapRequest.toString().toRequestBody("application/json".toMediaTypeOrNull())

    val swapApiRequest = Request.Builder()
        .url("https://api.jup.ag/swap/v1/swap")
        .post(requestBody)
        .header("Content-Type", "application/json")
        .build()

    val swapResponse = withContext(Dispatchers.IO) {
        httpClient.newCall(swapApiRequest).execute()
    }

    if (!swapResponse.isSuccessful) {
        throw Exception("Failed to get Jupiter swap transaction: ${swapResponse.message}")
    }

    val swapResponseBody = swapResponse.body?.string() ?:
    throw Exception("Empty response from Jupiter swap API")

    // Parse the swap response to get the transaction
    val swapResult = JSONObject(swapResponseBody)
    val swapTransaction = swapResult.getString("swapTransaction")

    // Step 3: Submit the transaction
    return submitSerializedTransaction(swapTransaction, owner, commitment)
}

/**
 * Create API headers for KineticSDK
 */
private fun KineticSdk.createHeaders(): Map<String, String> {
    return mapOf(
        "kinetic-environment" to this.sdkConfig.environment,
        "kinetic-index" to this.sdkConfig.index.toString()
    ) + this.sdkConfig.headers
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