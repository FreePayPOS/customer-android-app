package com.freepaypos

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log

data class WalletApp(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean = true
)

data class WalletSelection(
    val walletApp: WalletApp,
    val walletAddress: String
)

class WalletManager(private val context: Context) {
    private val TAG = "WalletManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("wallet_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val PREF_SELECTED_WALLET = "selected_wallet_package"
        private const val PREF_WALLET_ADDRESS = "wallet_address"
        
        // Common wallet package names with their display names
        private val KNOWN_WALLETS = mapOf(
            "io.metamask" to "MetaMask",
            "me.rainbow" to "Rainbow Wallet",
            "org.ethereum.mist" to "Mist Browser",
            "org.toshi" to "Coinbase Wallet", // Self-custodial wallet (correct package name)
            "com.wallet.crypto.trustapp" to "Trust Wallet",
            "im.token.app" to "imToken",
            "co.myst.android" to "Status",
            "com.alphawallet.app" to "AlphaWallet",
            "org.walleth" to "WallETH",
            "piuk.blockchain.android" to "Blockchain Wallet",
            "com.coinbase.android" to "Coinbase (Exchange)", // Main exchange app (not preferred for wallet)
            "com.exodus" to "Exodus",
            "com.myetherwallet.mewwallet" to "MEW wallet"
        )
    }
    
    /**
     * Get all available wallet apps that can handle ethereum: URIs
     */
    fun getAvailableWallets(): List<WalletApp> {
        val availableWallets = mutableListOf<WalletApp>()
        val pm = context.packageManager
        
        Log.d(TAG, "Starting wallet detection...")
        
        // Method 1: Create a test ethereum intent to find apps that can handle it
        val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("ethereum:"))
        // Use MATCH_ALL to find all possible handlers, not just defaults
        val activities: List<ResolveInfo> = pm.queryIntentActivities(testIntent, PackageManager.MATCH_ALL)
        
        Log.d(TAG, "Found ${activities.size} apps that can handle ethereum: URIs")
        
        // Add apps that can actually handle ethereum URIs
        for (activity in activities) {
            val packageName = activity.activityInfo.packageName
            val appName = getAppDisplayName(packageName) ?: activity.loadLabel(pm).toString()
            
            // Skip duplicates
            if (availableWallets.any { it.packageName == packageName }) continue
            
            // Skip non-wallet apps (browsers, etc.)
            if (shouldSkipApp(packageName)) {
                Log.d(TAG, "Skipping non-wallet app: $appName ($packageName)")
                continue
            }
            
            availableWallets.add(WalletApp(packageName, appName))
            Log.d(TAG, "Found wallet app via intent query: $appName ($packageName)")
        }
        
        // Method 2: Also check for known wallets that might not show up in the query
        Log.d(TAG, "Checking for known wallet packages...")
        for ((packageName, displayName) in KNOWN_WALLETS) {
            // Skip if already found
            if (availableWallets.any { it.packageName == packageName }) {
                Log.d(TAG, "Known wallet $displayName already found via intent query")
                continue
            }
            
            try {
                val packageInfo = pm.getPackageInfo(packageName, 0)
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val actualAppName = appInfo.loadLabel(pm).toString()
                
                availableWallets.add(WalletApp(packageName, "$displayName ($actualAppName)"))
                Log.d(TAG, "Found known wallet: $displayName -> $actualAppName ($packageName)")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Known wallet $displayName ($packageName) not installed")
            }
        }
        
        // Method 3: Try more URI schemes that wallets might handle
        val additionalSchemes = listOf("wc:", "wallet:", "ethereum:", "web3:")
        for (scheme in additionalSchemes) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                val schemeActivities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                Log.d(TAG, "Found ${schemeActivities.size} apps for scheme: $scheme")
                
                for (activity in schemeActivities) {
                    val packageName = activity.activityInfo.packageName
                    
                    // Skip if already found or if it's a browser/system app
                    if (availableWallets.any { it.packageName == packageName }) continue
                    if (shouldSkipApp(packageName)) continue
                    
                    val appName = activity.loadLabel(pm).toString()
                    availableWallets.add(WalletApp(packageName, "$appName (via $scheme)"))
                    Log.d(TAG, "Found potential wallet via $scheme: $appName ($packageName)")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Error checking scheme $scheme: ${e.message}")
            }
        }
        
        // Post-processing: Remove duplicates and apply prioritization
        val finalWallets = prioritizeAndFilterWallets(availableWallets)
        
        Log.d(TAG, "Final wallet detection result: ${finalWallets.size} wallets found")
        for (wallet in finalWallets) {
            Log.d(TAG, "  - ${wallet.appName} (${wallet.packageName})")
        }
        
        return finalWallets.sortedBy { it.appName }
    }
    
    /**
     * Check if an app should be skipped during wallet detection
     */
    private fun shouldSkipApp(packageName: String): Boolean {
        val skipPatterns = listOf(
            "browser", "chrome", "firefox", "edge", "opera", 
            "samsung.android", "google.", ".android.browser",
            "com.coinbase.android" // Skip main Coinbase app in favor of Coinbase Wallet
        )
        
        return skipPatterns.any { pattern -> 
            packageName.contains(pattern, ignoreCase = true) 
        }
    }
    
    /**
     * Prioritize wallet apps and remove unwanted duplicates
     */
    private fun prioritizeAndFilterWallets(wallets: List<WalletApp>): List<WalletApp> {
        val filteredWallets = mutableListOf<WalletApp>()
        
        // Special handling for Coinbase: prioritize Coinbase Wallet over main Coinbase app
        val coinbaseWallet = wallets.find { it.packageName == "org.toshi" }
        val coinbaseMain = wallets.find { it.packageName == "com.coinbase.android" }
        
        if (coinbaseWallet != null && coinbaseMain != null) {
            Log.d(TAG, "Found both Coinbase apps - prioritizing Coinbase Wallet")
            filteredWallets.add(coinbaseWallet)
            // Skip the main Coinbase app
        } else if (coinbaseWallet != null) {
            filteredWallets.add(coinbaseWallet)
        } else if (coinbaseMain != null) {
            // Only add main Coinbase if Wallet is not available
            filteredWallets.add(coinbaseMain.copy(appName = "Coinbase (Exchange App)"))
        }
        
        // Add all other wallets except the Coinbase ones we already handled
        filteredWallets.addAll(
            wallets.filter { 
                it.packageName != "org.toshi" && 
                it.packageName != "com.coinbase.android" 
            }
        )
        
        // Remove duplicates by package name
        return filteredWallets.distinctBy { it.packageName }
    }
    
    /**
     * Get display name for known wallet packages
     */
    private fun getAppDisplayName(packageName: String): String? {
        return KNOWN_WALLETS[packageName]
    }
    
    /**
     * Save the selected wallet and address
     */
    fun saveWalletSelection(packageName: String, walletAddress: String) {
        prefs.edit()
            .putString(PREF_SELECTED_WALLET, packageName)
            .putString(PREF_WALLET_ADDRESS, walletAddress)
            .apply()
        Log.d(TAG, "Saved wallet selection: $packageName with address: $walletAddress")
    }
    
    /**
     * Save the selected wallet package name (legacy method for backward compatibility)
     */
    fun saveSelectedWallet(packageName: String) {
        prefs.edit().putString(PREF_SELECTED_WALLET, packageName).apply()
        Log.d(TAG, "Saved selected wallet: $packageName")
    }
    
    /**
     * Get the currently selected wallet package name
     */
    fun getSelectedWallet(): String? {
        val selected = prefs.getString(PREF_SELECTED_WALLET, null)
        Log.d(TAG, "Retrieved selected wallet: $selected")
        return selected
    }
    
    /**
     * Get the stored wallet address
     */
    fun getWalletAddress(): String? {
        val address = prefs.getString(PREF_WALLET_ADDRESS, null)
        Log.d(TAG, "Retrieved wallet address: $address")
        return address
    }
    
    /**
     * Check if a wallet is still installed
     */
    fun isWalletInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Clear the selected wallet and address
     */
    fun clearSelectedWallet() {
        prefs.edit()
            .remove(PREF_SELECTED_WALLET)
            .remove(PREF_WALLET_ADDRESS)
            .apply()
        Log.d(TAG, "Cleared wallet selection and address")
    }
    
    /**
     * Get the selected wallet info, or null if none selected or not installed
     */
    fun getSelectedWalletInfo(): WalletApp? {
        val packageName = getSelectedWallet() ?: return null
        
        if (!isWalletInstalled(packageName)) {
            Log.w(TAG, "Selected wallet $packageName is no longer installed")
            clearSelectedWallet()
            return null
        }
        
        val displayName = getAppDisplayName(packageName) ?: run {
            try {
                context.packageManager.getApplicationInfo(packageName, 0)
                    .loadLabel(context.packageManager).toString()
            } catch (e: Exception) {
                packageName
            }
        }
        
        return WalletApp(packageName, displayName)
    }
    
    /**
     * Get the complete wallet selection including address
     */
    fun getWalletSelection(): WalletSelection? {
        val walletApp = getSelectedWalletInfo() ?: return null
        val address = getWalletAddress() ?: return null
        return WalletSelection(walletApp, address)
    }
    
    /**
     * Validate if an Ethereum address is properly formatted
     */
    fun isValidEthereumAddress(address: String): Boolean {
        val cleanAddress = address.trim()
        
        // Check if it starts with 0x and has correct length
        if (!cleanAddress.startsWith("0x")) return false
        if (cleanAddress.length != 42) return false
        
        // Check if all characters after 0x are valid hex
        val hexPart = cleanAddress.substring(2)
        return hexPart.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
} 