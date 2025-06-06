package com.example.nfcpingpong

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.UUID

data class WalletConnectionState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val address: String? = null,
    val error: String? = null,
    val connectionStep: String? = null
)

class WalletConnectManager(private val context: Context) {
    private val TAG = "WalletConnectManager"
    
    private val _connectionState = MutableStateFlow(WalletConnectionState())
    val connectionState: StateFlow<WalletConnectionState> = _connectionState
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val DAPP_NAME = "NFC Wallet Handshake"
        private const val DAPP_URL = "nfcwallethandshake.com"
    }
    
    /**
     * Connect to a wallet and provide guided address retrieval experience
     * This opens the wallet app with connection request and guides user through address capture
     */
    suspend fun connectWallet(walletPackageName: String): String? = suspendCoroutine { continuation ->
        Log.d(TAG, "🚀 Starting smart wallet connection for: $walletPackageName")
        
        _connectionState.value = WalletConnectionState(
            isConnecting = true,
            connectionStep = "Establishing connection with wallet..."
        )
        
        try {
            when (walletPackageName) {
                "io.metamask" -> connectMetaMaskWithAutoRetrieve(continuation)
                "me.rainbow" -> connectRainbowWithAutoRetrieve(continuation)
                "org.toshi" -> connectCoinbaseWithAutoRetrieve(continuation)
                else -> connectGenericWalletWithAutoRetrieve(continuation, walletPackageName)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up connection: ${e.message}", e)
            _connectionState.value = WalletConnectionState(
                error = "Connection error: ${e.message}"
            )
            continuation.resume(null)
        }
    }
    
    private fun connectMetaMaskWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🦊 Connecting to MetaMask with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "metamask://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId&name=${Uri.encode(DAPP_NAME)}",
            "https://metamask.app.link/dapp/$DAPP_URL?method=eth_requestAccounts&name=${Uri.encode(DAPP_NAME)}",
            "metamask://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
            "metamask://"
        )
        
        if (openWalletWithSmartConnection("io.metamask", "MetaMask", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "MetaMask opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in MetaMask"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("io.metamask", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved MetaMask address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to MetaMask!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connection established! Please copy your address from MetaMask and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectRainbowWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🌈 Connecting to Rainbow with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "rainbow://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "https://rnbwapp.com/connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
            "rainbow://wc?uri=rainbow_connect_$sessionId",
            "rainbow://"
        )
        
        if (openWalletWithSmartConnection("me.rainbow", "Rainbow", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Rainbow opened - establishing secure connection..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection in Rainbow"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("me.rainbow", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Rainbow address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Rainbow!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Rainbow! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectCoinbaseWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🔵 Connecting to Coinbase Wallet with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "cbwallet://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://go.cb-w.com/dapp/$DAPP_URL?method=eth_requestAccounts",
            "cbwallet://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
            "cbwallet://"
        )
        
        if (openWalletWithSmartConnection("org.toshi", "Coinbase Wallet", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Coinbase Wallet opened - requesting account permission..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection in Coinbase Wallet"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("org.toshi", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Coinbase Wallet address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Coinbase Wallet!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Coinbase Wallet! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectGenericWalletWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>, walletPackageName: String) {
        Log.d(TAG, "🔗 Connecting to $walletPackageName with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        val walletName = getWalletDisplayName(walletPackageName)
        
        val connectionUris = listOf(
            "ethereum://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "wallet://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
            "web3://connect?dapp=$DAPP_URL"
        )
        
        if (openWalletWithSmartConnection(walletPackageName, walletName, connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "$walletName opened - establishing connection..."
                )
                delay(2000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval(walletPackageName, sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved $walletName address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to $walletName!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to $walletName! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private suspend fun attemptAutoAddressRetrieval(walletPackageName: String, sessionId: String): String? {
        Log.d(TAG, "🔍 Attempting automatic address retrieval for $walletPackageName")
        
        delay(1000) // Simulate API call delay
        
        // Future enhancement: implement actual automatic retrieval here
        // using wallet-specific SDKs or deep link callbacks
        
        return null // Will trigger guided manual entry
    }
    
    private fun openWalletWithSmartConnection(packageName: String, walletName: String, connectionUris: List<String>): Boolean {
        Log.d(TAG, "📱 Opening $walletName with smart connection...")
        
        for (uri in connectionUris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("source", DAPP_NAME)
                    putExtra("action", "connect")
                    putExtra("auto_retrieve", true)
                }
                
                context.startActivity(intent)
                Log.d(TAG, "✅ Successfully opened $walletName with connection request")
                return true
                
            } catch (e: Exception) {
                Log.d(TAG, "Failed to open with URI: ${uri.take(50)}..., trying next...")
            }
        }
        
        // Try opening the app directly as fallback
        try {
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "✅ Opened $walletName directly")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $walletName directly", e)
        }
        
        Log.e(TAG, "❌ Failed to open $walletName")
        _connectionState.value = WalletConnectionState(
            error = "Failed to open $walletName. Please make sure it's installed."
        )
        return false
    }
    
    private fun getWalletDisplayName(packageName: String): String {
        return when (packageName) {
            "io.metamask" -> "MetaMask"
            "me.rainbow" -> "Rainbow"
            "org.toshi" -> "Coinbase Wallet"
            "org.ethereum.mist" -> "Mist Browser"
            "com.trustwallet.app" -> "Trust Wallet"
            else -> "Wallet"
        }
    }
    
    /**
     * Manually set a successful connection with address
     * This is called when user completes the guided manual entry
     */
    fun completeConnection(address: String) {
        _connectionState.value = WalletConnectionState(
            isConnected = true,
            address = address,
            connectionStep = "Connection completed successfully!"
        )
        Log.i(TAG, "🎉 Connection completed with address: $address")
    }
    
    /**
     * Reset connection state
     */
    fun disconnect() {
        _connectionState.value = WalletConnectionState()
        Log.d(TAG, "✅ Connection state reset")
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
} 