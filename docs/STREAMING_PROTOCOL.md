# GFN Streaming Protocol — Phase 3 Documentation

> Hasil reverse engineering GFN Android app v6.39.39022815 (com.nvidia.geforcenow)
> Metode: static analysis (JADX + JS decompile) + packet capture (PCAPdroid, tanpa root)
> Tanggal: 2026-10-18
> Status: arsitektur & endpoint terpetakan; format paket UDP belum dipecahkan

---

## Ringkasan Eksekutif

GFN punya **DUA stack streaming yang berbeda total**:

| Aspek | App Native (Android) | Web Client (Browser) |
|---|---|---|
| Protokol transport | UDP custom (proprietary) | WebRTC (standar terbuka) |
| Port | 5001 (ping), 5002 (stream) | 5004 (STUN/ICE) |
| Region lock | Ketat (Indonesia = block tanpa VPN) | Longgar (bisa akses semua server) |
| Codec | H264, H265, AV1 | H264, VP8/VP9 (via WebRTC) |
| Kesulitan RE | Sangat sulit (proprietary) | Sedang (standar terbuka) |

**Rekomendasi untuk client dari nol:** tiru pendekatan web (WebRTC), bukan native.

---

## 1. Session Management API

### 1.1 Endpoint
```
https://prod.cloudmatchbeta.nvidiagrid.net/
```
Ditemukan di: `assets/mall/main.*.js` (`streamingServiceUrl`)

### 1.2 Alur Session (dari JS)
```
sessionControl.startSession(params)
  → sessionControl.resumeSession(...) (jika ada session aktif)
  → StreamerClientSetup
  → StreamerConnection
  → SeatConfig
  → Stream mulai
```
Metrik yang di-track: `StreamerClientSetupDuration`, `StreamerConnectionDuration`, `SeatConfigDuration`.

### 1.3 Device Capabilities
Sebelum streaming, client kirim capabilities via bridge:
```java
// com.nvidia.geforcenow.bridgeService.commands.Streamer
DeviceCapabilitiesParameters {
    forceRefresh: Boolean,
    supportedModes: ParameterSupportedModes[]
}
```

---

## 2. Server Discovery & Ping Test (Native App)

### 2.1 Metode
Saat app dibuka, client melakukan **UDP ping ke SEMUA server global** via port 5001 untuk mengukur latency dan memilih server terbaik.

### 2.2 Server yang Di-ping (dari packet capture)
**US:**
- us-california-south, us-california-north, us-arizona, us-oregon
- us-illinois, us-texas, us-florida, us-georgia
- us-new-jersey, us-virginia

**Canada:**
- ca-ontario, ca-quebec

**Europe:**
- eu-united-kingdom-1, eu-united-kingdom-2, eu-sweden
- eu-netherlands-north, eu-netherlands-south
- eu-germany, eu-france-1, eu-france-2, eu-poland, eu-bulgaria

**Asia-Pacific:**
- ap-india (66.22.139.x) ← terdekat dari Indonesia
- ap-japan (66.22.144.x)

### 2.3 Karakteristik Ping
- Protokol: UDP port 5001
- Volume: ~50 paket per server, ~6.5KB kirim / ~5.4KB terima
- Format hostname: `[region].cloudmatchbeta.nvidiagrid.net`

---

## 3. Native App Streaming Protocol

### 3.1 Port & Transport
| Port | Protokol | Fungsi |
|------|----------|--------|
| 5001 | UDP | Server ping / latency test |
| 5002 | UDP | Game streaming (video/audio/input) |

Ditemukan juga enum di JS: `TCP`, `UDP`, `RELAY` — ada fallback TCP dan mode relay.

### 3.2 Bukti Streaming Terdeteksi
Dari packet capture (tanpa VPN, app tidak bisa connect penuh):
```
UDP port 5002 → 66.22.139.59 (ap-india)
202KB dikirim, 1.3MB diterima
421 paket kirim, 1423 paket terima
Durasi: ~2 detik
```
Ini kemungkinan initial handshake/burst saat client mencoba establish stream.

### 3.3 Codec yang Didukung
Dari static analysis JS: **H264, H265, AV1**

### 3.4 Yang Belum Terpecahkan
- Format paket UDP (header, sequencing, fragmentation)
- Enkripsi (apakah ada, algoritma apa)
- Encoding input (gamepad/keyboard/mouse → paket)
- Multiplexing video/audio/input dalam satu stream

> Ini membutuhkan analisis pcap level paket (bukan CSV summary) + waktu berbulan-bulan. Setara dengan yang dikerjakan OpenNOW selama bertahun-tahun.

---

## 4. Web Client Streaming Protocol (WebRTC)

### 4.1 Transport
- **WebRTC** dengan ICE/STUN di **UDP port 5004**
- Dari capture: STUN ke `80.250.97.39`, 3.5KB kirim / 337KB terima, 564 paket, ~40 detik

### 4.2 Session Flow (dari capture)
```
1. play.geforcenow.com (page load, QUIC+HTTPS)
2. login.nvidia.com (auth)
3. apps.gxn.nvidia.com (GraphQL catalog, 28KB kirim / 92KB terima)
4. prod.cloudmatchbeta.nvidiagrid.net → 66.22.144.45 (session request, 5KB/27KB)
5. STUN/WebRTC ke assigned server (stream!)
```

### 4.3 Server Assignment (tanpa VPN dari Indonesia)
```
eu-netherlands-south.cloudmatchbeta.nvidiagrid.net (80.250.97.40)
62KB kirim, 317KB terima, durasi 2.5 menit
```

