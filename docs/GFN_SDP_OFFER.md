# GFN WebRTC SDP Offer — Captured 2026-10-18

> Ditangkap dari WebSocket signaling `sign_in?peer_id=peer-1746639580...`
> Server: `80-84-17-x.cloudmatchbeta.nvidiagrid.net` (Eropa)
> Metode: Firefox DevTools → Network → WS → Messages
> Catatan: nilai sensitif (ice-pwd, fingerprint) adalah dari sesi sementara dan sudah kedaluwarsa.

## Ringkasan

| Item | Nilai |
|------|-------|
| Session | `odrerir` |
| ICE ufrag | `475acdbe` |
| ICE mode | `ice-lite` (server) |
| DTLS | `sha-256`, setup `actpass` |
| Audio | Opus 48kHz stereo + RED, sendrecv |
| Video | H264 (42001f, 42e01f) + H265 + AV1 + RTX, sendonly |
| DataChannel | SCTP port 5000, max 256KB |
| Resolusi | 1280×720 @ 60fps |
| Bitrate | init 12 Mbps, max 7 Mbps, min 1 Mbps |
| Server bundle port | 47999 |
| Client bundle port | 49006 |

## Alur Signaling (dari WS Messages)

```
Client → {"ack":1}
Server → {"ackId":2, "peer_msg":{"from":1, "msg":{"type":"offer","sdp":"...","nvstSdp":"..."}, "to":2}}
Client → {"ack":2}
Client → {"peer_msg":{"from":2,"to":1,"msg":{"type":"answer","sdp":"..."}}}
Server → {"peer_msg":{"from":2,"to":1,"msg":{"sdpMLineIndex":0,"sdpMid":"...","candidate":"..."}}} (×5 ICE candidates)
...ack...
Server → {"hb":1} (heartbeat)
Connection Closed: 4001 (signaling selesai)
```

## SDP Offer (WebRTC standar)

