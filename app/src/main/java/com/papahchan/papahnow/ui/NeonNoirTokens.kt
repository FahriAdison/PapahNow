package com.papahchan.papahnow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Neon-Noir 2.0 Design Tokens for PapahNow.
 * Strictly adheres to the approved Neon-Noir 2.0 specification:
 * - Base: #0A0E1A (nearly black deep navy)
 * - Accent: #FFB224 (Amber)
 * - 3 Disciplinary Radii: 8dp (sm), 10dp (md), 12dp (lg)
 * - Touch Target: Minimum 44x44 dp
 * - Zero purple/blue gradients, zero glassmorphism, zero AI robot avatars.
 */
object NeonNoir {
    // Base & Surfaces
    val Ink950 = Color(0xFF0A0E1A) // Deep Navy base for all screens
    val Ink900 = Color(0xFF0D1424) // Cards, panels, search field
    val Ink850 = Color(0xFF101A2E) // Secondary subtle surface
    val Ink800 = Color(0xFF131E33) // Raised surface & selected states

    // Lines & Borders
    val Line = Color(0xFF1E2A45)     // 1px standard border
    val LineSoft = Color(0xFF182238) // Secondary soft line

    // Primary Accents
    val Amber = Color(0xFFFFB224)    // Primary action & active state (10.3:1 contrast)
    val AmberDeep = Color(0xFFE09500)// Pressed amber state
    val AmberInk = Color(0xFF1A1206) // Dark ink on amber button

    // Typography & Contrast (WCAG AA)
    val Txt = Color(0xFFE9EDF5)      // Primary text: headings & titles (16.4:1)
    val Txt2 = Color(0xFF9AA5BB)     // Secondary text
    val Txt3 = Color(0xFF8695B0)     // Tertiary metadata (~5.7:1 contrast on #0A0E1A)

    // Functional Accents
    val Danger = Color(0xFFFF6A4D)   // Disconnect / Errors / Hot tag
    val Online = Color(0xFF2FD67B)   // Online status dot (Jakarta telemetry)

    // 3 Disciplinary Radii
    val RadiusSm = 8.dp
    val RadiusMd = 10.dp
    val RadiusLg = 12.dp

    // Touch targets
    val MinTouchTarget = 44.dp
}

data class NeonGame(
    val id: String,
    val title: String,
    val meta: String,
    val inputBadge: String, // "🎮" or "👆"
    val tag: String? = null,
    val tagType: String? = null, // "free", "new", "hot", "trial"
    val sessionProgress: String? = null,
    val progressPercent: Float = 0f,
    val savedTime: String? = null,
    val category: String = "all", // "all", "controller", "touch", "free", "coop"
    val gradientTop: Color = Color(0xFF182034),
    val gradientBottom: Color = Color(0xFF0A0E1A)
)

data class HeroSlide(
    val id: String,
    val title: String,
    val chip: String,
    val meta: String,
    val rigTag: String,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color
)