### 4.4 Pola Penamaan Server Regional
Format: `np-[kode-bandara]-[nomor].cloudmatchbeta.nvidiagrid.net`
- `np-bom-01` → Mumbai, India (BOM)
- `np-ams-07` → Amsterdam, Belanda (AMS)

### 4.5 Region Lock: Web vs Native
**Temuan penting:** Web GFN dari Indonesia bisa akses SEMUA server tanpa VPN, sementara app native di-block ("not available in your country").

Kemungkinan penyebab:
- App native: region check ketat di level aplikasi (IP geolocation)
- Web client: region check longgar atau user bisa pilih manual

### 4.6 Deep Dive: Bedah Paket WebRTC (dari file .pcap)

**Capture:** 4759 paket total (921 UDP, 3838 TCP), durasi ~6 menit, via PCAPdroid tanpa root.

**Handshake ICE/STUN (port 5004):**
```
Client → Server (14x): STUN Binding Request
  ├─ Type: 0x0001, Magic cookie: 0x2112A442 (standar)
  ├─ USERNAME: "f556cdd1:3mXE" (format ICE ufrag: local:remote)
  └─ MESSAGE-INTEGRITY: HMAC-SHA1 ✅

Server → Client (9x): STUN Binding Response (0x0101)
  └─ Koneksi ICE established!
```

**Enkripsi:**
```
DTLS handshake terdeteksi (0x17...) → SRTP key dinegosiasi
```

**Media Stream (SRTP):**
```
85 paket RTP/SRTP (0x90 = RTP v2 + header extension bit)
```

**Pola ukuran paket:**
| Ukuran payload | Jumlah | Identifikasi |
|----------------|--------|--------------|
| ~1180–1212 bytes | ~24x | Video (frame terfragmentasi) |
| ~58–102 bytes | ~29x | Audio / paket kontrol |

**Server:** `80.84.170.152:5004` (range Eropa)

**Kesimpulan deep dive:** Seluruh alur WebRTC GFN menggunakan **standar terbuka** (ICE/STUN → DTLS → SRTP). Tidak ada protokol proprietary di sisi web. Client WebRTC sendiri bisa dibangun dengan libwebrtc standar + signaling ke cloudmatch API.

---

## 5. Endpoint Lengkap (dari kedua capture)

### Auth & Config
| Endpoint | Fungsi |
|----------|--------|
| `login.nvidia.com` | OAuth 2.0 + PKCE (Phase 1) |
| `gx-target-rconfig-frontend-api.gx.nvidia.com` | Remote config |
| `gx-target-experiments-frontend-api.gx.nvidia.com` | A/B testing |
| `gx-target-survey-frontend-api.gx.nvidia.com` | Survey |

### Catalog & Session
| Endpoint | Fungsi |
|----------|--------|
| `apps.gxn.nvidia.com` | GraphQL catalog (Phase 2) |
| `prod.cloudmatchbeta.nvidiagrid.net` | Session management |
| `[region].cloudmatchbeta.nvidiagrid.net` | Regional session server |
| `pcs.geforcenow.com` | (kemungkinan: provisioning) |
| `pns.geforcenow.com` | (kemungkinan: push notification) |
| `mes.geforcenow.com` | (kemungkinan: messaging/event) |
| `public.games.geforce.com` | Public game data |

### Telemetry
| Endpoint | Fungsi |
|----------|--------|
| `events.gfe.nvidia.com` | Event telemetry |
| `events.telemetry.data.nvidia.com` | Telemetry data |
| `telemetry.gfe.nvidia.com` | Telemetry |
| `feedbacks.telemetry.data.nvidia.com` | Feedback |
| `prod.otel.kaizen.nvidia.com` | OpenTelemetry (port 8282 & 443) |
| `img.nvidiagrid.net` | Image CDN |

---

## 6. Kesimpulan & Next Step

### Yang Sudah Dikuasai
- ✅ Auth flow lengkap (Phase 1)
- ✅ Catalog API lengkap (Phase 2)
- ✅ Arsitektur streaming: endpoint, port, protokol, server list (Phase 3)
- ✅ Perbedaan native vs web terdokumentasi
- ✅ **Deep dive WebRTC: handshake STUN → DTLS → SRTP terbedah dari pcap** (Phase 3+)

### Yang Belum (Butuh Kerja Serius)
- ❌ Format paket UDP native app (proprietary, port 5002)
- ❌ WebRTC SDP signaling detail (butuh capture SDP exchange)
- ❌ Input encoding protocol (gamepad/keyboard → paket)

### Rekomendasi
Untuk project client dari nol: **bangun di atas WebRTC** (tiruan web client), bukan protokol UDP proprietary. Alasannya:
1. WebRTC adalah standar terbuka dengan library matang
2. Tidak ada region lock ketat
3. Codec dan signaling sudah standar
4. Jauh lebih feasible daripada reverse engineer protokol proprietary

---

*Dokumentasi ini murni dari analisis statis dan observasi traffic jaringan. Tidak ada pembobolan, tidak ada kredensial yang disalahgunakan.*

---

## Lampiran

- **`GFN_SDP_OFFER.md`** — SDP offer lengkap dari server (ditangkap via WebSocket signaling), termasuk ekstensi proprietary `nvstSdp` dan panduan implementasi client.
- **`GFN_INPUT_PROTOCOL.md`** — Protokol input hasil reverse engineering (keyboard 28-byte terpecahkan penuh via DataChannel interception).
