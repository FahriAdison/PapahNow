# GFN Android — Auth Flow Documentation (Phase 1)

**Sumber:** Analisis statis APK resmi GeForce NOW v6.39.39022815
**Tanggal:** 2026-10-18
**Metode:** Decompile via JADX + analisis web assets, grep manual di terminal VPS

---

## Ringkasan Alur

```
Web UI (Angular di assets/mall/)
    → kirim JSON {url} via Bridge → vu0.java
        → MallActivity.C(url)
            → Intent AUTH_ACTION → AuthActivity
                → Buka URL di Chrome Custom Tabs
                    → User login di login.nvidia.com
                        → Redirect ke nvidia://auth_redirect
                            → ACTION_REDIRECT → AuthActivity selesai
```

---

## Temuan Detail

### 1. Endpoint Auth
- **URL:** `https://login.nvidia.com/`
- **Nama internal:** "Starfleet" (`DebugUtils.getStarfleetEndpointUri(...)`)
- **File:** `defpackage/ag1.java:17`

### 2. Konfigurasi OAuth (dari `assets/mall/shared/assets/config/config.json`)

**Top-level `starfleet` (platformType: Browser):**
| Key | Value |
|---|---|
| `url` | `https://login.nvidia.com` |
| `logoutUrl` | `https://static-login.nvidia.com/service/gfn/logout-start` |
| `keyValueServiceUrl` | `https://userstore.nvidia.com/v1` |
| `settingsUrl` | `https://accounts.nvgs.nvidia.com/api/1` |
| `settingsClientId` | `156448804920361176` |
| `deviceId` | `gfnclient` |
| `clientId` | `ZU7sPN-miLujMD95LfOQ453IB0AtjM8sMyvgJ9wCXEQ` |
| `scope` | `openid consent email tk_client age` |
| `redirectUrl` | `http://localhost:{portnumber}` |
| `portNumbers` | `[2259, 6460, 7119, 8870, 9096]` |
| `useAuthorizationCodeFlow` | `true` |
| `jarvisIdpId` | `PDiAhv2kJTFeQ7WOPqiQ2tRZ7lGhR2X11dXvM4TZSxg` |
| `clientTokenRefreshDuration` | `2332800000` ms (~27 hari) |
| `clientTokenRefreshDurationInPercentage` | `30` |

**`androidConfig.starfleet` (khusus Android):**
```json
{
  "clientId": "Lgg_4meEdcNzoj7z2grMvEzUN1iB7g8VEdyYaUrvhms",
  "redirectUrl": "nvidia://auth_redirect",
  "redirectUrlIsRelative": false,
  "useNativeAuthCommands": true
}
```

### 3. Koreksi: Bukan Native C++!
- Awalnya diduga URL dibuat di native code (`.so`), TERNYATA SALAH
- `NativeAuthTypes$initiateLogInParameters` adalah **data class biasa** (satu field `url`), di-parse dari JSON via Gson
- Kata "Native" di sini = "auth bawaan" (vs Google OAuth), bukan JNI/C++
- URL-nya **dibuat di JavaScript** web app (Angular di `assets/mall/`)
- APK tidak mengandung file `.so` sama sekali (base APK tanpa native libs)

### 4. Bridge Web → Native
- **File:** `com/nvidia/geforcenow/bridgeService/commands/NativeAuth/`
- Web UI kirim JSON → `vu0.java` parse → panggil `MallActivity.C(url)`
- Class: `NativeAuthTypes$initiateLogInParameters`, `NativeAuthTypes$SessionData`, dll

### 5. UI Login (AuthActivity)
- **File:** `com/nvidia/geforcenow/account/ui/touch/activity/AuthActivity.java` (169 baris)
- Menerima intent dengan action `AUTH_ACTION` + URL sebagai data
- Membuka URL di **Chrome Custom Tabs** (bukan WebView langsung)
- Khusus Meta Quest: buka via Oculus browser (`ovrweb://`)
- Ada flag anti-phishing khusus Motorola

### 6. Callback Setelah Login
- Redirect ke custom scheme `nvidia://auth_redirect`
- Action `ACTION_REDIRECT` → `setResult(-1, intent)` → `finish()`
- Hasil dikembalikan ke `MallActivity` via `startActivityForResult(..., 12332)`

### 7. Penyimpanan Token
- Token disimpan di **Android AccountManager** (tipe akun `com.nvidia`)
- Token: `accessToken`, `idToken`, `clientToken` (+ masing-masing expiry)
- Field user: `sub`, `preferredUserName`, `idpId`, `idpName`, `externalId`
- **File:** `defpackage/vu0.java` (~baris 140-170)

### 8. Metode Auth yang Didukung
- Server memberi tahu client via `ServerInfo.AuthType_Enum`:
  - `NO_AUTH`
  - `GOOGLE_OAUTH`
  - (dan lainnya — belum di-list lengkap)
- **File:** `com/nvidia/message/v2/ServerInfo.java`

---

## Yang Masih Belum Diketahui
- Cara tepat URL auth dirakit di JS (parameter query apa saja) → **TERJAWAB! Lihat bagian 9**
- Semua nilai `AuthType_Enum`
- Detail refresh token flow (interval sudah diketahui: 30% dari 27 hari)

### 9. Template URL Authorize (dari `assets/mall/main.8c99e916bb297b05.js`)
**OAuth 2.0 + PKCE** (Proof Key for Code Exchange):
```
https://login.nvidia.com/authorize?
  response_type=code
  &device_id=:deviceId
  &scope=:scope
  &client_id=:clientId
  &redirect_uri=:redirectUri
  &ui_locales=:locale
  &nonce=:nonce
  &prompt=select_account
  &code_challenge=:codeChallenge
  &code_challenge_method=S256
```
- Ada 3 varian template: `id_token+token` (implicit), `code` (auth code), `code`+`scope=user_token`
- Fungsi terkait: `StarfleetGetAccessToken`, `StarfleetGetClientToken`, `StarfleetGetUserInfo`
- Bridge command: `CrimsonNative` → `NativeAuth` → `initiateLogIn`

## Opsi Lanjutan
- **Bedah JS web app** (`assets/mall/*.js`) — cari fungsi yang rakit URL login
- **Capture network** pakai mitmproxy pas login beneran

---

## Catatan
- Dokumentasi ini murni dari analisis kode statis
- Tidak ada kredensial asli yang digunakan
- Tidak ada request yang dikirim ke server NVIDIA
