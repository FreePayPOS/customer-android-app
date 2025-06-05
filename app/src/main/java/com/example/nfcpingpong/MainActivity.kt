package com.example.nfcpingpong

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
import com.example.nfcpingpong.ui.theme.NFCPingPongTheme
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var nfcDataState by mutableStateOf("Waiting for NFC data...")
    private var walletSelection by mutableStateOf<WalletSelection?>(null)
    private var showWalletSelector by mutableStateOf(false)
    private var availableWallets by mutableStateOf<List<WalletApp>>(emptyList())
    
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

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.e(TAG, "Device has no NFC hardware")
            return
        }

        val cardEmulation = CardEmulation.getInstance(nfcAdapter)
        val component = ComponentName(this, CardService::class.java)

        val ok = try {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,        // use constant for clarity
                listOf("F2222222222222")
            )
        } catch (e: Exception) {
            Log.e(TAG, "AID registration failed", e)
            false
        }
        Log.i(TAG, "Dynamic AID register result = $ok")
        
        // Load available wallets and selected wallet
        loadWalletInfo()

        enableEdgeToEdge()
        setContent {
            NFCPingPongTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        nfcData = nfcDataState,
                        walletSelection = walletSelection,
                        onSelectWallet = { showWalletSelector = true },
                        modifier = Modifier.padding(innerPadding)
                    )
                    
                    // Wallet selection dialog
                    if (showWalletSelector) {
                        WalletSelectionDialog(
                            wallets = availableWallets,
                            currentSelection = walletSelection,
                            onWalletSelected = { wallet ->
                                // Try to connect with WalletConnect first
                                connectToWallet(wallet)
                                showWalletSelector = false
                            },
                            onManualAddressEntry = { wallet, address ->
                                // Manual address entry fallback
                                selectWallet(wallet, address)
                                showWalletSelector = false
                            },
                            onDismiss = { showWalletSelector = false },
                            walletManager = walletManager,
                            walletConnectManager = walletConnectManager
                        )
                    }
                }
            }
        }
        
        LocalBroadcastManager.getInstance(this).registerReceiver(
            nfcDataReceiver,
            IntentFilter("com.example.nfcpingpong.NFC_DATA_RECEIVED")
        )
        
        // Show wallet selector if no wallet is selected
        if (walletSelection == null && availableWallets.isNotEmpty()) {
            showWalletSelector = true
        }
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
                    nfcDataState = "Connected to ${wallet.appName}!"
                } else {
                    Log.w(TAG, "Address retrieval will be completed through guided manual entry")
                    nfcDataState = "Please complete the connection by entering your address"
                    // Keep the dialog open for guided manual entry
                    showWalletSelector = true
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to wallet: ${e.message}", e)
                nfcDataState = "Connection error - please try again"
            }
        }
    }
    
    private fun selectWallet(wallet: WalletApp, address: String) {
        walletManager.saveWalletSelection(wallet.packageName, address)
        walletSelection = WalletSelection(wallet, address)
        walletConnectManager.completeConnection(address)
        Log.i(TAG, "Selected wallet: ${wallet.appName} (${wallet.packageName}) with address: $address")
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(nfcDataReceiver)
        walletConnectManager.cleanup()
    }

    companion object {
        private const val TAG = "PINGPONG_HCE"
    }
}

