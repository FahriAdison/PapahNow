package com.papahchan.papahnow.ui

import androidx.compose.ui.graphics.Color

object NeonCatalog {
    val heroSlides = listOf(
        HeroSlide(
            id = "wangan",
            title = "Wangan Midnight: C1 Loop",
            chip = "Rig Siap • Tanpa Antrean",
            meta = "🎮 Controller • 120 FPS • RTX On",
            rigTag = "RTX On",
            bgTop = Color(0xFF070B16),
            bgMid = Color(0xFF1A1228),
            bgBottom = Color(0xFF080B12)
        ),
        HeroSlide(
            id = "kurogane",
            title = "Kurogane: Iron Mandate",
            chip = "Eksklusif Cloud • Patch 2.4",
            meta = "🎮 Controller • 60 FPS • Ray Tracing",
            rigTag = "Ray Tracing",
            bgTop = Color(0xFF0A0F1D),
            bgMid = Color(0xFF142036),
            bgBottom = Color(0xFF070A12)
        ),
        HeroSlide(
            id = "sektor7",
            title = "Sektor 7: Karantina",
            chip = "Gratis Akhir Pekan • Co-op",
            meta = "👆 Touch & Pad • 60 FPS • Co-op 4 Pemain",
            rigTag = "Co-op 4 Pemain",
            bgTop = Color(0xFF080D18),
            bgMid = Color(0xFF1E172B),
            bgBottom = Color(0xFF080B12)
        )
    )

    val resumeSessions = listOf(
        NeonGame(
            id = "proyek-hantu",
            title = "Proyek Hantu: Jakarta 2049",
            meta = "Tersimpan di Cloud • 14m lalu",
            inputBadge = "🎮",
            sessionProgress = "Bab 4 • 68%",
            progressPercent = 0.68f,
            savedTime = "14m lalu",
            category = "controller",
            gradientTop = Color(0xFF151C2C),
            gradientBottom = Color(0xFF0B101C)
        ),
        NeonGame(
            id = "wangan-resume",
            title = "Wangan Midnight: C1 Loop",
            meta = "Tersimpan di Cloud • 2j lalu",
            inputBadge = "🎮",
            sessionProgress = "Sesi Siap",
            progressPercent = 1.0f,
            savedTime = "2j lalu",
            category = "controller",
            gradientTop = Color(0xFF1A1624),
            gradientBottom = Color(0xFF0A0E1A)
        ),
        NeonGame(
            id = "majapahit-resume",
            title = "Kronik Majapahit: Senjakala",
            meta = "Tersimpan di Cloud • Kemarin",
            inputBadge = "👆",
            sessionProgress = "Bab 2 • 35%",
            progressPercent = 0.35f,
            savedTime = "Kemarin",
            category = "touch",
            gradientTop = Color(0xFF1C1820),
            gradientBottom = Color(0xFF0A0D18)
        )
    )

