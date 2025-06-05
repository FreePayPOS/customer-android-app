package com.example.nfcpingpong      // adjust if you changed the package

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.nio.charset.StandardCharsets
import java.util.Arrays

class CardService : HostApduService() {
    private val TAG = "MyHostApduService"
    private lateinit var walletManager: WalletManager

    private val SELECT_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val UNKNOWN   = byteArrayOf(0x6A.toByte(), 0x82.toByte())

    /** APDU sent by reader to select our application */
    private val SELECT_PREFIX = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    /** Our invented "GET_STRING" APDU -> 80 CA 00 00 00 */
    private val GET_STRING_CMD = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
    /** PAYMENT command prefix -> 80 CF 00 00 (4 bytes, not 5!) */
    private val PAYMENT_CMD_PREFIX = byteArrayOf(0x80.toByte(), 0xCF.toByte(), 0x00.toByte(), 0x00.toByte())

    override fun onCreate() {
        super.onCreate()
        walletManager = WalletManager(this)
        Log.d(TAG, "CardService created, WalletManager initialized")
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "Received APDU: ${bytesToHex(commandApdu)} (length: ${commandApdu.size})")

        // Extract command components for debugging
        if (commandApdu.size >= 4) {
            val cla = commandApdu[0]
            val ins = commandApdu[1]
            val p1 = commandApdu[2]
            val p2 = commandApdu[3]
            Log.d(TAG, "Command: CLA=${String.format("%02X", cla)} INS=${String.format("%02X", ins)} P1=${String.format("%02X", p1)} P2=${String.format("%02X", p2)}")
        }

        // 1. Reader selects our AID
        if (commandApdu.startsWith(SELECT_PREFIX)) {
            Log.d(TAG, "Handling SELECT command")
            return SELECT_OK
        }

        // 2. Reader asks for the payload (GET command)
        if (commandApdu.contentEquals(GET_STRING_CMD)) {
            Log.d(TAG, "Handling GET_STRING command")
            
            // Get wallet address from saved selection
            val walletAddress = walletManager.getWalletAddress()
            val payloadString = walletAddress ?: "0x3f1214074399e56D0D7224056eb7f41c5E8619C4" // fallback
            
            val payload = payloadString.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "GET response: $payloadString (using ${if (walletAddress != null) "saved" else "fallback"} address)")
            
            // Send broadcast to update UI
            if (walletAddress != null) {
                sendDataToActivity("NFC request handled - sent wallet address: $payloadString")
            } else {
                sendDataToActivity("NFC request handled - no wallet selected, sent fallback address")
            }
            
