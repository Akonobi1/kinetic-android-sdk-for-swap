package org.kin.kineticdemo

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import org.kin.kinetic.Keypair
import org.kin.kinetic.KineticSdk
import org.kin.kinetic.LogLevel
import kotlinx.coroutines.*
import org.kin.kinetic.KineticSdkConfig
import org.kin.kinetic.jupiter.BasicAccountStorage



class MainActivity : AppCompatActivity() {
    private lateinit var accountHistoryText: TextView
    private lateinit var airdropButton: Button
    private lateinit var airdropText: TextView
    private lateinit var backupAccountButton: Button
    private lateinit var backupAccountText: TextView
    private lateinit var closeAccountButton: Button
    private lateinit var closeAccountText: TextView
    private lateinit var createAccountButton: Button
    private lateinit var createAccountText: TextView
    private lateinit var getAccountHistoryButton: Button
    private lateinit var getAccountInfoButton: Button
    private lateinit var generateMnemonicButton: Button
    private lateinit var generateMnemonicText: TextView
    private lateinit var getBalanceButton: Button
    private lateinit var getConfigButton: Button
    private lateinit var getTokenAccountsButton: Button
    private lateinit var getTransactionButton: Button
    private lateinit var kinAccountInfoText: TextView
    private lateinit var kinBalanceText: TextView
    private lateinit var makeTransferButton: Button
    private lateinit var makeTransferText: TextView
    //private lateinit var openSwapActivityButton: Button
    private lateinit var serverConfigText: TextView
    private lateinit var tokenAccountsText: TextView
    private lateinit var transactionText: TextView

    private var kinetic: KineticSdk? = null
    private var account: Keypair? = null
    private var storage: BasicAccountStorage? = null

    private val kineticNetworkScope = CoroutineScope(Dispatchers.IO)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        kineticNetworkScope.launch {
            kinetic = KineticSdk.setup(
                KineticSdkConfig(
                    "https://app.altude.so",
                    "mainnet",
                    134,
                    mapOf("kinetic-custom-header" to "Yay nice!"),
                )
            )
            storage = BasicAccountStorage(filesDir)
            account = storage!!.account()

            kinetic!!.logger.collect {
                // Handle logs how you prefer here.
                // Example: print each log from the Kin SDK to the console
                if (it.first == LogLevel.ERROR) {
                    Log.e("KinError", it.second)
                } else {
                    Log.d("KinLogs", it.second)
                }
            }
        }

        // Initialize UI elements
        initializeUiElements()

        // Setup button click listeners
        setupButtonClickListeners()
    }

    private fun initializeUiElements() {
        accountHistoryText = findViewById(R.id.account_history_text)
        airdropButton = findViewById(R.id.airdrop_button)
        airdropText = findViewById(R.id.airdrop_text)
        backupAccountButton = findViewById(R.id.backup_account_button)
        backupAccountText = findViewById(R.id.backup_account_text)
        closeAccountButton = findViewById(R.id.close_account_button)
        closeAccountText = findViewById(R.id.close_account_text)
        createAccountButton = findViewById(R.id.create_account_button)
        createAccountText = findViewById(R.id.create_account_text)
        getAccountHistoryButton = findViewById(R.id.get_account_history_button)
        getAccountInfoButton = findViewById(R.id.get_account_info_button)
        getBalanceButton = findViewById(R.id.get_balance_button)
        getConfigButton = findViewById(R.id.get_config_button)
        getTokenAccountsButton = findViewById(R.id.get_token_accounts_button)
        getTransactionButton = findViewById(R.id.get_transaction_button)
        generateMnemonicButton = findViewById(R.id.generate_mnemonic_button)
        generateMnemonicText = findViewById(R.id.generate_mnemonic_text)
        kinAccountInfoText = findViewById(R.id.kin_account_info_text)
        kinBalanceText = findViewById(R.id.kin_balance_text)
        makeTransferButton = findViewById(R.id.make_transfer_button)
        makeTransferText = findViewById(R.id.make_transfer_text)
        openSwapActivityButton = findViewById(R.id.open_swap_activity_button)
        serverConfigText = findViewById(R.id.server_config_text)
        tokenAccountsText = findViewById(R.id.token_accounts_text)
        transactionText = findViewById(R.id.transaction_text)
    }

    private fun setupButtonClickListeners() {
        // New button to open the Swap Activity
       /* openSwapActivityButton.setOnClickListener {
            val intent = Intent(this, SwapActivity::class.java)
            startActivity(intent)
        }
        */

        backupAccountButton.setOnClickListener {
            kineticNetworkScope.launch {
                val accountJson = account?.toJson()
                if (accountJson != null) {
                    Log.d("StoredAccount", accountJson)
                }

                val res = account?.mnemonic ?: account?.secretKey
                runOnUiThread { backupAccountText.text = res.toString() }
            }
        }

        getConfigButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.init()
                runOnUiThread { serverConfigText.text = res.toString() }
            }
        }

        getAccountInfoButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.getAccountInfo(account!!.publicKey)
                runOnUiThread { kinAccountInfoText.text = res.toString() }
            }
        }

        getBalanceButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.getBalance(account!!.publicKey)
                runOnUiThread { kinBalanceText.text = res.toString() }
            }
        }

        getTokenAccountsButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.getTokenAccounts(account!!.publicKey)
                runOnUiThread { tokenAccountsText.text = res.toString() }
            }
        }

        getAccountHistoryButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.getHistory(account!!.publicKey)
                runOnUiThread { accountHistoryText.text = res.toString() }
            }
        }

        generateMnemonicButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = Keypair.random().mnemonic
                runOnUiThread { generateMnemonicText.text = res.toString() }
            }
        }

        airdropButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.requestAirdrop(account!!.publicKey)
                runOnUiThread { airdropText.text = res.toString() }
            }
        }

        closeAccountButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.closeAccount(account!!.publicKey)
                runOnUiThread { closeAccountText.text = res.toString() }
            }
        }

        createAccountButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.createAccount(owner = account!!)
                runOnUiThread { createAccountText.text = res.toString() }
            }
        }

        makeTransferButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.makeTransfer(
                    "1",
                    "BobQoPqWy5cpFioy1dMTYqNH9WpC39mkAEDJWXECoJ9y",
                    account!!
                )
                runOnUiThread { makeTransferText.text = res.toString() }
            }
        }

        getTransactionButton.setOnClickListener {
            kineticNetworkScope.launch {
                val res = kinetic?.getTransaction("testTXsignature")
                runOnUiThread { transactionText.text = res.toString() }
            }
        }
    }
}