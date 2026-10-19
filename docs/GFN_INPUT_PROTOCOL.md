# GFN Input Protocol — Reverse Engineered 2026-10-18, mouse decoded 2026-10-19

> Metode: WebRTC DataChannel interception via `RTCDataChannel.prototype.send` monkey-patch di Firefox DevTools Console.
> Channel: `input_channel_v1` (SCTP, via SDP `m=application`)
> Status: **Keyboard TERPECAHKAN PENUH. Mouse TERPECAHKAN** (taksonomi paket + tombol + posisi absolut solid; field delta 47B masih hipotesis). Gamepad belum.

---

## Channel Discovery

Ditemukan via `about:webrtc` → `dataChannelStats` (sesi NTE, 2026-10-19). Total 6 channel dibuka, 5 terlihat di stats:

| Channel Name | ID | Fungsi | Format | Catatan |
|---|---|---|---|---|
| `cursor_channel` | 0 | Update kursor server→client | Binary | Hanya server→client (20 msg diterima, 0 terkirim). BARU — belum ada di riset lama |
| `control_channel` | 1 | Kontrol (protocol "chat") | Binary (~6B) | Heartbeat/kontrol sesi |
| `input_channel_partially_reliable` | 2 | (tidak terpakai) | — | OPEN tapi 0/0 pesan — belum pernah dipakai selama observasi |
| `input_channel_v1` | 4 | Input keyboard/mouse/gamepad | Binary (28/36/47 bytes) | Channel utama input |
| `stats_channel` | 6 | Telemetri client | Binary (~12-19B) | ~6 msg/detik |
| `stopStream` | — | Kontrol stop stream | (via `createDataChannel`) | Dari riset SDP sebelumnya |

> Catatan: `dataChannelsOpened: 6` tapi hanya 5 yang muncul di stats (ID 3 & 5 tidak terlihat).

---

## Keyboard Input — Format Paket (28 bytes)

Ditemukan dengan menekan tombol dan mengamati byte yang berubah.
**Revisi 2026-10-19:** magic sebenarnya 5 byte (`23 00 00 00 00`), bukan 4. Byte index 10 adalah event-type universal untuk SEMUA paket input (keyboard/mouse).

### Struktur

```
Offset  Size  Value               Keterangan
------  ----  ------------------  ---------------------------
0-4     5     23 00 00 00 00      Magic: input event (0x23)
5-8     4     xx xx xx xx         Timestamp/sequence (lihat catatan)
9       1     22                  Konstan (context ID?)
10      1     03 / 04             Event type: 03=KEY_DOWN, 04=KEY_UP
11      1     00                  Padding
12-13   2     XX 00               Keycode ASCII little-endian
14-27   14    00 ...              Padding/reserved
```

### Event Type Universal (byte index 10) — 2026-10-19

| Value | Ukuran paket | Arti |
|-------|--------------|------|
| `03` / `04` | 28B | Keyboard down / up |
| `05` | 36B | Mouse position (koordinat absolut) |
| `08` / `09` | 28B | Mouse button down / up |
| `00` | 47B | Mouse move (composite, bawa delta) |

### Contoh Nyata (dari capture)

**Tombol 'S' ditekan:**
```
23 00 00 00  00 06 65 87  08 22  03  00  53 00  00 00
00 00 00 00  65 8e d8  22 04  00
                ^^                  ^^
           event=DOWN           keycode='S' (0x53)
```

**Tombol Space dilepas:**
```
23 00 00 00  00 0a a2 a0  30 22  04  00  20 00  00 00
00 00 00 00  a2 a0 30  22 04  00
                      ^^        ^^
                 event=UP    keycode=' ' (0x20)
```

### Keycode Mapping (terverifikasi)

| Key | Hex | ASCII |
|-----|-----|-------|
| A | 41 00 | 0x41 |
| B | 42 00 | 0x42 |
| D | 44 00 | 0x44 |
| S | 53 00 | 0x53 |
| U | 55 00 | 0x55 |
| Space | 20 00 | 0x20 |

**Keycode = ASCII murni, little-endian.** Tidak ada scancode atau mapping khusus.

### Cara Membangun Paket (pseudocode)

```python
import struct, time

def build_key_packet(keycode_ascii, is_down):
    pkt = bytearray(28)
    struct.pack_into('<I', pkt, 0, 0x23)          # magic
    struct.pack_into('<I', pkt, 4, int(time.time() * 1000) & 0xFFFFFFFF)  # timestamp
    struct.pack_into('<H', pkt, 8, 0x2208)        # context (contoh)
    pkt[10] = 0x03 if is_down else 0x04           # event type
    pkt[11] = 0x00                                # padding
    struct.pack_into('<H', pkt, 12, keycode_ascii)  # ASCII keycode
    # bytes 14-27: padding (0x00)
    return bytes(pkt)

# Contoh: tekan 'W'
down = build_key_packet(ord('W'), True)
up   = build_key_packet(ord('W'), False)
# Kirim via DataChannel: dc.send(down) ... dc.send(up)
```

