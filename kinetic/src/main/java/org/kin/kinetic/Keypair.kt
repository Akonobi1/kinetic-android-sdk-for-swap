package org.kin.kinetic

import com.google.gson.Gson
import com.solana.core.HotAccount
import com.solana.vendor.TweetNaclFast
import com.solana.vendor.bip32.wallet.DerivableType
import com.solana.vendor.bip32.wallet.SolanaBip44
import com.solana.vendor.bip39.Mnemonic
import com.solana.vendor.bip39.WordCount
import org.bitcoinj.core.Base58
import org.bitcoinj.crypto.MnemonicCode

class Keypair {
    private var keyPair: TweetNaclFast.Signature.KeyPair
    var mnemonic: List<String>? = null
    internal var solanaKeypair: HotAccount

    val publicKey: String
        get() = PublicKey(keyPair.publicKey).toBase58()

    val secretKey: String?
        get() = Base58.encode(keyPair.secretKey)

    val solana: HotAccount
        get() = solanaKeypair

    val solanaPublicKey: com.solana.core.PublicKey
        get() = solanaKeypair.publicKey

    val solanaSecretKey: ByteArray
        get() = keyPair.secretKey

    constructor(secretKey: String) {
        val bytes = Base58.decode(secretKey)
        this.keyPair = TweetNaclFast.Signature.keyPair_fromSecretKey(bytes)
        this.solanaKeypair = HotAccount(this.keyPair.secretKey)
    }

    private constructor(keyPair: TweetNaclFast.Signature.KeyPair) {
        this.keyPair = keyPair
        this.solanaKeypair = HotAccount(keyPair.secretKey)
    }

    companion object {
        fun fromByteArray(byteArray: ByteArray): Keypair {
            return fromSecretKey(Base58.encode(byteArray))
        }

        fun fromMnemonic(mnemonic: List<String>): Keypair {
            return fromMnemonicSet(mnemonic)[0]
        }

        fun fromMnemonicSet(mnemonic: List<String>, from: Int = 0, to: Int = 10): List<Keypair> {
            // Always start with zero as minimum
            val from = if (from < 0) 0 else from
            // Always generate at least 1
            val to = if (to <= from) from + 1 else to

            val seed = MnemonicCode.toSeed(mnemonic, "")
            var keys: List<Keypair> = emptyList()
            for (i in from until to) {
                var kp = derive(seed, i)
                kp.mnemonic = mnemonic
                keys += kp
            }

            return keys
        }

        fun derive(seed: ByteArray, walletIndex: Int): Keypair {
            val privateKey = SolanaBip44().getPrivateKeyFromSeed(seed, DerivableType.BIP44CHANGE, walletIndex.toLong())
            var kp = Keypair(TweetNaclFast.Signature.keyPair_fromSeed(privateKey))
            return kp
        }

        fun fromSecretKey(secretKey: String): Keypair {
            return Keypair(secretKey)
        }

        fun random(): Keypair {
            val mnemonic = generateMnemonic()
            return fromMnemonic(mnemonic)
        }

        // TODO: Implement the 'strength: 128|256' parameter that generates a 12 or 24 word mnemonic
        fun generateMnemonic(): List<String> {
            return Mnemonic(WordCount.COUNT_12).phrase
        }

        fun fromJson(json: String): Keypair {
            val account = Gson().fromJson(json, Keypair::class.java)
            return Keypair(account.secretKey!!)
        }
    }


    fun toJson(): String {
        return Gson().toJson(this)
    }
}