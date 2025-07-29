# FreePay Customer App

Allows you to pay at FreePay terminals using your favorite crypto wallet. 

## Usage

1. Install the app from Google Play, or follow Setup instructions below to run in developer mode
2. Select your favorite wallet from the list of available wallets
3. Paste your wallet address into the companion app

When tapping to a FreePay POS terminal it will automatically open your selected wallet for payment. 

## 📱 Supported Wallets

See a full compatibility list on the [FreePay Website](https://freepaypos.org)

## 🛠️ Setup & Installation

### Quick Start

```bash
git clone <repository-url>
```

2. Open the project in Android Studio
3. Connect your phone and hit run

### Requirements

- Android 6.0+ (API 23)
- NFC-enabled device
- At least one supported wallet app installed (or manual address entry)

### Enable NFC

Go to Android Settings → Connections → NFC and enable it.

## 🔧 Technical Details

### NFC Protocol

- **AID**: `F043525950544F` (proprietary, non-payment card)
- **Commands**: SELECT, PAYMENT (handles both EIP-681 URIs and wallet:address)
- **Response**: Stored wallet address or fallback address

## 🌐 Supported Wallets

Most wallets should be supported, if you don't see yours listed please submit an issue. 

## 📄 License

This project is licensed under the MIT License.
