package com.openminis.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material 3 Expressive semantic chat & surface tokens.
 *
 * Harmonized with [ColorScheme] (including Android 12+ Material You dynamic colors)
 * so chat bubbles, command decks, tool capsules, and markdown code blocks share
 * one cohesive M3 Expressive tonal hierarchy instead of flat iOS system grays.
 */
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val onUserBubble: Color = primaryText,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color(0xFFEEF1F9),
    secondaryBg = Color(0xFFE6EBF6),
    inputBg = Color(0xFFFFFFFF),
    inputIconBg = Color(0xFFE6EBF6),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0x1F444856),
    primaryText = Color(0xFF171B23),
    secondaryText = Color(0xFF444856),
    tertiaryText = Color(0xFF747886),
    disabledText = Color(0x61171B23),
    userBubble = Color(0xFFDCE3FF),
    onUserBubble = Color(0xFF00164E),
    toolBg = Color(0xFFFFFFFF),
    toolBorder = Color(0x1A747886),
    toolCapsuleBg = Color(0xFFDCE2F1),
    separator = Color(0x24747886),
    sendButton = Color(0xFF2956D8),
    sendButtonDisabled = Color(0x38171B23),
    codeBlockBg = Color(0xFF121620),
    codeBlockText = Color(0xFF8BE9B4),
    inlineCodeBg = Color(0x8CDCE4F8),
    inlineCodeText = Color(0xFF10203E),
    link = Color(0xFF2956D8),
    blockquoteBar = Color(0x992956D8),
    thinking = Color(0xFF2956D8),
    warningBg = Color(0x1FD97706),
    warningText = Color(0xFF7C4A03),
    tableBorder = Color(0x24444856),
    inputShadow = Color(0x14171B23),
    toastBg = Color(0xFFDCE3FF),
    thumbnailBorder = Color(0x24444856),
    sheetHeaderBg = Color(0xFFF6F8FD),
    sheetHeaderBorder = Color(0x1A747886),
    fabAccent = Color(0xFF2956D8),
)

val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF0D1119),
    secondaryBg = Color(0xFF1D2331),
    inputBg = Color(0xFF262D3E),
    inputIconBg = Color(0xFF31394E),
    inputIconBorder = Color(0x288D91A0),
    inputBorder = Color(0x2E8D91A0),
    primaryText = Color(0xFFE3E6F0),
    secondaryText = Color(0xFFC2C6D6),
    tertiaryText = Color(0xFF8D91A0),
    disabledText = Color(0x52E3E6F0),
    userBubble = Color(0xFF1E3FA8),
    onUserBubble = Color(0xFFDCE3FF),
    toolBg = Color(0xFF171C27),
    toolBorder = Color(0x288D91A0),
    toolCapsuleBg = Color(0xFF262D3E),
    separator = Color(0x2E8D91A0),
    sendButton = Color(0xFFB5C4FF),
    sendButtonDisabled = Color(0x38E3E6F0),
    codeBlockBg = Color(0xFF080B12),
    codeBlockText = Color(0xFF8CF3B5),
    inlineCodeBg = Color(0x8C303F5F),
    inlineCodeText = Color(0xFFDCE4F8),
    link = Color(0xFFB5C4FF),
    blockquoteBar = Color(0x99B5C4FF),
    thinking = Color(0xFFB5C4FF),
    warningBg = Color(0x24F59E0B),
    warningText = Color(0xFFFCD34D),
    tableBorder = Color(0x2E8D91A0),
    inputShadow = Color(0x73000000),
    toastBg = Color(0xFF1E3FA8),
    thumbnailBorder = Color(0x2E8D91A0),
    sheetHeaderBg = Color(0xFF171C27),
    sheetHeaderBorder = Color(0x248D91A0),
    fabAccent = Color(0xFF1E3FA8),
)

/**
 * Derives a cohesive [ChatPalette] from the active Material 3 [ColorScheme]
 * so both the signature Expressive Cobalt/Iris theme and Android 12+ Dynamic Color
 * produce harmonious chat bubbles, tool pills, and command decks.
 */
fun expressiveChatPalette(scheme: ColorScheme, isDark: Boolean): ChatPalette {
    val base = if (isDark) DarkChatPalette else LightChatPalette
    return base.copy(
        background = scheme.background,
        secondaryBg = scheme.surfaceContainer,
        inputBg = if (isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
        inputIconBg = scheme.surfaceContainerHigh,
        inputBorder = scheme.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.42f),
        primaryText = scheme.onSurface,
        secondaryText = scheme.onSurfaceVariant,
        tertiaryText = scheme.outline,
        disabledText = scheme.onSurface.copy(alpha = 0.36f),
        userBubble = scheme.primaryContainer,
        onUserBubble = scheme.onPrimaryContainer,
        toolBg = if (isDark) scheme.surfaceContainerLow else scheme.surfaceContainerLowest,
        toolBorder = scheme.outlineVariant.copy(alpha = 0.35f),
        toolCapsuleBg = scheme.surfaceContainerHigh,
        separator = scheme.outlineVariant.copy(alpha = 0.42f),
        sendButton = scheme.primary,
        sendButtonDisabled = scheme.onSurface.copy(alpha = 0.22f),
        // Harmonized tonal inline code (replaces jarring magenta/plum tertiary text)
        inlineCodeBg = scheme.secondaryContainer.copy(alpha = if (isDark) 0.52f else 0.62f),
        inlineCodeText = scheme.onSecondaryContainer,
        link = scheme.primary,
        blockquoteBar = scheme.primary.copy(alpha = 0.65f),
        thinking = scheme.primary,
        tableBorder = scheme.outlineVariant.copy(alpha = 0.45f),
        toastBg = scheme.primaryContainer,
        thumbnailBorder = scheme.outlineVariant.copy(alpha = 0.42f),
        sheetHeaderBg = scheme.surfaceContainerLow,
        sheetHeaderBorder = scheme.outlineVariant.copy(alpha = 0.30f),
        fabAccent = scheme.primaryContainer,
    )
}

val LocalChatPalette = compositionLocalOf { LightChatPalette }

val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