---

## Mouse Input — TERDECODE 2026-10-19

Capture terkontrol via collector snippet (MOVE-only vs CLICK-only terpisah, data sebagai teks via `copy()`).
Tiga jenis paket mouse, semua di `input_channel_v1`:

### 1. Mouse Button — 28 bytes (SOLID ✅)

```
Offset  Size  Value        Keterangan
------  ----  -----------  ---------------------------
0-4     5     23 00 00 00 00  Magic
5-8     4     xx xx xx xx  Timestamp (berbeda tiap event)
9       1     22           Konstan
10      1     08 / 09      08=BUTTON_DOWN, 09=BUTTON_UP
11-13   3     00 00 00     Padding
14      1     01           Konstan
15-23   9     00 ...       Padding
24-27   4     xx xx xx xx  Echo timestamp (3 byte pertama = byte 5-7)
```

Contoh nyata (klik kiri down lalu up):
```
28B 23 00 00 00 00 0f 5c 21 00 22 08 00 00 00 01 00 00 00 00 00 00 00 00 00 0f 5c 19 30
28B 23 00 00 00 00 0f 5c 6f 20 22 09 00 00 00 01 00 00 00 00 00 00 00 00 00 0f 5c 6f 20
                                    ^^
                              08=down, 09=up
```

### 2. Mouse Position — 36 bytes (koordinat SOLID ✅)

```
Offset  Size  Value        Keterangan
------  ----  -----------  ---------------------------
0-4     5     23 00 00 00 00  Magic
5-8     4     xx xx xx xx  Timestamp
9       1     22           Konstan
10      1     05           Event type = position
11-14   4     00 00 00 00  Padding
15-16   2     XX XX        Koordinat X, little-endian 16-bit
17-18   2     YY YY        Koordinat Y, little-endian 16-bit
19      1     00           Padding
20      1     03           Konstan
21      1     00           Padding
22-23   2     01 49        Marker (khas paket move/position)
24-31   8     00 ...       Padding
32-35   4     xx xx xx xx  Echo timestamp
```

Contoh nyata: `... 33 00 9c 00 ...` → x=0x0033=51, y=0x009c=156. Identik antar paket saat mouse diam.

### 3. Mouse Move — 47 bytes (taksonomi SOLID ✅, field detail HIPOTESIS ⚠️)

Paket composite: header + timestamp + section move (marker `01 49` ganda) + ekor yang mengulang tipe + timestamp parsial.

```
23 00 00 00 00 | 05 | 62 24 b0 24 | 00 00 00 00 | ce 18 | be 00 21 00 | 1a 05 |
00 00 00 02 | fe | 01 49 | 00 00 | 03 | 00 | 01 49 | 00 × 8 | 05 | 62 24 b0
```

- Byte 5 = `05` (tipe move), byte 10 = `00`
- Byte 26 (`fe`→`fa`→`f2`→`e6`→`d1`→`b8` pada gerakan satu arah) turun akseleratif → **hipotesis: signed delta pergerakan**
- Ekor `05 62 24 b0` mengulang byte 5 + 3 byte pertama timestamp
- Untuk implementasi awal, paket 36B (posisi absolut) + 28B (tombol) sudah cukup; 47B bisa direplikasi belakangan jika gerakan terasa kurang mulus

### Implikasi untuk NTE

Paket mouse dari client web **sudah benar formatnya** — masalah NTE (kamera diam, klik nol respon di 1.0.69–1.0.73) terkonfirmasi di sisi server (NTE mengabaikan virtual mouse), bukan format paket.

---

## Gamepad Input — TERDECODE 2026-10-19

**Temuan kunci:** gamepad TIDAK lewat `input_channel_v1` — dia pakai **`input_channel_partially_reliable`** (channel yang tadinya 0/0, hanya aktif saat ada input gamepad). Masuk akal: gamepad butuh latensi rendah, boleh drop packet.

Metode: virtual gamepad via spoof `navigator.getGamepads()` di Firefox (tanpa hardware fisik).

### Format Paket (54 bytes, fix)

