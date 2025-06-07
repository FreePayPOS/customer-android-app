# 🔗 NFC Wallet Handshake

A modern Android app for seamless NFC-based cryptocurrency payments with smart wallet detection and address management.

## ✨ Features

- 📱 **Smart Wallet Detection**: Automatically detects installed wallets (MetaMask, Rainbow, Coinbase Wallet, etc.)
- 🔄 **Guided Connection**: Intelligent wallet opening with connection requests and guided address capture
- 💾 **Persistent Storage**: Saves wallet preferences and addresses for future transactions
- 🌐 **Multi-chain Support**: Ethereum, Base, Arbitrum, Optimism, Polygon
- 🛡️ **Secure NFC**: Host Card Emulation with proprietary AID for secure communication

## 🚀 How It Works

1. **Select Wallet**: Choose from detected wallet apps
2. **Smart Connection**: App opens your wallet with connection request
3. **Address Capture**: Enter your wallet address (validated automatically)
4. **NFC Ready**: Tap your device to NFC terminals to send address or receive payment requests

## 📱 Supported Wallets

- **MetaMask** - Leading Ethereum wallet
- **Rainbow** - User-friendly DeFi wallet
- **Coinbase Wallet** - Self-custodial wallet
- **Trust Wallet** - Multi-blockchain support
- **Plus many more** - Any wallet that handles ethereum: URIs

## 🛠️ Setup & Installation

### Quick Start

```bash
git clone <repository-url>
cd AndroidProject
./gradlew installDebug
```

### Requirements

- Android 6.0+ (API 23)
- NFC-enabled device
- At least one supported wallet app installed

### Enable NFC

Go to Android Settings → Connections → NFC and enable it.

## 🔧 Technical Details

### Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MainActivity  │    │  WalletManager  │    │   CardService   │
│                 │    │                 │    │    (NFC HCE)    │
│ • UI & Flow     │◄──►│ • Detection     │    │ • APDU Handler  │
│ • User Input    │    │ • Storage       │    │ • Payment URIs  │
│ • Status        │    │ • Validation    │    │ • Address Tx    │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### Key Components

- **WalletManager**: Detects installed wallets and manages address storage
- **CardService**: NFC Host Card Emulation using proprietary AID `D2760000850101`
- **WalletConnectManager**: Handles wallet connection flow with intelligent guidance
- **MainActivity**: Modern Jetpack Compose UI with real-time status updates

### NFC Protocol

- **AID**: `D2760000850101` (proprietary, non-payment card)
- **Commands**: SELECT, GET (address), PAYMENT (EIP-681 URIs)
- **Response**: Stored wallet address or fallback address

## 🌐 Supported Networks

- Ethereum (1), Base (8453), Arbitrum (42161), Optimism (10), Polygon (137)

## 🔧 Recent Improvements

- ✅ **Fixed Coinbase Wallet Detection**: Updated package name from `com.coinbase.wallet` to `org.toshi`
- ✅ **Improved Wallet Selection Flow**: Fixed wallet changing process and address entry
- ✅ **Enhanced NFC Compatibility**: Changed from payment card AID to proprietary AID
- ✅ **Better Error Handling**: Comprehensive logging and fallback mechanisms
- ✅ **UI/UX Improvements**: Smooth wallet selection and connection flow

## 🧪 Testing

1. Install the app on an NFC-enabled Android device
2. Install a supported wallet (MetaMask, Coinbase Wallet, etc.)
3. Open the app and select your wallet
4. Enter your wallet address when prompted
5. Test NFC communication with another NFC-enabled device or terminal

## 📄 License

This project is licensed under the Apache 2.0 License.

---

**Built for seamless Web3 payments via NFC** 🚀 