            return payload + SELECT_OK        // concat data || SW1 SW2
        }

        // 3. Handle PAYMENT command (80CF0000 + NDEF data)
        if (commandApdu.size >= PAYMENT_CMD_PREFIX.size &&
            commandApdu.take(PAYMENT_CMD_PREFIX.size).toByteArray().contentEquals(PAYMENT_CMD_PREFIX)
        ) {
            Log.d(TAG, "Handling PAYMENT command")

            // Extract NDEF data (everything after the 4-byte command)
            if (commandApdu.size > PAYMENT_CMD_PREFIX.size) {
                val ndefData = commandApdu.drop(PAYMENT_CMD_PREFIX.size).toByteArray()
                Log.d(TAG, "NDEF data: ${bytesToHex(ndefData)} (length: ${ndefData.size})")

                return handleNDEFPaymentRequest(ndefData)
            } else {
                Log.w(TAG, "PAYMENT command received but no NDEF data")
                return byteArrayOf(0x6A.toByte(), 0x80.toByte()) // Wrong data
            }
        }

        // 4. Unknown command
        Log.w(TAG, "Unknown command received")
        return UNKNOWN
    }

    private fun handleNDEFPaymentRequest(ndefData: ByteArray): ByteArray {
        Log.d(TAG, "Processing NDEF payment request...")

        // Parse NDEF and extract the ethereum: URI
        val ethereumUri: String? = this.parseEthereumUriFromNDEF(ndefData)

        if (ethereumUri != null) {
            Log.i(TAG, "Successfully parsed Ethereum URI: $ethereumUri")

            // Parse chain ID from URI for additional logging
            val chainId = extractChainIdFromUri(ethereumUri)
            if (chainId != null) {
                Log.i(TAG, "Detected chain ID: $chainId")
                val chainName = getChainName(chainId)
                Log.i(TAG, "Chain: $chainName")
            }

            handleEthereumPaymentRequest(ethereumUri)
            return byteArrayOf(0x90.toByte(), 0x00.toByte()) // Success
        } else {
            Log.e(TAG, "Failed to parse Ethereum URI from NDEF")
            return byteArrayOf(0x6A.toByte(), 0x80.toByte()) // Wrong data
        }
    }

    private fun extractChainIdFromUri(uri: String): Int? {
        try {
            // Look for @chainId pattern in URI
            val atIndex = uri.indexOf('@')
            if (atIndex == -1) return null

            // Find the end of chain ID (next ? or / character)
            var endIndex = uri.length
            val questionIndex = uri.indexOf('?', atIndex)
            val slashIndex = uri.indexOf('/', atIndex)

            if (questionIndex != -1) endIndex = minOf(endIndex, questionIndex)
            if (slashIndex != -1) endIndex = minOf(endIndex, slashIndex)

            val chainIdStr = uri.substring(atIndex + 1, endIndex)
            return chainIdStr.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract chain ID from URI: $uri", e)
            return null
        }
    }

    private fun getChainName(chainId: Int): String {
        return when (chainId) {
            1 -> "Ethereum"
            8453 -> "Base"
            42161 -> "Arbitrum One"
            10 -> "Optimism"
            137 -> "Polygon"
            else -> "Chain $chainId"
        }
    }

    private fun handleEthereumPaymentRequest(ethereumUri: String) {
        Log.i(TAG, "Opening wallet with URI: $ethereumUri")

        // Get the selected wallet
        val selectedWallet = walletManager.getSelectedWalletInfo()
        
        if (selectedWallet != null) {
            Log.i(TAG, "Using selected wallet: ${selectedWallet.appName} (${selectedWallet.packageName})")
            
            try {
                // Create intent for specific wallet
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
                intent.setPackage(selectedWallet.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                
                Log.i(TAG, "Successfully launched selected wallet: ${selectedWallet.appName}")
                
                // Send broadcast to update UI
                sendDataToActivity("Payment request sent to ${selectedWallet.appName}")
                return
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open selected wallet: ${selectedWallet.appName}", e)
                
                // Check if wallet is still installed
                if (!walletManager.isWalletInstalled(selectedWallet.packageName)) {
                    Log.w(TAG, "Selected wallet is no longer installed, clearing selection")
                    walletManager.clearSelectedWallet()
                    sendDataToActivity("Selected wallet not found, please select a new wallet")
                }
                
                // Fall through to generic wallet opening
            }
        } else {
            Log.w(TAG, "No wallet selected, using generic intent")
            sendDataToActivity("No wallet selected - showing app picker")
        }

        // Fallback: Use generic intent (original behavior)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "Successfully launched generic wallet intent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open wallet app with generic intent", e)

            // Final fallback: Try to find wallet apps specifically
            try {
                val pm = packageManager
                val activities = pm.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, Uri.parse("ethereum:")),
                    PackageManager.MATCH_DEFAULT_ONLY
                )

                if (activities.isNotEmpty()) {
                    Log.i(TAG, "Trying fallback wallet app: ${activities[0].activityInfo.packageName}")
                    val walletIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
                    walletIntent.setPackage(activities[0].activityInfo.packageName)
                    walletIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(walletIntent)
                    sendDataToActivity("Payment request sent to ${activities[0].loadLabel(pm)}")
                } else {
                    Log.e(TAG, "No wallet apps found to handle ethereum: URIs")
                    sendDataToActivity("No wallet apps found - please install a wallet app")

                    // Try some common wallet package names as last resort
                    val commonWallets = listOf(
                        "io.metamask" to "MetaMask",
                        "me.rainbow" to "Rainbow Wallet",
                        "org.ethereum.mist" to "Mist Browser",
                        "com.coinbase.android" to "Coinbase Wallet"
                    )

                    for ((packageName, displayName) in commonWallets) {
                        try {
                            pm.getPackageInfo(packageName, 0)
                            Log.i(TAG, "Trying common wallet: $displayName")
                            val walletIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
                            walletIntent.setPackage(packageName)
                            walletIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(walletIntent)
                            sendDataToActivity("Payment request sent to $displayName")
                            break
                        } catch (ex: Exception) {
                            // Wallet not installed, try next
                        }
                    }
                }
            } catch (fallbackError: Exception) {
                Log.e(TAG, "All wallet opening attempts failed", fallbackError)
                sendDataToActivity("Failed to open wallet - please check your wallet apps")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val result = StringBuilder()
        for (b in bytes) {
            result.append(String.format("%02x", b))
        }
        return result.toString()
    }

    private fun parseEthereumUriFromNDEF(ndefData: ByteArray): String? {
        try {
            Log.d(TAG, "Parsing NDEF data: ${bytesToHex(ndefData)}")

            // NDEF URI Record structure we created:
            // [0] Record Header (0xD1)
            // [1] Type Length (0x01)
            // [2] Payload Length
            // [3] Type ('U' = 0x55)
            // [4] URI abbreviation code (0x00)
            // [5...] URI data

            if (ndefData.size < 5) {
                Log.e(TAG, "NDEF data too short: ${ndefData.size} bytes")
                return null
            }

            // Verify it's a URI record
            val recordHeader = ndefData[0]
            val typeLength = ndefData[1]
            val payloadLength = ndefData[2]
            val recordType = ndefData[3]

            Log.d(TAG, "NDEF record - Header: ${String.format("%02X", recordHeader)}, TypeLen: ${String.format("%02X", typeLength)}, PayloadLen: ${String.format("%02X", payloadLength)}, Type: ${String.format("%02X", recordType)}")

            // Check if it's a Well-Known URI record
            if ((recordHeader.toInt() and 0x07) != 0x01 ||  // TNF must be 001 (Well Known)
                typeLength.toInt() != 0x01 ||               // Type length must be 1
                recordType.toInt() != 0x55) {               // Type must be 'U' (0x55)
                Log.e(TAG, "Not a valid URI record")
                return null
            }

            // Extract URI abbreviation and data
            val uriAbbreviation = ndefData[4]
            val uriDataLength = payloadLength - 1 // Subtract 1 for abbreviation byte

            Log.d(TAG, "URI abbreviation: ${String.format("%02X", uriAbbreviation)}, data length: $uriDataLength")

            if (ndefData.size < 5 + uriDataLength) {
                Log.e(TAG, "NDEF data truncated")
                return null
            }

            // Extract the URI data
            val uriBytes = Arrays.copyOfRange(ndefData, 5, 5 + uriDataLength)
            val uri = String(uriBytes, StandardCharsets.UTF_8)

            Log.d(TAG, "Extracted raw URI: '$uri'")

            // Handle URI abbreviation codes (we use 0x00 = no abbreviation)
            val fullUri = applyUriAbbreviation(uriAbbreviation, uri)

            Log.d(TAG, "Full URI after abbreviation handling: '$fullUri'")

            // Verify it's an Ethereum URI and validate the format
            if (fullUri != null && fullUri.startsWith("ethereum:")) {
                // Additional validation for EIP-681 format
                if (isValidEIP681Uri(fullUri)) {
                    Log.i(TAG, "Successfully extracted valid EIP-681 URI: $fullUri")
                    return fullUri
                } else {
                    Log.w(TAG, "URI format may not be fully EIP-681 compliant but proceeding: $fullUri")
                    return fullUri  // Still try to process it
                }
            } else {
                Log.e(TAG, "Not an Ethereum URI: '$fullUri'")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NDEF data", e)
            return null
        }
    }

    private fun isValidEIP681Uri(uri: String): Boolean {
        try {
            // Basic EIP-681 validation
            if (!uri.startsWith("ethereum:")) return false

            // Check for common patterns
            val hasChainId = uri.contains("@")
            val hasTransfer = uri.contains("/transfer")
            val hasValue = uri.contains("value=")
            val hasAddress = uri.contains("address=")

            Log.d(TAG, "URI validation - hasChainId: $hasChainId, hasTransfer: $hasTransfer, hasValue: $hasValue, hasAddress: $hasAddress")

            return true  // Accept all ethereum: URIs for now
        } catch (e: Exception) {
            Log.w(TAG, "Error validating EIP-681 URI", e)
            return false
        }
    }

    private fun applyUriAbbreviation(abbreviationCode: Byte, uri: String): String {
        // URI abbreviation codes as defined in NFC Forum URI Record Type Definition
        when (abbreviationCode) {
            0x00.toByte() -> return uri // No abbreviation
            0x01.toByte() -> return "http://www.$uri"
            0x02.toByte() -> return "https://www.$uri"
            0x03.toByte() -> return "http://$uri"
            0x04.toByte() -> return "https://$uri"
            0x05.toByte() -> return "tel:$uri"
            0x06.toByte() -> return "mailto:$uri"
            0x07.toByte() -> return "ftp://anonymous:anonymous@$uri"
            0x08.toByte() -> return "ftp://ftp.$uri"
            0x09.toByte() -> return "ftps://$uri"
            0x0A.toByte() -> return "sftp://$uri"
            0x0B.toByte() -> return "smb://$uri"
            0x0C.toByte() -> return "nfs://$uri"
            0x0D.toByte() -> return "ftp://$uri"
            0x0E.toByte() -> return "dav://$uri"
            0x0F.toByte() -> return "news:$uri"
            0x10.toByte() -> return "telnet://$uri"
            0x11.toByte() -> return "imap:$uri"
            0x12.toByte() -> return "rtsp://$uri"
            0x13.toByte() -> return "urn:$uri"
            0x14.toByte() -> return "pop:$uri"
            0x15.toByte() -> return "sip:$uri"
            0x16.toByte() -> return "sips:$uri"
            0x17.toByte() -> return "tftp://$uri"
            0x18.toByte() -> return "btspp://$uri"
            0x19.toByte() -> return "btl2cap://$uri"
            0x1A.toByte() -> return "btgoep://$uri"
            0x1B.toByte() -> return "tcpobex://$uri"
            0x1C.toByte() -> return "irdaobex://$uri"
            0x1D.toByte() -> return "file://$uri"
            0x1E.toByte() -> return "urn:epc:id:$uri"
            0x1F.toByte() -> return "urn:epc:tag:$uri"
            0x20.toByte() -> return "urn:epc:pat:$uri"
            0x21.toByte() -> return "urn:epc:raw:$uri"
            0x22.toByte() -> return "urn:epc:$uri"
            0x23.toByte() -> return "urn:nfc:$uri"
            else -> {
                Log.w(TAG, "Unknown URI abbreviation code: ${String.format("%02X", abbreviationCode)}")
                return uri // Treat as no abbreviation
            }
        }
    }

    private fun sendDataToActivity(message: String) {
        val intent = Intent("com.example.nfcpingpong.NFC_DATA_RECEIVED")
        intent.putExtra("nfc_data", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast with message: $message")
    }

    override fun onDeactivated(reason: Int) {
        // Called when link is lost (card removed, reader moved away, etc.)
        Log.d(TAG, "HCE deactivated, reason: $reason")
    }
}

/** Simple helper extension so we can do `byteArray.startsWith()` */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    this.size >= prefix.size && this.sliceArray(0 until prefix.size).contentEquals(prefix)