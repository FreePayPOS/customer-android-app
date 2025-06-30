package com.freepaypos

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
        Log.d(TAG, "🚀 CardService.onCreate() called")
        
        try {
            walletManager = WalletManager(this)
            Log.d(TAG, "✅ WalletManager initialized successfully")
            
            // Verify initialization
            val selectedWallet = walletManager.getSelectedWalletInfo()
            val walletAddress = walletManager.getWalletAddress()
            Log.d(TAG, "Initial state - Selected wallet: ${selectedWallet?.appName}, Address: $walletAddress")
            
            Log.d(TAG, "✅ CardService fully initialized and ready")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing CardService", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 CardService.onStartCommand() called")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "📨 APDU RECEIVED! Length: ${commandApdu.size}")
        Log.d(TAG, "📨 Raw APDU: ${bytesToHex(commandApdu)}")

        // Extract command components for debugging
        if (commandApdu.size >= 4) {
            val cla = commandApdu[0]
            val ins = commandApdu[1]
            val p1 = commandApdu[2]
            val p2 = commandApdu[3]
            Log.d(TAG, "📨 Command: CLA=${String.format("%02X", cla)} INS=${String.format("%02X", ins)} P1=${String.format("%02X", p1)} P2=${String.format("%02X", p2)}")
        }

        // Send broadcast that we received an APDU
        sendDataToActivity("🔄 NFC reader connected - processing request...")

        // 1. Reader selects our AID
        if (commandApdu.startsWith(SELECT_PREFIX)) {
            Log.d(TAG, "✅ Handling SELECT command")
            sendDataToActivity("✅ NFC handshake established")
            return SELECT_OK
        }

        // 2. Reader asks for the payload (GET command)
        if (commandApdu.contentEquals(GET_STRING_CMD)) {
            Log.d(TAG, "✅ Handling GET_STRING command")
            
            // Get wallet address from saved selection
            val walletAddress = walletManager.getWalletAddress()
            val selectedWallet = walletManager.getSelectedWalletInfo()
            
            Log.d(TAG, "Current state - Selected wallet: ${selectedWallet?.appName}, Address: $walletAddress")
            
            if (walletAddress != null) {
                val payload = walletAddress.toByteArray(Charsets.UTF_8)
                Log.d(TAG, "✅ GET response: Using saved address: $walletAddress")
                sendDataToActivity("✅ Sent wallet address: ${walletAddress.take(6)}...${walletAddress.takeLast(4)}")
                return payload + SELECT_OK
            } else {
                Log.e(TAG, "❌ No wallet configured - cannot provide address")
                sendDataToActivity("❌ No wallet configured - please set up a wallet first")
                return byteArrayOf(0x6A.toByte(), 0x82.toByte()) // File not found
            }
        }

        // 3. Handle PAYMENT command (80CF0000 + NDEFLength + NDEF data)
        if (commandApdu.size >= PAYMENT_CMD_PREFIX.size &&
            commandApdu.take(PAYMENT_CMD_PREFIX.size).toByteArray().contentEquals(PAYMENT_CMD_PREFIX)
        ) {
            Log.d(TAG, "Handling PAYMENT command")

            // Check if we have at least the command + length byte
            if (commandApdu.size <= PAYMENT_CMD_PREFIX.size) {
                Log.w(TAG, "PAYMENT command received but no length/data")
                return byteArrayOf(0x6A.toByte(), 0x80.toByte()) // Wrong data
            }

            // Extract NDEF length - always use 1-byte length as specified
            val lengthStartIndex = PAYMENT_CMD_PREFIX.size
            val ndefLength = commandApdu[lengthStartIndex].toInt() and 0xFF
            val lengthBytes = 1
            val ndefDataStartIndex = lengthStartIndex + lengthBytes
            
            Log.d(TAG, "PAYMENT command structure - Command: ${bytesToHex(commandApdu.take(PAYMENT_CMD_PREFIX.size).toByteArray())}, NDEFLength: $ndefLength (1 byte), Total APDU size: ${commandApdu.size}")

            // Validate we have enough data
            val expectedTotalSize = PAYMENT_CMD_PREFIX.size + lengthBytes + ndefLength
            if (commandApdu.size < expectedTotalSize) {
                Log.e(TAG, "PAYMENT command data incomplete - expected $expectedTotalSize bytes, got ${commandApdu.size}")
                Log.e(TAG, "Missing ${expectedTotalSize - commandApdu.size} bytes of NDEF data")
                return byteArrayOf(0x6A.toByte(), 0x80.toByte()) // Wrong data
            }

            // Extract exactly the specified amount of NDEF data
            val ndefData = Arrays.copyOfRange(commandApdu, ndefDataStartIndex, ndefDataStartIndex + ndefLength)
            Log.d(TAG, "Extracted NDEF data: ${bytesToHex(ndefData)} (specified length: $ndefLength, actual length: ${ndefData.size})")

            return handleNDEFPaymentRequest(ndefData)
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
        Log.i(TAG, "URI length: ${ethereumUri.length} characters")

        // Check for potential URI length issues
        if (ethereumUri.length > 2000) {
            Log.w(TAG, "⚠️ Very long URI detected (${ethereumUri.length} chars) - some wallets may have issues")
        }

        // Get the selected wallet
        val selectedWallet = walletManager.getSelectedWalletInfo()
        
        if (selectedWallet != null) {
            Log.i(TAG, "Using selected wallet: ${selectedWallet.appName} (${selectedWallet.packageName})")
            
            // Try multiple intent approaches for better compatibility
            val intentStrategies = listOf(
                // Strategy 1: Standard intent with minimal flags
                { 
                    Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri)).apply {
                        setPackage(selectedWallet.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                },
                // Strategy 2: Intent with CLEAR_TOP flag (helps with some wallet apps)
                {
                    Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri)).apply {
                        setPackage(selectedWallet.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                },
                // Strategy 3: Intent with SINGLE_TOP flag
                {
                    Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri)).apply {
                        setPackage(selectedWallet.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                }
            )
            
            for ((index, strategy) in intentStrategies.withIndex()) {
                try {
                    val intent = strategy()
                    Log.d(TAG, "Trying intent strategy ${index + 1} for ${selectedWallet.appName}")
                    
                    // Additional logging for debugging
                    Log.d(TAG, "Intent action: ${intent.action}")
                    Log.d(TAG, "Intent data: ${intent.data}")
                    Log.d(TAG, "Intent package: ${intent.`package`}")
                    Log.d(TAG, "Intent flags: ${intent.flags}")
                    
                    startActivity(intent)
                    
                    Log.i(TAG, "✅ Successfully launched ${selectedWallet.appName} with strategy ${index + 1}")
                    sendDataToActivity("Payment request sent to ${selectedWallet.appName}")
                    return
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Strategy ${index + 1} failed for ${selectedWallet.appName}: ${e.message}")
                }
            }
            
            // If all strategies failed
            Log.e(TAG, "❌ All intent strategies failed for ${selectedWallet.appName}")
            
            // Check if wallet is still installed
            if (!walletManager.isWalletInstalled(selectedWallet.packageName)) {
                Log.w(TAG, "Selected wallet is no longer installed, clearing selection")
                walletManager.clearSelectedWallet()
                sendDataToActivity("Selected wallet not found, please select a new wallet")
                return
            }
            
            // Try opening wallet app directly first, then user can paste URI manually
            try {
                val packageManager = packageManager
                val launchIntent = packageManager.getLaunchIntentForPackage(selectedWallet.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Log.i(TAG, "⚠️ Opened ${selectedWallet.appName} directly - URI may be too complex")
                    sendDataToActivity("Opened ${selectedWallet.appName} - please paste the payment URI manually if needed")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open ${selectedWallet.appName} directly: ${e.message}")
            }
            
            sendDataToActivity("❌ Failed to open ${selectedWallet.appName} - please open manually")
            
        } else {
            Log.w(TAG, "No wallet selected, using generic intent")
            sendDataToActivity("No wallet selected - showing app picker")
        }

        // Generic fallback when no wallet is selected
        Log.i(TAG, "Attempting generic wallet intent (system app picker)")
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "✅ Successfully launched generic wallet intent")
            sendDataToActivity("Payment request sent to system app picker")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open wallet app with generic intent: ${e.message}")
            Log.i(TAG, "URI that failed: ${ethereumUri.take(100)}${if (ethereumUri.length > 100) "..." else ""}")
            sendDataToActivity("❌ Failed to open wallet - please install a wallet app or set up wallet selection")
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

            // Parse NDEF record header properly
            val recordHeader = ndefData[0].toInt() and 0xFF
            val typeLength = ndefData[1].toInt() and 0xFF
            
            // Check if it's a Short Record (SR bit = bit 4)
            val isShortRecord = (recordHeader and 0x10) != 0
            val hasIdLength = (recordHeader and 0x08) != 0
            
            Log.d(TAG, "NDEF Header Analysis - Header: 0x${String.format("%02X", recordHeader)}, TypeLen: $typeLength, ShortRecord: $isShortRecord, HasIdLength: $hasIdLength")
            
            // Parse payload length (1 byte for short record, 4 bytes for long record)
            val (payloadLength, payloadLengthBytes) = if (isShortRecord) {
                val length = ndefData[2].toInt() and 0xFF
                Pair(length, 1)
            } else {
                // Long record - 4 byte payload length (big endian)
                if (ndefData.size < 6) {
                    Log.e(TAG, "NDEF data too short for long record")
                    return null
                }
                val length = ((ndefData[2].toInt() and 0xFF) shl 24) or
                           ((ndefData[3].toInt() and 0xFF) shl 16) or
                           ((ndefData[4].toInt() and 0xFF) shl 8) or
                           (ndefData[5].toInt() and 0xFF)
                Pair(length, 4)
            }
            
            // Calculate positions
            val typeStart = 2 + payloadLengthBytes
            val idLengthPos = if (hasIdLength) typeStart + typeLength else -1
            val idLength = if (hasIdLength) ndefData[idLengthPos].toInt() and 0xFF else 0
            val payloadStart = typeStart + typeLength + (if (hasIdLength) 1 + idLength else 0)
            
            if (ndefData.size < payloadStart) {
                Log.e(TAG, "NDEF data too short - expected at least $payloadStart bytes, got ${ndefData.size}")
                return null
            }
            
            val recordType = ndefData[typeStart]
            
            Log.d(TAG, "NDEF record - PayloadLen: $payloadLength, PayloadStart: $payloadStart, Type: ${String.format("%02X", recordType)}")

            // Check if it's a Well-Known URI record
            if ((recordHeader and 0x07) != 0x01 ||  // TNF must be 001 (Well Known)
                typeLength != 0x01 ||               // Type length must be 1
                (recordType.toInt() and 0xFF) != 0x55) {  // Type must be 'U' (0x55)
                Log.e(TAG, "Not a valid URI record - TNF: ${recordHeader and 0x07}, TypeLen: $typeLength, Type: ${String.format("%02X", recordType)}")
                return null
            }

            // Extract URI abbreviation and data
            if (payloadStart >= ndefData.size) {
                Log.e(TAG, "No payload data available")
                return null
            }
            
            val uriAbbreviation = ndefData[payloadStart]
            val uriDataLength = payloadLength - 1 // Subtract 1 for abbreviation byte

            Log.d(TAG, "URI abbreviation: ${String.format("%02X", uriAbbreviation)}, data length: $uriDataLength, total NDEF size: ${ndefData.size}")

            val uriDataStart = payloadStart + 1  // Skip the abbreviation byte
            val uriDataEnd = payloadStart + payloadLength
            
            if (ndefData.size < uriDataEnd) {
                Log.e(TAG, "NDEF data truncated - expected $uriDataEnd bytes, got ${ndefData.size}")
                Log.e(TAG, "Attempting to parse with available data...")
                
                // Try to extract what we can
                val availableDataLength = ndefData.size - uriDataStart
                if (availableDataLength <= 0) {
                    Log.e(TAG, "No URI data available")
                    return null
                }
                
                val uriBytes = Arrays.copyOfRange(ndefData, uriDataStart, ndefData.size)
                val uri = String(uriBytes, StandardCharsets.UTF_8)
                Log.w(TAG, "Extracted partial URI (may be truncated): '$uri'")
                
                // Handle URI abbreviation codes (we use 0x00 = no abbreviation)
                val fullUri = applyUriAbbreviation(uriAbbreviation, uri)
                Log.d(TAG, "Partial URI after abbreviation handling: '$fullUri'")
                
                return if (fullUri != null && fullUri.startsWith("ethereum:")) fullUri else null
            }

            // Extract the URI data
            val uriBytes = Arrays.copyOfRange(ndefData, uriDataStart, uriDataEnd)
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
        val intent = Intent("com.freepaypos.NFC_DATA_RECEIVED")
        intent.putExtra("nfc_data", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast with message: $message")
    }

    override fun onDeactivated(reason: Int) {
        // Called when link is lost (card removed, reader moved away, etc.)
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
        Log.d(TAG, "💔 HCE deactivated, reason: $reasonStr")
        sendDataToActivity("💔 NFC connection lost")
    }
    
    override fun onDestroy() {
        Log.d(TAG, "💀 CardService.onDestroy() called")
        super.onDestroy()
    }
}

/** Simple helper extension so we can do `byteArray.startsWith()` */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    this.size >= prefix.size && this.sliceArray(0 until prefix.size).contentEquals(prefix)