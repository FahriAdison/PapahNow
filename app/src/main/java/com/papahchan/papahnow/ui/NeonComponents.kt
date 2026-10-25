package com.papahchan.papahnow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Top Appbar for Neon-Noir 2.0.
 * Features:
 * - Brand wordmark "PAPAHNOW" with amber indicator dot
 * - Telemetry pill with online dot and single-source latency ("estimasi")
 * - Notification bell & user avatar "P"
 * - Touch targets >= 44x44 dp
 */
@Composable
fun NeonTopBar(
    latencyText: String = "ID-JKT • estimasi",
    onTelemetryClick: () -> Unit = {},
    onBellClick: () -> Unit = {},
    onAvatarClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xD90A0E1A))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand & Telemetry Cluster
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(NeonNoir.Amber, CircleShape)
                )
                Text(
                    text = "PAPAH",
                    color = NeonNoir.Txt,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "NOW",
                    color = NeonNoir.Amber,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Telemetry Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(NeonNoir.RadiusSm))
                    .background(NeonNoir.Ink900)
                    .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                    .clickable { onTelemetryClick() }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(NeonNoir.Online, CircleShape)
                    )
                    Text(
                        text = latencyText,
                        color = NeonNoir.Txt2,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Action icons: Bell & Avatar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBellClick,
                modifier = Modifier.size(NeonNoir.MinTouchTarget)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint = NeonNoir.Txt2,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(NeonNoir.MinTouchTarget)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(NeonNoir.Ink850, CircleShape)
                        .border(1.dp, NeonNoir.Line, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "P",
                        color = NeonNoir.Txt,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * True Full-Bleed Hero Carousel for Neon-Noir 2.0.
 * Touching edge-to-edge with scrim gradient fading downward into #0A0E1A.
 */
@Composable
fun NeonHeroCarousel(
    slides: List<HeroSlide> = NeonCatalog.heroSlides,
    onLaunchClick: (HeroSlide) -> Unit,
    onLibraryClick: () -> Unit
) {
    if (slides.isEmpty()) return
    var activeIndex by remember { mutableIntStateOf(0) }
    val slide = slides[activeIndex.coerceIn(0, slides.size - 1)]

    var totalDragX by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(370.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(slide.bgTop, slide.bgMid, slide.bgBottom)
                )
            )
            .pointerInput(slides.size) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDragX += dragAmount },
                    onDragEnd = {
                        if (totalDragX < -60f) {
                            activeIndex = (activeIndex + 1) % slides.size
                        } else if (totalDragX > 60f) {
                            activeIndex = if (activeIndex > 0) activeIndex - 1 else slides.size - 1
                        }
                        totalDragX = 0f
                    }
                )
            }
    ) {
        // Dark Scrim Gradient for text contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0x590A0E1A),
                        0.35f to Color(0x1F0A0E1A),
                        0.68f to Color(0xD60A0E1A),
                        1.0f to NeonNoir.Ink950
                    )
                )
        )

        // Slide Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // Chip row & Touch Dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Chip badge
                Box(
                    modifier = Modifier
                        .background(Color(0xD20A0E1A), RoundedCornerShape(NeonNoir.RadiusSm))
                        .border(1.dp, Color(0x73FFB224), RoundedCornerShape(NeonNoir.RadiusSm))
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = slide.chip.uppercase(),
                        color = NeonNoir.Amber,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                // Touch-friendly 44px dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    slides.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = NeonNoir.MinTouchTarget)
                                .clickable { activeIndex = index },
                            contentAlignment = Alignment.Center
                        ) {
                            val isOn = index == activeIndex
                            Box(
                                modifier = Modifier
                                    .size(width = if (isOn) 22.dp else 8.dp, height = 8.dp)
                                    .background(
                                        if (isOn) NeonNoir.Amber else Color(0x59E9EDF5),
                                        RoundedCornerShape(NeonNoir.RadiusSm)
                                    )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Game Title
            Text(
                text = slide.title,
                color = NeonNoir.Txt,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Meta Row
            Text(
                text = slide.meta,
                color = NeonNoir.Txt2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(14.dp))

            // CTA Buttons Row (min-height 44dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onLaunchClick(slide) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonNoir.Amber,
                        contentColor = NeonNoir.AmberInk
                    ),
                    shape = RoundedCornerShape(NeonNoir.RadiusMd),
                    modifier = Modifier
                        .heightIn(min = NeonNoir.MinTouchTarget)
                        .weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = NeonNoir.AmberInk,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Mainkan (Preview)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                Button(
                    onClick = onLibraryClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xC70D1424),
                        contentColor = NeonNoir.Txt
                    ),
                    shape = RoundedCornerShape(NeonNoir.RadiusMd),
                    modifier = Modifier
                        .heightIn(min = NeonNoir.MinTouchTarget)
                        .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                ) {
                    Text(
                        text = "+ Pustaka (Preview)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Mobile Search Bar Trigger (taps open the Command Palette).
 */
@Composable
fun NeonSearchBar(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .height(NeonNoir.MinTouchTarget)
            .clip(RoundedCornerShape(NeonNoir.RadiusMd))
            .background(NeonNoir.Ink900)
            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = NeonNoir.Txt3,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Cari judul, metode input, atau mode…",
                    color = NeonNoir.Txt3,
                    fontSize = 13.sp
                )
            }

            Box(
                modifier = Modifier
                    .background(NeonNoir.Ink850, RoundedCornerShape(NeonNoir.RadiusSm))
                    .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Filter",
                    color = NeonNoir.Txt2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Rail 1: Landscape 16:9 "Lanjutkan Sesi" (Riwayat Cloud).
 * Features 216x122 dp cover with bottom progress bar and input badge.
 */
@Composable
fun NeonRailLandscape(
    title: String = "Lanjutkan Sesi (Konsep / Preview)",
    subtitle: String = "Konsep / Preview",
    items: List<NeonGame> = NeonCatalog.resumeSessions,
    onGameClick: (NeonGame) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = NeonNoir.Txt,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
            Text(
                text = subtitle,
                color = NeonNoir.Txt3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.width(6.dp)) }
            items(items) { game ->
                Column(
                    modifier = Modifier
                        .width(216.dp)
                        .clickable { onGameClick(game) }
                ) {
                    // Cover box (16:9 -> 216x122 dp)
                    Box(
                        modifier = Modifier
                            .size(width = 216.dp, height = 122.dp)
                            .clip(RoundedCornerShape(NeonNoir.RadiusMd))
                            .background(
                                Brush.verticalGradient(
                                    listOf(game.gradientTop, game.gradientBottom)
                                )
                            )
                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                    ) {
                        // Preview badge top-left
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color(0xD60A0E1A), RoundedCornerShape(NeonNoir.RadiusSm))
                                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PREVIEW",
                                color = NeonNoir.Txt2,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Input badge top-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xD60A0E1A), RoundedCornerShape(NeonNoir.RadiusSm))
                                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = game.inputBadge,
                                fontSize = 11.sp
                            )
                        }

                        // Mini play badge bottom-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 12.dp, end = 8.dp)
                                .size(30.dp)
                                .background(NeonNoir.Amber, RoundedCornerShape(NeonNoir.RadiusSm)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = NeonNoir.AmberInk,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Progress bar at bottom edge
                        if (game.sessionProgress != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(Color(0xD90A0E1A))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(game.progressPercent.coerceIn(0.1f, 1f))
                                        .height(4.dp)
                                        .background(NeonNoir.Amber)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = game.title,
                        color = NeonNoir.Txt,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(NeonNoir.Amber, CircleShape)
                        )
                        Text(
                            text = game.meta,
                            color = NeonNoir.Txt3,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.width(6.dp)) }
        }
    }
}

