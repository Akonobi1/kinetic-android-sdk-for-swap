package org.kin.kinetic.helpers

import android.util.Base64
import org.kin.kinetic.KinBinaryMemo
import org.kin.kinetic.PublicKey
import com.solana.core.TransactionInstruction

internal fun generateKinMemoInstruction(appIndex: Int, type: KinBinaryMemo.TransactionType): TransactionInstruction {
    val kinMemo = KinBinaryMemo.Builder(appIndex)
        .setTransferType(type)
        .build()
    val encodedMemo = Base64.encodeToString(kinMemo.encode(), 0).toByteArray()
    
    // Define the MEMO program ID directly in the helper function
    val memoV1ProgramId = PublicKey("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
    
    return TransactionInstruction(
        memoV1ProgramId.solanaPublicKey,
        emptyList(),
        encodedMemo
    )
}