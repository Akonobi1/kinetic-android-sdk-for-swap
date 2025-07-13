package org.kin.kinetic.solana

//import com.funkatronics.encoders.Encoder

suspend fun PublicKey.isOnCurve() = bytes.isOnCurve()