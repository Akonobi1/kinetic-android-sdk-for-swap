package org.kin.kinetic.jupiter

import android.util.Base64
import android.util.Log
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import com.solana.core.Transaction
import org.json.JSONArray
import org.json.JSONObject
import org.kin.kinetic.Keypair
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.executeJupiterSwap
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.generated.api.model.Transaction as KineticTransaction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Adapter for integrating Jupiter swaps with Kinetic SDK while preserving Kinetic's fee model
 * Supports versioned transactions for improved efficiency
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
     * Uses versioned transactions by default for improved efficiency
     *
     * @param fromToken Source token mint address
     * @param toToken Destination token mint address
     * @param amount Amount to swap (in decimal format, e.g., "10.5")
     * @param slippagePercent Maximum slippage tolerance (in percent, e.g., "1.0" for 1%)
     * @param owner Keypair of the wallet initiating the swap
     * @param commitment Transaction commitment level
     * @param useLegacyTransaction Whether to use legacy transactions (default: false)
     * @return Transaction result
     */
    suspend fun executeJupiterSwap(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        owner: Keypair,
        commitment: Commitment? = null,
        useLegacyTransaction: Boolean = false,
        // Add new parameters with default values
        simplifyRoutes: Boolean = true,
        maxRouteHops: Int = 2
    ): KineticTransaction {
        // Then pass these parameters to the extension function
        return kinetic.executeJupiterSwap(
            fromToken = fromToken,
            toToken = toToken,
            amount = amount,
            slippagePercent = slippagePercent,
            owner = owner,
            commitment = commitment,
            useLegacyTransaction = useLegacyTransaction,
            // Pass along the route parameters
            simplifyRoutes = simplifyRoutes,
            maxRouteHops = maxRouteHops
        )
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
                    "networkFee" to "Paid by Kinnnected!",
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