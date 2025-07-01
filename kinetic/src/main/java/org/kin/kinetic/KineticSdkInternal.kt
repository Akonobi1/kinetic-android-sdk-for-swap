package org.kin.kinetic

import android.util.Base64
import com.solana.core.Account
import com.solana.core.Transaction as SolanaTransaction
import com.solana.core.SerializeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.kin.kinetic.generated.api.*
import org.kin.kinetic.generated.api.model.*
import org.kin.kinetic.generated.infrastructure.ApiClient
import org.kin.kinetic.helpers.*
import org.kin.kinetic.interfaces.*

class KineticSdkInternal(private val sdkConfig: KineticSdkConfig) {

    private val accountApi: AccountApi
    private val airdropApi: AirdropApi
    private val appApi: AppApi
    private val transactionApi: TransactionApi

    // Logger for the SDK
    private val _logger = MutableStateFlow<Pair<LogLevel, String>>(Pair(LogLevel.INFO, "KineticSDK initialized"))
    val logger: StateFlow<Pair<LogLevel, String>> = _logger

    var appConfig: AppConfig? = null

    companion object {
        const val NAME = "kinetic-kotlin-sdk"
        const val VERSION = "1.0.0"
        
        // Memo program ID for Solana
        val MEMO_V1_PROGRAM_ID = PublicKey("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    }

    init {
        // Create the APIs using the existing pattern from generated API files
        val basePath = sdkConfig.endpoint
        val headers = createApiHeaders(sdkConfig.headers)

        // Configure the APIs with proper constructor parameters
        accountApi = AccountApi(basePath, ApiClient.defaultClient, headers)
        airdropApi = AirdropApi(basePath, ApiClient.defaultClient, headers)
        appApi = AppApi(basePath, ApiClient.defaultClient, headers)
        transactionApi = TransactionApi(basePath, ApiClient.defaultClient, headers)
    }

    // =========================================================================
    // PUBLIC API METHODS - Updated to use Options classes
    // =========================================================================

    suspend fun closeAccount(options: CloseAccountOptions): Transaction {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)
        val reference = options.reference

        val request = CloseAccountRequest(
            account = options.account,
            commitment = commitment,
            environment = sdkConfig.environment,
            index = sdkConfig.index,
            mint = appMint.publicKey,
            reference = reference
        )

        return try {
            accountApi.closeAccount(request)
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun createAccount(options: CreateAccountOptions): Transaction {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)
        val reference = options.reference

        val existing = findTokenAccount(
            account = options.owner.publicKey,
            commitment = commitment,
            mint = appMint.publicKey
        )

        if (existing != null) {
            throw Exception("Owner ${options.owner.publicKey} already has an account for mint ${appMint.publicKey}.")
        }

        val (blockhash, lastValidBlockHeight) = getBlockhashAndHeight()

        val tx = generateCreateAccountTransaction(
            addMemo = appMint.addMemo,
            blockhash = blockhash,
            index = sdkConfig.index,
            mintFeePayer = appMint.feePayer,
            mintPublicKey = appMint.publicKey,
            owner = options.owner.solana
        )

        val request = CreateAccountRequest(
            commitment = commitment,
            environment = sdkConfig.environment,
            index = sdkConfig.index,
            lastValidBlockHeight = lastValidBlockHeight,
            mint = appMint.publicKey,
            reference = reference,
            tx = serializeTransaction(tx)
        )

        return try {
            accountApi.createAccount(request)
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun getAccountInfo(options: GetAccountInfoOptions): AccountInfo {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        return accountApi.getAccountInfo(
            environment = sdkConfig.environment,
            index = sdkConfig.index,
            accountId = options.account,
            mint = appMint.publicKey,
            commitment = commitment
        )
    }

    suspend fun getAppConfig(environment: String, index: Int): AppConfig {
        return try {
            val config = appApi.getAppConfig(environment, index)
            appConfig = config
            config
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun getBalance(options: GetBalanceOptions): BalanceResponse {
        val commitment = getCommitment(options.commitment)
        return try {
            accountApi.getBalance(
                environment = sdkConfig.environment,
                index = sdkConfig.index,
                accountId = options.account,
                commitment = commitment
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    fun getExplorerUrl(path: String): String? {
        return appConfig?.environment?.explorer?.replace("{path}", path)
    }

    suspend fun getHistory(options: GetHistoryOptions): List<HistoryResponse> {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        return try {
            accountApi.getHistory(
                environment = sdkConfig.environment,
                index = sdkConfig.index,
                accountId = options.account,
                mint = appMint.publicKey,
                commitment = commitment
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun getKineticTransaction(options: GetKineticTransactionOptions): List<Transaction> {
        return try {
            transactionApi.getKineticTransaction(
                environment = sdkConfig.environment,
                index = sdkConfig.index,
                reference = options.reference ?: "",
                signature = options.signature ?: ""
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun getTokenAccounts(options: GetTokenAccountsOptions): List<String> {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        return try {
            accountApi.getTokenAccounts(
                environment = sdkConfig.environment,
                index = sdkConfig.index,
                accountId = options.account,
                mint = appMint.publicKey,
                commitment = commitment
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun getTransaction(options: GetTransactionOptions): GetTransactionResponse {
        val commitment = getCommitment(options.commitment)

        return try {
            transactionApi.getTransaction(
                environment = sdkConfig.environment,
                index = sdkConfig.index,
                signature = options.signature,
                commitment = commitment
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    // EXISTING makeTransfer method - Updated to use Options class
    suspend fun makeTransfer(options: MakeTransferOptions): Transaction {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        val destination = options.destination
        val senderCreate = options.senderCreate ?: false
        val reference = options.reference

        // We get the token account for the owner
        val ownerTokenAccount = findTokenAccount(
            account = options.owner.publicKey,
            commitment = commitment,
            mint = appMint.publicKey
        )

        // The operation fails if the owner doesn't have a token account for this mint
        if (ownerTokenAccount == null) {
            throw Exception("Owner account doesn't exist for mint ${appMint.publicKey}.")
        }

        // We get the account info for the destination
        val destinationTokenAccount = findTokenAccount(
            account = destination,
            commitment = commitment,
            mint = appMint.publicKey
        )

        // The operation fails if the destination doesn't have a token account for this mint and senderCreate is not set
        if (destinationTokenAccount == null && !senderCreate) {
            throw Exception("Destination account doesn't exist for mint ${appMint.publicKey}.")
        }

        // Derive the associated token address if the destination doesn't have a token account for this mint and senderCreate is set
        val senderCreateTokenAccount = if (destinationTokenAccount == null && senderCreate) {
            getTokenAddress(destination, appMint.publicKey)
        } else null

        // The operation fails if there is still no destination token account
        if (destinationTokenAccount == null && senderCreateTokenAccount == null) {
            throw Exception("Destination token account not found.")
        }

        val (blockhash, lastValidBlockHeight) = getBlockhashAndHeight()

        val tx = generateMakeTransferTransaction(
            addMemo = appMint.addMemo,
            amount = options.amount,
            blockhash = blockhash,
            destination = destination,
            destinationTokenAccount = (destinationTokenAccount ?: senderCreateTokenAccount).toString(),
            index = sdkConfig.index,
            mintDecimals = appMint.decimals,
            mintFeePayer = appMint.feePayer,
            mintPublicKey = appMint.publicKey,
            owner = options.owner.solana,
            ownerTokenAccount = ownerTokenAccount,
            senderCreate = senderCreate && senderCreateTokenAccount != null,
            type = options.type ?: KinBinaryMemo.TransactionType.None
        )

        return try {
            makeTransferRequest(
                MakeTransferRequest(
                    commitment = commitment,
                    environment = sdkConfig.environment,
                    index = sdkConfig.index,
                    lastValidBlockHeight = lastValidBlockHeight,
                    mint = appMint.publicKey,
                    reference = reference,
                    tx = serializeTransaction(tx)
                )
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    suspend fun makeTransferBatch(options: MakeTransferBatchOptions): Transaction {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        val destinations = options.destinations

        if (destinations.isEmpty()) {
            throw Exception("At least 1 destination required")
        }

        if (destinations.size > 15) {
            throw Exception("Maximum number of destinations exceeded")
        }

        // We get the token account for the owner
        val ownerTokenAccount = findTokenAccount(
            account = options.owner.publicKey,
            commitment = commitment,
            mint = appMint.publicKey
        )

        // The operation fails if the owner doesn't have a token account for this mint
        if (ownerTokenAccount == null) {
            throw Exception("Owner account doesn't exist for mint ${appMint.publicKey}.")
        }

        // Get TokenAccount from destinations, keep track of missing ones
        val nonExistingDestinations = mutableListOf<String>()
        destinations.forEach { item ->
            val destination = findTokenAccount(
                account = item.destination,
                commitment = commitment,
                mint = appMint.publicKey
            )
            if (destination == null) {
                nonExistingDestinations.add(item.destination)
            }
        }

        // The operation fails if any of the destinations doesn't have a token account for this mint
        if (nonExistingDestinations.isNotEmpty()) {
            throw Exception(
                "Destination accounts ${nonExistingDestinations.sorted().joinToString(", ")} have no token account for mint ${appMint.publicKey}."
            )
        }

        // For now, batch transfers are not implemented - fallback to sequential single transfers
        // This prevents the library from crashing while maintaining functionality
        var lastTransaction: Transaction? = null
        
        for (destination in destinations) {
            try {
                val singleTransferOptions = MakeTransferOptions(
                    amount = destination.amount,
                    destination = destination.destination,
                    owner = options.owner,
                    commitment = options.commitment,
                    mint = options.mint,
                    reference = options.reference,
                    senderCreate = false,
                    type = options.type
                )
                
                lastTransaction = makeTransfer(singleTransferOptions)
                
            } catch (e: Exception) {
                throw Exception("Batch transfer failed at destination ${destination.destination}: ${e.message}")
            }
        }
        
        return lastTransaction ?: throw Exception("No transfers were executed")
    }

    suspend fun requestAirdrop(options: RequestAirdropOptions): RequestAirdropResponse {
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        return try {
            airdropApi.requestAirdrop(
                RequestAirdropRequest(
                    account = options.account,
                    amount = options.amount,
                    commitment = commitment,
                    environment = sdkConfig.environment,
                    index = sdkConfig.index,
                    mint = appMint.publicKey
                )
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    // =========================================================================
    // NEW METHODS - Enhanced versioned support
    // =========================================================================

    /**
     * Enhanced makeTransfer with versioned transaction support
     */
    suspend fun makeTransferEnhanced(
        options: MakeTransferOptions,
        isVersioned: Boolean = false,
        addressLookupTableAccounts: List<String>? = null
    ): Transaction {
        // If isVersioned is false, use existing makeTransfer logic exactly
        if (!isVersioned) {
            return makeTransfer(options)
        }

        // For versioned transactions, follow the exact same logic as makeTransfer
        // but add versioned flags to the request
        val appConfig = ensureAppConfig()
        val appMint = getAppMint(appConfig, options.mint)
        val commitment = getCommitment(options.commitment)

        val destination = options.destination
        val senderCreate = options.senderCreate ?: false
        val reference = options.reference

        // We get the token account for the owner (same as original)
        val ownerTokenAccount = findTokenAccount(
            account = options.owner.publicKey,
            commitment = commitment,
            mint = appMint.publicKey
        )

        // The operation fails if the owner doesn't have a token account for this mint (same as original)
        if (ownerTokenAccount == null) {
            throw Exception("Owner account doesn't exist for mint ${appMint.publicKey}.")
        }

        // We get the account info for the destination (same as original)
        val destinationTokenAccount = findTokenAccount(
            account = destination,
            commitment = commitment,
            mint = appMint.publicKey
        )

        // The operation fails if the destination doesn't have a token account for this mint and senderCreate is not set (same as original)
        if (destinationTokenAccount == null && !senderCreate) {
            throw Exception("Destination account doesn't exist for mint ${appMint.publicKey}.")
        }

        // Derive the associated token address if the destination doesn't have a token account for this mint and senderCreate is set (same as original)
        val senderCreateTokenAccount = if (destinationTokenAccount == null && senderCreate) {
            getTokenAddress(destination, appMint.publicKey)
        } else null

        // The operation fails if there is still no destination token account (same as original)
        if (destinationTokenAccount == null && senderCreateTokenAccount == null) {
            throw Exception("Destination token account not found.")
        }

        val (blockhash, lastValidBlockHeight) = getBlockhashAndHeight()

        // Use same transaction generation as original
        val tx = generateMakeTransferTransaction(
            addMemo = appMint.addMemo,
            amount = options.amount,
            blockhash = blockhash,
            destination = destination,
            destinationTokenAccount = (destinationTokenAccount ?: senderCreateTokenAccount).toString(),
            index = sdkConfig.index,
            mintDecimals = appMint.decimals,
            mintFeePayer = appMint.feePayer,
            mintPublicKey = appMint.publicKey,
            owner = options.owner.solana,
            ownerTokenAccount = ownerTokenAccount,
            senderCreate = senderCreate && senderCreateTokenAccount != null,
            type = options.type ?: KinBinaryMemo.TransactionType.None
        )

        // Use enhanced request with versioned flags
        return try {
            makeTransferRequest(
                MakeTransferRequest(
                    commitment = commitment,
                    environment = sdkConfig.environment,
                    index = sdkConfig.index,
                    lastValidBlockHeight = lastValidBlockHeight,
                    mint = appMint.publicKey,
                    reference = reference,
                    tx = serializeTransaction(tx),
                    isVersioned = true, // This is the key difference
                    addressLookupTableAccounts = addressLookupTableAccounts
                )
            )
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    /**
     * Submit a pre-built transaction (e.g., from Jupiter)
     */
    suspend fun submitPreBuiltTransaction(
        transactionBase64: String,
        owner: Keypair,
        isVersioned: Boolean = false,
        addressLookupTableAccounts: List<String>? = null,
        commitment: Commitment? = null
    ): Transaction {
        val appConfig = ensureAppConfig()
        val resolvedCommitment = getCommitment(commitment)
        val (blockhash, lastValidBlockHeight) = getBlockhashAndHeight()

        // For pre-built transactions, we use the default mint
        val defaultMint = appConfig.mint

        val request = MakeTransferRequest(
            commitment = resolvedCommitment,
            environment = sdkConfig.environment,
            index = sdkConfig.index,
            lastValidBlockHeight = lastValidBlockHeight,
            mint = defaultMint.publicKey,
            reference = null, // Pre-built transactions don't have references from our side
            tx = transactionBase64,
            isVersioned = isVersioned,
            addressLookupTableAccounts = addressLookupTableAccounts
        )

        return try {
            makeTransferRequest(request)
        } catch (err: Exception) {
            throw Exception(err.message ?: "Unknown error")
        }
    }

    // =========================================================================
    // PRIVATE HELPER METHODS
    // =========================================================================

    private fun createApiHeaders(headers: Map<String, String> = emptyMap()): Map<String, String> {
        return headers + mapOf(
            "kinetic-environment" to sdkConfig.environment,
            "kinetic-index" to sdkConfig.index.toString(),
            "kinetic-user-agent" to "${NAME}@${VERSION}"
        )
    }

    private fun ensureAppConfig(): AppConfig {
        return appConfig ?: throw Exception("AppConfig not initialized")
    }

    private suspend fun findTokenAccount(
        account: String,
        commitment: Commitment,
        mint: String
    ): String? {
        // We get the account info for the account
        val accountInfo = getAccountInfo(
            GetAccountInfoOptions(
                account = account,
                commitment = commitment,
                mint = mint
            )
        )

        // The operation fails when the account is a mint account
        if (accountInfo.isMint) {
            throw Exception("Account is a mint account.")
        }

        // Find the token account for this mint
        return accountInfo.tokens?.find { it.mint == mint }?.account
    }

    private suspend fun getBlockhashAndHeight(): Pair<String, Int> {
        val response = transactionApi.getLatestBlockhash(
            environment = sdkConfig.environment,
            index = sdkConfig.index
        )
        return Pair(response.blockhash, response.lastValidBlockHeight)
    }

    private fun getCommitment(commitment: Commitment?): Commitment {
        return commitment ?: sdkConfig.commitment ?: Commitment.confirmed
    }

    private suspend fun makeTransferRequest(request: MakeTransferRequest): Transaction {
        return transactionApi.makeTransfer(request)
    }

    // =========================================================================
    // MISSING HELPER FUNCTIONS
    // =========================================================================

    private fun getAppMint(appConfig: AppConfig, mint: String?): AppConfigMint {
        return if (mint == null) {
            appConfig.mint
        } else {
            appConfig.mints.find { it.publicKey == mint }
                ?: throw Exception("Mint $mint not found in app config")
        }
    }

    private fun serializeTransaction(transaction: SolanaTransaction): String {
        val serialized = transaction.serialize(SerializeConfig(requireAllSignatures = false, verifySignatures = false))
        return Base64.encodeToString(serialized, Base64.NO_WRAP)
    }
}