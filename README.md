# PhoneBridge

Remote cellular-call controller for a Wi-Fi-only Android tablet and a cellular Android phone.

## Project

PhoneBridge is being developed for a **Lenovo Phab 2 Plus (Android 6)** acting as the cellular host and a **Redmi Pad 2 Wi-Fi** acting as the remote UI.

The Phab remains responsible for the actual carrier call. The Pad communicates with it over the local network.

## Phase 1

- Phab-side Android 6-compatible bridge
- Local Wi-Fi transport
- Remote `DIAL`, `ANSWER`, `REJECT`, and `HANGUP` commands
- Call-state reporting
- No call-audio forwarding yet

## Architecture

```text
Redmi Pad 2
    |
    | local Wi-Fi
    v
PhoneBridge on Lenovo Phab 2 Plus
    |
    v
Android telephony / Telecom
    |
    v
SIM / carrier network
```

## Important limitation

Remote control and call-state synchronization are separate from call-audio forwarding. Android 6/vendor telephony behavior may prevent a third-party application from exposing cellular call audio to another Android device. Audio bridging will therefore be investigated only after the control path works reliably.

## Repository layout

- `phab-bridge/` — phone-side bridge
- `pad-client/` — tablet-side client
- `protocol/` — shared protocol documentation

## Development

The project targets Android 6/API 23 on the Phab side and a current Android SDK on the Pad side.
