package com.zeiglerbd5.companion.gemmapoc.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * User-selectable color palettes. Mirrors the `Theme` enum in the iOS
 * sibling's `ChatView.swift` — same 6 cases, same display names, same
 * per-property colors. Adding a case = add a row to each `when` below.
 *
 * Most of the per-property colors apply to chat bubbles + input field
 * surfaces that the Android port hasn't built yet. They live on the
 * enum anyway so the values are ready when those surfaces land; for
 * now `background`, `accent`, `bubbleText`/`inputText` (mapped onto
 * Material 3 `onBackground` / `onSurface`), and `isDark` drive the
 * visible UI.
 */
enum class AppTheme(val displayName: String) {
    System("System"),
    Terminal("Terminal"),
    Tactical("Tactical"),
    Parchment("Parchment"),
    WarmCream("Warm Cream"),
    Sky("Sky");

    /** Chat-scroll-view background. `null` means "let the system pick". */
    val background: Color?
        get() = when (this) {
            System -> null
            Terminal -> Color.Black
            Tactical -> Color(0xFF2A2E1F)
            Parchment -> Color(0xFFF4ECD8)
            WarmCream -> Color(0xFFFAF8F2)
            Sky -> Color(0xFF85B8E2)
        }

    val userBubble: Color
        get() = when (this) {
            System -> Color(0xFF0000FF).copy(alpha = 0.18f)
            Terminal -> Color(0xFF0F1F0F)
            Tactical -> Color(0xFF3D4A2F)
            Parchment -> Color(0xFFE8D9B8)
            WarmCream -> Color(0xFFC8A055).copy(alpha = 0.5f)
            Sky -> Color(0xFF3D6FAC)
        }

    val modelBubble: Color
        get() = when (this) {
            System -> Color.Gray.copy(alpha = 0.18f)
            Terminal -> Color(0xFF161616)
            Tactical -> Color(0xFF4A4538)
            Parchment -> Color(0xFFEDE3CC)
            WarmCream -> Color(0xFFD6D2C8)
            Sky -> Color(0xFF5E83B7)
        }

    val toolBubble: Color
        get() = when (this) {
            System -> Color(0xFF00C853).copy(alpha = 0.12f)
            Terminal -> Color(0xFF28FE14).copy(alpha = 0.15f)
            Tactical -> Color(0xFFD4C19C).copy(alpha = 0.22f)
            Parchment -> Color(0xFFC8B482).copy(alpha = 0.35f)
            WarmCream -> Color(0xFFB4A078).copy(alpha = 0.3f)
            Sky -> Color(0xFF5E83B7)
        }

    /**
     * Bubble-text color override. `null` = use the system primary text
     * color (which adapts to light/dark). Terminal forces phosphor
     * green on every bubble; Sky forces white for contrast on the
     * deep-blue bubbles.
     */
    val bubbleText: Color?
        get() = when (this) {
            System, Tactical, Parchment, WarmCream -> null
            Terminal -> Color(0xFF28FE14)
            Sky -> Color.White
        }

    val inputBackground: Color
        get() = when (this) {
            System -> Color(0xFFE9E9EA)
            Terminal -> Color(0xFF0C0E0C)
            Tactical -> Color(0xFF383C2A)
            Parchment -> Color(0xFFEEE5CD)
            WarmCream -> Color(0xFFF4F0E6)
            Sky -> Color(0xFFFAFDFF)
        }

    /**
     * Text color of what the user is typing in the input field.
     * Separate from `bubbleText` because the input lives on a different
     * background — e.g. Sky has white bubble text on dark-blue bubbles
     * but the input is white, so input text needs deep navy to read.
     */
    val inputText: Color?
        get() = when (this) {
            System, Tactical, Parchment, WarmCream -> null
            Terminal -> Color(0xFF28FE14)
            Sky -> Color(0xFF142C50)
        }

    val inputBorder: Color
        get() = when (this) {
            System -> Color(0xFFC6C6C8)
            Terminal -> Color(0xFF28FE14).copy(alpha = 0.55f)
            Tactical -> Color(0xFFD4C19C).copy(alpha = 0.55f)
            Parchment -> Color(0xFF3D2E1F).copy(alpha = 0.3f)
            WarmCream -> Color(0xFF5A3C1E).copy(alpha = 0.25f)
            Sky -> Color(0xFF5082BE).copy(alpha = 0.45f)
        }

    /** Tint for interactive elements (button, cursor, selection). */
    val accent: Color?
        get() = when (this) {
            System -> null
            Terminal -> Color(0xFF28FE14)
            Tactical -> Color(0xFFD4C19C)
            Parchment -> Color(0xFF5A3C1E)
            WarmCream -> Color(0xFF6E5028)
            Sky -> Color(0xFF1950A0)
        }

    /**
     * Forces light/dark mode based on the theme's overall lightness so
     * the system text color adapts without us overriding every Text.
     * `null` = follow the user's system setting.
     */
    val isDark: Boolean?
        get() = when (this) {
            System -> null
            Terminal, Tactical -> true
            Parchment, WarmCream, Sky -> false
        }

    companion object {
        fun fromName(name: String?): AppTheme =
            entries.find { it.name == name } ?: System
    }
}
