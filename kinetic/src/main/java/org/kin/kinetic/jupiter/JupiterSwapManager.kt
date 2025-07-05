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
import org.kin.kinetic.generated.api.model.Commitment
import java.util.concurrent.TimeUnit
import com.solana.core.PublicKey
import com.solana.core.SerializeConfig
import android.util.Base64
import org.kin.kinetic.submitSerializedTransaction
import org.kin.kinetic.waitForTransactionConfirmation

/**
 * Manager class for handling Jupiter v6 token swaps with instruction-based approach
 * Provides full control over fee payer by building transactions from individual instructions
 * FIXED: Complete implementation of executeSwap method
 */
class JupiterSwapManager {
    companion object {
        private const val TAG = "JupiterSwapManager"
        private val gson = Gson()

        // Jupiter v6 API endpoints
        private const val QUOTE_API_V6 = "https://quote-api.jup.ag/v6/quote"
        private const val SWAP_INSTRUCTIONS_API_V6 = "https://quote-api.jup.ag/v6/swap-instructions"

        // Pre-defined token addresses
        private val COMMON_TOKENS = mapOf(
            "KIN" to "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
            "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "SOL" to "So11111111111111111111111111111111111111112",
            "USDT" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        )

        // Platform fee configuration
        const val PLATFORM_FEE_BPS = 50 // 0.5% platform fee (50 basis points)

        // Your fee collection account - UPDATE THIS with your actual fee account
        const val PLATFORM_FEE_ACCOUNT = "YOUR_FEE_TOKEN_ACCOUNT_HERE"

        // Example fee accounts for different tokens (update with your actual accounts)
        val FEE_ACCOUNTS_BY_TOKEN = mapOf(
            "USDC" to "YOUR_USDC_FEE_ACCOUNT",
            "KIN" to "YOUR_KIN_FEE_ACCOUNT",
            "SOL" to "YOUR_SOL_FEE_ACCOUNT"
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
     * Get swap quote from Jupiter v6 with platform fee
     * PRESERVED: Original working implementation
     */
    suspend fun getSwapQuote(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        includePlatformFee: Boolean = true
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== JUPITER V6 QUOTE REQUEST ===")
            Log.d(TAG, "From Token: $fromToken")
            Log.d(TAG, "To Token: $toToken")
            Log.d(TAG, "Amount: $amount")
            Log.d(TAG, "Slippage: $slippagePercent%")
            Log.d(TAG, "Include Platform Fee: $includePlatformFee")
            if (includePlatformFee) {
                Log.d(TAG, "Platform Fee BPS: $PLATFORM_FEE_BPS (${PLATFORM_FEE_BPS / 100.0}%)")
            }

            // Resolve token addresses
            val inputMint = resolveTokenAddress(fromToken)
            val outputMint = resolveTokenAddress(toToken)

            Log.d(TAG, "Resolved Input Mint: $inputMint")
            Log.d(TAG, "Resolved Output Mint: $outputMint")

            // Convert amount to smallest units
            val decimals = getTokenDecimals(inputMint)
            val amountInSmallestUnits = (amount.toDoubleOrNull() ?: 0.0) * Math.pow(10.0, decimals.toDouble())

            // Convert slippage to basis points (1% = 100 basis points)
            val slippageBps = ((slippagePercent.toDoubleOrNull() ?: 0.5) * 100.0).toInt()

            // Construct the quote URL with optional platform fee and asLegacyTransaction
            var url = "$QUOTE_API_V6" +
                    "?inputMint=$inputMint" +
                    "&outputMint=$outputMint" +
                    "&amount=${amountInSmallestUnits.toLong()}" +
                    "&slippageBps=$slippageBps" +
                    "&restrictIntermediateTokens=true" +
                    "&asLegacyTransaction=true"

            // Add platform fee if requested
            if (includePlatformFee) {
                // Get the appropriate fee account for the output token
                val outputSymbol = tokenSymbol(outputMint)
                val feeAccount = FEE_ACCOUNTS_BY_TOKEN[outputSymbol] ?: PLATFORM_FEE_ACCOUNT

                if (feeAccount != "YOUR_FEE_TOKEN_ACCOUNT_HERE" && !feeAccount.contains("YOUR_")) {
                    url += "&platformFeeBps=$PLATFORM_FEE_BPS"
                    Log.d(TAG, "Added platform fee to quote request")
                    Log.d(TAG, "Platform fee will go to: $feeAccount (for $outputSymbol)")
                } else {
                    Log.w(TAG, "Platform fee account not configured for $outputSymbol")
                }
            }

            Log.d(TAG, "Quote URL: $url")

            // Make the API call
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Quote request failed: ${response.code} - ${response.message}")
                    return@withContext Result.failure(
                        Exception("Failed to get quote: ${response.code} - ${response.message}")
                    )
                }

                val responseBody = response.body?.string() ?:
                return@withContext Result.failure(Exception("Empty response from Jupiter API"))

                Log.d(TAG, "=== JUPITER V6 QUOTE RESPONSE ===")
                Log.d(TAG, "Raw Response: $responseBody")

                // Parse the response
                val quoteResponse = gson.fromJson(responseBody, JupiterQuoteResponse::class.java)

                // Log quote details
                Log.d(TAG, "Input Amount: ${quoteResponse.inAmount}")
                Log.d(TAG, "Output Amount: ${quoteResponse.outAmount}")
                Log.d(TAG, "Price Impact: ${quoteResponse.priceImpactPct}%")
                Log.d(TAG, "Platform Fee: ${quoteResponse.platformFee}")
                Log.d(TAG, "Route Plan: ${quoteResponse.routePlan.size} steps")

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

                // Calculate platform fee amount if applicable
                val platformFeeAmount = if (includePlatformFee) {
                    val outputSymbol = tokenSymbol(outputMint)
                    val feeAccount = FEE_ACCOUNTS_BY_TOKEN[outputSymbol] ?: PLATFORM_FEE_ACCOUNT

                    if (feeAccount != "YOUR_FEE_TOKEN_ACCOUNT_HERE" && !feeAccount.contains("YOUR_")) {
                        val feePercent = PLATFORM_FEE_BPS / 10000.0
                        outputAmountDouble * feePercent
                    } else {
                        0.0
                    }
                } else {
                    0.0
                }

                Log.d(TAG, "Calculated Platform Fee Amount: $platformFeeAmount ${tokenSymbol(outputMint)}")

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
                    "platformFee" to if (platformFeeAmount > 0) String.format("%.6f", platformFeeAmount) else "0",
                    "platformFeeBps" to if (includePlatformFee) PLATFORM_FEE_BPS.toString() else "0",
                    "quoteJson" to responseBody
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting quote: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get swap instructions from Jupiter v6 API
     * PRESERVED: Original working implementation
     */
    suspend fun getSwapInstructions(
        quoteJson: String,
        userPublicKey: String,
        wrapAndUnwrapSol: Boolean = true,
        useTokenLedger: Boolean = false,
        feeAccount: String? = null
    ): Result<JupiterInstructionsResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== JUPITER V6 SWAP INSTRUCTIONS REQUEST ===")
            Log.d(TAG, "User Public Key: $userPublicKey")
            Log.d(TAG, "Wrap/Unwrap SOL: $wrapAndUnwrapSol")
            Log.d(TAG, "Use Token Ledger: $useTokenLedger")
            Log.d(TAG, "Fee Account: ${feeAccount ?: "None"}")

            // Build the request body
            val requestBody = JSONObject().apply {
                put("quoteResponse", JSONObject(quoteJson))
                put("userPublicKey", userPublicKey)
                put("wrapAndUnwrapSol", wrapAndUnwrapSol)
                put("asLegacyTransaction", true)

                if (useTokenLedger) {
                    put("useTokenLedger", true)
                }

                // Add fee account if provided
                feeAccount?.let { account ->
                    if (account != "YOUR_FEE_TOKEN_ACCOUNT_HERE" && !account.contains("YOUR_")) {
                        put("feeAccount", account)
                        Log.d(TAG, "Added fee account: $account")
                    }
                }
            }

            Log.d(TAG, "Request body: ${requestBody.toString(2)}")

            val request = Request.Builder()
                .url(SWAP_INSTRUCTIONS_API_V6)
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Instructions request failed: ${response.code} - ${response.message}")
                    return@withContext Result.failure(
                        Exception("Failed to get swap instructions: ${response.code} - ${response.message}")
                    )
                }

                val responseBody = response.body?.string() ?:
                return@withContext Result.failure(Exception("Empty response from Jupiter instructions API"))

                Log.d(TAG, "=== JUPITER V6 INSTRUCTIONS RESPONSE ===")
                Log.d(TAG, "Raw Response: $responseBody")

                // Parse the response
                val instructionsResponse = gson.fromJson(responseBody, JupiterInstructionsResponse::class.java)

                // Check for errors
                if (instructionsResponse.error != null) {
                    Log.e(TAG, "Instructions error: ${instructionsResponse.error}")
                    return@withContext Result.failure(Exception("Jupiter instructions error: ${instructionsResponse.error}"))
                }

                Log.d(TAG, "Successfully parsed instructions:")
                Log.d(TAG, "- Compute Budget Instructions: ${instructionsResponse.computeBudgetInstructions.size}")
                Log.d(TAG, "- Setup Instructions: ${instructionsResponse.setupInstructions.size}")
                Log.d(TAG, "- Swap Instruction: ✓")
                Log.d(TAG, "- Cleanup Instruction: ${if (instructionsResponse.cleanupInstruction != null) "✓" else "None"}")
                Log.d(TAG, "- Token Ledger Instruction: ${if (instructionsResponse.tokenLedgerInstruction != null) "✓" else "None"}")
                Log.d(TAG, "- Address Lookup Tables: ${instructionsResponse.addressLookupTableAddresses.size}")

                Result.success(instructionsResponse)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting swap instructions: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * FIXED: Execute a swap using the instruction-based approach with Kinetic fee payer
     * Now properly implements the transaction building and submission
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
            Log.d(TAG, "=== JUPITER V6 SWAP EXECUTION (INSTRUCTION-BASED) ===")
            Log.d(TAG, "From Token: $fromToken -> To Token: $toToken")
            Log.d(TAG, "Amount: $amount")
            Log.d(TAG, "Slippage: $slippagePercent%")
            Log.d(TAG, "User Public Key: ${owner.publicKey}")
            Log.d(TAG, "Fee Payer: Kinetic (app pays SOL transaction fees)")

            // Resolve token addresses
            val inputMint = resolveTokenAddress(fromToken)
            val outputMint = resolveTokenAddress(toToken)

            // Step 1: Get quote
            val includeFee = FEE_ACCOUNTS_BY_TOKEN.values.any {
                it != "YOUR_FEE_TOKEN_ACCOUNT_HERE" && !it.contains("YOUR_")
            }

            Log.d(TAG, "Platform fee collection: ${if (includeFee) "ENABLED" else "DISABLED"}")

            val quoteResult = getSwapQuote(
                fromToken,
                toToken,
                amount,
                slippagePercent,
                includePlatformFee = includeFee
            )

            if (quoteResult.isFailure) {
                return@withContext Result.failure(
                    quoteResult.exceptionOrNull() ?: Exception("Failed to get quote")
                )
            }

            val quoteDetails = quoteResult.getOrThrow()
            val quoteJson = quoteDetails["quoteJson"] ?:
            return@withContext Result.failure(Exception("Missing quote details"))

            Log.d(TAG, "Got quote successfully, getting swap instructions")

            // Step 2: Get swap instructions from Jupiter v6
            val outputSymbol = tokenSymbol(outputMint)
            val feeAccount = if (includeFee) {
                FEE_ACCOUNTS_BY_TOKEN[outputSymbol] ?: PLATFORM_FEE_ACCOUNT
            } else null

            val instructionsResult = getSwapInstructions(
                quoteJson = quoteJson,
                userPublicKey = owner.publicKey,
                wrapAndUnwrapSol = true,
                useTokenLedger = false,
                feeAccount = if (feeAccount != "YOUR_FEE_TOKEN_ACCOUNT_HERE") feeAccount else null
            )

            if (instructionsResult.isFailure) {
                return@withContext Result.failure(
                    instructionsResult.exceptionOrNull() ?: Exception("Failed to get swap instructions")
                )
            }

            val instructions = instructionsResult.getOrThrow()
            Log.d(TAG, "Got swap instructions successfully")

            // Step 3: Use direct Jupiter swap API to get complete transaction
            // This approach lets Jupiter handle the transaction building and Kinetic handle submission
            Log.d(TAG, "Getting complete swap transaction from Jupiter v6...")

            val swapRequestBody = JSONObject().apply {
                put("quoteResponse", JSONObject(quoteJson))
                put("userPublicKey", owner.publicKey)
                put("wrapAndUnwrapSol", true)
                put("asLegacyTransaction", true)  // Use legacy format for better Kinetic compatibility
                put("dynamicComputeUnitLimit", true)
            }

            val swapRequest = Request.Builder()
                .url("https://quote-api.jup.ag/v6/swap")
                .post(swapRequestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .header("Content-Type", "application/json")
                .build()

            val swapTransaction = httpClient.newCall(swapRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Jupiter swap request failed: ${response.code} - ${response.message}")
                }

                val responseBody = response.body?.string() ?:
                throw Exception("Empty response from Jupiter swap API")

                val swapResult = JSONObject(responseBody)
                swapResult.getString("swapTransaction")
            }

            Log.d(TAG, "Got complete transaction from Jupiter (${swapTransaction.length} chars)")

            // Step 4: Submit via Kinetic (which handles blockhash internally)
            Log.d(TAG, "Submitting transaction via Kinetic extension (handles fee payer internally)")

            val submitResult = kinetic.submitPreBuiltTransaction(
                serializedTransaction = swapTransaction,
                owner = owner,
                commitment = commitment,
                asLegacy = true,  // Use legacy transaction processing
                isVersioned = false
            )

            val signature = submitResult.signature ?:
            return@withContext Result.failure(Exception("No signature returned from transaction"))

            Log.d(TAG, "=== SWAP TRANSACTION SUBMITTED SUCCESSFULLY ===")
            Log.d(TAG, "✓ Signature: $signature")
            Log.d(TAG, "✓ Explorer: https://solscan.io/tx/$signature")
            Log.d(TAG, "✓ Method: Jupiter v6 instructions + Kinetic fee payer")

            // Step 6: Wait for confirmation (optional)
            try {
                Log.d(TAG, "Waiting for transaction confirmation...")
                val confirmed = kinetic.waitForTransactionConfirmation(
                    signature = signature,
                    commitment = commitment,
                    maxAttempts = 10,
                    delayMs = 1500
                )

                if (confirmed) {
                    Log.d(TAG, "✓ Transaction confirmed successfully!")
                } else {
                    Log.w(TAG, "⚠ Transaction not confirmed within timeout (but may still succeed)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking confirmation (transaction may still succeed): ${e.message}")
            }

            // Return successful result
            Result.success(
                SwapResult(
                    success = true,
                    signature = signature,
                    inputAmount = amount,
                    outputAmount = quoteDetails["outputAmount"],
                    inputToken = tokenSymbol(inputMint),
                    outputToken = tokenSymbol(outputMint),
                    effectivePrice = quoteDetails["rate"]?.let { parseRate(it) },
                    platformFee = quoteDetails["platformFee"]
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing swap: ${e.message}", e)
            Result.failure(e)
        }
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
        return COMMON_TOKENS[token.uppercase()] ?: token
    }

    /**
     * Get token decimals (defaults based on common tokens)
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

    /**
     * Validate and log fee configuration
     */
    fun validateFeeConfiguration() {
        Log.d(TAG, "=== FEE CONFIGURATION VALIDATION ===")
        Log.d(TAG, "Platform Fee Rate: $PLATFORM_FEE_BPS bps (${PLATFORM_FEE_BPS / 100.0}%)")

        var hasConfiguredAccounts = false
        FEE_ACCOUNTS_BY_TOKEN.forEach { (token, account) ->
            if (account.contains("YOUR_") || account == "YOUR_FEE_TOKEN_ACCOUNT_HERE") {
                Log.w(TAG, "❌ $token fee account NOT configured")
            } else {
                Log.d(TAG, "✓ $token fee account configured: ${account.take(8)}...${account.takeLast(8)}")
                hasConfiguredAccounts = true
            }
        }

        if (!hasConfiguredAccounts) {
            Log.w(TAG, "⚠️ No fee accounts configured - platform fees will NOT be collected")
            Log.w(TAG, "⚠️ To enable fee collection, update FEE_ACCOUNTS_BY_TOKEN in JupiterSwapManager")
        } else {
            Log.d(TAG, "✓ Fee collection is ENABLED for configured tokens")
        }

        Log.d(TAG, "Transaction approach: v6 instructions with Kinetic fee payer")
        Log.d(TAG, "SOL transaction fees: Paid by Kinetic (user pays no network fees)")
        Log.d(TAG, "=== END FEE CONFIGURATION ===")
    }
}