```
v=0
o=- 4373647202393833435 2 IN IP4 127.0.0.1
s=odrerir
t=0 0
a=group:BUNDLE 0 1 2
a=ice-options:trickle
a=ice-lite
a=ice-ufrag:475acdbe
a=ice-pwd:566576d0-0b18-4371-88a1-ecb5ad6d4811
a=fingerprint:sha-256 30:F6:6A:86:BC:B6:DA:78:C4:A4:8A:E5:D4:1A:4D:57:0A:2C:68:E8:ED:2C:A5:F6:06:A9:71:ED:72:04:9D:67
a=setup:actpass

m=audio 47998 UDP/TLS/RTP/SAVPF 63 111
c=IN IP4 0.0.0.0
a=rtcp:47998 IN IP4 0.0.0.0
a=mid:0
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=sendrecv
a=msid:second_stream_id audio
a=rtcp-mux
a=rtcp-rsize
a=rtpmap:63 red/48000/2
a=rtcp-fb:63 transport-cc
a=fmtp:63 111/111
a=rtpmap:111 opus/48000/2
a=rtcp-fb:111 transport-cc
a=fmtp:111 minptime=10;useinbandfec=1
a=ptime:10
a=ssrc:1 cname:odrerir

m=video 47998 UDP/TLS/RTP/SAVPF 99 100 107 108 103 104 96 97 101 102 98
c=IN IP4 0.0.0.0
a=rtcp:47998 IN IP4 0.0.0.0
a=mid:1
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=extmap:7 http://www.webrtc.org/experiments/rtp-hdrext/video-timing
a=extmap:12 http://www.webrtc.org/experiments/rtp-hdrext/playout-delay
a=sendonly
a=msid:stream_id fbc-video-0
a=rtcp-mux
a=rtcp-rsize
a=rtpmap:96 H264/90000
a=rtcp-fb:96 transport-cc
a=rtcp-fb:96 ccm fir
a=rtcp-fb:96 nack
a=rtcp-fb:96 nack pli
a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;sps-pps-idr-in-keyframe=1;profile-level-id=42001f
a=rtpmap:97 rtx/90000
a=fmtp:97 apt=96;rtx-time=125
a=rtpmap:101 H264/90000
a=rtcp-fb:101 transport-cc
a=rtcp-fb:101 ccm fir
a=rtcp-fb:101 nack
a=rtcp-fb:101 nack pli
a=fmtp:101 level-asymmetry-allowed=1;packetization-mode=1;sps-pps-idr-in-keyframe=1;profile-level-id=42e01f
a=rtpmap:102 rtx/90000
a=fmtp:102 apt=101;rtx-time=125
a=rtpmap:99 AV1/90000
a=rtcp-fb:99 transport-cc
a=rtcp-fb:99 ccm fir
a=rtcp-fb:99 nack
a=rtcp-fb:99 nack pli
a=rtpmap:100 rtx/90000
a=fmtp:100 apt=99;rtx-time=125
a=rtpmap:107 H265/90000
a=rtcp-fb:107 transport-cc
a=rtcp-fb:107 ccm fir
a=rtcp-fb:107 nack
a=rtcp-fb:107 nack pli
a=fmtp:107 level-id=153;profile-id=2;tier-flag=1
a=rtpmap:108 rtx/90000
a=fmtp:108 apt=107;rtx-time=125
a=rtpmap:103 H265/90000
a=rtcp-fb:103 transport-cc
a=rtcp-fb:103 ccm fir
a=rtcp-fb:103 nack
a=rtcp-fb:103 nack pli
a=fmtp:103 level-id=153;profile-id=1;tier-flag=1
a=rtpmap:104 rtx/90000
a=fmtp:104 apt=103;rtx-time=125
a=ssrc:2 cname:fbc-video-02
a=ssrc-group:FID 2 3
a=ssrc:3 cname:fbc-video-02
a=ssrc-group:FEC-FR 2 4
a=ssrc:4 cname:fbc-video-02
a=rtpmap:98 flexfec-03/90000
a=rtcp-fb:98 transport-cc
a=fmtp:98 repair-window=10000000

m=application 47998 UDP/DTLS/SCTP webrtc-datachannel
c=IN IP4 0.0.0.0
a=mid:2
a=sctp-port:5000
a=max-message-size:262144
```

## nvstSdp (Ekstensi Proprietary NVIDIA)

> Ratusan parameter konfigurasi NVIDIA. Yang paling penting:

### General
```
general.serverBundlePort:47999
general.clientBundlePort:49006
general.rtspScheme:4
general.videoContentType:1
general.serverNetwork:2
```

### Video
```
video.clientViewportWd:1280
video.clientViewportHt:720
video.maxFPS:60
video.initialBitrateKbps:12000
video.initialPeakBitrateKbps:12000
video.minQp:15
video.initialQp:35
video.encoderPreset:5
video.packetSize:1140
video.bitDepth:8
video.maxH264Profile:3
video.maxH264Level:51
```

### Bandwidth (vqos.bw)
```
vqos.bw.maximumBitrateKbps:7000
vqos.bw.minimumBitrateKbps:1000
vqos.bw.minimumEncoderBitrateKbps:650
vqos.bw.lossThreshold:2
```

### Input (ri = remote input)
```
ri.protocol:0
ri.useMultipleGamepads:1
ri.hidDeviceMask:4
ri.timestampsEnabled:1
```

### Audio
```
audio.samplesPerSecond:48000
audio.surround.numChannels:2
aqos.bitRate:96000
```

### Enkripsi
```
runtime.audioSrtp:1
runtime.videoSrtp:1
runtime.micSrtp:1
runtime.srtpReplayWindowSize:1024
```

## Catatan untuk Implementasi Client

1. **SDP standar** — libwebrtc bisa parse langsung
2. **nvstSdp** — sebagian besar adalah tuning; client minimal bisa kirim answer tanpa memodifikasi semua param
3. **DataChannel** — buka SCTP ke port 5000 untuk kirim input
4. **ICE** — server pakai ice-lite, jadi client yang aktif kirim STUN binding
5. **DTLS** — verifikasi fingerprint SHA-256 dari offer