    val rtxRail = listOf(
        NeonGame(
            id = "kurogane-rtx",
            title = "Kurogane: Iron Mandate",
            meta = "Controller • 60 FPS",
            inputBadge = "🎮",
            tag = "RTX On",
            tagType = "new",
            category = "controller",
            gradientTop = Color(0xFF131D30),
            gradientBottom = Color(0xFF090E1A)
        ),
        NeonGame(
            id = "gravel-tarmac",
            title = "Gravel & Tarmac: Rally ID",
            meta = "Controller • 120 FPS",
            inputBadge = "🎮",
            tag = "RTX On",
            tagType = "new",
            category = "controller",
            gradientTop = Color(0xFF1A1F2D),
            gradientBottom = Color(0xFF090D18)
        ),
        NeonGame(
            id = "gema-runtuh",
            title = "Gema Runtuh: Stasiun Nol",
            meta = "Controller • RTX On",
            inputBadge = "🎮",
            tag = "Baru",
            tagType = "hot",
            category = "controller",
            gradientTop = Color(0xFF201625),
            gradientBottom = Color(0xFF0A0D18)
        ),
        NeonGame(
            id = "pecinan-noir",
            title = "Pecinan Noir: Kasus 404",
            meta = "Touch • RTX On",
            inputBadge = "👆",
            tag = "RTX On",
            tagType = "new",
            category = "touch",
            gradientTop = Color(0xFF151D2A),
            gradientBottom = Color(0xFF090E1A)
        ),
        NeonGame(
            id = "skuadron-garuda",
            title = "Skuadron Garuda: Langit Timur",
            meta = "Controller • 60 FPS",
            inputBadge = "🎮",
            category = "controller",
            gradientTop = Color(0xFF121A2C),
            gradientBottom = Color(0xFF080C16)
        ),
        NeonGame(
            id = "benthic",
            title = "Benthic: Palung Banda",
            meta = "Touch • Tanpa Antrean",
            inputBadge = "👆",
            category = "touch",
            gradientTop = Color(0xFF0E1A2B),
            gradientBottom = Color(0xFF070B14)
        )
    )

    val freeRail = listOf(
        NeonGame(
            id = "sektor7-free",
            title = "Sektor 7: Karantina",
            meta = "Co-op • Tanpa Antrean",
            inputBadge = "👆",
            tag = "Gratis",
            tagType = "free",
            category = "free",
            gradientTop = Color(0xFF171324),
            gradientBottom = Color(0xFF090D18)
        ),
        NeonGame(
            id = "valhalla-1999",
            title = "Valhalla Express 1999",
            meta = "Touch • 60 FPS",
            inputBadge = "👆",
            tag = "Gratis",
            tagType = "free",
            category = "free",
            gradientTop = Color(0xFF181B26),
            gradientBottom = Color(0xFF090D18)
        ),
        NeonGame(
            id = "arena-baja",
            title = "Arena Baja: Knockout",
            meta = "Controller • 60 FPS",
            inputBadge = "🎮",
            tag = "Gratis",
            tagType = "free",
            category = "free",
            gradientTop = Color(0xFF1D1828),
            gradientBottom = Color(0xFF0A0E1A)
        ),
        NeonGame(
            id = "morotai",
            title = "Benteng Terakhir: Morotai",
            meta = "Co-op • Tanpa Antrean",
            inputBadge = "👆",
            tag = "Gratis",
            tagType = "free",
            category = "coop",
            gradientTop = Color(0xFF131C28),
            gradientBottom = Color(0xFF080D16)
        ),
        NeonGame(
            id = "kaldera",
            title = "Ekspedisi Kaldera",
            meta = "Co-op • 60 FPS",
            inputBadge = "👆",
            tag = "Gratis",
            tagType = "free",
            category = "coop",
            gradientTop = Color(0xFF1A1722),
            gradientBottom = Color(0xFF090D18)
        ),
        NeonGame(
            id = "relik-kuno",
            title = "Relik Kuno: Labirin Batu",
            meta = "Touch • Tanpa Antrean",
            inputBadge = "👆",
            tag = "Gratis",
            tagType = "free",
            category = "free",
            gradientTop = Color(0xFF141925),
            gradientBottom = Color(0xFF080C16)
        ),
        NeonGame(
            id = "sentul-gt",
            title = "Sirkuit Sentul: GT Pro",
            meta = "Controller • 120 FPS",
            inputBadge = "🎮",
            tag = "Uji Coba",
            tagType = "new",
            category = "controller",
            gradientTop = Color(0xFF191D2B),
            gradientBottom = Color(0xFF090D18)
        )
    )

    val allGames: List<NeonGame> by lazy {
        val list = mutableListOf<NeonGame>()
        list.addAll(resumeSessions)
        list.addAll(rtxRail)
        list.addAll(freeRail)
        list.distinctBy { it.title }
    }
}
