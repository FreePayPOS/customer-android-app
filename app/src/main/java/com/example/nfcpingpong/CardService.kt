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

    private val SELECT_OK = byteArrayOf(0x90.toByte(), 0x00)
    private val UNKNOWN   = byteArrayOf(0x6A.toByte(), 0x82.toByte())

    /** APDU sent by reader to select our application */
    private val SELECT_PREFIX = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
    /** Our invented “GET_STRING” APDU -> 80 CA 00 00 00 */
    private val GET_STRING_CMD = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
    private val APDU_PREFIX_TO_MATCH = byteArrayOf(0x80.toByte(), 0xCF.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // 1. Reader selects our AID
        if (commandApdu.startsWith(SELECT_PREFIX)) {
            return SELECT_OK
        }
        // 2. Reader asks for the payload
        if (commandApdu.contentEquals(GET_STRING_CMD)) {
            val payload = "0x03d13d64643F51012BB23e81c4Adf7366F58C33b".toByteArray(Charsets.UTF_8)
            return payload + SELECT_OK        // concat data || SW1 SW2
        }

        Log.d(TAG, "Received APDU: ${commandApdu.toString()}")

        if (commandApdu.size >= APDU_PREFIX_TO_MATCH.size &&
            commandApdu.take(APDU_PREFIX_TO_MATCH.size).toByteArray().contentEquals(APDU_PREFIX_TO_MATCH)
        ) {
            // The APDU starts with 80CF000000
            if (commandApdu.size > APDU_PREFIX_TO_MATCH.size) {
                // Extract the rest of the data
                val dataPayload = commandApdu.drop(APDU_PREFIX_TO_MATCH.size).toByteArray()
                val dataString = dataPayload.toString(Charsets.UTF_8) // Or convert to String if it's text data

                Log.i(TAG, "Matching APDU received. Data: $dataString")

                // Display the data on the screen (e.g., using a Toast)
                // For a more persistent display, you might send a broadcast to an Activity
                // or update a LiveData object.
                // Running on a background thread, so use runOnUiThread for UI updates
                // if you were in an Activity context. Here, Toast is simpler for demonstration.
                // Consider using a Handler to post to the main thread if needed.
//                val message = "Received Data: $dataString"
                // Toast messages from a service can be tricky as they need a context.
                // For simplicity in this example, we'll log it.
                // To show a Toast, you'd typically need to pass context or use a Handler.
//                println("NFC Data Received: $message") // Prints to Logcat, visible in Android Studio

//                sendDataToActivity(message)
                return handleNDEFPaymentRequest(dataPayload)
                // You might want to send this data to your UI.
                // For example, using a LocalBroadcastManager or a SharedViewModel.
            } else {
                Log.w(TAG, "Matching APDU prefix, but no additional data.")
                return SELECT_OK // Or an error indicating missing data if expected
            }
        }

        // 3. Anything else -> “file not found”
        return UNKNOWN
    }

    private fun handleNDEFPaymentRequest(ndefData: ByteArray): ByteArray {
        // Parse NDEF and extract the ethereum: URI
        // Then create an Intent to open the wallet app
        val ethereumUri: String? = this.parseEthereumUriFromNDEF(ndefData)

        if (ethereumUri != null) {
            handleEthereumPaymentRequest(ethereumUri)
            return byteArrayOf(0x90.toByte(), 0x00.toByte()) // Success
        } else {
            Log.e("HCE", "Failed to parse Ethereum URI from NDEF")
            return byteArrayOf(0x6A.toByte(), 0x80.toByte()) // Wrong data
        }

        return byteArrayOf(0x6A.toByte(), 0x82.toByte()) // Not found
    }

    private fun handleEthereumPaymentRequest(ethereumUri: String) {
        Log.i("HCE", "Opening wallet with URI: $ethereumUri")

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("HCE", "Failed to open wallet app", e)


            // Fallback: Try to find wallet apps specifically
            val pm = packageManager
            val activities = pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("ethereum:")),
                PackageManager.MATCH_DEFAULT_ONLY
            )

            if (!activities.isEmpty()) {
                val walletIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ethereumUri))
                walletIntent.setPackage(activities[0].activityInfo.packageName)
                walletIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(walletIntent)
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
            // NDEF URI Record structure we created:
            // [0] Record Header (0xD1)
            // [1] Type Length (0x01)
            // [2] Payload Length
            // [3] Type ('U' = 0x55)
            // [4] URI abbreviation code (0x00)
            // [5...] URI data

            if (ndefData.size < 5) {
                Log.e("NDEF", "NDEF data too short")
                return null
            }


            // Verify it's a URI record
            val recordHeader = ndefData[0]
            val typeLength = ndefData[1]
            val payloadLength = ndefData[2]
            val recordType = ndefData[3]


            // Check if it's a Well-Known URI record
            if ((recordHeader.toInt() and 0x07) != 0x01 ||  // TNF must be 001 (Well Known)
                typeLength.toInt() != 0x01 ||  // Type length must be 1
                recordType.toInt() != 0x55
            ) {             // Type must be 'U' (0x55)
                Log.e("NDEF", "Not a valid URI record")
                return null
            }


            // Extract URI abbreviation and data
            val uriAbbreviation = ndefData[4]
            val uriDataLength = payloadLength - 1 // Subtract 1 for abbreviation byte

            if (ndefData.size < 5 + uriDataLength) {
                Log.e("NDEF", "NDEF data truncated")
                return null
            }


            // Extract the URI data
            val uriBytes = Arrays.copyOfRange(ndefData, 5, 5 + uriDataLength)
            val uri = String(uriBytes, StandardCharsets.UTF_8)


            // Handle URI abbreviation codes (we use 0x00 = no abbreviation)
            val fullUri = applyUriAbbreviation(uriAbbreviation, uri)


            // Verify it's an Ethereum URI
            if (fullUri != null && fullUri.startsWith("ethereum:")) {
                Log.i("NDEF", "Extracted Ethereum URI: $fullUri")
                return fullUri
            } else {
                Log.e("NDEF", "Not an Ethereum URI: $fullUri")
                return null
            }
        } catch (e: java.lang.Exception) {
            Log.e("NDEF", "Error parsing NDEF data", e)
            return null
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
            0x23.toByte() -> return "urn:nfc:$uri" // Corrected line
            else -> {
                Log.w("NDEF", "Unknown URI abbreviation code: $abbreviationCode")
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
    }
}

/** Simple helper extension so we can do `byteArray.startsWith()` */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    this.size >= prefix.size && this.sliceArray(0 until prefix.size).contentEquals(prefix)
