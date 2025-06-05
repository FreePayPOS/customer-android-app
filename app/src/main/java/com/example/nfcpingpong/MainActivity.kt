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

class MainActivity : ComponentActivity() {
    private var nfcDataState by mutableStateOf("Waiting for NFC data...")

    private val nfcDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("nfc_data")?.let {
                nfcDataState = it // Update your Composable state
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


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

        enableEdgeToEdge()
        setContent {
            NFCPingPongTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = nfcDataState,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            nfcDataReceiver,
            IntentFilter("com.example.nfcpingpong.NFC_DATA_RECEIVED")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(nfcDataReceiver)
    }

    companion object {
        private const val TAG = "PINGPONG_HCE"    // <-- THIS fixes the error
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NFCPingPongTheme {
        Greeting("Android")
    }
}