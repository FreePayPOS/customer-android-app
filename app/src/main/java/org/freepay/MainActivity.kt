package org.freepay

import android.content.BroadcastReceiver
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.freepay.ui.theme.FreePayPOSTheme
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {
    private var nfcDataState by mutableStateOf("Waiting for NFC data...")
    private var walletSelection by mutableStateOf<WalletSelection?>(null)
    private var availableWallets by mutableStateOf<List<WalletApp>>(emptyList())
    private var showWalletSelection by mutableStateOf(false)
    
    private lateinit var walletManager: WalletManager
    private lateinit var walletConnectManager: WalletConnectManager

    private val nfcDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("nfc_data")?.let {
                nfcDataState = it // Update your Composable state
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize managers
        walletManager = WalletManager(this)
        walletConnectManager = WalletConnectManager(this)

        // Comprehensive NFC debugging
        Log.d(TAG, "=== NFC SETUP DEBUGGING ===")
        
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e(TAG, "❌ Device has no NFC hardware")
            nfcDataState = "❌ NFC not available on this device"
            return
        }
        
        Log.d(TAG, "✅ NFC adapter found")
        Log.d(TAG, "NFC enabled: ${nfcAdapter.isEnabled}")
        
        if (!nfcAdapter.isEnabled) {
            Log.w(TAG, "⚠️ NFC is disabled - please enable in device settings")
            nfcDataState = "⚠️ NFC is disabled - please enable in device settings"
        }

        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        val component = ComponentName(this, CardService::class.java)
        
        Log.d(TAG, "CardEmulation instance: $cardEmulation")
        Log.d(TAG, "CardService component: $component")

        // Check if HCE is supported
        val packageManager = packageManager
        val hasHce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        Log.d(TAG, "HCE supported: $hasHce")
        
        if (!hasHce) {
            Log.e(TAG, "❌ Host Card Emulation not supported on this device")
            nfcDataState = "❌ Host Card Emulation not supported"
            return
        }

        // Register AIDs dynamically
        val ok = try {
            val result = cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf("F046524545504159")
            )
            Log.d(TAG, "Dynamic AID registration result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ AID registration failed", e)
            false
        }
        
        if (ok) {
            Log.i(TAG, "✅ AID registration successful")
            nfcDataState = "✅ NFC ready - CardService registered"
        } else {
            Log.e(TAG, "❌ AID registration failed")
            nfcDataState = "❌ NFC service registration failed"
        }
        
        // Check if our service is the default for the AID
        val isDefault = cardEmulation.isDefaultServiceForAid(component, "F046524545504159")
        Log.d(TAG, "Is default service for AID: $isDefault")
        
        // Get list of registered AIDs for our service
        val registeredAids = cardEmulation.getAidsForService(component, CardEmulation.CATEGORY_OTHER)
        Log.d(TAG, "Registered AIDs: $registeredAids")
        
        Log.d(TAG, "=== END NFC SETUP DEBUGGING ===")
        
        // Load available wallets and selected wallet
        loadWalletInfo()

        enableEdgeToEdge()
        setContent {
            FreePayPOSTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Show wallet selection screen if no wallet is selected OR user wants to change wallet
                    if (walletSelection == null || showWalletSelection) {
                        WalletSelectionScreen(
                            wallets = availableWallets,
                            currentWalletSelection = if (showWalletSelection) walletSelection else null,
                            onWalletSelected = { wallet ->
                                // Disconnect previous connection when selecting new wallet
                                if (showWalletSelection) {
                                    walletConnectManager.disconnect()
                                    walletManager.clearSelectedWallet()
                                }
                                // Try to connect with WalletConnect first
                                connectToWallet(wallet)
                                // Don't close the wallet selection screen yet - let connectToWallet handle it
                            },
                            onManualAddressEntry = { wallet, address ->
                                // Disconnect previous connection when entering new address
                                if (showWalletSelection) {
                                    walletConnectManager.disconnect()
                                    walletManager.clearSelectedWallet()
                                }
                                // Manual address entry fallback
                                selectWallet(wallet, address)
                                showWalletSelection = false
                            },
                            onBackToMain = {
                                // Return to main screen without disconnecting
                                showWalletSelection = false
                            },
                            walletManager = walletManager,
                            walletConnectManager = walletConnectManager,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        MainContent(
                            walletSelection = walletSelection,
                            onSelectWallet = { 
                                // Just show wallet selection screen, don't disconnect yet
                                showWalletSelection = true
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            nfcDataReceiver,
            IntentFilter("org.freepay.NFC_DATA_RECEIVED")
        )
    }
    
    private fun loadWalletInfo() {
        Log.d(TAG, "=== Starting wallet info loading ===")
        
        availableWallets = walletManager.getAvailableWallets()
        walletSelection = walletManager.getWalletSelection()
        
        Log.d(TAG, "=== Wallet loading complete ===")
        Log.d(TAG, "Loaded ${availableWallets.size} available wallets")
        Log.d(TAG, "Selected wallet: ${walletSelection?.walletApp?.appName ?: "None"}")
        Log.d(TAG, "Wallet address: ${walletSelection?.walletAddress ?: "None"}")
        
        // Extra debug: List all found wallets
        if (availableWallets.isEmpty()) {
            Log.w(TAG, "⚠️ NO WALLETS FOUND! This might indicate a detection issue.")
            Log.w(TAG, "Check logcat for WalletManager debug messages with tag 'WalletManager'")
        } else {
            Log.i(TAG, "✅ Found wallets:")
            availableWallets.forEachIndexed { index, wallet ->
                Log.i(TAG, "  ${index + 1}. ${wallet.appName} (${wallet.packageName})")
            }
        }
    }
    
    private fun connectToWallet(wallet: WalletApp) {
        Log.d(TAG, "Attempting to connect to wallet: ${wallet.appName}")
        
        lifecycleScope.launch {
            try {
                nfcDataState = "Connecting to ${wallet.appName}..."
                
                val address = walletConnectManager.connectWallet(wallet.packageName)
                
                if (address != null) {
                    Log.i(TAG, "Successfully retrieved address from ${wallet.appName}: $address")
                    selectWallet(wallet, address)
                    showWalletSelection = false // Only close on successful connection
                } else {
                    Log.w(TAG, "Address retrieval will be completed through guided manual entry")
                    nfcDataState = "Please enter your ${wallet.appName} address to complete setup"
                    // Keep wallet selection screen open for manual entry
                    // The WalletSelectionScreen will handle the transition to manual entry
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to wallet: ${e.message}", e)
                nfcDataState = "Connection error with ${wallet.appName} - please try again"
                // Keep wallet selection screen open so user can try again
            }
        }
    }
    
    private fun selectWallet(wallet: WalletApp, address: String) {
        walletManager.saveWalletSelection(wallet.packageName, address)
        walletSelection = WalletSelection(wallet, address)
        walletConnectManager.completeConnection(address)
        nfcDataState = "Ready to use ${wallet.appName} with address: ${address.take(6)}...${address.takeLast(4)}"
        Log.i(TAG, "Selected wallet: ${wallet.appName} (${wallet.packageName}) with address: $address")
    }


    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(nfcDataReceiver)
        walletConnectManager.cleanup()
    }

    companion object {
        private const val TAG = "FREEPAY_HCE"
    }
}

@Composable
fun MainContent(
    walletSelection: WalletSelection?,
    onSelectWallet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Logo
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "FreePay Logo",
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(bottom = 8.dp),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center
        )
        
        // Wallet Selection
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Selected Wallet",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                if (walletSelection != null) {
                    Text(
                        text = walletSelection.walletApp.appName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = walletSelection.walletApp.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Wallet Address:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = walletSelection.walletAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "No wallet selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectWallet) {
                    Text(if (walletSelection != null) "Change Wallet" else "Select Wallet")
                }
            }
        }
        
        // Instructions
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Instructions",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. Select your preferred wallet app.\n" +
                            "2. Copy your wallet address in the wallet app.\n" +
                            "3. Paste your wallet address into FreePay.\n\n" +
                          "FreePay may need to be running in the background when tapping a FreePay POS terminal for payment.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun WalletSelectionScreen(
    wallets: List<WalletApp>,
    currentWalletSelection: WalletSelection? = null,
    onWalletSelected: (WalletApp) -> Unit,
    onManualAddressEntry: (WalletApp, String) -> Unit,
    onBackToMain: (() -> Unit)? = null,
    walletManager: WalletManager,
    walletConnectManager: WalletConnectManager,
    modifier: Modifier = Modifier
) {
    var selectedWallet by remember { mutableStateOf<WalletApp?>(null) }
    var showManualEntry by remember { mutableStateOf(false) }
    var walletAddress by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    
    // Observe WalletConnect state
    val connectionState by walletConnectManager.connectionState.collectAsState()
    
    // Handle back button
    if (onBackToMain != null) {
        BackHandler {
            onBackToMain()
        }
    }
    
    // Auto-switch to manual entry when wallet connection requires it
    LaunchedEffect(connectionState.connectionStep) {
        if (connectionState.connectionStep != null && 
            !connectionState.isConnecting && 
            connectionState.connectionStep!!.contains("copy your address")) {
            showManualEntry = true
        }
    }
    
    // Track wallet selection from outside connections
    LaunchedEffect(connectionState.connectionStep) {
        if (connectionState.connectionStep != null && 
            !connectionState.isConnecting &&
            selectedWallet == null) {
            // Try to find which wallet was being connected based on connection state
            // This helps maintain the selected wallet context during manual entry
            connectionState.connectionStep?.let { step ->
                wallets.forEach { wallet ->
                    if (step.contains(wallet.appName, ignoreCase = true)) {
                        selectedWallet = wallet
                    }
                }
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with optional back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackToMain != null) {
                IconButton(onClick = onBackToMain) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back to main"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (onBackToMain != null) "Change Wallet" else "Setup Your Wallet",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Choose your preferred wallet for NFC payments",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Connection status
        if (connectionState.isConnecting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Connecting to wallet...",
                        style = MaterialTheme.typography.titleMedium
                    )
                    connectionState.connectionStep?.let { step ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else if (connectionState.connectionStep != null && !connectionState.isConnecting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "✅ Wallet Connected!",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        connectionState.connectionStep!!,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        
        // Error display
        if (connectionState.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Error: ${connectionState.error}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        
        if (showManualEntry) {
            // Manual address entry screen
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Enter Your Wallet Address",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    if (selectedWallet != null) {
                        Text(
                            text = "Selected wallet: ${selectedWallet!!.appName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else {
                        Text(
                            text = "Manual wallet address entry",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    
                    OutlinedTextField(
                        value = walletAddress,
                        onValueChange = { 
                            walletAddress = it
                            addressError = null
                        },
                        label = { Text("Wallet Address") },
                        placeholder = { Text("0x1234567890abcdef...") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = addressError != null,
                        supportingText = {
                            if (addressError != null) {
                                Text(
                                    text = addressError!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text("Paste your Ethereum wallet address (42 characters starting with 0x)")
                            }
                        },
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { 
                                if (onBackToMain != null && selectedWallet == null) {
                                    // If we're changing wallet and no wallet selected, go back to main
                                    onBackToMain()
                                } else {
                                    // Otherwise go back to wallet selection
                                    showManualEntry = false 
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back")
                        }
                        
                        Button(
                            onClick = {
                                val trimmedAddress = walletAddress.trim()
                                if (!walletManager.isValidEthereumAddress(trimmedAddress)) {
                                    addressError = "Invalid Ethereum address format"
                                    return@Button
                                }
                                
                                // Use the selected wallet if available, otherwise create a manual entry
                                val walletToSave = selectedWallet ?: WalletApp(
                                    packageName = "manual_entry",
                                    appName = "Manual Entry"
                                )
                                
                                onManualAddressEntry(walletToSave, trimmedAddress)
                            },
                            enabled = walletAddress.trim().isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Address")
                        }
                    }
                }
            }
        } else if (wallets.isEmpty()) {
            // No wallets found
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Wallet Apps Found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Please install a wallet app that supports Ethereum, such as MetaMask, Rainbow, or Coinbase Wallet.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // Wallet selection
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Available Wallets",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(wallets) { wallet ->
                            val isCurrentlyConnected = currentWalletSelection?.walletApp?.packageName == wallet.packageName
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrentlyConnected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                border = if (isCurrentlyConnected) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                                onClick = if (isCurrentlyConnected) {
                                    {} // Don't allow clicking on currently connected wallet
                                } else {
                                    {
                                        selectedWallet = wallet
                                        onWalletSelected(wallet)
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column {
                                            Text(
                                                text = wallet.appName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isCurrentlyConnected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            Text(
                                                text = wallet.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isCurrentlyConnected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                            
                                            if (isCurrentlyConnected && currentWalletSelection != null) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "✓ Connected",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Address: ${currentWalletSelection.walletAddress.take(10)}...${currentWalletSelection.walletAddress.takeLast(6)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                    
                                    if (isCurrentlyConnected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Currently connected",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Connect to ${wallet.appName}",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Manual entry option
            if (connectionState.isConnected && connectionState.address != null) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedWallet?.let { wallet ->
                                    onManualAddressEntry(wallet, connectionState.address!!)
                                }
                            },
                            enabled = selectedWallet != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Connected Address")
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Don't see your wallet?",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedButton(
                            onClick = { showManualEntry = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enter Address Manually")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    FreePayPOSTheme {
        MainContent(
            walletSelection = WalletSelection(
                WalletApp("io.metamask", "MetaMask"),
                "0x3f1214074399e56D0D7224056eb7f41c5E8619C4"
            ),
            onSelectWallet = {}
        )
    }
}