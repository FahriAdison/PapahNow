# GFN Android — Catalog API Documentation (Phase 2)

**Sumber:** Analisis `assets/mall/` dari APK resmi GeForce NOW v6.39.39022815
**Tanggal:** 2026-10-18
**Metode:** Extract web assets, grep JS + config.json di terminal VPS

---

## Endpoint

| Item | Value |
|---|---|
| **GraphQL URL** | `https://apps.gxn.nvidia.com/graphql` |
| **Client ID** | `ec7e38d4-03af-4b58-b131-cfb0495903ab` |
| **Content-Type** | `application/graphql` |
| **Config source** | `assets/mall/shared/assets/config/config.json` → `lcars` |

Bisa juga via proxy: `proxyConfig.proxyUrl + "/graphql"`

---

## Contoh Query

### Get App Data by CMS ID
```graphql
query GetAppDataQueryForCmsId($vpcId: String!, $locale: String!, $cmsIds: [Int]!) {
  apps(vpcId: $vpcId, language: $locale, variantIds: $cmsIds) {
    items {
      appStore
      contentRatings {
        categoryKey
        contentDescriptorKeys
        interactiveElementKeys
        ...
      }
      ...
    }
  }
}
```

---

## Daftar Query yang Ditemukan

| Query Name | Kemungkinan Fungsi |
|---|---|
| `GetAppDataQueryForAppId` | Detail game by App ID |
| `GetAppDataQueryForCmsId` | Detail game by CMS ID |
| `GetAppDataQueryForCmsIdWithoutVpcId` | Detail game tanpa VPC |
| `GetAppDataQueryForStoreGame` | Detail game dari store |
| `GetAppsPatchInfoWithLibraryFilter` | Info patch/update game |
| `GetCampaignsInfo` | Info kampanye/promo |
| `GetFilterGroupAndSortOrderDefinitions` | Definisi filter & sorting |
| `GetGameSection` | Section/rail game |
| `GetLoginWallData` | Data login wall |
| `GetLoginWallStrings` | String login wall |

---

## API Terkait Lainnya

| Endpoint | Fungsi |
|---|---|
| `https://prod.cloudmatchbeta.nvidiagrid.net/` | Cloudmatch (session/matchmaking) |
| `https://api.gdn.nvidia.com/cloudvariables/v3` | Cloud variables |
| `https://userstore.nvidia.com/v1` | Key-value user store |

---

## Catatan
- Butuh auth token untuk akses (dari Phase 1)
- `vpcId` kemungkinan = region/virtual PC ID
- `locale` = bahasa (misal `en-US`, `id-ID`)
