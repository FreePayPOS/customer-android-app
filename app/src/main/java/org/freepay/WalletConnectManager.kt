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
    private var pendingClipboardCheck: String? = null
    
    // Broadcast receiver for wallet responses
    private val walletResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleWalletResponse(intent)
        }
    }
    
    companion object {
        // Broadcast actions for wallet responses
        private const val ACTION_WALLET_ADDRESS_RESPONSE = "org.freepay.WALLET_ADDRESS_RESPONSE"
        private const val EXTRA_WALLET_ADDRESS = "wallet_address"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_SUCCESS = "success"
        
        // Standard Web3 intent action
        private const val ACTION_GET_ADDRESS = "com.web3.WALLET_GET_ADDRESS"
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
                delay(100) // Small delay to ensure clipboard is accessible
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
     * This opens the wallet app and guides user through address capture via clipboard
     */
    suspend fun connectWallet(walletPackageName: String): String? = suspendCoroutine { continuation ->
        val walletInfo = getWalletInfo(walletPackageName)
        Log.d(TAG, "${walletInfo.emoji} Starting wallet connection for: ${walletInfo.name}")
        
        _connectionState.value = WalletConnectionState(
            isConnecting = true,
            connectionStep = "Opening ${walletInfo.name}..."
        )
        
        try {
            connectWalletWithClipboardMonitoring(continuation, walletPackageName, walletInfo)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up connection: ${e.message}", e)
            _connectionState.value = WalletConnectionState(
                error = "Connection error: ${e.message}"
            )
            continuation.resume(null)
        }
    }
    
    /**
     * Generic wallet connection with clipboard monitoring
     */
    private fun connectWalletWithClipboardMonitoring(
        continuation: kotlin.coroutines.Continuation<String?>,
        walletPackageName: String,
        walletInfo: WalletInfo
    ) {
        val sessionId = UUID.randomUUID().toString()
        pendingClipboardCheck = sessionId
        pendingRequests[sessionId] = continuation
        
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(walletPackageName)
        
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.d(TAG, "✅ Opened ${walletInfo.name}")
            
            _connectionState.value = WalletConnectionState(
                isConnecting = true,
                connectionStep = "💡 In ${walletInfo.name}: ${walletInfo.instruction}"
            )
            
            // Set timeout for clipboard monitoring
            scope.launch {
                delay(30000) // 30 second timeout
                if (pendingRequests.containsKey(sessionId)) {
                    pendingRequests.remove(sessionId)
                    pendingClipboardCheck = null
                    _connectionState.value = WalletConnectionState(
                        connectionStep = "Please copy your address from ${walletInfo.name} and paste it below"
                    )
                    continuation.resume(null)
                }
            }
        } else {
            Log.e(TAG, "❌ Failed to open ${walletInfo.name}")
            _connectionState.value = WalletConnectionState(
                error = "Failed to open ${walletInfo.name}. Please make sure it's installed."
            )
            continuation.resume(null)
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
                    val trimmedText = clipText.trim()
                    
                    // Check if it's a valid Ethereum address
                    if (isValidEthereumAddress(trimmedText)) {
                        Log.i(TAG, "✅ Found valid Ethereum address on foreground return: ${trimmedText.take(6)}...${trimmedText.takeLast(4)}")
                        handleAddressFound(trimmedText, sessionId)
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
    
    /**
     * Data class for wallet information
     */
    private data class WalletInfo(
        val name: String,
        val emoji: String,
        val instruction: String
    )
    
    /**
     * Get wallet display information
     */
    private fun getWalletInfo(packageName: String): WalletInfo {
        return when (packageName) {
            "io.metamask" -> WalletInfo(
                "MetaMask",
                "🦊",
                "tap your account name/address to copy it, then return to FreePay"
            )
            "me.rainbow" -> WalletInfo(
                "Rainbow",
                "🌈",
                "tap your account address to copy it, then return to FreePay"
            )
            "org.toshi" -> WalletInfo(
                "Coinbase Wallet",
                "🔵",
                "tap your account address to copy it, then return to FreePay"
            )
            "com.debank.rabbymobile" -> WalletInfo(
                "Rabby Wallet",
                "🐰",
                "tap your account address to copy it, then return to FreePay"
            )
            "app.phantom" -> WalletInfo(
                "Phantom Wallet",
                "👻",
                "tap your Ethereum address to copy it, then return to FreePay"
            )
            "com.daimo" -> WalletInfo(
                "Daimo",
                "💰",
                "copy your wallet address, then return to FreePay"
            )
            "com.railway.rtp" -> WalletInfo(
                "Railway Wallet",
                "🚄",
                "tap your address to copy it, then return to FreePay"
            )
            "com.polybaselabs.wallet" -> WalletInfo(
                "Payy Wallet",
                "💳",
                "tap your address to copy it, then return to FreePay"
            )
            "money.stables" -> WalletInfo(
                "Stables",
                "🏛️",
                "tap your address to copy it, then return to FreePay"
            )
            "org.ethereum.mist" -> WalletInfo(
                "Mist Browser",
                "🌫️",
                "copy your wallet address, then return to FreePay"
            )
            "com.trustwallet.app" -> WalletInfo(
                "Trust Wallet",
                "🔐",
                "tap your address to copy it, then return to FreePay"
            )
            "com.avaxwallet" -> WalletInfo(
                "Avalanche Wallet",
                "🌀",
                "copy your address, then return to FreePay"
            )
            else -> WalletInfo(
                "Wallet",
                "🔗",
                "copy your wallet address, then return to FreePay"
            )
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