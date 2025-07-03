/**
 * Custom MakeTransferRequest with versioned transaction support
 * Based on OpenAPI Generator output but customized for app use
 */
@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package org.kin.kinetic.generated.api.model

import org.kin.kinetic.generated.api.model.Commitment
import com.squareup.moshi.Json

/**
 * MakeTransferRequest with comprehensive transaction format support
 *
 * @param commitment
 * @param environment
 * @param index
 * @param mint
 * @param lastValidBlockHeight
 * @param tx
 * @param reference
 * @param isVersioned Indicates if this is a versioned transaction
 * @param asLegacy Forces transaction to be processed as legacy format (overrides isVersioned)
 * @param addressLookupTableAccounts Base58-encoded addresses of lookup tables required for versioned transactions
 */

data class MakeTransferRequest (

    @Json(name = "commitment")
    val commitment: Commitment,

    @Json(name = "environment")
    val environment: kotlin.String,

    @Json(name = "index")
    val index: kotlin.Int,

    @Json(name = "mint")
    val mint: kotlin.String,

    @Json(name = "lastValidBlockHeight")
    val lastValidBlockHeight: kotlin.Int,

    @Json(name = "tx")
    val tx: kotlin.String,

    @Json(name = "reference")
    val reference: kotlin.String? = null,

    /* Indicates if this is a versioned transaction */
    @Json(name = "isVersioned")
    val isVersioned: kotlin.Boolean? = false,

    /* Forces transaction to be processed as legacy format (overrides isVersioned when true) */
    @Json(name = "asLegacy")
    val asLegacy: kotlin.Boolean? = false,

    /* Base58-encoded addresses of lookup tables required for versioned transactions */
    @Json(name = "addressLookupTableAccounts")
    val addressLookupTableAccounts: kotlin.collections.List<kotlin.String>? = null

)