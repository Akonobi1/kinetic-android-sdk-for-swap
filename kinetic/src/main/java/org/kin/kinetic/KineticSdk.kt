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
        type: KinBinaryMemo.TransactionType = KinBinaryMemo.TransactionType.None
    ): Transaction {
        return internal.makeTransfer(
            amount,
            destination,
            owner,
            commitment,
            mint,
            reference,
            senderCreate,
            type
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