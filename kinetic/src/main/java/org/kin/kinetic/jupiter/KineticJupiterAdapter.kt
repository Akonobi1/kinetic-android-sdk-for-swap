package org.kin.kinetic.jupiter

import android.util.Base64
import android.util.Log
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import com.solana.core.Transaction as SolanaTransaction
import com.solana.core.SignaturePubkeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.kin.kinetic.Keypair
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.KineticSdkConfig
import org.kin.kinetic.generated.api.TransactionApi
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.generated.api.model.MakeTransferRequest
import org.kin.kinetic.generated.api.model.Transaction as KineticTransaction
import java.util.concurrent.TimeUnit

/**
 * Adapter for integrating Jupiter swaps with Kinetic SDK while preserving Kinetic's fee model
 */
class KineticJupiterAdapter(private val kinetic: KineticSdk) {
    companion object {
        private const val TAG = "KineticJupiterAdapter"

        // HTTP client for Jupiter API calls
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Execute a Jupiter swap while using Kinetic's fee payer model
     *
     * @param fromToken Source token mint address
     * @param toToken Destination token mint address
     * @param amount Amount to swap (in decimal format, e.g., "10.5")
     * @param slippagePercent Maximum slippage tolerance (in percent, e.g., "1.0" for 1%)
     * @param owner Keypair of the wallet initiating the swap
     * @param commitment Transaction commitment level
     * @return Transaction result
     */
    suspend fun executeJupiterSwap(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        owner: Keypair,
        commitment: Commitment? = null
    ): KineticTransaction {
        // Ensure the SDK is initialized
        val appConfig = kinetic.config ?:
        throw IllegalStateException("Kinetic SDK not initialized")

        // Prepare amount based on decimals
        val inputDecimals = if (fromToken.equals("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", ignoreCase = true)) 5 else 6
        val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, inputDecimals.toDouble())

        // Convert slippage to basis points (1% = 100 basis points)
        val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

        // Step 1: Get a quote from Jupiter
        val quoteUrl = "https://api.jup.ag/swap/v1/quote" +
                "?inputMint=$fromToken" +
                "&outputMint=$toToken" +
                "&amount=${amountInSmallestUnits.toLong()}" +
                "&slippageBps=$slippageBps" +
                "&restrictIntermediateTokens=true"

        Log.d(TAG, "Getting Jupiter quote: $quoteUrl")

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

        Log.d(TAG, "Jupiter quote response: $quoteJson")

        // Step 2: Get swap transaction from Jupiter
        val swapRequest = JSONObject().apply {
            put("quoteResponse", JSONObject(quoteJson))
            put("userPublicKey", owner.publicKey)
            // Important: Request serialized transaction format for better compatibility
            put("wrapUnwrapSOL", true)  // Auto-wrap/unwrap SOL if needed
            put("asLegacyTransaction", true)  // Use legacy transaction format
        }

        val requestBody = swapRequest.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val swapApiRequest = Request.Builder()
            .url("https://api.jup.ag/swap/v1/swap")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Requesting Jupiter swap transaction: ${swapRequest}")

        val swapResponse = withContext(Dispatchers.IO) {
            httpClient.newCall(swapApiRequest).execute()
        }

        if (!swapResponse.isSuccessful) {
            throw Exception("Failed to get Jupiter swap transaction: ${swapResponse.message}")
        }

        val swapResponseBody = swapResponse.body?.string() ?:
        throw Exception("Empty response from Jupiter swap API")

        Log.d(TAG, "Jupiter swap response: $swapResponseBody")

        // Extract the transaction from the response
        val swapResult = JSONObject(swapResponseBody)
        val swapTransaction = swapResult.getString("swapTransaction")

        // Step 3: Adapt and resubmit the transaction using Kinetic's fee model
        return adaptAndSubmitTransaction(swapTransaction, owner, commitment)
    }