```
Offset  Size  Value               Keterangan
------  ----  ------------------  ---------------------------
0-4     5     23 00 00 00 00      Magic (sama seperti keyboard/mouse)
5-8     4     xx xx xx xx         Timestamp
9       1     26                  Konstan
10-11   2     00 00               Padding
12      1     xx                  Sequence/subtype (bervariasi)
13      1     21                  Konstan
14      1     00                  Padding
15      1     26                  Konstan
16-19   4     0c 00 00 00         Konstan (= 12)
20      1     1a                  Konstan (= 26)
21-23   3     00 00 00            Padding
24-25   2     01 01               Konstan
26      1     14                  Konstan (= 20)
27-28   2     00 00               Padding
29-31   3     BB BB BB            BUTTON BITMASK (lihat bawah)
32-33   2     XX XX               Left Stick X, int16 LE
34-35   2     YY YY               Left Stick Y, int16 LE (NEGATED!)
36-37   2     XX XX               Right Stick X, int16 LE
38-39   2     YY YY               Right Stick Y, int16 LE (NEGATED!)
40-41   2     00 00               Reserved (trigger?)
42      1     55                  Konstan (= 0x55)
43-46   4     00 00 00 00         Padding
47-49   3     xx xx xx            Echo timestamp (parsial)
50      1     xx                  Bervariasi
51-53   3     00 00 00            Padding
```

### Button Bitmask (byte 29-31) — TERVERIFIKASI ✅

Rumus: **tombol index `i` → bit `(4+i)`**. Diverifikasi via capture A, B, X, Y individual + kombinasi A+B (`0x30`) dan X+Y (`0xc0`).

| Tombol | Index | Lokasi | Hex |
|---|---|---|---|
| A/Cross | 0 | [29].bit4 | `0x10` ✅ |
| B/Circle | 1 | [29].bit5 | `0x20` ✅ |
| X/Square | 2 | [29].bit6 | `0x40` ✅ |
| Y/Triangle | 3 | [29].bit7 | `0x80` ✅ |
| LB/L1 | 4 | [30].bit0 | `0x01` (ekstrapolasi) |
| RB/R1 | 5 | [30].bit1 | `0x02` (ekstrapolasi) |
| LT/L2 | 6 | [30].bit2 | `0x04` (ekstrapolasi) |
| RT/R2 | 7 | [30].bit3 | `0x08` (ekstrapolasi) |
| Select/Back | 8 | [30].bit4 | `0x10` (ekstrapolasi) |
| Start | 9 | [30].bit5 | `0x20` (ekstrapolasi) |
| L3 | 10 | [30].bit6 | `0x40` (ekstrapolasi) |
| R3 | 11 | [30].bit7 | `0x80` (ekstrapolasi) |
| D-pad Up/Down/Left/Right | 12-15 | [31].bit0-3 | `0x01/02/04/08` (ekstrapolasi) |

### Analog Sticks — TERVERIFIKASI ✅

Format: **signed 16-bit little-endian**, range -32768…32767.

| Axis | Offset | Tes | Hasil |
|---|---|---|---|
| Left X | [32-33] | set -1.0 | `00 80` = -32768 ✅ |
| Left Y | [34-35] | set +0.5 | `00 c0` = -16384 ⚠️ |
| Right X | [36-37] | set +0.25 | `ff 1f` = 8191 ✅ |
| Right Y | [38-39] | set -0.75 | `ff 5f` = 24575 ⚠️ |

**PENTING: sumbu Y di-NEGATE** relatif terhadap konvensi W3C Gamepad API! X normal, Y kebalik. Untuk implementasi client: `packet_y = -w3c_y`. Kalau tidak, atas-bawah stick kebalik di game.

Terverifikasi juga: buttons dan axes **independen** — paket release (`[29]=00`) tetap membawa nilai axis terakhir.

### Cara Membangun Paket (pseudocode)

```python
import struct

def build_gamepad_packet(buttons_mask_24bit, lx, ly, rx, ry):
    # buttons: bit (4+i) untuk tombol i
    # sticks: float -1.0..1.0, Y di-negate!
    pkt = bytearray(54)
    struct.pack_into('<I', pkt, 0, 0x23)       # magic (byte 4 = 00)
    pkt[4] = 0x00
    # [5-8]: timestamp (monotonik)
    pkt[9] = 0x26
    pkt[13] = 0x21
    pkt[15] = 0x26
    struct.pack_into('<I', pkt, 16, 12)
    pkt[20] = 0x1a
    pkt[24] = 0x01; pkt[25] = 0x01
    pkt[26] = 0x14
    # [29-31]: button bitmask (24-bit LE)
    pkt[29] = buttons_mask_24bit & 0xFF
    pkt[30] = (buttons_mask_24bit >> 8) & 0xFF
    pkt[31] = (buttons_mask_24bit >> 16) & 0xFF
    # sticks: int16 LE, Y negated
    struct.pack_into('<h', pkt, 32, int(lx * 32767))
    struct.pack_into('<h', pkt, 34, int(-ly * 32767))  # NOTE: negate Y!
    struct.pack_into('<h', pkt, 36, int(rx * 32767))
    struct.pack_into('<h', pkt, 38, int(-ry * 32767))  # NOTE: negate Y!
    pkt[42] = 0x55
    # [47-49]: echo 3 byte pertama timestamp
    return bytes(pkt)

# Contoh: tekan A + stick kiri kanan penuh
pkt = build_gamepad_packet(0x10, 1.0, 0.0, 0.0, 0.0)
# Kirim via DataChannel input_channel_partially_reliable
```

