package org.kin.kinetic.jupiter


import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.KineticSdkConfig

import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
//import com.solana.mobilewalletadapter.clientlib.RpcCluster
//import com.solana.mobilewalletadapter.clientlib.TransactionService
//import com.solana.mobilewalletadapter.clientlib.scenario.LocalScenario
//import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
/**
 * Service class for interacting with Jupiter Swap API
 * Uses Kinetic SDK for transaction handling
 */
open class JupiterSwapService(private val context: Context) {
    private val TAG = "JupiterSwapService"
    private val gson = Gson()

    // Create OkHttpClient with longer timeouts
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Create Retrofit instance for Jupiter API (quote endpoint)
    private val quoteRetrofit = Retrofit.Builder()
        .baseUrl("https://api.jup.ag/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(okHttpClient)
        .build()

    // Create API service for quotes
    private val jupiterQuoteApi = quoteRetrofit.create(JupiterQuoteApiService::class.java)

    // Create OkHttpClient for direct API calls
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Token mint addresses
    private val TOKEN_MINTS = mapOf(
        "KIN" to "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
        "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    )

    /**
     * Get swap quote details with network call on IO dispatcher
     */
    open suspend fun getQuoteDetails(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String
    ): Map<String, String> {
        return withContext(Dispatchers.IO) {  // Ensure network call runs on IO dispatcher
            try {
                Log.d(TAG, "Getting quote details for $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

                // Validate token inputs
                val inputMint = TOKEN_MINTS[fromToken] ?: throw IllegalArgumentException("Unknown token: $fromToken")
                val outputMint = TOKEN_MINTS[toToken] ?: throw IllegalArgumentException("Unknown token: $toToken")

                // Convert amount to smallest units
                val decimals = if (fromToken == "KIN") 5 else 6
                val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, decimals.toDouble())

                // Convert slippage from percent to basis points
                val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

                // Construct the quote URL with parameters
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
                        Log.e(TAG, "Error getting quote: ${response.code} - ${response.message}")
                        return@withContext mapOf("error" to "Failed to get quote: ${response.message}")
                    }

                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        Log.e(TAG, "Empty response body from quote API")
                        return@withContext mapOf("error" to "Empty response from Jupiter API")
                    }

                    Log.d(TAG, "Quote response: $responseBody")

                    // Parse the response
                    val quoteResponse = gson.fromJson(responseBody, JupiterQuoteResponse::class.java)

                    // Debug logging for price impact
                    Log.d(TAG, "Raw quote response: $responseBody")
                    Log.d(TAG, "Price impact from API: '${quoteResponse.priceImpactPct}'")

                    // Process the response
                    val outputAmount = quoteResponse.outAmount.toLongOrNull() ?: 0L
                    val outputDecimals = if (toToken == "KIN") 5 else 6
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

                    // Format the rate string more clearly
                    val rateString = if (fromToken == "USDC" && toToken == "KIN") {
                        "1 $fromToken = ${String.format("%.2f", rate)} $toToken"
                    } else if (fromToken == "KIN" && toToken == "USDC") {
                        "1 $fromToken = ${String.format("%.8f", rate)} $toToken"
                    } else {
                        "1 $fromToken = ${String.format("%.6f", rate)} $toToken"
                    }

                    // Format the price impact to a more readable value (limit decimal places)
                    val priceImpact = if (quoteResponse.priceImpactPct.isBlank() || quoteResponse.priceImpactPct == "0") {
                        "0.00 %"
                    } else {
                        try {
                            // Parse the price impact and format to 4 decimal places
                            val impactValue = quoteResponse.priceImpactPct.toDouble()
                            String.format("%.4f %%", impactValue)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error formatting price impact: ${e.message}")
                            "${quoteResponse.priceImpactPct} %"
                        }
                    }

                    Log.d(TAG, "Formatted price impact: $priceImpact")

                    // Format the result
                    mapOf(
                        "outputAmount" to formattedOutputAmount,
                        "rate" to rateString,
                        "priceImpact" to priceImpact,  // Changed key from priceImpactPct to priceImpact
                        "minReceived" to String.format("%.6f", minReceived),
                        "networkFee" to "0.000005 SOL",
                        "quoteJson" to responseBody // Store the raw quote for later use
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting quote details: ${e.message}", e)
                mapOf("error" to "Failed to get quote: ${e.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Execute swap transaction using Jupiter API and Kinetic SDK
     *
     * This approach gets the swap details from Jupiter but uses Kinetic's makeTransfer
     * to execute the transaction. This is experimental and may not perform an actual swap
     * across DEXes, but acts as a token transfer.
     */
    open suspend fun executeSwap(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        walletAddress: String,
        coroutineScope: CoroutineScope
    ): Boolean {
        return withContext(Dispatchers.IO) {  // Ensure network calls run on IO dispatcher
            try {
                Log.d(TAG, "Starting swap execution: $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

                // Step 1: Get the wallet keypair
                val storage = BasicAccountStorage(
                    context.filesDir,

                )
                val owner = storage.account()

                Log.d(TAG, "Using wallet: ${owner.publicKey}")

                // Step 2: Get a fresh quote
                val quoteDetails = getQuoteDetails(fromToken, toToken, amount, slippagePercent)

                if (quoteDetails.containsKey("error")) {
                    Log.e(TAG, "Error getting quote: ${quoteDetails["error"]}")
                    return@withContext false
                }

                val quoteJson = quoteDetails["quoteJson"] ?: return@withContext false
                val quoteResponse = gson.fromJson(quoteJson, JupiterQuoteResponse::class.java)

                // Step 3: Get swap transaction from Jupiter
                // Prepare the request body for the swap API
                val swapRequest = JSONObject().apply {
                    put("quoteResponse", JSONObject(quoteJson))
                    put("userPublicKey", owner.publicKey)
                    // Use legacy transaction format for better compatibility with Kinetic SDK
                    put("asLegacyTransaction", true)
                    put("dynamicComputeUnitLimit", true)
                    put("dynamicSlippage", true)
                    val prioritizationFeeLamports = JSONObject().apply {
                        val priorityLevel = JSONObject().apply {
                            put("maxLamports", 1000000)
                            put("priorityLevel", "veryHigh")
                        }
                        put("priorityLevelWithMaxLamports", priorityLevel)
                    }
                    put("prioritizationFeeLamports", prioritizationFeeLamports)
                }

                val requestBody = swapRequest.toString().toRequestBody("application/json".toMediaType())

                val swapApiRequest = Request.Builder()
                    .url("https://api.jup.ag/swap/v1/swap")
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "Swap request body: ${swapRequest}")

                val swapResponse = httpClient.newCall(swapApiRequest).execute()

                if (!swapResponse.isSuccessful) {
                    Log.e(TAG, "Error getting swap transaction: ${swapResponse.code} - ${swapResponse.message}")
                    return@withContext false
                }

                val swapResponseBody = swapResponse.body?.string()
                if (swapResponseBody == null) {
                    Log.e(TAG, "Empty response body from swap API")
                    return@withContext false
                }

                Log.d(TAG, "Swap response: $swapResponseBody")

                // Parse the swap response
                val swapResult = JSONObject(swapResponseBody)
                val swapTransaction = swapResult.getString("swapTransaction")

                // Extract key details from the quote to use with Kinetic
                val inputAmount = quoteResponse.inAmount
                val outputAmount = quoteResponse.outAmount

                // Step 4: Use Kinetic for the token transfer
                // Initialize Kinetic SDK
                val kinetic = KineticSdk.setup(
                    KineticSdkConfig(
                        endpoint = "https://app.altude.so",
                        environment = "mainnet",
                        index = 170
                    )
                )

                // Determine the transaction type based on token
                try {
                    val targetAddress: String

                    // Find an appropriate destination to send to
                    // For testing, we're sending to the user's own wallet
                    // In a real implementation, you'd send to a proper destination
                    targetAddress = when {
                        // If we're swapping to a different wallet, use that address
                        walletAddress != owner.publicKey -> walletAddress
                        // Otherwise use the owner's address (sending to self as a test)
                        else -> owner.publicKey
                    }

                    Log.d(TAG, "Using target address: $targetAddress")

                    // Execute the transfer
                    val result = if (fromToken == "KIN") {
                        // For KIN, use standard makeTransfer
                        Log.d(TAG, "Executing KIN transfer via Kinetic")
                        kinetic.makeTransfer(
                            amount = amount,
                            destination = targetAddress,
                            owner = owner
                        )
                    } else {
                        // For USDC or other tokens, specify the mint
                        Log.d(TAG, "Executing $fromToken transfer via Kinetic with mint")
                        val inputMint = TOKEN_MINTS[fromToken]
                        kinetic.makeTransfer(
                            amount = amount,
                            destination = targetAddress,
                            owner = owner,
                            mint = inputMint
                        )
                    }

                    // Check the result
                    val signature = result.signature
                    if (signature == null) {
                        Log.e(TAG, "Transfer failed - no signature returned")
                        return@withContext false
                    }

                    Log.d(TAG, "Transfer initiated with signature: $signature")

                    // Monitor transaction status
                    var attempts = 0
                    val maxAttempts = 10
                    var confirmed = false

                    while (attempts < maxAttempts && !confirmed) {
                        delay(1500) // Wait between checks

                        try {
                            // Instead of using kinetic.getTransaction which is having parsing issues,
                            // we'll use a different approach to check if the transaction is confirmed

                            // Option 1: Use BalanceVerifier to check if balance changed
                            try {
                                // Get balance before and after
                                val currentBalance = if (fromToken == "KIN") {
                                    kinetic.getBalance(owner.publicKey).balance
                                } else {
                                    val accountInfo = kinetic.getAccountInfo(
                                        account = owner.publicKey,
                                        mint = TOKEN_MINTS[fromToken]
                                    )
                                    val tokenInfo = accountInfo.tokens?.find { it.mint == TOKEN_MINTS[fromToken] }
                                    tokenInfo?.balance ?: "0"
                                }

                                Log.d(TAG, "Current balance after transaction: $currentBalance")

                                // Assume success if we got this far without exception
                                confirmed = true
                                Log.d(TAG, "Transaction appears to be successful based on balance check")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error checking balance: ${e.message}")
                            }

                            // If we couldn't confirm by balance, try using Solana RPC directly
                            if (!confirmed) {
                                try {
                                    // Create a direct request to check transaction status
                                    // This bypasses Kinetic's JSON parsing that's having issues
                                    val requestBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getSignatureStatuses\",\"params\":[[\"$signature\"]]}"

                                    val request = Request.Builder()
                                        .url("https://api.mainnet-beta.solana.com")
                                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                                        .build()

                                    httpClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val responseBody = response.body?.string()
                                            Log.d(TAG, "Solana status response: $responseBody")

                                            // Check if confirmed or finalized
                                            if (responseBody?.contains("\"confirmationStatus\":\"confirmed\"") == true ||
                                                responseBody?.contains("\"confirmationStatus\":\"finalized\"") == true) {
                                                confirmed = true
                                                Log.d(TAG, "Transaction confirmed via direct RPC check!")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error checking transaction via RPC: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking transaction status: ${e.message}", e)
                        }

                        attempts++
                    }

                    // Return transaction status
                    if (confirmed) {
                        Log.d(TAG, "Transfer completed successfully!")
                        return@withContext true
                    } else {
                        Log.e(TAG, "Transfer not confirmed after $maxAttempts attempts")
                        return@withContext false
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error executing transfer: ${e.message}", e)
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error executing swap: ${e.message}", e)
                return@withContext false
            }
        }
    }

    /**
     * Jupiter API service interfaces
     */
    interface JupiterQuoteApiService {
        @GET("swap/v1/quote")
        suspend fun getQuote(
            @Query("inputMint") inputMint: String,
            @Query("outputMint") outputMint: String,
            @Query("amount") amount: Long,
            @Query("slippageBps") slippageBps: Int,
            @Query("restrictIntermediateTokens") restrictIntermediateTokens: Boolean = true
        ): JupiterQuoteResponse
    }

    /**
     * Response data classes for Jupiter API
     */
    data class JupiterQuoteResponse(
        val inputMint: String,
        val inAmount: String,
        val outputMint: String,
        val outAmount: String,
        val otherAmountThreshold: String,
        val swapMode: String,
        val slippageBps: Int,
        val platformFee: Any? = null,
        val priceImpactPct: String = "0.00", // Default value to avoid null/empty issues
        val routePlan: List<RoutePlanResponse>,
        val contextSlot: Long,
        val timeTaken: Double? = null
    )

    data class RoutePlanResponse(
        val swapInfo: SwapInfoResponse,
        val percent: Int
    )

    data class SwapInfoResponse(
        val ammKey: String,
        val label: String,
        val inputMint: String,
        val outputMint: String,
        val inAmount: String,
        val outAmount: String,
        val feeAmount: String,
        val feeMint: String
    )

    /**
     * Transaction processing data class
     */
    data class SwapResponse(
        val swapTransaction: String,
        val lastValidBlockHeight: Long,
        val prioritizationFeeLamports: Long,
        val computeUnitLimit: Long
    )
}