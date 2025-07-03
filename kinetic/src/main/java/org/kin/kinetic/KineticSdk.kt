package org.kin.kinetic

import org.kin.kinetic.helpers.getSolanaRPCEndpoint
import com.solana.Solana
import com.solana.networking.HttpNetworkingRouter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kin.kinetic.generated.api.model.*
import org.kin.kinetic.helpers.validateKineticSdkConfig

class KineticSdk {
    val sdkConfig: KineticSdkConfig
    var solana: Solana? = null
    private var internal: KineticSdkInternal
    val logger: StateFlow<Pair<LogLevel, String>>

    private constructor(
        sdkConfig: KineticSdkConfig,
    ) {
        this.sdkConfig = sdkConfig
        this.internal = KineticSdkInternal(sdkConfig)
        this.logger = this.internal.logger.asStateFlow()
    }

    var config: AppConfig? = null
        get() {
            return this.internal.appConfig
        }

    var endpoint: String = ""
        get() {
            return this.sdkConfig.endpoint
        }

    var solanaRpcEndpoint: String? = null
        get() {
            return this.sdkConfig.solanaRpcEndpoint
        }

    // =========================================================================
    // EXISTING KINETIC METHODS - PRESERVED AS-IS
    // =========================================================================

    suspend fun closeAccount(
        account: String,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
    ): Transaction {
        return internal.closeAccount(
            account,
            commitment,
            mint,
            reference,
        )
    }

    suspend fun createAccount(
        owner: Keypair,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
    ): Transaction {
        return internal.createAccount(
            owner,
            commitment,
            mint,
            reference,
        )
    }

    suspend fun getAccountInfo(account: String, commitment: Commitment? = null, mint: String? = null): AccountInfo {
        return internal.getAccountInfo(account, commitment, mint)
    }

    suspend fun getBalance(account: String, commitment: Commitment? = null): BalanceResponse {
        return internal.getBalance(account, commitment)
    }

    fun getExplorerUrl(path: String): String? {
        return internal.appConfig?.environment?.explorer?.replace("{path}", path)
    }

    suspend fun getHistory(account: String, commitment: Commitment? = null, mint: String? = null): List<HistoryResponse> {
        return internal.getHistory(account, commitment, mint)
    }

    suspend fun getTokenAccounts(account: String, commitment: Commitment? = null, mint: String? = null): List<String> {
        return internal.getTokenAccounts(account, commitment, mint)
    }

    suspend fun getTransaction(signature: String, commitment: Commitment? = null): GetTransactionResponse {
        return internal.getTransaction(signature, commitment)
    }

    suspend fun makeTransfer(
        amount: String,
        destination: String,
        owner: Keypair,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
        senderCreate: Boolean = false,
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None,
        asLegacy: Boolean? = null,
        isVersioned: Boolean? = null,
        addressLookupTableAccounts: List<String>? = null
    ): Transaction {
        return internal.makeTransfer(
            amount = amount,
            destination = destination,
            owner = owner,
            commitment = commitment,
            mint = mint,
            reference = reference,
            senderCreate = senderCreate,
            type = type,
            asLegacy = asLegacy,
            isVersioned = isVersioned,
            addressLookupTableAccounts = addressLookupTableAccounts
        )
    }

    /**
     * Submit a pre-built transaction with explicit format control
     * This allows you to submit Jupiter or other external transactions as either legacy or versioned
     *
     * @param serializedTransaction Base64 encoded transaction
     * @param owner Keypair of the wallet
     * @param commitment Transaction commitment level
     * @param asLegacy Force transaction to be processed as legacy format (overrides isVersioned)
     * @param isVersioned Process transaction as versioned format (ignored if asLegacy is true)
     * @param addressLookupTableAccounts Optional lookup table addresses for versioned transactions
     * @param reference Optional reference for tracking
     * @return Transaction result with signature
     */
    suspend fun submitPreBuiltTransaction(
        serializedTransaction: String,
        owner: Keypair,
        commitment: Commitment? = null,
        asLegacy: Boolean? = null,
        isVersioned: Boolean? = null,
        addressLookupTableAccounts: List<String>? = null,
        reference: String? = null
    ): Transaction {
        return internal.makeTransferFromSerializedTransaction(
            serializedTransaction = serializedTransaction,
            owner = owner,
            commitment = commitment,
            asLegacy = asLegacy ?: false,
            isVersioned = isVersioned ?: false,
            addressLookupTableAccounts = addressLookupTableAccounts,
            reference = reference
        )
    }

    suspend fun requestAirdrop(
        account: String,
        amount: String? = null,
        commitment: Commitment? = null,
        mint: String? = null
    ): RequestAirdropResponse {
        return internal.requestAirdrop(
            account,
            amount,
            commitment,
            mint
        )
    }



    // =========================================================================
    // SOLANA RPC METHODS - DIRECT ACCESS
    // =========================================================================

