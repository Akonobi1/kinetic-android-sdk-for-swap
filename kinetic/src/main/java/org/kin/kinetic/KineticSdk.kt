package org.kin.kinetic

import org.kin.kinetic.helpers.getSolanaRPCEndpoint
import com.solana.Solana
import com.solana.networking.HttpNetworkingRouter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kin.kinetic.generated.api.model.*
import org.kin.kinetic.helpers.validateKineticSdkConfig
import org.kin.kinetic.interfaces.*

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

    suspend fun closeAccount(
        account: String,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
    ): Transaction {
        return internal.closeAccount(
            CloseAccountOptions(
                account = account,
                commitment = commitment,
                mint = mint,
                reference = reference
            )
        )
    }

    suspend fun createAccount(
        owner: Keypair,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
    ): Transaction {
        return internal.createAccount(
            CreateAccountOptions(
                owner = owner,
                commitment = commitment,
                mint = mint,
                reference = reference
            )
        )
    }

    suspend fun getAccountInfo(account: String, commitment: Commitment? = null, mint: String? = null): AccountInfo {
        return internal.getAccountInfo(
            GetAccountInfoOptions(
                account = account,
                commitment = commitment,
                mint = mint
            )
        )
    }

    suspend fun getBalance(account: String, commitment: Commitment? = null): BalanceResponse {
        return internal.getBalance(
            GetBalanceOptions(
                account = account,
                commitment = commitment
            )
        )
    }

    fun getExplorerUrl(path: String): String? {
        return internal.appConfig?.environment?.explorer?.replace("{path}", path)
    }

    suspend fun getHistory(account: String, commitment: Commitment? = null, mint: String? = null): List<HistoryResponse> {
        return internal.getHistory(
            GetHistoryOptions(
                account = account,
                commitment = commitment,
                mint = mint
            )
        )
    }

    suspend fun getTokenAccounts(account: String, commitment: Commitment? = null, mint: String? = null): List<String> {
        return internal.getTokenAccounts(
            GetTokenAccountsOptions(
                account = account,
                commitment = commitment,
                mint = mint
            )
        )
    }

    suspend fun getTransaction(signature: String, commitment: Commitment? = null): GetTransactionResponse {
        return internal.getTransaction(
            GetTransactionOptions(
                signature = signature,
                commitment = commitment
            )
        )
    }

    suspend fun makeTransfer(
        amount: String,
        destination: String,
        owner: Keypair,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
        senderCreate: Boolean = false,
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None
    ): Transaction {
        return internal.makeTransfer(
            MakeTransferOptions(
                amount = amount,
                destination = destination,
                owner = owner,
                commitment = commitment,
                mint = mint,
                reference = reference,
                senderCreate = senderCreate,
                type = type
            )
        )
    }

    suspend fun requestAirdrop(
        account: String,
        amount: String? = null,
        commitment: Commitment? = null,
        mint: String? = null
    ): RequestAirdropResponse {
        return internal.requestAirdrop(
            RequestAirdropOptions(
                account = account,
                amount = amount,
                commitment = commitment,
                mint = mint
            )
        )
    }

    // =========================================================================
    // VERSIONED TRANSACTION METHODS - PUBLIC API
    // =========================================================================

    /**
     * Make a versioned transfer transaction
     * Provides versioned transaction benefits while maintaining same reliability as legacy transfers
     * 
     * @param amount Amount to transfer in decimals (e.g. "10.5")
     * @param destination Destination wallet public key
     * @param owner Keypair of the sender
     * @param commitment Transaction commitment level
     * @param mint Token mint address (defaults to app's default mint)
     * @param reference Optional reference for tracking
     * @param senderCreate Whether sender should create destination account if it doesn't exist
     * @param type Transaction type for KIN ecosystem tracking
     * @param addressLookupTableAccounts Optional lookup table addresses for gas optimization
     * @return Transaction result with signature (same as existing makeTransfer)
     */
    suspend fun makeVersionedTransfer(
        amount: String,
        destination: String,
        owner: Keypair,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
        senderCreate: Boolean = false,
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None,
        addressLookupTableAccounts: List<String>? = null
    ): Transaction {
        return internal.makeTransferEnhanced(
            MakeTransferOptions(
                amount = amount,
                destination = destination,
                owner = owner,
                commitment = commitment,
                mint = mint,
                reference = reference,
                senderCreate = senderCreate,
                type = type
            ),
            isVersioned = true,
            addressLookupTableAccounts = addressLookupTableAccounts
        )
    }

    /**
     * Make a transfer with automatic format selection
     * Tries versioned first, falls back to legacy if needed
     * 
     * @param amount Amount to transfer in decimals (e.g. "10.5")
     * @param destination Destination wallet public key
     * @param owner Keypair of the sender
     * @param preferVersioned Whether to prefer versioned transaction format (default: true)
     * @param commitment Transaction commitment level
     * @param mint Token mint address (defaults to app's default mint)
     * @param reference Optional reference for tracking
     * @param senderCreate Whether sender should create destination account if it doesn't exist
     * @param type Transaction type for KIN ecosystem tracking
     * @param addressLookupTableAccounts Optional lookup table addresses for gas optimization
     * @return Transaction result with signature (same as existing makeTransfer)
     */
    suspend fun makeTransferAuto(
        amount: String,
        destination: String,
        owner: Keypair,
        preferVersioned: Boolean = true,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
        senderCreate: Boolean = false,
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None,
        addressLookupTableAccounts: List<String>? = null
    ): Transaction {
        val options = MakeTransferOptions(
            amount = amount,
            destination = destination,
            owner = owner,
            commitment = commitment,
            mint = mint,
            reference = reference,
            senderCreate = senderCreate,
            type = type
        )

        return if (preferVersioned) {
            try {
                // Try versioned first
                internal.makeTransferEnhanced(options, true, addressLookupTableAccounts)
            } catch (versionedError: Exception) {
                // Fallback to legacy
                try {
                    internal.makeTransfer(options)
                } catch (legacyError: Exception) {
                    throw Exception("Both versioned (${versionedError.message}) and legacy (${legacyError.message}) transfers failed")
                }
            }
        } else {
            try {
                // Try legacy first
                internal.makeTransfer(options)
            } catch (legacyError: Exception) {
                // Fallback to versioned
                internal.makeTransferEnhanced(options, true, addressLookupTableAccounts)
            }
        }
    }

    /**
     * Make a transfer with explicit format selection
     * Allows choosing between legacy and versioned transaction formats
     * 
     * @param amount Amount to transfer in decimals (e.g. "10.5")
     * @param destination Destination wallet public key
     * @param owner Keypair of the sender
     * @param isVersioned Whether to use versioned transaction format
     * @param commitment Transaction commitment level
     * @param mint Token mint address (defaults to app's default mint)
     * @param reference Optional reference for tracking
     * @param senderCreate Whether sender should create destination account if it doesn't exist
     * @param type Transaction type for KIN ecosystem tracking
     * @param addressLookupTableAccounts Optional lookup table addresses for gas optimization
     * @return Transaction result with signature (same as existing makeTransfer)
     */
    suspend fun makeTransferWithFormat(
        amount: String,
        destination: String,
        owner: Keypair,
        isVersioned: Boolean = false,
        commitment: Commitment? = null,
        mint: String? = null,
        reference: String? = null,
        senderCreate: Boolean = false,
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None,
        addressLookupTableAccounts: List<String>? = null
    ): Transaction {
        val options = MakeTransferOptions(
            amount = amount,
            destination = destination,
            owner = owner,
            commitment = commitment,
            mint = mint,
            reference = reference,
            senderCreate = senderCreate,
            type = type
        )

        return if (isVersioned) {
            internal.makeTransferEnhanced(options, true, addressLookupTableAccounts)
        } else {
            internal.makeTransfer(options)
        }
    }

    /**
     * Submit a pre-built transaction (e.g., from Jupiter)
     * Handles pre-built transactions from external sources like Jupiter
     * 
     * @param transactionBase64 Base64 encoded transaction
     * @param owner Keypair of the wallet
     * @param isVersioned Whether this is a versioned transaction
     * @param addressLookupTableAccounts Optional lookup table addresses
     * @param commitment Transaction commitment level
     * @return Transaction result with signature
     */
    suspend fun submitPreBuiltTransaction(
        transactionBase64: String,
        owner: Keypair,
        isVersioned: Boolean = false,
        addressLookupTableAccounts: List<String>? = null,
        commitment: Commitment? = null
    ): Transaction {
        return internal.submitPreBuiltTransaction(
            transactionBase64 = transactionBase64,
            owner = owner,
            isVersioned = isVersioned,
            addressLookupTableAccounts = addressLookupTableAccounts,
            commitment = commitment
        )
    }

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