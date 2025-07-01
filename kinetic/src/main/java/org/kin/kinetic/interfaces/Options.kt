package org.kin.kinetic.interfaces

import org.kin.kinetic.Keypair
import org.kin.kinetic.KinBinaryMemo
import org.kin.kinetic.generated.api.model.Commitment

/**
 * Options for closing an account
 */
data class CloseAccountOptions(
    val account: String,
    val commitment: Commitment? = null,
    val mint: String? = null,
    val reference: String? = null
)

/**
 * Options for creating an account
 */
data class CreateAccountOptions(
    val owner: Keypair,
    val commitment: Commitment? = null,
    val mint: String? = null,
    val reference: String? = null
)

/**
 * Options for getting account info
 */
data class GetAccountInfoOptions(
    val account: String,
    val commitment: Commitment? = null,
    val mint: String? = null
)

/**
 * Options for getting balance
 */
data class GetBalanceOptions(
    val account: String,
    val commitment: Commitment? = null
)

/**
 * Options for getting transaction history
 */
data class GetHistoryOptions(
    val account: String,
    val commitment: Commitment? = null,
    val mint: String? = null
)

/**
 * Options for getting kinetic transaction
 */
data class GetKineticTransactionOptions(
    val reference: String? = null,
    val signature: String? = null
)

/**
 * Options for getting token accounts
 */
data class GetTokenAccountsOptions(
    val account: String,
    val commitment: Commitment? = null,
    val mint: String? = null
)

/**
 * Options for getting transaction
 */
data class GetTransactionOptions(
    val signature: String,
    val commitment: Commitment? = null
)

/**
 * Options for making a transfer
 */
data class MakeTransferOptions(
    val amount: String,
    val destination: String,
    val owner: Keypair,
    val commitment: Commitment? = null,
    val mint: String? = null,
    val reference: String? = null,
    val senderCreate: Boolean? = null,
    val type: KinBinaryMemo.TransactionType? = null
)

/**
 * Destination for batch transfer
 */
data class TransferDestination(
    val amount: String,
    val destination: String
)

/**
 * Options for making a batch transfer
 */
data class MakeTransferBatchOptions(
    val destinations: List<TransferDestination>,
    val owner: Keypair,
    val commitment: Commitment? = null,
    val mint: String? = null,
    val reference: String? = null,
    val type: KinBinaryMemo.TransactionType? = null
)

/**
 * Options for requesting airdrop
 */
data class RequestAirdropOptions(
    val account: String,
    val amount: String? = null,
    val commitment: Commitment? = null,
    val mint: String? = null
)