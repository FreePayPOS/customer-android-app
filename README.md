# 🔗 NFC Wallet Handshake

A modern Android app for seamless NFC-based cryptocurrency payments with smart wallet detection and guided address setup.

## ✨ Features

- 📱 **Smart Wallet Detection**: Automatically detects installed wallets (MetaMask, Rainbow, Coinbase Wallet, etc.)
- 🎯 **Guided Connection**: Opens wallets with deep links and provides clear instructions for address retrieval
- 💾 **Persistent Storage**: Saves wallet preferences and addresses for future transactions
- 🌐 **Multi-chain Support**: Ethereum, Base, Arbitrum, Optimism, Polygon
- 🛡️ **Secure NFC**: Host Card Emulation with proprietary AID for secure communication
- 🚀 **Direct Wallet Targeting**: Bypasses system app picker for seamless payment flows

## 🚀 How It Works

1. **Select Wallet**: Choose from detected wallet apps or enter address manually
2. **Guided Setup**: App opens your wallet and guides you through address capture
3. **Smart Validation**: Automatic Ethereum address format validation
4. **NFC Ready**: Tap your device to NFC terminals to send address or receive payment requests

## 📱 Supported Wallets

- **MetaMask** - Leading Ethereum wallet
- **Rainbow** - User-friendly DeFi wallet  
- **Coinbase Wallet** - Self-custodial wallet
- **Trust Wallet** - Multi-blockchain support
- **Rabby** - DeFi-focused wallet
- **Plus many more** - Any wallet that handles ethereum: URIs

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
- **CardService**: NFC Host Card Emulation using proprietary AID `F043525950544F`
- **Connection Manager**: Handles guided wallet opening with deep links and user instructions
- **MainActivity**: Modern Jetpack Compose UI with real-time status updates

### NFC Protocol

- **AID**: `F043525950544F` (proprietary, non-payment card)
- **Commands**: SELECT, PAYMENT (handles both EIP-681 URIs and wallet:address)
- **Response**: Stored wallet address or fallback address

## 🌐 Supported Networks

- Ethereum (1), Base (8453), Arbitrum (42161), Optimism (10), Polygon (137)

## 🧪 Testing

1. Install the app on an NFC-enabled Android device
2. Install a supported wallet (MetaMask, Coinbase Wallet, etc.)
3. Open the app and select your wallet
4. Follow the guided setup to capture your wallet address
5. Test NFC communication with another NFC-enabled device or terminal

## 📄 License

This project is licensed under the MIT License.

---

**Built for seamless Web3 payments via NFC** 🚀 