/**
 * Rails 2 & 3: Portrait Cards (124x156 dp cover).
 */
@Composable
fun NeonRailPortrait(
    title: String,
    actionLabel: String = "Lihat semua",
    items: List<NeonGame>,
    onGameClick: (NeonGame) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = NeonNoir.Txt,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
            Text(
                text = actionLabel,
                color = NeonNoir.Txt2,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.width(6.dp)) }
            items(items) { game ->
                Column(
                    modifier = Modifier
                        .width(124.dp)
                        .clickable { onGameClick(game) }
                ) {
                    // Portrait Cover (124x156 dp)
                    Box(
                        modifier = Modifier
                            .size(width = 124.dp, height = 156.dp)
                            .clip(RoundedCornerShape(NeonNoir.RadiusMd))
                            .background(
                                Brush.verticalGradient(
                                    listOf(game.gradientTop, game.gradientBottom)
                                )
                            )
                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                    ) {
                        // Tag top-left
                        val tagText = game.tag ?: "Preview"
                        val (tagBg, tagColor, tagBorder) = when (game.tagType) {
                            "free" -> Triple(NeonNoir.Amber, NeonNoir.AmberInk, NeonNoir.Amber)
                            "hot" -> Triple(Color(0xE00A0E1A), NeonNoir.Danger, Color(0x80FF6A4D))
                            else -> Triple(Color(0xE00A0E1A), NeonNoir.Amber, Color(0x73FFB224))
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                                .background(tagBg, RoundedCornerShape(NeonNoir.RadiusSm))
                                .border(1.dp, tagBorder, RoundedCornerShape(NeonNoir.RadiusSm))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            val displayTag = if (tagText.contains("Preview", ignoreCase = true)) {
                                tagText.uppercase()
                            } else {
                                "${tagText.uppercase()} • PREVIEW"
                            }
                            Text(
                                text = displayTag,
                                color = tagColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Input badge top-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(7.dp)
                                .background(Color(0xD60A0E1A), RoundedCornerShape(NeonNoir.RadiusSm))
                                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = game.inputBadge,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = game.title,
                        color = NeonNoir.Txt,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = game.meta,
                        color = NeonNoir.Txt3,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item { Spacer(modifier = Modifier.width(6.dp)) }
        }
    }
}

/**
 * Diagnostics & Debug Trigger Card for Phase 1 + 2.
 * Fully restyled in Neon-Noir 2.0 theme (#0D1424 surface, #1E2A45 border, #FFB224 amber).
 */
@Composable
fun NeonDiagnosticsCard(
    isGamepadOpen: Boolean,
    isControlOpen: Boolean,
    gamepadChannelId: Int,
    controlChannelId: Int,
    packetsSent: Int,
    debugStatus: String,
    onTestGamepadClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NeonNoir.Ink900),
        shape = RoundedCornerShape(NeonNoir.RadiusMd),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "🎮", fontSize = 14.sp)
                    Text(
                        text = "Gamepad DataChannel",
                        color = NeonNoir.Txt,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }

                val statusText = if (isGamepadOpen) "OPEN (id $gamepadChannelId)" else "CLOSED"
                val statusColor = if (isGamepadOpen) NeonNoir.Amber else NeonNoir.Danger
                Text(
                    text = statusText,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Control channel & TX count row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Control: ${if (isControlOpen) "OPEN (id $controlChannelId)" else "CLOSED"}",
                    color = if (isControlOpen) NeonNoir.Txt2 else NeonNoir.Txt3,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "TX Packets: $packetsSent",
                    color = NeonNoir.Txt2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (debugStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = debugStatus,
                    color = NeonNoir.Amber,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Test Gamepad Button (Amber #FFB224, min height 44dp)
            Button(
                onClick = onTestGamepadClick,
                enabled = isGamepadOpen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonNoir.Amber,
                    contentColor = NeonNoir.AmberInk,
                    disabledContainerColor = NeonNoir.Ink800,
                    disabledContentColor = NeonNoir.Txt3
                ),
                shape = RoundedCornerShape(NeonNoir.RadiusMd),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NeonNoir.MinTouchTarget)
            ) {
                Text(
                    text = "Test Gamepad (A + Sticks)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Command Palette / Mobile Search Sheet with Filter Chips.
 */
@Composable
fun NeonCommandPalette(
    onDismiss: () -> Unit,
    onSelectGame: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf("all") }

    val filterChips = listOf(
        "all" to "Semua",
        "controller" to "Controller",
        "touch" to "Touch",
        "free" to "Gratis",
        "coop" to "Co-op"
    )

    val filteredGames = remember(query, activeFilter) {
        NeonCatalog.allGames.filter { game ->
            val matchesQuery = query.isBlank() ||
                game.title.contains(query, ignoreCase = true) ||
                game.meta.contains(query, ignoreCase = true)
            val matchesFilter = when (activeFilter) {
                "all" -> true
                "controller" -> game.inputBadge == "🎮" || game.category == "controller"
                "touch" -> game.inputBadge == "👆" || game.category == "touch"
                "free" -> game.tagType == "free" || game.category == "free"
                "coop" -> game.category == "coop" || game.meta.contains("co-op", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC03050A))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NeonNoir.Ink900),
                shape = RoundedCornerShape(NeonNoir.RadiusLg),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 580.dp)
                    .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusLg))
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    // Search Input Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeonNoir.Ink850, RoundedCornerShape(NeonNoir.RadiusMd))
                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusMd))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = NeonNoir.Txt3,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            textStyle = TextStyle(
                                color = NeonNoir.Txt,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(NeonNoir.Amber),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(NeonNoir.MinTouchTarget)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = NeonNoir.Txt2,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Filter Chips Row
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filterChips) { (key, label) ->
                            val isSelected = activeFilter == key
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(NeonNoir.RadiusSm))
                                    .background(if (isSelected) NeonNoir.Ink800 else NeonNoir.Ink850)
                                    .border(
                                        1.dp,
                                        if (isSelected) NeonNoir.Amber else NeonNoir.Line,
                                        RoundedCornerShape(NeonNoir.RadiusSm)
                                    )
                                    .clickable { activeFilter = key }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) NeonNoir.Amber else NeonNoir.Txt2,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Results List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (filteredGames.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Tidak ada game yang cocok dengan filter ini.\nCoba pilih filter 'Semua' atau kata kunci lain.",
                                        color = NeonNoir.Txt3,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                        } else {
                            items(filteredGames) { game ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(NeonNoir.RadiusMd))
                                        .background(NeonNoir.Ink850)
                                        .border(1.dp, NeonNoir.LineSoft, RoundedCornerShape(NeonNoir.RadiusMd))
                                        .clickable {
                                            onDismiss()
                                            onSelectGame(game.title)
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Thumbnail
                                    Box(
                                        modifier = Modifier
                                            .size(width = 40.dp, height = 48.dp)
                                            .clip(RoundedCornerShape(NeonNoir.RadiusSm))
                                            .background(game.gradientTop)
                                            .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = game.inputBadge, fontSize = 14.sp)
                                    }

                                    // Titles & metadata
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = game.title,
                                            color = NeonNoir.Txt,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${game.inputBadge} ${game.meta}",
                                            color = NeonNoir.Txt3,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Preview Badge & Platform / Type Pill
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // Preview Badge (konsisten dengan badge di kartu)
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xD60A0E1A), RoundedCornerShape(NeonNoir.RadiusSm))
                                                .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "PREVIEW",
                                                color = NeonNoir.Txt2,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        // Platform / Type Pill
                                        Box(
                                            modifier = Modifier
                                                .border(
                                                    1.dp,
                                                    if (game.tagType == "free") NeonNoir.Amber else NeonNoir.Line,
                                                    RoundedCornerShape(NeonNoir.RadiusSm)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (game.tagType == "free") "GRATIS" else "INSTAN",
                                                color = if (game.tagType == "free") NeonNoir.Amber else NeonNoir.Txt2,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Controller Action Glyphs Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = NeonNoir.MinTouchTarget)
                            .border(1.dp, NeonNoir.LineSoft, RoundedCornerShape(NeonNoir.RadiusSm))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(min = NeonNoir.MinTouchTarget)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(NeonNoir.Amber, RoundedCornerShape(NeonNoir.RadiusSm))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "A",
                                    color = NeonNoir.AmberInk,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Text(
                                text = "Pilih",
                                color = NeonNoir.Txt2,
                                fontSize = 11.5.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .heightIn(min = NeonNoir.MinTouchTarget)
                                .clickable { onDismiss() }
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(NeonNoir.Ink800, RoundedCornerShape(NeonNoir.RadiusSm))
                                    .border(1.dp, NeonNoir.Line, RoundedCornerShape(NeonNoir.RadiusSm))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "B",
                                    color = NeonNoir.Txt2,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Tutup",
                                color = NeonNoir.Txt2,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom Navigation Bar for Neon-Noir 2.0.
 */
@Composable
fun NeonBottomTabbar(
    activeTab: String,
    onTabSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFA090C16))
            .border(1.dp, NeonNoir.Line)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf(
            "home" to ("Beranda" to Icons.Default.Home),
            "search" to ("Cari" to Icons.Default.Search),
            "library" to ("Pustaka" to Icons.Default.PlayArrow),
            "profile" to ("Profil" to Icons.Default.Person)
        )

        tabs.forEach { (key, pair) ->
            val (label, icon) = pair
            val isOn = activeTab == key
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(NeonNoir.MinTouchTarget)
                    .clickable { onTabSelect(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isOn) NeonNoir.Amber else NeonNoir.Txt3,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = label,
                    color = if (isOn) NeonNoir.Amber else NeonNoir.Txt3,
                    fontSize = 10.5.sp,
                    fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
