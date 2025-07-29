package org.freepay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.UUID
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.app.Activity
import android.content.pm.PackageManager

data class WalletConnectionState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val address: String? = null,
    val error: String? = null,
    val connectionStep: String? = null
)

class WalletConnectManager(private val context: Context) : DefaultLifecycleObserver {
    private val TAG = "WalletConnectManager"
    
    private val _connectionState = MutableStateFlow(WalletConnectionState())
    val connectionState: StateFlow<WalletConnectionState> = _connectionState
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Map to track pending address requests
    private val pendingRequests = mutableMapOf<String, kotlin.coroutines.Continuation<String?>>()
    
    // Track app foreground state
    private var isAppInForeground = true
    private var lastClipboardCheck = 0L
    private var pendingClipboardCheck: String? = null
    private var clipboardPrimed = false
    
    // Broadcast receiver for wallet responses
    private val walletResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleWalletResponse(intent)
        }
    }
    
    companion object {
        private const val DAPP_NAME = "NFC Wallet Handshake"
        private const val DAPP_URL = "nfcwallethandshake.com"
        
        // Broadcast actions for wallet responses
        private const val ACTION_WALLET_ADDRESS_RESPONSE = "org.freepay.WALLET_ADDRESS_RESPONSE"
        private const val EXTRA_WALLET_ADDRESS = "wallet_address"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_SUCCESS = "success"
        
        // Standard Web3 intent action
        private const val ACTION_GET_ADDRESS = "com.web3.WALLET_GET_ADDRESS"
        private const val EXTRA_CHAIN_ID = "chain_id"
        private const val EXTRA_REQUESTING_APP = "requesting_app"
        private const val EXTRA_CALLBACK_ACTION = "callback_action"
    }
    
    init {
        // Register broadcast receiver for wallet responses
        val filter = IntentFilter().apply {
            addAction(ACTION_WALLET_ADDRESS_RESPONSE)
            addAction(ACTION_GET_ADDRESS + ".RESPONSE")
        }
        try {
            context.registerReceiver(walletResponseReceiver, filter)
            Log.d(TAG, "✅ Registered wallet response broadcast receiver")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register broadcast receiver: ${e.message}")
        }
        
        // Register lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    
    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "📱 App returned to foreground")
        isAppInForeground = true
        
        // Check clipboard immediately when app returns to foreground
        pendingClipboardCheck?.let { sessionId ->
            scope.launch {
                checkClipboardImmediate(sessionId)
            }
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "📱 App went to background")
        isAppInForeground = false
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
                "com.debank.rabbymobile" -> connectRabbyWithAutoRetrieve(continuation)
                "app.phantom" -> connectPhantomWithAutoRetrieve(continuation)
                "com.daimo" -> connectDaimoWithAutoRetrieve(continuation)
                "com.railway.rtp" -> connectRailwayWithAutoRetrieve(continuation)
                "com.polybaselabs.wallet" -> connectPayyWithAutoRetrieve(continuation)
                "money.stables" -> connectStablesWithAutoRetrieve(continuation)
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
        
        scope.launch {
            // First try standard Web3 intent
            _connectionState.value = WalletConnectionState(
                isConnecting = true,
                connectionStep = "Attempting to connect to MetaMask..."
            )
            
            val web3Address = tryStandardWeb3Intent("io.metamask", sessionId)
            if (web3Address != null) {
                Log.i(TAG, "🎉 Successfully retrieved MetaMask address via Web3 intent!")
                continuation.resume(web3Address)
                return@launch
            }
            
            // Fall back to opening wallet with clipboard monitoring
            val connectionUris = listOf(
                "metamask://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId&name=${Uri.encode(DAPP_NAME)}",
                "https://metamask.app.link/dapp/$DAPP_URL?method=eth_requestAccounts&name=${Uri.encode(DAPP_NAME)}",
                "metamask://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
                "metamask://"
            )
            
            if (openWalletWithSmartConnection("io.metamask", "MetaMask", connectionUris)) {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Opening MetaMask to your portfolio view..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "💡 In MetaMask: tap your account name/address and copy it. Then switch back to this app!"
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
            } else {
                continuation.resume(null)
            }
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
    
    private fun connectRabbyWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🐰 Connecting to Rabby Wallet with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "rabby://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://rabby.io/dapp/$DAPP_URL?method=eth_requestAccounts",
            "rabby://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "ethereum://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "rabby://"
        )
        
        if (openWalletWithSmartConnection("com.debank.rabbymobile", "Rabby Wallet", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Rabby Wallet opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in Rabby Wallet"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("com.debank.rabbymobile", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Rabby Wallet address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Rabby Wallet!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Rabby Wallet! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectPhantomWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "👻 Connecting to Phantom Wallet with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "phantom://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://phantom.app/ul/dapp/$DAPP_URL?method=eth_requestAccounts",
            "phantom://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "phantom://"
        )
        
        if (openWalletWithSmartConnection("app.phantom", "Phantom Wallet", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Phantom Wallet opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in Phantom Wallet"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("app.phantom", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Phantom Wallet address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Phantom Wallet!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Phantom Wallet! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectDaimoWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "💰 Connecting to Daimo with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        scope.launch {
            // First try standard Web3 intent
            _connectionState.value = WalletConnectionState(
                isConnecting = true,
                connectionStep = "Attempting to connect to Daimo..."
            )
            
            val web3Address = tryStandardWeb3Intent("com.daimo", sessionId)
            if (web3Address != null) {
                Log.i(TAG, "🎉 Successfully retrieved Daimo address via Web3 intent!")
                continuation.resume(web3Address)
                return@launch
            }
            
            // For Daimo, just open the app normally without deep links
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage("com.daimo")
            
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "✅ Opened Daimo directly")
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Daimo opened - please navigate to your account..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "💡 In Daimo: copy your wallet address and return to this app"
                )
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("com.daimo", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Daimo address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Daimo!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Daimo! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            } else {
                Log.e(TAG, "❌ Failed to open Daimo")
                _connectionState.value = WalletConnectionState(
                    error = "Failed to open Daimo. Please make sure it's installed."
                )
                continuation.resume(null)
            }
        }
    }
    
    private fun connectRailwayWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🚄 Connecting to Railway Wallet with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "railway://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://railway.xyz/dapp/$DAPP_URL?method=eth_requestAccounts",
            "railway://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "railway://"
        )
        
        if (openWalletWithSmartConnection("com.railway.rtp", "Railway Wallet", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Railway Wallet opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in Railway Wallet"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("com.railway.rtp", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Railway Wallet address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Railway Wallet!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Railway Wallet! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectPayyWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "💳 Connecting to Payy Wallet with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "payy://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://payy.link/dapp/$DAPP_URL?method=eth_requestAccounts",
            "payy://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "payy://"
        )
        
        if (openWalletWithSmartConnection("com.polybaselabs.wallet", "Payy Wallet", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Payy Wallet opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in Payy Wallet"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("com.polybaselabs.wallet", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Payy Wallet address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Payy Wallet!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Payy Wallet! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }
    
    private fun connectStablesWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>) {
        Log.d(TAG, "🏛️ Connecting to Stables with auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        
        val connectionUris = listOf(
            "stables://dapp/$DAPP_URL?method=eth_requestAccounts&sessionId=$sessionId",
            "https://stables.money/dapp/$DAPP_URL?method=eth_requestAccounts",
            "stables://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
            "stables://"
        )
        
        if (openWalletWithSmartConnection("money.stables", "Stables", connectionUris)) {
            scope.launch {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Stables opened - requesting account access..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "Please approve the connection request in Stables"
                )
                delay(3000)
                
                val autoRetrievedAddress = attemptAutoAddressRetrieval("money.stables", sessionId)
                
                if (autoRetrievedAddress != null) {
                    Log.i(TAG, "🎉 Auto-retrieved Stables address: $autoRetrievedAddress")
                    _connectionState.value = WalletConnectionState(
                        isConnected = true,
                        address = autoRetrievedAddress,
                        connectionStep = "Successfully connected to Stables!"
                    )
                    continuation.resume(autoRetrievedAddress)
                } else {
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Connected to Stables! Please copy your address and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            continuation.resume(null)
        }
    }

    private fun connectGenericWalletWithAutoRetrieve(continuation: kotlin.coroutines.Continuation<String?>, walletPackageName: String) {
        Log.d(TAG, "🔗 Connecting to $walletPackageName with enhanced auto-retrieval...")
        
        val sessionId = UUID.randomUUID().toString()
        val walletName = getWalletDisplayName(walletPackageName)
        
        scope.launch {
            // First try standard Web3 intent
            _connectionState.value = WalletConnectionState(
                isConnecting = true,
                connectionStep = "Attempting to connect to $walletName..."
            )
            
            val web3Address = tryStandardWeb3Intent(walletPackageName, sessionId)
            if (web3Address != null) {
                Log.i(TAG, "🎉 Successfully retrieved $walletName address via Web3 intent!")
                continuation.resume(web3Address)
                return@launch
            }
            
            // Fall back to opening wallet with enhanced clipboard monitoring
            val connectionUris = listOf(
                "ethereum://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL&sessionId=$sessionId",
                "wallet://connect?name=${Uri.encode(DAPP_NAME)}&url=$DAPP_URL",
                "web3://connect?dapp=$DAPP_URL"
            )
            
            if (openWalletWithSmartConnection(walletPackageName, walletName, connectionUris)) {
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "$walletName opened - establishing connection..."
                )
                delay(2000)
                
                _connectionState.value = WalletConnectionState(
                    isConnecting = true,
                    connectionStep = "💡 Please copy your wallet address and return to this app"
                )
                
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
            } else {
                continuation.resume(null)
            }
        }
    }
    
    private suspend fun attemptAutoAddressRetrieval(walletPackageName: String, sessionId: String): String? {
        Log.d(TAG, "🔍 Starting guided address retrieval for $walletPackageName with session: $sessionId")
        
        return try {
            // Use a guided approach that works within Android's security model
            when (walletPackageName) {
                "io.metamask" -> guideMetaMaskAddressRetrieval(sessionId)
                "org.toshi" -> guideCoinbaseAddressRetrieval(sessionId)
                "me.rainbow" -> guideRainbowAddressRetrieval(sessionId)
                else -> guideGenericWalletAddressRetrieval(walletPackageName, sessionId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Guided address retrieval failed: ${e.message}")
            null
        }
    }
    
    /**
     * Guide user through MetaMask address copying - opens to portfolio/account view
     */
    private suspend fun guideMetaMaskAddressRetrieval(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🦊 Starting guided MetaMask address retrieval")
        
        try {
            // Open MetaMask to main app (portfolio view) instead of transaction
            // This avoids the "Ethereum is needed" error when user has no ETH
            val intent = Intent().apply {
                action = Intent.ACTION_MAIN
                setPackage("io.metamask")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🦊 Opened MetaMask to main portfolio view")
            
            // Wait for user to interact and potentially copy address
            delay(3000)
            
            // Try to get address when app comes back to foreground
            return@withContext waitForAddressOnResume(sessionId, 30000) // 30 second window
            
        } catch (e: Exception) {
            Log.d(TAG, "MetaMask guidance failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Guide user through Coinbase address copying
     */
    private suspend fun guideCoinbaseAddressRetrieval(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔵 Starting guided Coinbase address retrieval")
        
        try {
            val intent = Intent().apply {
                action = Intent.ACTION_MAIN
                setPackage("org.toshi")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🔵 Opened Coinbase Wallet")
            
            delay(3000)
            return@withContext waitForAddressOnResume(sessionId, 25000)
            
        } catch (e: Exception) {
            Log.d(TAG, "Coinbase guidance failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Guide user through Rainbow address copying
     */
    private suspend fun guideRainbowAddressRetrieval(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌈 Starting guided Rainbow address retrieval")
        
        try {
            val intent = Intent().apply {
                action = Intent.ACTION_MAIN
                setPackage("me.rainbow")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🌈 Opened Rainbow Wallet")
            
            delay(3000)
            return@withContext waitForAddressOnResume(sessionId, 25000)
            
        } catch (e: Exception) {
            Log.d(TAG, "Rainbow guidance failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Guide user through generic wallet address copying
     */
    private suspend fun guideGenericWalletAddressRetrieval(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔗 Starting guided address retrieval for $walletPackageName")
        
        try {
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(walletPackageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                Log.d(TAG, "🔗 Opened $walletPackageName")
                
                delay(3000)
                return@withContext waitForAddressOnResume(sessionId, 20000)
            }
            
        } catch (e: Exception) {
            Log.d(TAG, "Generic wallet guidance failed: ${e.message}")
        }
        
        return@withContext null
    }
    
    /**
     * Prime clipboard with a marker to detect when user copies address
     */
    private fun primeClipboard() {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FreePay", "Waiting for wallet address...")
            clipboardManager.setPrimaryClip(clip)
            clipboardPrimed = true
            Log.d(TAG, "📋 Clipboard primed for address detection")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prime clipboard: ${e.message}")
        }
    }
    
    /**
     * Wait for app to resume and check clipboard when user returns
     */
    private suspend fun waitForAddressOnResume(sessionId: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "⏳ Enhanced clipboard monitoring started (${timeoutMs}ms timeout)")
        
        // Prime clipboard before switching to wallet
        primeClipboard()
        
        // Store session for foreground check
        pendingClipboardCheck = sessionId
        
        val startTime = System.currentTimeMillis()
        var lastClipboardContent: String? = if (clipboardPrimed) "Waiting for wallet address..." else null
        var lastValidCheck = 0L
        val checkInterval = 300L // Check every 300ms when in foreground for faster response
        
        // First, try immediate check if we're in foreground
        if (isAppInForeground) {
            val immediateResult = checkClipboardNow()
            if (immediateResult != null) {
                pendingClipboardCheck = null
                clipboardPrimed = false
                return@withContext immediateResult
            }
        }
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val now = System.currentTimeMillis()
            
            // Only check clipboard if app is in foreground and enough time has passed
            if (isAppInForeground && now - lastValidCheck >= checkInterval) {
                try {
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clipData = clipboardManager.primaryClip
                    
                    if (clipData != null && clipData.itemCount > 0) {
                        val clipText = clipData.getItemAt(0).text?.toString()
                        
                        // Only process if clipboard content changed
                        if (clipText != null && clipText != lastClipboardContent) {
                            lastClipboardContent = clipText
                            Log.d(TAG, "📋 New clipboard content detected")
                            
                            // Check if it's a valid Ethereum address
                            val trimmedText = clipText.trim()
                            if (isValidEthereumAddress(trimmedText)) {
                                Log.i(TAG, "✅ Found valid Ethereum address: ${trimmedText.take(6)}...${trimmedText.takeLast(4)}")
                                pendingClipboardCheck = null
                                clipboardPrimed = false
                                return@withContext trimmedText
                            }
                            
                            // Check if clipboard contains text with an address pattern
                            val addressPattern = Regex("0x[a-fA-F0-9]{40}")
                            val match = addressPattern.find(clipText)
                            if (match != null && isValidEthereumAddress(match.value)) {
                                Log.i(TAG, "✅ Found valid Ethereum address pattern: ${match.value.take(6)}...${match.value.takeLast(4)}")
                                pendingClipboardCheck = null
                                return@withContext match.value
                            }
                        }
                    }
                    
                    lastValidCheck = now
                } catch (e: Exception) {
                    // Expected when app is in background
                    if (isAppInForeground) {
                        Log.w(TAG, "Clipboard check failed while in foreground: ${e.message}")
                    }
                }
            }
            
            // Use shorter delay when in foreground
            delay(if (isAppInForeground) checkInterval else 2000L)
        }
        
        pendingClipboardCheck = null
        Log.d(TAG, "⏰ Enhanced clipboard monitoring timeout after ${timeoutMs}ms")
        return@withContext null
    }
    
    /**
     * Immediate clipboard check
     */
    private fun checkClipboardNow(): String? {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipData = clipboardManager.primaryClip
            
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString()
                
                if (clipText != null) {
                    val trimmedText = clipText.trim()
                    if (isValidEthereumAddress(trimmedText)) {
                        return trimmedText
                    }
                    
                    val addressPattern = Regex("0x[a-fA-F0-9]{40}")
                    val match = addressPattern.find(clipText)
                    if (match != null && isValidEthereumAddress(match.value)) {
                        return match.value
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Immediate clipboard check failed: ${e.message}")
        }
        return null
    }
    
    /**
     * Strategy 1: Try wallet-specific enhanced methods
     */
    private suspend fun tryWalletSpecificMethods(walletPackageName: String, sessionId: String): String? {
        Log.d(TAG, "🎯 Trying wallet-specific methods for $walletPackageName")
        
        return when (walletPackageName) {
            "io.metamask" -> tryMetaMaskSpecificMethods(sessionId)
            "org.toshi" -> tryCoinbaseSpecificMethods(sessionId)
            "me.rainbow" -> tryRainbowSpecificMethods(sessionId)
            "com.debank.rabbymobile" -> tryRabbySpecificMethods(sessionId)
            "app.phantom" -> tryPhantomSpecificMethods(sessionId)
            else -> null
        }
    }
    
    /**
     * MetaMask-specific address extraction methods
     */
    private suspend fun tryMetaMaskSpecificMethods(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🦊 Trying MetaMask-specific address extraction")
        
        // Method 1: Open MetaMask with a transaction-like request that will show the address
        val transactionRequestUris = listOf(
            // Use a minimal ETH send to 0x0 address - MetaMask will show "From: [user_address]"
            "https://metamask.app.link/send/0x0000000000000000000000000000000000000000@1?value=0",
            "metamask://send/0x0000000000000000000000000000000000000000@1?value=0",
            // Alternative: use a dapp URL that MetaMask will open and show connected address
            "https://metamask.app.link/dapp/$DAPP_URL",
            "metamask://dapp/$DAPP_URL",
            // Wallet connection flow
            "metamask://wc?uri=wc:bridge-$sessionId",
            "https://metamask.app.link/wc?uri=wc:bridge-$sessionId"
        )
        
        for (uri in transactionRequestUris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    setPackage("io.metamask")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                Log.d(TAG, "🦊 Opening MetaMask with: ${uri.take(50)}...")
                context.startActivity(intent)
                
                // MetaMask is now open - monitor for address in clipboard or return values
                val foundAddress = monitorMetaMaskForAddress(sessionId)
                if (foundAddress != null) {
                    return@withContext foundAddress
                }
                
                delay(2000) // Wait before trying next approach
                
            } catch (e: Exception) {
                Log.d(TAG, "MetaMask URI failed: ${uri.take(50)} - ${e.message}")
            }
        }
        
        // Method 2: Try to extract from system logs (if accessible)
        val addressFromLogs = extractAddressFromSystemLogs()
        if (addressFromLogs != null) {
            Log.i(TAG, "🦊 Found MetaMask address from system monitoring: ${addressFromLogs.take(6)}...${addressFromLogs.takeLast(4)}")
            return@withContext addressFromLogs
        }
        
        // Method 3: Try accessibility service approach (if available)
        val addressFromAccessibility = tryAccessibilityAddressExtraction()
        if (addressFromAccessibility != null) {
            Log.i(TAG, "🦊 Found MetaMask address via accessibility: ${addressFromAccessibility.take(6)}...${addressFromAccessibility.takeLast(4)}")
            return@withContext addressFromAccessibility
        }
        
        null
    }
    
    /**
     * Monitor MetaMask app for address display
     */
    private suspend fun monitorMetaMaskForAddress(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🦊 Monitoring MetaMask for address display...")
        
        // Method 1: Check clipboard for copied address
        repeat(10) { // Check for 10 seconds
            try {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val clipText = clipData.getItemAt(0).text?.toString()
                    if (clipText != null && isValidEthereumAddress(clipText)) {
                        Log.i(TAG, "🦊 Found valid address in clipboard: ${clipText.take(6)}...${clipText.takeLast(4)}")
                        return@withContext clipText
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Clipboard check failed: ${e.message}")
            }
            
            delay(1000) // Check every second
        }
        
        null
    }
    
    /**
     * Try to extract address from system logs (limited access)
     */
    private suspend fun extractAddressFromSystemLogs(): String? = withContext(Dispatchers.IO) {
        try {
            // This will only work with developer-enabled logging or root access
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "MetaMask:D", "MetaMask:I"))
            val reader = process.inputStream.bufferedReader()
            
            val addressPattern = Regex("0x[a-fA-F0-9]{40}")
            reader.useLines { lines ->
                lines.forEach { line ->
                    val match = addressPattern.find(line)
                    if (match != null) {
                        val address = match.value
                        if (isValidEthereumAddress(address)) {
                            Log.d(TAG, "🦊 Found address in logs: ${address.take(6)}...${address.takeLast(4)}")
                            return@withContext address
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Log extraction failed (expected on non-debug builds): ${e.message}")
        }
        
        null
    }
    
    /**
     * Try accessibility service approach for address extraction
     */
    private suspend fun tryAccessibilityAddressExtraction(): String? = withContext(Dispatchers.IO) {
        try {
            // Check if we can access window content (requires accessibility permissions)
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            if (!accessibilityManager.isEnabled) {
                Log.d(TAG, "🦊 Accessibility service not enabled - cannot extract from UI")
                return@withContext null
            }
            
            // This would require implementing an AccessibilityService, which is complex
            // For now, just return null and rely on other methods
            Log.d(TAG, "🦊 Accessibility extraction not implemented yet")
            return@withContext null
            
        } catch (e: Exception) {
            Log.d(TAG, "Accessibility approach failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Coinbase Wallet-specific address extraction methods
     */
    private suspend fun tryCoinbaseSpecificMethods(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔵 Trying Coinbase-specific address extraction")
        
        // Try Coinbase-specific content providers
        val coinbaseUris = listOf(
            "content://org.toshi.provider/wallet",
            "content://org.toshi.provider/accounts", 
            "content://org.toshi.provider/selectedAccount",
            "content://org.toshi/wallet_address"
        )
        
        for (uriString in coinbaseUris) {
            try {
                val uri = Uri.parse(uriString)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        for (i in 0 until c.columnCount) {
                            try {
                                val value = c.getString(i)
                                if (isValidEthereumAddress(value)) {
                                    Log.i(TAG, "🔵 Found Coinbase address: ${value.take(6)}...${value.takeLast(4)}")
                                    return@withContext value
                                }
                            } catch (e: Exception) {
                                // Continue checking other columns
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Coinbase URI failed: $uriString - ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Rainbow-specific address extraction methods
     */
    private suspend fun tryRainbowSpecificMethods(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌈 Trying Rainbow-specific address extraction")
        
        // Rainbow may store data in different locations
        val rainbowUris = listOf(
            "content://me.rainbow.provider/accounts",
            "content://me.rainbow.provider/wallet",
            "content://me.rainbow/selectedWallet"
        )
        
        for (uriString in rainbowUris) {
            try {
                val uri = Uri.parse(uriString)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        for (i in 0 until c.columnCount) {
                            try {
                                val value = c.getString(i)
                                if (isValidEthereumAddress(value)) {
                                    Log.i(TAG, "🌈 Found Rainbow address: ${value.take(6)}...${value.takeLast(4)}")
                                    return@withContext value
                                }
                            } catch (e: Exception) {
                                // Continue checking other columns
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Rainbow URI failed: $uriString - ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Rabby-specific address extraction methods
     */
    private suspend fun tryRabbySpecificMethods(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🐰 Trying Rabby-specific address extraction")
        
        // Rabby might use DeBankAPI patterns
        val rabbyUris = listOf(
            "content://com.debank.rabbymobile.provider/accounts",
            "content://com.debank.rabbymobile.provider/wallet", 
            "content://com.debank.rabbymobile/currentAccount"
        )
        
        for (uriString in rabbyUris) {
            try {
                val uri = Uri.parse(uriString)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        for (i in 0 until c.columnCount) {
                            try {
                                val value = c.getString(i)
                                if (isValidEthereumAddress(value)) {
                                    Log.i(TAG, "🐰 Found Rabby address: ${value.take(6)}...${value.takeLast(4)}")
                                    return@withContext value
                                }
                            } catch (e: Exception) {
                                // Continue checking other columns
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Rabby URI failed: $uriString - ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Phantom-specific address extraction methods
     */
    private suspend fun tryPhantomSpecificMethods(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "👻 Trying Phantom-specific address extraction")
        
        // Phantom might use Solana-style addresses, but we want Ethereum
        val phantomUris = listOf(
            "content://app.phantom.provider/ethereum",
            "content://app.phantom.provider/accounts",
            "content://app.phantom/ethereum_accounts"
        )
        
        for (uriString in phantomUris) {
            try {
                val uri = Uri.parse(uriString)
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        for (i in 0 until c.columnCount) {
                            try {
                                val value = c.getString(i)
                                if (isValidEthereumAddress(value)) {
                                    Log.i(TAG, "👻 Found Phantom Ethereum address: ${value.take(6)}...${value.takeLast(4)}")
                                    return@withContext value
                                }
                            } catch (e: Exception) {
                                // Continue checking other columns
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Phantom URI failed: $uriString - ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Strategy 2: Query wallet app's content provider for address data
     */
    private suspend fun queryWalletContentProvider(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "📋 Trying content provider query for $walletPackageName")
        
        val contentUris = listOf(
            // Common content provider patterns for wallet apps
            "content://$walletPackageName.provider/accounts",
            "content://$walletPackageName.provider/addresses", 
            "content://$walletPackageName.provider/wallet",
            "content://$walletPackageName/accounts",
            "content://$walletPackageName/addresses",
            "content://$walletPackageName/wallet",
            // MetaMask specific
            "content://io.metamask.provider/accounts",
            "content://io.metamask.provider/address",
            // Coinbase specific
            "content://org.toshi.provider/accounts",
            "content://org.toshi.provider/address"
        )
        
        for (uriString in contentUris) {
            try {
                val uri = Uri.parse(uriString)
                val cursor: Cursor? = context.contentResolver.query(
                    uri,
                    arrayOf("address", "account", "ethereum_address", "wallet_address"),
                    null,
                    null,
                    null
                )
                
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        // Try different column names
                        val possibleColumns = listOf("address", "account", "ethereum_address", "wallet_address")
                        for (column in possibleColumns) {
                            try {
                                val columnIndex = c.getColumnIndex(column)
                                if (columnIndex >= 0) {
                                    val address = c.getString(columnIndex)
                                    if (isValidEthereumAddress(address)) {
                                        Log.i(TAG, "✅ Found address via content provider: $uriString")
                                        return@withContext address
                                    }
                                }
                            } catch (e: Exception) {
                                // Column doesn't exist or wrong type, continue
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Content provider query failed for $uriString: ${e.message}")
            }
        }
        
        null
    }
    
    /**
     * Strategy 2: Send intent to wallet requesting address
     */
    private suspend fun requestAddressViaIntent(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "📤 Trying intent-based address request for $walletPackageName")
        
        return@withContext suspendCoroutine { continuation ->
            pendingRequests[sessionId] = continuation
            
            try {
                val addressRequestIntent = Intent().apply {
                    action = "org.freepay.REQUEST_ADDRESS"
                    setPackage(walletPackageName)
                    putExtra("dapp_name", DAPP_NAME)
                    putExtra("session_id", sessionId)
                    putExtra("response_action", ACTION_WALLET_ADDRESS_RESPONSE)
                    putExtra("callback_package", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                context.startActivity(addressRequestIntent)
                Log.d(TAG, "📤 Sent address request intent to $walletPackageName")
                
                // Set up timeout
                scope.launch {
                    delay(10000) // 10 second timeout for intent response
                    if (pendingRequests.containsKey(sessionId)) {
                        pendingRequests.remove(sessionId)
                        continuation.resume(null)
                    }
                }
                
            } catch (e: Exception) {
                Log.d(TAG, "Intent address request failed: ${e.message}")
                pendingRequests.remove(sessionId)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Strategy 3: Use wallet-specific deep links to request address
     */
    private suspend fun requestAddressViaDeepLink(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔗 Trying practical deep link approach for $walletPackageName")
        
        return@withContext when (walletPackageName) {
            "io.metamask" -> tryMetaMaskPracticalApproach(sessionId)
            "org.toshi" -> tryCoinbasePracticalApproach(sessionId)
            "me.rainbow" -> tryRainbowPracticalApproach(sessionId)
            else -> tryGenericPracticalApproach(walletPackageName, sessionId)
        }
    }
    
    /**
     * Practical MetaMask approach: Use transaction preview to reveal address
     */
    private suspend fun tryMetaMaskPracticalApproach(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🦊 Using practical MetaMask approach - transaction preview")
        
        try {
            // Open MetaMask with a transaction that will show the user's address in the "From" field
            val transactionUri = "https://metamask.app.link/send/0x0000000000000000000000000000000000000000@1?value=0.000000000000000001"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(transactionUri)).apply {
                setPackage("io.metamask")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🦊 Opened MetaMask transaction preview")
            
            // Give user 15 seconds to see their address and copy it
            delay(2000)
            
            // Monitor clipboard for the address
            return@withContext monitorClipboardForAddress(sessionId, 15000)
            
        } catch (e: Exception) {
            Log.d(TAG, "MetaMask practical approach failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Practical Coinbase approach: Use wallet info screen
     */
    private suspend fun tryCoinbasePracticalApproach(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔵 Using practical Coinbase approach")
        
        try {
            // Open Coinbase with portfolio/wallet view
            val portfolioUri = "cbwallet://wallet"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portfolioUri)).apply {
                setPackage("org.toshi")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🔵 Opened Coinbase wallet view")
            
            delay(2000)
            return@withContext monitorClipboardForAddress(sessionId, 10000)
            
        } catch (e: Exception) {
            Log.d(TAG, "Coinbase practical approach failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Practical Rainbow approach: Use portfolio view
     */
    private suspend fun tryRainbowPracticalApproach(sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🌈 Using practical Rainbow approach")
        
        try {
            val portfolioUri = "rainbow://portfolio"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portfolioUri)).apply {
                setPackage("me.rainbow")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🌈 Opened Rainbow portfolio")
            
            delay(2000)
            return@withContext monitorClipboardForAddress(sessionId, 10000)
            
        } catch (e: Exception) {
            Log.d(TAG, "Rainbow practical approach failed: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Generic practical approach for other wallets
     */
    private suspend fun tryGenericPracticalApproach(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔗 Using generic practical approach for $walletPackageName")
        
        try {
            // Just open the wallet app normally
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(walletPackageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "🔗 Opened $walletPackageName")
                
                delay(2000)
                return@withContext monitorClipboardForAddress(sessionId, 8000)
            }
            
        } catch (e: Exception) {
            Log.d(TAG, "Generic practical approach failed: ${e.message}")
        }
        
        return@withContext null
    }
    
    /**
     * Monitor clipboard for Ethereum addresses
     */
    private suspend fun monitorClipboardForAddress(sessionId: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "📋 Monitoring clipboard for address (${timeoutMs}ms timeout)")
        
        val startTime = System.currentTimeMillis()
        var lastClipContent = ""
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipData = clipboardManager.primaryClip
                
                if (clipData != null && clipData.itemCount > 0) {
                    val clipText = clipData.getItemAt(0).text?.toString()
                    
                    if (clipText != null && clipText != lastClipContent) {
                        lastClipContent = clipText
                        
                        // Check if it's a valid Ethereum address
                        if (isValidEthereumAddress(clipText.trim())) {
                            Log.i(TAG, "✅ Found valid Ethereum address in clipboard: ${clipText.trim().take(6)}...${clipText.trim().takeLast(4)}")
                            return@withContext clipText.trim()
                        }
                        
                        // Check if clipboard contains text with an address pattern
                        val addressPattern = Regex("0x[a-fA-F0-9]{40}")
                        val match = addressPattern.find(clipText)
                        if (match != null && isValidEthereumAddress(match.value)) {
                            Log.i(TAG, "✅ Found valid Ethereum address pattern in clipboard: ${match.value.take(6)}...${match.value.takeLast(4)}")
                            return@withContext match.value
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Clipboard monitoring error: ${e.message}")
            }
            
            delay(500) // Check clipboard every 500ms
        }
        
        Log.d(TAG, "📋 Clipboard monitoring timeout after ${timeoutMs}ms")
        return@withContext null
    }
    
    /**
     * Strategy 4: Send broadcast to wallet requesting address
     */
    private suspend fun requestAddressViaBroadcast(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "📡 Trying broadcast address request for $walletPackageName")
        
        return@withContext suspendCoroutine { continuation ->
            pendingRequests[sessionId] = continuation
            
            try {
                val broadcastIntent = Intent().apply {
                    action = "org.freepay.REQUEST_WALLET_ADDRESS"
                    setPackage(walletPackageName)
                    putExtra("dapp_name", DAPP_NAME)
                    putExtra("session_id", sessionId)
                    putExtra("response_action", ACTION_WALLET_ADDRESS_RESPONSE)
                    putExtra("callback_package", context.packageName)
                }
                
                context.sendBroadcast(broadcastIntent)
                Log.d(TAG, "📡 Sent broadcast address request to $walletPackageName")
                
                // Set up timeout
                scope.launch {
                    delay(5000) // 5 second timeout for broadcast response
                    if (pendingRequests.containsKey(sessionId)) {
                        pendingRequests.remove(sessionId)
                        continuation.resume(null)
                    }
                }
                
            } catch (e: Exception) {
                Log.d(TAG, "Broadcast address request failed: ${e.message}")
                pendingRequests.remove(sessionId)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Check clipboard immediately when app returns to foreground
     */
    private suspend fun checkClipboardImmediate(sessionId: String) {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clipData = clipboardManager.primaryClip
            
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString()
                
                if (clipText != null) {
                    // Check if it's a valid Ethereum address
                    if (isValidEthereumAddress(clipText.trim())) {
                        Log.i(TAG, "✅ Found valid Ethereum address on foreground return: ${clipText.trim().take(6)}...${clipText.trim().takeLast(4)}")
                        handleAddressFound(clipText.trim(), sessionId)
                        return
                    }
                    
                    // Check if clipboard contains text with an address pattern
                    val addressPattern = Regex("0x[a-fA-F0-9]{40}")
                    val match = addressPattern.find(clipText)
                    if (match != null && isValidEthereumAddress(match.value)) {
                        Log.i(TAG, "✅ Found valid Ethereum address pattern on foreground return: ${match.value.take(6)}...${match.value.takeLast(4)}")
                        handleAddressFound(match.value, sessionId)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Immediate clipboard check failed: ${e.message}")
        }
    }
    
    /**
     * Handle when an address is found
     */
    private fun handleAddressFound(address: String, sessionId: String) {
        pendingClipboardCheck = null
        val continuation = pendingRequests.remove(sessionId)
        if (continuation != null) {
            _connectionState.value = WalletConnectionState(
                isConnected = true,
                address = address,
                connectionStep = "Successfully retrieved wallet address!"
            )
            continuation.resume(address)
        }
    }
    
    /**
     * Try to get address via standard Web3 intent first
     */
    private suspend fun tryStandardWeb3Intent(walletPackageName: String, sessionId: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎯 Trying standard Web3 intent for $walletPackageName")
        
        return@withContext suspendCoroutine { continuation ->
            pendingRequests[sessionId] = continuation
            
            try {
                val intent = Intent(ACTION_GET_ADDRESS).apply {
                    setPackage(walletPackageName)
                    putExtra(EXTRA_REQUESTING_APP, context.packageName)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_CHAIN_ID, "1") // Ethereum mainnet
                    putExtra(EXTRA_CALLBACK_ACTION, ACTION_WALLET_ADDRESS_RESPONSE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                // Check if wallet can handle this intent
                val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo != null) {
                    Log.d(TAG, "✅ Wallet supports Web3 standard intent!")
                    context.startActivity(intent)
                    
                    // Set up timeout
                    scope.launch {
                        delay(15000) // 15 second timeout
                        if (pendingRequests.containsKey(sessionId)) {
                            Log.d(TAG, "⏰ Web3 intent timeout for session $sessionId")
                            pendingRequests.remove(sessionId)
                            continuation.resume(null)
                        }
                    }
                } else {
                    Log.d(TAG, "❌ Wallet does not support Web3 standard intent")
                    pendingRequests.remove(sessionId)
                    continuation.resume(null)
                }
                
            } catch (e: Exception) {
                Log.d(TAG, "Web3 intent failed: ${e.message}")
                pendingRequests.remove(sessionId)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Handle incoming wallet address responses
     */
    private fun handleWalletResponse(intent: Intent?) {
        when (intent?.action) {
            ACTION_WALLET_ADDRESS_RESPONSE -> handleLegacyResponse(intent)
            ACTION_GET_ADDRESS + ".RESPONSE" -> handleWeb3Response(intent)
        }
    }
    
    private fun handleLegacyResponse(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val address = intent.getStringExtra(EXTRA_WALLET_ADDRESS)
        val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
        
        Log.d(TAG, "📨 Received wallet response - Session: $sessionId, Success: $success, Address: ${address?.take(6)}...${address?.takeLast(4)}")
        
        if (sessionId != null && pendingRequests.containsKey(sessionId)) {
            val continuation = pendingRequests.remove(sessionId)
            
            if (success && address != null && isValidEthereumAddress(address)) {
                Log.i(TAG, "✅ Received valid address via broadcast: ${address.take(6)}...${address.takeLast(4)}")
                continuation?.resume(address)
            } else {
                Log.w(TAG, "❌ Received invalid or unsuccessful response")
                continuation?.resume(null)
            }
        } else {
            Log.w(TAG, "⚠️ Received response for unknown or expired session: $sessionId")
        }
    }
    
    private fun handleWeb3Response(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val address = intent.getStringExtra("address")
        val chainId = intent.getStringExtra("chain_id")
        
        Log.d(TAG, "🌐 Received Web3 response - Session: $sessionId, Chain: $chainId, Address: ${address?.take(6)}...${address?.takeLast(4)}")
        
        if (sessionId != null && address != null && isValidEthereumAddress(address)) {
            handleAddressFound(address, sessionId)
        } else {
            pendingRequests.remove(sessionId)?.resume(null)
        }
    }
    
    /**
     * Validate Ethereum address format
     */
    private fun isValidEthereumAddress(address: String?): Boolean {
        if (address == null) return false
        val cleanAddress = address.trim()
        
        // Check if it starts with 0x and has correct length
        if (!cleanAddress.startsWith("0x")) return false
        if (cleanAddress.length != 42) return false
        
        // Check if all characters after 0x are valid hex
        val hexPart = cleanAddress.substring(2)
        return hexPart.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
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
            "com.debank.rabbymobile" -> "Rabby Wallet"
            "app.phantom" -> "Phantom Wallet"
            "com.daimo" -> "Daimo"
            "com.railway.rtp" -> "Railway Wallet"
            "com.polybaselabs.wallet" -> "Payy Wallet"
            "money.stables" -> "Stables"
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
        
        // Unregister broadcast receiver
        try {
            context.unregisterReceiver(walletResponseReceiver)
            Log.d(TAG, "✅ Unregistered wallet response broadcast receiver")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error unregistering broadcast receiver: ${e.message}")
        }
        
        // Clear pending requests
        pendingRequests.clear()
        
        scope.cancel()
    }
} 