    /**
     * Get transaction signatures for a specific address using direct Solana RPC
     * Exposes Solana SDK's getSignatureForAddress functionality through KineticSdk
     *
     * @param address Public key of the account to get signatures for
     * @param limit Maximum number of signatures to return (default: 1000)
     * @param before Start searching backwards from this transaction signature
     * @param until Stop searching when this transaction signature is reached
     * @param commitment Commitment level for the query
     * @return List of confirmed signature info objects
     */
    suspend fun getSignaturesForAddress(
        address: String,
        limit: Int = 1000,
        before: String? = null,
        until: String? = null,
        commitment: Commitment = Commitment.confirmed
    ): List<ConfirmedSignatureInfo> {
        val solanaClient = solana ?: throw IllegalStateException("Solana client not initialized. Call init() first.")
        
        return try {
            val publicKey = com.solana.core.PublicKey(address)
            
            // Convert Kinetic Commitment to Solana Commitment
            val solanaCommitment = when (commitment) {
                Commitment.processed -> com.solana.core.Commitment.PROCESSED
                Commitment.confirmed -> com.solana.core.Commitment.CONFIRMED
                Commitment.finalized -> com.solana.core.Commitment.FINALIZED
            }
            
            // Create the request options
            val options = mutableMapOf<String, Any>().apply {
                put("limit", limit)
                if (before != null) put("before", before)
                if (until != null) put("until", until)
                put("commitment", solanaCommitment.value)
            }
            
            // Call Solana SDK's getSignaturesForAddress method
            val signatures = solanaClient.api.getSignaturesForAddress(publicKey, options)
            
            // Convert Solana SDK response to KineticSDK model
            signatures.map { solanaSignature ->
                ConfirmedSignatureInfo(
                    signature = solanaSignature.signature,
                    slot = solanaSignature.slot,
                    err = solanaSignature.err?.toString(),
                    memo = solanaSignature.memo,
                    blockTime = solanaSignature.blockTime
                )
            }
        } catch (e: Exception) {
            throw Exception("Failed to get signatures for address $address: ${e.message}")
        }
    }

    /**
     * Get transaction signatures for a specific address with direct Solana commitment
     * For when you want to use Solana SDK commitment types directly
     *
     * @param address Public key of the account to get signatures for
     * @param limit Maximum number of signatures to return (default: 1000)
     * @param before Start searching backwards from this transaction signature
     * @param until Stop searching when this transaction signature is reached
     * @param commitment Solana commitment level for the query
     * @return List of confirmed signature info objects
     */
    suspend fun getSignaturesForAddress(
        address: String,
        limit: Int = 1000,
        before: String? = null,
        until: String? = null,
        commitment: com.solana.core.Commitment
    ): List<ConfirmedSignatureInfo> {
        val solanaClient = solana ?: throw IllegalStateException("Solana client not initialized. Call init() first.")
        
        return try {
            val publicKey = com.solana.core.PublicKey(address)
            
            // Create the request options
            val options = mutableMapOf<String, Any>().apply {
                put("limit", limit)
                if (before != null) put("before", before)
                if (until != null) put("until", until)
                put("commitment", commitment.value)
            }
            
            // Call Solana SDK's getSignaturesForAddress method
            val signatures = solanaClient.api.getSignaturesForAddress(publicKey, options)
            
            // Convert Solana SDK response to KineticSDK model
            signatures.map { solanaSignature ->
                ConfirmedSignatureInfo(
                    signature = solanaSignature.signature,
                    slot = solanaSignature.slot,
                    err = solanaSignature.err?.toString(),
                    memo = solanaSignature.memo,
                    blockTime = solanaSignature.blockTime
                )
            }
        } catch (e: Exception) {
            throw Exception("Failed to get signatures for address $address: ${e.message}")
        }
    }

    /**
     * Get recent transaction signatures for an address (convenience method)
     * Gets the most recent signatures without pagination parameters
     *
     * @param address Public key of the account to get signatures for
     * @param limit Maximum number of recent signatures to return (default: 50)
     * @param commitment Commitment level for the query
     * @return List of recent confirmed signature info objects
     */
    suspend fun getRecentSignaturesForAddress(
        address: String,
        limit: Int = 50,
        commitment: Commitment = Commitment.confirmed
    ): List<ConfirmedSignatureInfo> {
        return getSignaturesForAddress(
            address = address,
            limit = limit,
            before = null,
            until = null,
            commitment = commitment
        )
    }

    // =========================================================================
    // INITIALIZATION
    // =========================================================================

    suspend fun init(): AppConfig {
        val config = internal.getAppConfig(sdkConfig.environment, sdkConfig.index)
        val rpcEndpoint = if (sdkConfig.solanaRpcEndpoint != null) getSolanaRPCEndpoint(sdkConfig.solanaRpcEndpoint)
            else getSolanaRPCEndpoint(config.environment.cluster.endpoint)
        val networkingRouter = HttpNetworkingRouter(rpcEndpoint)
        solana = Solana(networkingRouter)
        return config
    }

    companion object {
        suspend fun setup(
            sdkConfig: KineticSdkConfig,
        ): KineticSdk {
            var sdk = KineticSdk(validateKineticSdkConfig(sdkConfig))
            sdk.init()
            return sdk
        }
    }
}