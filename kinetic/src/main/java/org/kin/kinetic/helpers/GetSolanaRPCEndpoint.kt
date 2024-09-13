package org.kin.kinetic.helpers

import com.solana.networking.Network
import com.solana.networking.RPCEndpoint
import java.net.URI
import java.net.URL


fun getSolanaRPCEndpoint(endpoint: String): RPCEndpoint {
    return when (endpoint) {
        "devnet" -> RPCEndpoint.devnetSolana
        "testnet" -> RPCEndpoint.testnetSolana
        "mainnet", "mainnet-beta" -> RPCEndpoint.mainnetBetaSolana
        else -> {
            val webSocketString = endpoint.replace("https", "wss").replace("http", "wss")

            // Use URI for validation since it supports wss://
            val httpEndpoint = URL(endpoint) // This works fine for http/https
            val webSocketURI = URI(webSocketString) // Validate the wss:// using URI

            // Convert the WebSocket URI back to a URL (if needed) while bypassing URL checks
            val webSocketURL = URL(webSocketURI.toString())

            return RPCEndpoint.custom(httpEndpoint, webSocketURL, Network.mainnetBeta)
        }
    }
}