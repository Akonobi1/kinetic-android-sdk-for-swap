package org.kin.kinetic.solana

abstract class Ed25519Signer : Signer {
    override val ownerLength = 32
    override val signatureLength = 64
}