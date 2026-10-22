# PapahNow — Phase 1 COMPLETE ✅

**Date:** 2026-10-22
**Branch:** `phase1-poc` → merged to `main`
**Scope:** session → signaling → video pipeline (no input yet)

## Proven on-device

- **v43 (0.1.18, `882718c`):** game NTE rendered on-device, status Streaming, ICE CONNECTED (2026-10-21). First end-to-end video.
- **Audit-fix build (`ad931a1`):** queue → connect → streaming → disconnect → re-queue cycles all pass on-device (2026-10-22). Reconnect no longer shows black screen.

## The road here (v26 → v43, ~3 days)

Key root causes, in order:
1. **Token:** must use `client_token` via `GET login.nvidia.com/client_token` — the web `id_token` passes signature but the orchestrator rejects it (HTTP 500 `8A910003`).
2. **Claim:** GET prefetch + re-resolve the real server host before PUT claim; unclaimed ready sessions are killed by the server (poll flips to HTTP 500 `02F12011`).
3. **Signaling (native):** `wss://<host:443>/nvst/sign_in`, version=2, 12-hex peer id, explicit `Host` header, `Sec-WebSocket-Protocol: x-nv-sessionid.<id>`.
4. **peer_info identity (the final one):** client MUST announce its own `peer_info` after onOpen and only adopt an id from incoming `peer_info` whose `name ==` its own `peerName`. Adopting the server's id (1) broke the answer routing (`from=1 to=1`).
5. **nvstSdp:** the native client BUILDS its own answer nvstSdp (ICE ufrag/pwd + DTLS fingerprint from the local answer) — echoing the server's is wrong.
6. **ICE servers** come from the GET poll response after claim (not the PUT claim response), plus 3 STUN fallbacks.

Protocol docs: `AUTH_FLOW.md`, `CATALOG_API.md`, `STREAMING_PROTOCOL.md`, `GFN_SDP_OFFER.md`, `GFN_INPUT_PROTOCOL.md`.

## Hardening audit (2026-10-21)

Independent audit (DeepSeek-V4.1-Flash, thinking High) found 9 must-fix items (M1–M9): runaway coroutines, EGL context after reconnect, poll killed by one IOException, no session teardown, dropped ICE candidates, no offer timeout, double close callback, OkHttpClient leak, stale docs. All fixed across 4 commits (`54710d0`, `812fa79`, `34422f7`, `ad931a1`), reviewed by Nanally (Muse Spark) + DHS double-check, then device-tested.

## Known issues (Phase 2 polish)

- **Cosmetic:** after Disconnect, the last video frame lingers small on the left instead of clearing to black. Clears on app restart. Does not affect function.
- Auth is still manual GFNJWT paste (TTL 4h). OAuth auto-token is Phase 2 backlog.
- No reconnect logic yet — backgrounding the app kills the WebRTC session (tap Connect again).

## Next: Phase 2 — Input

1. Verify the 3 DataChannels actually OPEN + send a neutral packet.
2. On-screen virtual gamepad (twin-stick + ABXY; 54-byte packet format already decoded in `GFN_INPUT_PROTOCOL.md`). Priority: NTE rejects keyboard/mouse — gamepad is the only path.
3. Optional: OAuth Starfleet auto-token + reconnect handling.
