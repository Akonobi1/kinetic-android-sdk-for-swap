package org.kin.kinetic.jupiter



import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.kin.kinetic.Keypair
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.KineticSdkConfig

/**
 * Improved service class for interacting with Jupiter Swap API
 * Uses KineticJupiterAdapter to preserve Kinetic's fee payer model
 * Supports versioned transactions for improved efficiency
 */
class ExtendedJupiterSwapService(private val context: Context) {
    private val TAG = "JupiterSwapService"

    // State management
    private val _swapState = MutableStateFlow<SwapState>(SwapState.Idle)
    val swapState: StateFlow<SwapState> = _swapState.asStateFlow()

    // Kinetic SDK instance
    private var kinetic: KineticSdk? = null

    // Jupiter adapter
    private var jupiterAdapter: KineticJupiterAdapter? = null

    // Transaction version flag - set to false to use versioned transactions by default
    private var useLegacyTransactions = false

    // Whether to fall back to legacy transactions if versioned ones fail
    private var fallbackToLegacy = true

    // Token mint addresses
    private val TOKEN_MINTS = mapOf(
        "KIN" to "kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6",
        "USDC" to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "SOL" to "So11111111111111111111111111111111111111112",
        "USDT" to "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    )

    private var simplifyRoutes = true
    private var maxRouteHops = 2

    /**
     * Initialize service and SDK
     */
    suspend fun initialize() {
        if (kinetic != null) return

        _swapState.value = SwapState.Initializing

        try {
            // Initialize Kinetic SDK
            kinetic = KineticSdk.setup(
                KineticSdkConfig(
                    endpoint = "https://app.altude.so",
                    environment = "mainnet",
                    index = 170
                )
            )

            // Create Jupiter adapter
            jupiterAdapter = kinetic?.let { KineticJupiterAdapter(it) }

            _swapState.value = SwapState.Idle

            Log.d(TAG, "Kinetic SDK and Jupiter adapter initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Kinetic SDK: ${e.message}", e)
            _swapState.value = SwapState.Error("Initialization failed: ${e.message}")
        }
    }

    /**
     * Set transaction format preferences
     *
     * @param useLegacy Whether to use legacy transactions (default: false)
     * @param fallbackToLegacy Whether to fall back to legacy transactions if versioned ones fail (default: true)
     */
    fun setTransactionFormatPreferences(useLegacy: Boolean = false, fallbackToLegacy: Boolean = true) {
        this.useLegacyTransactions = useLegacy
        this.fallbackToLegacy = fallbackToLegacy
        Log.d(TAG, "Transaction format set to ${if (useLegacy) "legacy" else "versioned"}, fallback=${fallbackToLegacy}")
    }

    /**
     * Get swap quote details with network call on IO dispatcher
     */
    suspend fun getQuoteDetails(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String
    ): Map<String, String> {
        // Make sure we're initialized
        val adapter = jupiterAdapter ?: run {
            initialize()
            jupiterAdapter ?: return mapOf("error" to "Failed to initialize service")
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Getting quote details for $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

                // Resolve token addresses
                val inputMint = resolveTokenAddress(fromToken)
                val outputMint = resolveTokenAddress(toToken)

                // Get quote from adapter
                val quoteResult = adapter.getSwapQuote(
                    fromToken = inputMint,
                    toToken = outputMint,
                    amount = amount,
                    slippagePercent = slippagePercent
                )

                if (quoteResult.isFailure) {
                    val error = quoteResult.exceptionOrNull()?.message ?: "Unknown error getting quote"
                    Log.e(TAG, "Error getting quote: $error")
                    return@withContext mapOf("error" to error)
                }

                // Return the quote details
                quoteResult.getOrThrow()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting quote details: ${e.message}", e)
                mapOf("error" to "Failed to get quote: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun setSwapRouteOptions(
        simplifyRoutes: Boolean = true,
        maxRouteHops: Int = 2
    ) {
        this.simplifyRoutes = simplifyRoutes
        this.maxRouteHops = maxRouteHops
        Log.d(TAG, "Swap route options set: simplify=$simplifyRoutes, maxHops=$maxRouteHops")
    }
    /**
     * Execute swap transaction using Jupiter API and Kinetic SDK with fee payer model preserved
     * Uses versioned transactions for improved efficiency
     */
    suspend fun executeSwap(
        fromToken: String,
        toToken: String,
        amount: String,
        slippagePercent: String,
        owner: Keypair,
        coroutineScope: CoroutineScope
    ): Boolean {
        // Make sure we're initialized
        val adapter = jupiterAdapter ?: run {
            initialize()
            jupiterAdapter ?: run {
                _swapState.value = SwapState.Error("Failed to initialize service")
                return false
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting swap execution: $fromToken -> $toToken, amount: $amount, slippage: $slippagePercent%")

                _swapState.value = SwapState.LoadingQuote(fromToken, toToken, amount)

                // Resolve token addresses
                val inputMint = resolveTokenAddress(fromToken)
                val outputMint = resolveTokenAddress(toToken)

                // First get a quote
                val quoteResult = adapter.getSwapQuote(
                    fromToken = inputMint,
                    toToken = outputMint,
                    amount = amount,
                    slippagePercent = slippagePercent
                )

                if (quoteResult.isFailure) {
                    val error = quoteResult.exceptionOrNull()?.message ?: "Unknown error getting quote"
                    Log.e(TAG, "Error getting quote: $error")
                    _swapState.value = SwapState.Error("Failed to get quote: $error")
                    return@withContext false
                }

                val quoteDetails = quoteResult.getOrThrow()

                _swapState.value = SwapState.QuoteReady(
                    fromToken = fromToken,
                    toToken = toToken,
                    inputAmount = amount,
                    outputAmount = quoteDetails["outputAmount"] ?: "0",
                    rate = quoteDetails["rate"] ?: "",
                    priceImpact = quoteDetails["priceImpact"] ?: "0%",
                    networkFee = quoteDetails["networkFee"] ?: "Paid by Kinnected!"
                )

                // Execute the swap
                _swapState.value = SwapState.Submitting

                // First try with the configured transaction format
                try {
                    // Execute swap transaction
                    val result = adapter.executeJupiterSwap(
                        fromToken = inputMint,
                        toToken = outputMint,
                        amount = amount,
                        slippagePercent = slippagePercent,
                        owner = owner,
                        useLegacyTransaction = useLegacyTransactions,
                        simplifyRoutes = simplifyRoutes,
                        maxRouteHops = maxRouteHops
                    )

                    // Check for signature
                    val signature = result.signature
                    if (signature == null) {
                        Log.e(TAG, "Swap failed - no signature returned")
                        _swapState.value = SwapState.Error("Swap failed - no transaction signature")
                        return@withContext false
                    }

                    Log.d(TAG, "Swap transaction submitted with signature: $signature")

                    // We'll consider the transaction as successful once it's submitted
                    // since Kinetic handles confirmation
                    _swapState.value = SwapState.Success(
                        signature = signature,
                        inputAmount = amount,
                        outputAmount = quoteDetails["outputAmount"] ?: "0",
                        inputToken = fromToken,
                        outputToken = toToken
                    )

                    return@withContext true
                } catch (e: Exception) {
                    // If versioned transaction failed and fallback is enabled, try with legacy
                    if (!useLegacyTransactions && fallbackToLegacy) {
                        Log.w(TAG, "Versioned transaction failed, attempting with legacy transaction: ${e.message}")

                        try {
                            _swapState.value = SwapState.Submitting

                            // Retry with legacy transaction format
                            val fallbackResult = adapter.executeJupiterSwap(
                                fromToken = inputMint,
                                toToken = outputMint,
                                amount = amount,
                                slippagePercent = slippagePercent,
                                owner = owner,
                                useLegacyTransaction = true
                            )

                            // Check for signature
                            val fallbackSignature = fallbackResult.signature
                            if (fallbackSignature == null) {
                                Log.e(TAG, "Fallback swap failed - no signature returned")
                                _swapState.value = SwapState.Error("Fallback swap failed - no transaction signature")
                                return@withContext false
                            }

                            Log.d(TAG, "Fallback swap transaction submitted with signature: $fallbackSignature")

                            // Update state
                            _swapState.value = SwapState.Success(
                                signature = fallbackSignature,
                                inputAmount = amount,
                                outputAmount = quoteDetails["outputAmount"] ?: "0",
                                inputToken = fromToken,
                                outputToken = toToken
                            )

                            return@withContext true
                        } catch (fallbackError: Exception) {
                            Log.e(TAG, "Fallback swap also failed: ${fallbackError.message}")
                            _swapState.value = SwapState.Error("Swap failed: ${fallbackError.message}")
                            return@withContext false
                        }
                    } else {
                        // No fallback, just report the original error
                        Log.e(TAG, "Error executing swap: ${e.message}", e)
                        _swapState.value = SwapState.Error("Swap error: ${e.message}")
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing swap: ${e.message}", e)
                _swapState.value = SwapState.Error("Swap error: ${e.message}")
                return@withContext false
            }
        }
    }

    /**
     * Resolve token symbol to mint address
     */
    private fun resolveTokenAddress(token: String): String {
        // If it looks like an address already, return it
        if (token.length > 30) {
            return token
        }

        // Try to resolve from common tokens
        return TOKEN_MINTS[token.uppercase()] ?: token
    }



    /**
     * Swap states for UI updates
     */
    sealed class SwapState {
        object Idle : SwapState()
        object Initializing : SwapState()

        data class LoadingQuote(
            val fromToken: String,
            val toToken: String,
            val amount: String
        ) : SwapState()

        data class QuoteReady(
            val fromToken: String,
            val toToken: String,
            val inputAmount: String,
            val outputAmount: String,
            val rate: String,
            val priceImpact: String,
            val networkFee: String
        ) : SwapState()

        object Submitting : SwapState()

        data class Success(
            val signature: String,
            val inputAmount: String,
            val outputAmount: String,
            val inputToken: String,
            val outputToken: String
        ) : SwapState()

        data class Error(val message: String) : SwapState()
    }
}