package org.kin.kinetic.jupiter

/**
 * Data models for Jupiter Swap API
 */

/**
 * Jupiter quote response
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
    val routePlan: List<RoutePlanItem>,
    val contextSlot: Long,
    val timeTaken: Double? = null
)

/**
 * Route plan item in Jupiter quote
 */
data class RoutePlanItem(
    val swapInfo: SwapInfo,
    val percent: Int
)

/**
 * Swap info in Jupiter route plan
 */
data class SwapInfo(
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
 * Jupiter swap response
 */
data class JupiterSwapResponse(
    val swapTransaction: String,
    val lastValidBlockHeight: Long,
    val prioritizationFeeLamports: Long,
    val computeUnitLimit: Long
)

/**
 * Jupiter token information
 */
data class JupiterToken(
    val address: String,
    val chainId: Int,
    val decimals: Int,
    val name: String,
    val symbol: String,
    val logoURI: String? = null,
    val tags: List<String>? = null
)

/**
 * Result of a swap operation
 */
data class SwapResult(
    val success: Boolean,
    val signature: String? = null,
    val error: String? = null,
    val inputAmount: String? = null,
    val outputAmount: String? = null,
    val inputToken: String? = null,
    val outputToken: String? = null,
    val effectivePrice: Double? = null
)