    /**
     * Adapt a Jupiter transaction to use Kinetic's fee payer model
     *
     * This extracts the instructions from the Jupiter transaction and
     * rebuilds it to use Kinetic's fee payer
     */
    private suspend fun adaptAndSubmitTransaction(
        serializedTransaction: String,
        owner: Keypair,
        commitment: Commitment? = null
    ): KineticTransaction {
        val appConfig = kinetic.config ?: throw IllegalStateException("Kinetic SDK not initialized")

        // Find the appropriate mint config - default to the main mint
        val mintConfig = appConfig.mint

        // Get the fee payer address from Kinetic's config
        val feePayer = PublicKey(mintConfig.feePayer)

        // Create transaction API client
        val transactionApi = TransactionApi(
            basePath = kinetic.endpoint,
            headers = createHeaders(kinetic.sdkConfig)
        )

        // Get a fresh blockhash using the transaction API
        val blockHash = transactionApi.getLatestBlockhash(
            kinetic.sdkConfig.environment,
            kinetic.sdkConfig.index
        )

        // Decode the transaction
        val transactionBytes = Base64.decode(serializedTransaction, Base64.DEFAULT)
        val jupiterTransaction = SolanaTransaction.from(transactionBytes)

        // Extract instructions from Jupiter's transaction
        val instructions = jupiterTransaction.instructions

        Log.d(TAG, "Extracted ${instructions.size} instructions from Jupiter transaction")

        // Create a new transaction with Kinetic's fee payer
        val kineticTransaction = SolanaTransaction().apply {
            this.feePayer = feePayer
            this.recentBlockhash = blockHash.blockhash

            // Add owner's public key to the signers
            this.signatures.add(SignaturePubkeyPair(null, owner.solanaPublicKey))

            // Add all instructions from Jupiter's transaction
            instructions.forEach {
                this.add(it)
            }
        }

        // Sign the transaction with the owner's keypair
        kineticTransaction.partialSign(owner.solana)

        // Serialize with Kinetic's settings
        val serializedKineticTx = kineticTransaction.serialize(
            SerializeConfig(requireAllSignatures = false, verifySignatures = false)
        )

        val encodedTransaction = Base64.encodeToString(serializedKineticTx, Base64.NO_WRAP)

        // Create a transfer request
        val transferRequest = MakeTransferRequest(
            commitment = commitment ?: kinetic.sdkConfig.commitment ?: Commitment.confirmed,
            environment = kinetic.sdkConfig.environment,
            index = kinetic.sdkConfig.index,
            mint = mintConfig.publicKey,
            lastValidBlockHeight = blockHash.lastValidBlockHeight,
            tx = encodedTransaction,
            reference = null
        )

        // Submit the transaction (already running in a suspend function)
        return transactionApi.makeTransfer(transferRequest)
    }

    /**
     * Create API headers for Kinetic
     */
    private fun createHeaders(sdkConfig: KineticSdkConfig): Map<String, String> {
        return mapOf(
            "kinetic-environment" to sdkConfig.environment,
            "kinetic-index" to sdkConfig.index.toString(),
            "kinetic-user-agent" to "KineticJupiterAdapter"
        ) + sdkConfig.headers
    }

    /**
     * Get information about a quoted swap from Jupiter
     */
    suspend fun getSwapQuote(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            // Prepare amount based on decimals
            val inputDecimals = if (fromToken.equals("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", ignoreCase = true)) 5 else 6
            val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, inputDecimals.toDouble())

            // Convert slippage to basis points
            val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

            // Construct the quote URL
            val url = "https://api.jup.ag/swap/v1/quote" +
                    "?inputMint=$fromToken" +
                    "&outputMint=$toToken" +
                    "&amount=${amountInSmallestUnits.toLong()}" +
                    "&slippageBps=$slippageBps" +
                    "&restrictIntermediateTokens=true"

            Log.d(TAG, "Quote URL: $url")

            // Make the API call
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to get quote: ${response.code} - ${response.message}")
                    )
                }

                val responseBody = response.body?.string() ?:
                return@withContext Result.failure(Exception("Empty response from Jupiter API"))

                Log.d(TAG, "Quote response: $responseBody")

                // Parse the response
                val quoteResponse = JSONObject(responseBody)

                // Process the response
                val outputDecimals = if (toToken.equals("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", ignoreCase = true)) 5 else 6
                val outputAmount = quoteResponse.getString("outAmount").toLongOrNull() ?: 0L
                val formattedOutputAmount = (outputAmount.toDouble() / Math.pow(10.0, outputDecimals.toDouble()))
                    .toString()

                // Get input/output token symbols
                val inputSymbol = getTokenSymbol(fromToken)
                val outputSymbol = getTokenSymbol(toToken)

                // Calculate rate (price per token)
                val inputAmountDouble = amount.toDoubleOrNull() ?: 1.0
                val outputAmountDouble = formattedOutputAmount.toDoubleOrNull() ?: 0.0
                val rate = if (inputAmountDouble > 0) {
                    outputAmountDouble / inputAmountDouble
                } else 0.0

                // Format the rate string
                val rateString = "1 $inputSymbol = ${String.format("%.6f", rate)} $outputSymbol"

                // Format the price impact
                val priceImpact = try {
                    val impactValue = quoteResponse.optString("priceImpactPct", "0").toDoubleOrNull() ?: 0.0
                    String.format("%.4f %%", impactValue)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing price impact", e)
                    "0.00 %"
                }

                // Calculate minimum received amount based on slippage
                val slippageFactor = 1.0 - ((slippagePercent.toDoubleOrNull() ?: 0.5) / 100.0)
                val minReceived = (formattedOutputAmount.toDoubleOrNull() ?: 0.0) * slippageFactor

                // Return the formatted result
                Result.success(mapOf(
                    "inputAmount" to amount,
                    "inputToken" to inputSymbol,
                    "outputAmount" to formattedOutputAmount,
                    "outputToken" to outputSymbol,
                    "rate" to rateString,
                    "priceImpact" to priceImpact,
                    "minReceived" to String.format("%.6f", minReceived),
                    "networkFee" to "Paid by Kinetic",
                    "quoteResponse" to responseBody
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting quote: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get token symbol from mint address
     */
    private fun getTokenSymbol(mintAddress: String): String {
        return when (mintAddress) {
            "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6" -> "KIN"
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" -> "USDC"
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" -> "USDT"
            "So11111111111111111111111111111111111111112" -> "SOL"
            else -> mintAddress.take(4) + "..." + mintAddress.takeLast(4)
        }
    }
}