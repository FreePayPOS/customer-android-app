# Wallet Integration

This guide describes how to make your Web3 wallet work with FreePay. If you have any questions please create an issue on this repo. 

## Basics

For a wallet to work with FreePay it must support [ERC-681](https://eips.ethereum.org/EIPS/eip-681) URL's via Android intents, this is the only hard requirement. 

After this please submit a PR to include your wallet in the [KNOWN_WALLETS](./app/src/main/java/com/example/nfcpingpong/WalletManager.kt#L31) list.

## Advanced

For full compatibility with FreePay your wallet should implement the following:

### Wallet Get Address Intent

This is so that FreePay can automatically pull the users address from your wallet when the user chooses your wallet in the selection list

  // Receive intent
  action: "com.web3.WALLET_GET_ADDRESS"
  extras:
    - requesting_app: String
    - session_id: String
    - chain_id: String (optional, default "1")
    - callback_action: String

  // Response intent
  action: "com.web3.WALLET_GET_ADDRESS.RESPONSE"
  extras:
    - session_id: String
    - address: String
    - chain_id: String


Your wallet should respond to com.web3.WALLET_GET_ADDRESS with com.web3.WALLET_GET_ADDRESS.RESPONSE with the users address and optionally the chainId included. 