@Composable
fun MainContent(
    nfcData: String,
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
        // NFC Status
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "NFC Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = nfcData,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
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
                    text = "1. Select your preferred wallet app - address will be retrieved automatically\n" +
                          "2. Hold your device near an NFC terminal\n" +
                          "3. Payment requests will use your wallet address and open directly in your chosen wallet",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun WalletSelectionDialog(
    wallets: List<WalletApp>,
    currentSelection: WalletSelection?,
    onWalletSelected: (WalletApp) -> Unit,
    onManualAddressEntry: (WalletApp, String) -> Unit,
    onDismiss: () -> Unit,
    walletManager: WalletManager,
    walletConnectManager: WalletConnectManager
) {
    var selectedWallet by remember { mutableStateOf(currentSelection?.walletApp) }
    var showManualEntry by remember { mutableStateOf(false) }
    var walletAddress by remember { mutableStateOf(currentSelection?.walletAddress ?: "") }
    var addressError by remember { mutableStateOf<String?>(null) }
    
    // Observe WalletConnect state
    val connectionState by walletConnectManager.connectionState.collectAsState()
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = if (showManualEntry) "Enter Wallet Address" else "Choose Wallet App",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (connectionState.isConnecting) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Connecting to wallet...")
                        connectionState.connectionStep?.let { step ->
                            Text(step, 
                                 style = MaterialTheme.typography.bodySmall,
                                 textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                } else if (connectionState.connectionStep != null && !connectionState.isConnecting) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✅ Wallet Opened!", 
                             color = Color.Green, 
                             style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            connectionState.connectionStep!!,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Enter your wallet address below:", 
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.primary)
                        
                        // Auto-switch to manual entry
                        showManualEntry = true
                    }
                } else if (connectionState.isConnected && connectionState.address != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✅ Connected!", color = Color.Green, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Address: ${connectionState.address}")
                        Text("Connection successful!", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (connectionState.error != null) {
                    Column {
                        Text(
                            text = "Connection Error:",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = connectionState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("You can try manual entry below:", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { showManualEntry = true }) {
                            Text("Manual Entry")
                        }
                    }
                } else if (showManualEntry) {
                    // Manual address entry
                    OutlinedTextField(
                        value = walletAddress,
                        onValueChange = { 
                            walletAddress = it
                            addressError = null
                        },
                        label = { Text("0x...") },
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
                                Text("Enter your Ethereum wallet address (42 characters starting with 0x)")
                            }
                        },
                        singleLine = true
                    )
                } else if (wallets.isEmpty()) {
                    Text(
                        text = "No wallet apps found. Please install a wallet app that supports Ethereum.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    // Wallet selection
                    Text(
                        text = "Select Wallet App:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(wallets) { wallet ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = wallet.packageName == selectedWallet?.packageName,
                                        onClick = { selectedWallet = wallet }
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = wallet.packageName == selectedWallet?.packageName,
                                    onClick = { selectedWallet = wallet }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = wallet.appName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = wallet.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    
                    if (connectionState.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error: ${connectionState.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    
                    if (showManualEntry) {
                        TextButton(onClick = { showManualEntry = false }) {
                            Text("Back")
                        }
                        
                        Button(
                            onClick = {
                                if (selectedWallet == null) return@Button
                                
                                val trimmedAddress = walletAddress.trim()
                                if (!walletManager.isValidEthereumAddress(trimmedAddress)) {
                                    addressError = "Invalid Ethereum address format"
                                    return@Button
                                }
                                
                                onManualAddressEntry(selectedWallet!!, trimmedAddress)
                            },
                            enabled = selectedWallet != null && walletAddress.trim().isNotEmpty()
                        ) {
                            Text("Save")
                        }
                    } else if (connectionState.isConnected && connectionState.address != null) {
                        Button(
                            onClick = {
                                selectedWallet?.let { wallet ->
                                    onManualAddressEntry(wallet, connectionState.address!!)
                                }
                            },
                            enabled = selectedWallet != null
                        ) {
                            Text("Save Address")
                        }
                    } else {
                        TextButton(onClick = { showManualEntry = true }) {
                            Text("Manual Entry")
                        }
                        
                        Button(
                            onClick = {
                                selectedWallet?.let { onWalletSelected(it) }
                            },
                            enabled = selectedWallet != null && !connectionState.isConnecting
                        ) {
                            Text("Connect")
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
    NFCPingPongTheme {
        MainContent(
            nfcData = "Waiting for NFC data...",
            walletSelection = WalletSelection(
                WalletApp("io.metamask", "MetaMask"),
                "0x3f1214074399e56D0D7224056eb7f41c5E8619C4"
            ),
            onSelectWallet = {}
        )
    }
}