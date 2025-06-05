# 🔗 NFC Wallet Handshake

A modern Android application that provides **TRUE automatic wallet address retrieval** using Reown AppKit and seamless NFC payment requests. No more manual address entry - just connect once and everything works automatically!

## ✨ Features

- 🚀 **Automatic Address Retrieval**: Uses official Reown AppKit for true automatic wallet connection and address retrieval
- 📱 **NFC Payment Support**: Send payment requests via NFC to other devices
- 💳 **600+ Wallet Support**: Compatible with MetaMask, Rainbow, Coinbase Wallet, Trust Wallet, and many more
- 🔄 **Social & Email Login**: Support for Web3 authentication methods
- 🌐 **Multi-chain Support**: Ethereum, Base, Arbitrum, Optimism, Polygon
- 🛡️ **Secure HCE**: Host Card Emulation for secure NFC communication

## 🛠️ Setup Instructions

### 1. Configure Reown Project ID

1. Visit [https://cloud.reown.com](https://cloud.reown.com) and create a new project
2. Copy your Project ID
3. Open `app/src/main/res/values/strings.xml`
4. Replace `YOUR_PROJECT_ID_HERE` with your actual Project ID:

```xml
<string name="reown_project_id">your_actual_project_id_here</string>
```

### 2. Build and Install

```bash
./gradlew build
./gradlew installDebug
```

### 3. Enable NFC

- Go to your Android device Settings
- Enable NFC in Connections/Wireless settings
- The app will show NFC status on startup

## 🚀 How It Works

### Automatic Wallet Connection

1. **Tap "Connect Wallet"** - Opens Reown AppKit modal
2. **Select Your Wallet** - Choose from 600+ supported wallets
3. **Automatic Address Retrieval** - No manual input needed!
4. **Ready for NFC** - Address is automatically saved and used

### NFC Payment Flow

1. **Connected Wallet** sends address via NFC when requested
2. **Payment Device** receives address and creates payment request
3. **NFC Handshake** transfers payment URI back to wallet device
4. **Wallet Opens** automatically with pre-filled payment request

## 📱 Supported Wallets

- **MetaMask** - Leading Ethereum wallet
- **Rainbow** - Colorful and user-friendly
- **Coinbase Wallet** - Enterprise-grade security
- **Trust Wallet** - Multi-blockchain support
- **WalletConnect** compatible wallets
- **Social & Email** login options via Reown AppKit

## 🔧 Technical Implementation

### Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   MainActivity  │    │  ReownAppKit    │    │   CardService   │
│                 │    │   Manager       │    │    (NFC HCE)    │
│ • UI State      │◄──►│                 │    │                 │
│ • User Input    │    │ • Wallet Connect│    │ • APDU Handler  │
│ • NFC Status    │    │ • Address Mgmt  │    │ • Payment URIs  │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                 │                        │
                       ┌─────────▼─────────┐    ┌─────────▼─────────┐
                       │   WalletManager   │    │   NFC Protocol    │
                       │                   │    │                   │
                       │ • Address Storage │    │ • NDEF Parsing    │
                       │ • Wallet Selection│    │ • EIP-681 URIs    │
                       └───────────────────┘    └───────────────────┘
```

### Key Components

- **ReownAppKitManager**: Handles automatic wallet connection and address retrieval
- **CardService**: NFC Host Card Emulation for secure communication
- **WalletManager**: Persistent storage of wallet addresses and preferences
- **MainActivity**: Modern Compose UI with real-time status updates

## 🌐 Supported Networks

- **Ethereum** (Chain ID: 1)
- **Base** (Chain ID: 8453)
- **Arbitrum One** (Chain ID: 42161)
- **Optimism** (Chain ID: 10)
- **Polygon** (Chain ID: 137)

## 🔒 Security Features

- ✅ **No Manual Input**: Eliminates address copy/paste errors
- ✅ **HCE Security**: Android's secure NFC card emulation
- ✅ **Address Validation**: Automatic Ethereum address format checking
- ✅ **Wallet Verification**: Only connects to verified wallet applications
- ✅ **EIP-681 Compliance**: Standard Ethereum payment request format

## 📝 Usage Examples

### Basic Wallet Connection

```kotlin
// Automatic connection with address retrieval
val address = reownAppKitManager.connectWallet()
walletManager.setWalletAddress(address)
```

### NFC Payment Request

```kotlin
// Create EIP-681 payment URI
val paymentUri = "ethereum:0xAddress@8453/transfer?address=0xRecipient&uint256=1000000000000000000"

// Send via NFC (handled automatically by CardService)
// Recipient's wallet opens with pre-filled transaction
```

## 🏗️ Development Setup

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 34
- Minimum SDK 23 (Android 6.0)
- NFC-enabled Android device for testing

### Dependencies

```kotlin
// Reown AppKit for automatic wallet connection
implementation(platform("com.reown:android-bom:1.3.3"))
implementation("com.reown:android-core")
implementation("com.reown:appkit")

// Modern Android development
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test on NFC-enabled device
5. Submit a pull request

## 📄 License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **Reown (formerly WalletConnect)** for the excellent AppKit SDK
- **Android NFC Team** for Host Card Emulation APIs
- **Ethereum Community** for EIP-681 payment URI standard

## 📞 Support

- 📖 **Documentation**: Check the code comments for detailed implementation notes
- 🐛 **Issues**: Report bugs via GitHub Issues
- 💬 **Discussions**: Join the discussions for questions and feature requests

---

**Built with ❤️ for the Web3 community**

*Experience the future of seamless cryptocurrency payments with NFC technology!* 