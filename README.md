# PapahNow

A from-scratch GeForce NOW cloud gaming client for Android, built on reverse-engineered protocol research. No NVIDIA SDK, no upstream fork — just libwebrtc and the wire protocol.

## Vision

Play your GeForce NOW library through a client you control: Neon-Noir UI, no bloat, input built for touch and gamepads. The full protocol research (auth, catalog, streaming, SDP, input) lives in [`docs/`](docs/).

## Roadmap

- [x] **Phase 0 — Scaffold** ✅: Android project, libwebrtc dependency, Neon-Noir theme baseline, protocol docs.
- [ ] **Phase 1 — PoC signaling + video**: WebSocket signaling client (`signaling/`), PeerConnection wrapper (`webrtc/`), render the first video frame. No input yet.
- [ ] **Phase 2 — Input**: DataChannel packet builders (`input/`) from the decoded keyboard/mouse/gamepad protocol in `docs/GFN_INPUT_PROTOCOL.md`.
- [ ] **Phase 3 — Polish**: Neon-Noir 2.0 UI, session management, reconnection, queue polling.

## Build

Debug APK via GitHub Actions (`.github/workflows/build.yml`), or locally:

```bash
gradle assembleDebug
```

## Protocol docs

| Doc | Contents |
|---|---|
| `docs/AUTH_FLOW.md` | OAuth2 + PKCE login flow |
| `docs/CATALOG_API.md` | GraphQL catalog queries |
| `docs/STREAMING_PROTOCOL.md` | WebRTC streaming overview |
| `docs/GFN_SDP_OFFER.md` | SDP offer format |
| `docs/GFN_INPUT_PROTOCOL.md` | Keyboard / mouse / gamepad packet formats |

## Disclaimer

Independent research project. Not affiliated with NVIDIA. You need your own GeForce NOW account.
