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
    // SOLANA RPC METHODS - PLACEHOLDER FOR FUTURE IMPLEMENTATION
    // =========================================================================

    /**
     * TODO: Get transaction signatures for a specific address using direct Solana RPC
     * This method needs to be implemented with the correct Solana SDK API calls
     * Currently commented out to avoid compilation errors
     */
    // suspend fun getSignaturesForAddress(...) { ... }

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