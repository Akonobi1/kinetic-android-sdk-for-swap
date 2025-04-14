package org.kin.kinetic.jupiter

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.kin.kinetic.Keypair
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.executeJupiterSwap
import org.kin.kinetic.generated.api.model.Commitment
import org.kin.kinetic.isTransactionConfirmed
import org.kin.kinetic.submitSerializedTransaction
import org.kin.kinetic.waitForTransactionConfirmation
import java.util.concurrent.TimeUnit

/**
 * Manager class for handling Jupiter token swaps
 */
class JupiterSwapManager {
    companion object {
        private const val TAG = "JupiterSwapManager"
        private val gson = Gson()

        // Pre-defined token addresses
        private val COMMON_TOKENS = mapOf(
            "KIN" to "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
            "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "SOL" to "So11111111111111111111111111111111111111112",
            "USDT" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        )

        // Default OkHttpClient with longer timeouts
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Get swap quote from Jupiter
     *
     * @param fromToken Source token mint address or symbol (KIN, USDC, etc.)
     * @param toToken Destination token mint address or symbol
     * @param amount Amount in decimal format (e.g. "10.5")
     * @param slippagePercent Maximum slippage in percent (e.g. "1.0" for 1%)
     * @return Quote response with rate details
     */
    suspend fun getSwapQuote(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting quote for $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

            // Resolve token addresses
            val inputMint = resolveTokenAddress(fromToken)
            val outputMint = resolveTokenAddress(toToken)

            // Convert amount to smallest units
            val decimals = getTokenDecimals(inputMint)
            val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, decimals.toDouble())

            // Convert slippage to basis points (1% = 100 basis points)
            val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

            // Construct the quote URL
            val url = "https://api.jup.ag/swap/v1/quote" +
                    "?inputMint=$inputMint" +
                    "&outputMint=$outputMint" +
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
                val quoteResponse = gson.fromJson(responseBody, JupiterQuoteResponse::class.java)

                // Process the response
                val outputDecimals = getTokenDecimals(outputMint)
                val outputAmount = quoteResponse.outAmount.toLongOrNull() ?: 0L
                val formattedOutputAmount = (outputAmount.toDouble() / Math.pow(10.0, outputDecimals.toDouble()))
                    .toString()

                // Calculate minimum received amount based on slippage
                val slippageFactor = 1.0 - ((slippagePercent.toDoubleOrNull() ?: 0.5) / 100.0)
                val minReceived = (formattedOutputAmount.toDoubleOrNull() ?: 0.0) * slippageFactor

                // Calculate rate (price per token)
                val inputAmountDouble = amount.toDoubleOrNull() ?: 1.0
                val outputAmountDouble = formattedOutputAmount.toDoubleOrNull() ?: 0.0
                val rate = if (inputAmountDouble > 0) {
                    outputAmountDouble / inputAmountDouble
                } else 0.0

                // Format the rate string
                val rateString = "1 ${tokenSymbol(inputMint)} = ${String.format("%.6f", rate)} ${tokenSymbol(outputMint)}"

                // Format the price impact
                val priceImpact = if (quoteResponse.priceImpactPct.isBlank() || quoteResponse.priceImpactPct == "0") {
                    "0.00 %"
                } else {
                    try {
                        val impactValue = quoteResponse.priceImpactPct.toDouble()
                        String.format("%.4f %%", impactValue)
                    } catch (e: Exception) {
                        "${quoteResponse.priceImpactPct} %"
                    }
                }

                // Return the formatted result
                Result.success(mapOf(
                    "inputAmount" to amount,
                    "outputAmount" to formattedOutputAmount,
                    "rate" to rateString,
                    "priceImpact" to priceImpact,
                    "minReceived" to String.format("%.6f", minReceived),
                    "networkFee" to "0.000005 SOL",
                    "quoteJson" to responseBody
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting quote: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Execute a swap transaction using Jupiter and Kinetic SDK
     *
     * @param kinetic Initialized KineticSdk instance
     * @param owner Keypair of the wallet initiating the swap
     * @param fromToken Source token mint address or symbol
     * @param toToken Destination token mint address or symbol
     * @param amount Amount to swap in decimal format
     * @param slippagePercent Maximum slippage in percent
     * @param commitment Transaction commitment level
     * @return Result with swap details or error
     */
    suspend fun executeSwap(
        kinetic: KineticSdk,
        owner: Keypair,
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        commitment: Commitment? = null
    ): Result<SwapResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing swap: $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

            // Resolve token addresses
            val inputMint = resolveTokenAddress(fromToken)
            val outputMint = resolveTokenAddress(toToken)

            // Get quote first
            val quoteResult = getSwapQuote(fromToken, toToken, amount, slippagePercent)

            if (quoteResult.isFailure) {
                return@withContext Result.failure(
                    quoteResult.exceptionOrNull() ?: Exception("Failed to get quote")
                )
            }

            val quoteDetails = quoteResult.getOrThrow()
            val quoteJson = quoteDetails["quoteJson"] ?:
            return@withContext Result.failure(Exception("Missing quote details"))

            // Execute the transaction using Kinetic extension
            val transaction = kinetic.executeJupiterSwap(
                fromToken = inputMint,
                toToken = outputMint,
                amount = amount,
                slippagePercent = slippagePercent,
                owner = owner,
                commitment = commitment
            )

            val signature = transaction.signature ?:
            return@withContext Result.failure(Exception("No signature returned from transaction"))

            Log.d(TAG, "Swap transaction submitted with signature: $signature")

            // Wait for confirmation
            val confirmed = kinetic.waitForTransactionConfirmation(
                signature = signature,
                commitment = commitment,
                maxAttempts = 10,
                delayMs = 1500
            )

            if (!confirmed) {
                return@withContext Result.failure(
                    Exception("Transaction not confirmed after multiple attempts")
                )
            }

            Log.d(TAG, "Swap transaction confirmed!")

            // Return successful result
            Result.success(
                SwapResult(
                    success = true,
                    signature = signature,
                    inputAmount = amount,
                    outputAmount = quoteDetails["outputAmount"],
                    inputToken = tokenSymbol(inputMint),
                    outputToken = tokenSymbol(outputMint),
                    effectivePrice = quoteDetails["rate"]?.let { parseRate(it) }
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing swap: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Resolve a token identifier to its mint address
     *
     * @param token Symbol or mint address
     * @return Resolved mint address
     */
    private fun resolveTokenAddress(token: String): String {
        // If it looks like an address already, return it
        if (token.length > 30) {
            return token
        }

        // Try to resolve from common tokens
        return COMMON_TOKENS[token.uppercase()] ?: token
    }

    /**
     * Get token decimals (defaults based on common tokens)
     *
     * @param mintAddress Token mint address
     * @return Number of decimal places
     */
    private fun getTokenDecimals(mintAddress: String): Int {
        return when (mintAddress) {
            COMMON_TOKENS["KIN"] -> 5
            COMMON_TOKENS["SOL"] -> 9
            else -> 6 // Most SPL tokens use 6 decimals (USDC, USDT, etc.)
        }
    }

    /**
     * Get token symbol from mint address
     *
     * @param mintAddress Token mint address
     * @return Token symbol or shortened address
     */
    private fun tokenSymbol(mintAddress: String): String {
        COMMON_TOKENS.forEach { (symbol, address) ->
            if (address == mintAddress) {
                return symbol
            }
        }

        // If not found, return shortened address
        return if (mintAddress.length > 8) {
            "${mintAddress.take(4)}...${mintAddress.takeLast(4)}"
        } else {
            mintAddress
        }
    }

    /**
     * Parse rate string to extract numeric value
     *
     * @param rateString Rate string like "1 KIN = 0.000123 USDC"
     * @return Numeric rate value or null
     */
    private fun parseRate(rateString: String): Double? {
        try {
            // Extract the numeric part after the equals sign
            val parts = rateString.split("=")
            if (parts.size == 2) {
                val numericPart = parts[1].trim().split(" ")[0]
                return numericPart.toDoubleOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing rate: $rateString", e)
        }
        return null
    }
}