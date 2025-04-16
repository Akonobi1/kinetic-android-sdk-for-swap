package org.kin.kineticdemo

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kin.kinetic.R
import org.kin.kinetic.jupiter.BasicAccountStorage
import org.kin.kinetic.jupiter.ExtendedJupiterSwapService

class SwapActivity : AppCompatActivity() {
    // UI elements
    private lateinit var fromTokenSpinner: Spinner
    private lateinit var toTokenSpinner: Spinner
    private lateinit var amountEditText: EditText
    private lateinit var slippageEditText: EditText
    private lateinit var getQuoteButton: Button
    private lateinit var executeSwapButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var quoteDetailsView: TextView

    // Swap service
    private lateinit var swapService: ExtendedJupiterSwapService

    // Token lists
    private val tokenOptions = listOf("KIN", "USDC", "SOL", "USDT")

    // Current quote details
    private var currentQuoteDetails: Map<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(org.kin.kineticdemo.R.layout.activity_swap)

        // Initialize UI elements
        initializeUI()

        // Initialize swap service
        swapService = ExtendedJupiterSwapService(this)

        // Setup state observation
        setupStateObservation()

        // Initialize swap service in a background coroutine
        // Using Dispatchers.IO for network operations
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Update UI on main thread before network operation
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Initializing swap service..."
                    progressBar.visibility = View.VISIBLE
                }

                // Perform network operation on IO thread
                swapService.initialize()

                // Update UI on main thread after network operation completes
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Ready to swap"
                    progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                // Handle errors on main thread
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Error initializing: ${e.message}"
                    progressBar.visibility = View.GONE
                    Log.e("SwapActivity", "Error initializing Kinetic SDK", e)
                }
            }
        }
    }

    private fun initializeUI() {
        // Find views
        fromTokenSpinner = findViewById(org.kin.kineticdemo.R.id.fromTokenSpinner)
        toTokenSpinner = findViewById(org.kin.kineticdemo.R.id.toTokenSpinner)
        amountEditText = findViewById(org.kin.kineticdemo.R.id.amountEditText)
        slippageEditText = findViewById(org.kin.kineticdemo.R.id.slippageEditText)
        getQuoteButton = findViewById(org.kin.kineticdemo.R.id.getQuoteButton)
        executeSwapButton = findViewById(org.kin.kineticdemo.R.id.executeSwapButton)
        progressBar = findViewById(org.kin.kineticdemo.R.id.progressBar)
        statusTextView = findViewById(org.kin.kineticdemo.R.id.statusTextView)
        quoteDetailsView = findViewById(org.kin.kineticdemo.R.id.quoteDetailsView)

        // Setup spinners
        val tokenAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tokenOptions)
        tokenAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        fromTokenSpinner.adapter = tokenAdapter
        toTokenSpinner.adapter = tokenAdapter

        // Default to KIN -> USDC
        fromTokenSpinner.setSelection(0) // KIN
        toTokenSpinner.setSelection(1) // USDC

        // Default values
        amountEditText.setText("10")
        slippageEditText.setText("1.0")

        // Setup swap direction toggle
        val swapDirectionButton = findViewById<Button>(org.kin.kineticdemo.R.id.swapDirectionButton)
        swapDirectionButton.setOnClickListener {
            val fromPosition = fromTokenSpinner.selectedItemPosition
            val toPosition = toTokenSpinner.selectedItemPosition

            fromTokenSpinner.setSelection(toPosition)
            toTokenSpinner.setSelection(fromPosition)

            // Clear previous quote
            currentQuoteDetails = null
            quoteDetailsView.text = ""
            executeSwapButton.isEnabled = false
        }

        // Setup quote button
        getQuoteButton.setOnClickListener {
            getQuote()
        }

        // Setup execute button
        executeSwapButton.isEnabled = false
        executeSwapButton.setOnClickListener {
            executeSwap()
        }
    }

    private fun setupStateObservation() {
        lifecycleScope.launch {
            swapService.swapState.collectLatest { state ->
                withContext(Dispatchers.Main) {
                    when (state) {
                        is ExtendedJupiterSwapService.SwapState.Idle -> {
                            statusTextView.text = "Ready"
                            progressBar.visibility = View.GONE
                        }
                        is ExtendedJupiterSwapService.SwapState.Initializing -> {
                            statusTextView.text = "Initializing..."
                            progressBar.visibility = View.VISIBLE
                            executeSwapButton.isEnabled = false
                        }
                        is ExtendedJupiterSwapService.SwapState.LoadingQuote -> {
                            statusTextView.text = "Loading quote for ${state.fromToken} -> ${state.toToken}..."
                            progressBar.visibility = View.VISIBLE
                            executeSwapButton.isEnabled = false
                        }
                        is ExtendedJupiterSwapService.SwapState.QuoteReady -> {
                            statusTextView.text = "Quote ready"
                            progressBar.visibility = View.GONE
                            executeSwapButton.isEnabled = true

                            displayQuoteDetails(
                                inputAmount = state.inputAmount,
                                inputToken = state.fromToken,
                                outputAmount = state.outputAmount,
                                outputToken = state.toToken,
                                rate = state.rate,
                                priceImpact = state.priceImpact,
                                networkFee = state.networkFee
                            )
                        }
                        is ExtendedJupiterSwapService.SwapState.Submitting -> {
                            statusTextView.text = "Submitting swap..."
                            progressBar.visibility = View.VISIBLE
                            getQuoteButton.isEnabled = false
                            executeSwapButton.isEnabled = false
                        }
                        is ExtendedJupiterSwapService.SwapState.Success -> {
                            statusTextView.text = "Swap successful! Signature: ${state.signature}"
                            progressBar.visibility = View.GONE
                            getQuoteButton.isEnabled = true
                            executeSwapButton.isEnabled = false
                            Toast.makeText(this@SwapActivity,
                                "Successfully swapped ${state.inputAmount} ${state.inputToken} to ${state.outputAmount} ${state.outputToken}",
                                Toast.LENGTH_LONG).show()
                        }
                        is ExtendedJupiterSwapService.SwapState.Error -> {
                            statusTextView.text = "Error: ${state.message}"
                            progressBar.visibility = View.GONE
                            getQuoteButton.isEnabled = true
                            Toast.makeText(this@SwapActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun getQuote() {
        // Validate inputs
        val fromToken = fromTokenSpinner.selectedItem as String
        val toToken = toTokenSpinner.selectedItem as String

        if (fromToken == toToken) {
            Toast.makeText(this, "Please select different tokens", Toast.LENGTH_SHORT).show()
            return
        }

        val amountStr = amountEditText.text.toString()
        if (amountStr.isEmpty()) {
            Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
            return
        }

        val slippageStr = slippageEditText.text.toString()
        if (slippageStr.isEmpty()) {
            Toast.makeText(this, "Please enter slippage percentage", Toast.LENGTH_SHORT).show()
            return
        }

        // Request quote - using IO dispatcher for network operations
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Update UI before network call (on main thread)
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Getting quote..."
                    progressBar.visibility = View.VISIBLE
                    getQuoteButton.isEnabled = false
                    executeSwapButton.isEnabled = false
                }

                // Network call on IO thread
                val quoteDetails = swapService.getQuoteDetails(
                    fromToken = fromToken,
                    toToken = toToken,
                    amount = amountStr,
                    slippagePercent = slippageStr
                )

                // Update UI after network call (on main thread)
                withContext(Dispatchers.Main) {
                    if (quoteDetails.containsKey("error")) {
                        statusTextView.text = "Error: ${quoteDetails["error"]}"
                        Toast.makeText(this@SwapActivity, quoteDetails["error"], Toast.LENGTH_LONG).show()
                    } else {
                        currentQuoteDetails = quoteDetails
                        statusTextView.text = "Quote received"
                        displayQuoteDetails(
                            inputAmount = amountStr,
                            inputToken = fromToken,
                            outputAmount = quoteDetails["outputAmount"] ?: "0",
                            outputToken = toToken,
                            rate = quoteDetails["rate"] ?: "",
                            priceImpact = quoteDetails["priceImpact"] ?: "0%",
                            networkFee = quoteDetails["networkFee"] ?: "Paid by Kinnected!"
                        )
                        executeSwapButton.isEnabled = true
                    }

                    progressBar.visibility = View.GONE
                    getQuoteButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                    getQuoteButton.isEnabled = true
                    Toast.makeText(this@SwapActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayQuoteDetails(
        inputAmount: String,
        inputToken: String,
        outputAmount: String,
        outputToken: String,
        rate: String,
        priceImpact: String,
        networkFee: String
    ) {
        val details = """
            You will swap: $inputAmount $inputToken
            You will receive: $outputAmount $outputToken
            Rate: $rate
            Price Impact: $priceImpact
            Network Fee: $networkFee
        """.trimIndent()

        quoteDetailsView.text = details
    }

    private fun executeSwap() {
        // Validate inputs and quote
        if (currentQuoteDetails == null) {
            Toast.makeText(this, "Please get a quote first", Toast.LENGTH_SHORT).show()
            return
        }

        val fromToken = fromTokenSpinner.selectedItem as String
        val toToken = toTokenSpinner.selectedItem as String
        val amountStr = amountEditText.text.toString()
        val slippageStr = slippageEditText.text.toString()

        // Execute swap - using IO dispatcher for network operations
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get the wallet keypair
                val storage = BasicAccountStorage(
                    filesDir
                )
                val owner = storage.account()

                val success = swapService.executeSwap(
                    fromToken = fromToken,
                    toToken = toToken,
                    amount = amountStr,
                    slippagePercent = slippageStr,
                    owner = owner,
                    coroutineScope = lifecycleScope
                )

                // Result handling is done through the state flow observation
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE
                    getQuoteButton.isEnabled = true
                    executeSwapButton.isEnabled = true
                    Toast.makeText(this@SwapActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}