---

## Stats Channel

Format (12 bytes):
```
01 01 00 00  09 00 0c 00  00 00 00 00
01 01 00 00  0b 00 0c 00  00 00 00 00
02 02 02 00  00 00 01 9a d7 6f  00 00 00 00  00 67 fa 40
```
Kemungkinan: heartbeat + statistik performa client. Tidak kritis untuk client minimal.

---

## Cara Mereproduksi (untuk verifikasi)

1. Buka `play.geforcenow.com` di Firefox/Chrome — **satu tab saja** (tab ganda menipu: halaman detail game juga ada video trailer!)
2. Klik Play → tunggu game tampil → F12 di **tab yang sama**
3. Tempel collector (satu baris) — PENTING: baca ukuran pesan via `byteOffset`/`byteLength`, JANGAN `d.buffer` mentah (view ke buffer besar akan salah ukur dan lolos filter):
```javascript
window.__pk=[];(function(){const o=RTCDataChannel.prototype.send;RTCDataChannel.prototype.send=function(d){if(this.label&&this.label.includes("input")){try{let u;if(d instanceof Blob){d.arrayBuffer().then(ab=>{const x=new Uint8Array(ab);if(x.length<=128)window.__pk.push(x.length+"B "+[...x].map(b=>b.toString(16).padStart(2,"0")).join(" "))});}else{if(d instanceof ArrayBuffer)u=new Uint8Array(d);else if(ArrayBuffer.isView(d))u=new Uint8Array(d.buffer,d.byteOffset,d.byteLength);else return o.call(this,d);if(u.length<=128)window.__pk.push(u.length+"B "+[...u].map(b=>b.toString(16).padStart(2,"0")).join(" "))}}catch(e){}}return o.call(this,d)}})();console.log("armed");
```
4. Lakukan SATU jenis aksi (JANGAN campur): gerak mouse saja, atau klik saja
5. `copy(window.__pk.slice(-8).join("\n"))` → paste sebagai TEKS (jangan screenshot — byte kepotong susah dianalisis)
6. Verifikasi tab benar: `document.querySelectorAll('video').length` ≥ 1 DAN `location.href` = play.geforcenow.com — tapi ingat, halaman detail juga lolos kedua cek ini; patokannya: **tab yang gambar gamenya tampil**

Gotcha yang ditemukan 2026-10-19:
- Patch `RTCDataChannel.prototype.send` **mati saat navigasi** — tempel ulang setelah klik PLAY
- Paket input hanya ter-generate saat pointer **di atas elemen video** (gerak di atas DevTools tidak menghasilkan paket)
- `about:webrtc` → `dataChannelStats` untuk verifikasi channel terbuka & counter `messagesSent` (tidak perlu refresh jika Auto Refresh on)

---

## Implikasi

Dengan protokol keyboard, mouse, DAN gamepad yang terpecahkan, client WebRTC minimal yang bisa **mengirim semua input** sudah feasible:
1. WebSocket signaling ke `sign_in?peer_id=...`
2. SDP offer/answer (ikuti format di `GFN_SDP_OFFER.md`)
3. ICE + DTLS handshake (libwebrtc)
4. Buka DataChannel `input_channel_v1` (keyboard/mouse) + `input_channel_partially_reliable` (gamepad)
5. Kirim paket 28-byte per key/button event, 36-byte untuk posisi mouse absolut, 54-byte untuk state gamepad

Video/audio diterima via SRTP standar (libwebrtc handle otomatis).

Sisa roadmap: **implementasi client libwebrtc** (semua input sudah terdecode!).

---

*Catatan: nilai timestamp/sequence dan context ID pada contoh adalah dari sesi nyata yang sudah kedaluwarsa. Untuk implementasi, generate nilai monotonik sendiri.*
*Update 2026-10-19: mouse (47B/28B/36B) + gamepad (54B) terdecode via capture terkontrol di Firefox. Detail lengkap di section masing-masing.*
