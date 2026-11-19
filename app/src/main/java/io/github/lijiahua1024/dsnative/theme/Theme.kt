package io.github.lijiahua1024.dsnative.theme

import androidx.compose.ui.graphics.Color

/** DeepSeek design tokens, mirrored from src/app/theme.css of the WebView reference. */
data class DsColors(
    val dark: Boolean,
    // surfaces
    val bgBase: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val bgSkeleton: Color,
    // borders
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderInverted: Color,
    val borderL2DarkmodeThin: Color,
    // brand
    val brandPrimary: Color,
    val brandPrimaryInvert: Color,
    val brandText: Color,
    val buttonPrimaryHover: Color,
    val buttonPrimaryFill: Color,
    // labels
    val labelPrimary: Color,
    val labelPrimaryForeground: Color,
    val labelPrimaryInverted: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    // interactive
    val interactiveBgHover: Color,
    val interactiveBgActive: Color,
    val interactiveBgHoverAccent: Color,
    val interactiveBgHoverDanger: Color,
    // buttons
    val buttonContrastFill: Color,
    val buttonElevatedFill: Color,
    val buttonGhostActiveFill: Color,
    val buttonGhostActiveBorder: Color,
    val buttonGhostActiveHover: Color,
    // markdown
    val markdownCitation: Color,
    val markdownCodeBlockBanner: Color,
    val markdownCodeBlock: Color,
    val markdownInlineCode: Color,
    val markdownPlaceholder: Color,
    val markdownTag: Color,
    // states
    val stateErrorPrimary: Color,
    val stateErrorSecondary: Color,
    val toastBg: Color,
    val tooltipBg: Color,
    // specific
    val bubble: Color,
    val inputMajor: Color,
    val loginInput: Color,
    val menu: Color,
    val selector: Color,
    val sidebarFill: Color,
    val sidebarNavItemActiveAccent: Color,
    val sidebarNavItemHover: Color,
    // android thinking block
    val thinkingText: Color,
    val thinkingDot: Color,
    val thinkingLine: Color,
    val searchSummaryText: Color,
)

val LightColors = DsColors(
    dark = false,
    bgBase = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    bgSkeleton = Color(0x0A000000),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    brandPrimary = Color(0xFF3964FE),
    brandPrimaryInvert = Color(0xFF3964FE),
    borderInverted = Color(0x00000000),
    borderL2DarkmodeThin = Color(0x1A000000),
    brandText = Color(0xFF426EFE),
    buttonPrimaryFill = Color(0xFF3964FE),
    buttonPrimaryHover = Color(0xFF5686FE),
    labelPrimary = Color(0xFF0F0F0F),
    labelPrimaryForeground = Color(0xFFFFFFFF),
    labelPrimaryInverted = Color(0xFFFFFFFF),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFB9BABB),
    labelDimmed = Color(0xFFE1E5EE),
    interactiveBgHover = Color(0x0F263148),
    interactiveBgActive = Color(0x1A263148),
    interactiveBgHoverAccent = Color(0x24263148),
    interactiveBgHoverDanger = Color(0x0DEC1313),
    buttonContrastFill = Color(0xFF61666B),
    buttonElevatedFill = Color(0xFFFFFFFF),
    buttonGhostActiveFill = Color(0xFFEDF3FE),
    buttonGhostActiveBorder = Color(0xFFCFDFFF),
    buttonGhostActiveHover = Color(0xFFE4EDFD),
    markdownCitation = Color(0xFFEBEEF2),
    markdownCodeBlockBanner = Color(0xFFF9FAFB),
    markdownCodeBlock = Color(0xFFF9FAFB),
    markdownInlineCode = Color(0xFFEBEEF2),
    markdownPlaceholder = Color(0xFFF5F6F7),
    markdownTag = Color(0xFFF1F3F5),
    stateErrorPrimary = Color(0xFFEC1313),
    stateErrorSecondary = Color(0xFFF25A5A),
    toastBg = Color(0xFF353638),
    tooltipBg = Color(0xFF2C2C2E),
    bubble = Color(0xFFEDF3FE),
    inputMajor = Color(0xFFFFFFFF),
    loginInput = Color(0xFFF9FAFB),
    menu = Color(0xFFFFFFFF),
    selector = Color(0xFFF1F3F5),
    sidebarFill = Color(0xFFF9FAFB),
    sidebarNavItemActiveAccent = Color(0xFFE4EDFD),
    sidebarNavItemHover = Color(0xFFF1F3F5),
    thinkingText = Color(0xFF7D7F85),
    thinkingDot = Color(0xFFA2A4A6),
    thinkingLine = Color(0xFFE5E5E5),
    searchSummaryText = Color(0xFF545557),
)

val DarkColors = DsColors(
    dark = true,
    bgBase = Color(0xFF151517),
    bgLayer2 = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    bgSkeleton = Color(0x14FFFFFF),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    brandPrimary = Color(0xFF5686FE),
    brandPrimaryInvert = Color(0xFFFFFFFF),
    borderInverted = Color(0x0FFFFFFF),
    borderL2DarkmodeThin = Color(0x0FFFFFFF),
    brandText = Color(0xFF679EFE),
    buttonPrimaryFill = Color(0xFF5686FE),
    buttonPrimaryHover = Color(0xFF3964FE),
    labelPrimary = Color(0xFFF9FAFB),
    labelPrimaryForeground = Color(0xFFFFFFFF),
    labelPrimaryInverted = Color(0xFF353638),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),
    interactiveBgHover = Color(0x14FFFFFF),
    interactiveBgActive = Color(0x24FFFFFF),
    interactiveBgHoverAccent = Color(0x3DFFFFFF),
    interactiveBgHoverDanger = Color(0x26F25A5A),
    buttonContrastFill = Color(0xFFF9FAFB),
    buttonElevatedFill = Color(0xFF43454A),
    buttonGhostActiveFill = Color(0xFF283142),
    buttonGhostActiveBorder = Color(0xFF4868B2),
    buttonGhostActiveHover = Color(0xFF34415B),
    markdownCitation = Color(0xFF353638),
    markdownCodeBlockBanner = Color(0xFF2C2C2E),
    markdownCodeBlock = Color(0xFF1B1B1C),
    markdownInlineCode = Color(0xFF2C2C2E),
    markdownPlaceholder = Color(0xFF2C2C2E),
    markdownTag = Color(0xFF2C2C2E),
    stateErrorPrimary = Color(0xFFF25A5A),
    stateErrorSecondary = Color(0xFFF25A5A),
    toastBg = Color(0xFF43454A),
    tooltipBg = Color(0xFF43454A),
    bubble = Color(0xFF2C2C2E),
    inputMajor = Color(0xFF2C2C2E),
    loginInput = Color(0xFF1B1B1C),
    menu = Color(0xFF353638),
    selector = Color(0xFF43454A),
    sidebarFill = Color(0xFF1B1B1C),
    sidebarNavItemActiveAccent = Color(0xFF353638),
    sidebarNavItemHover = Color(0xFF2C2C2E),
    thinkingText = Color(0xFFADB2B8),
    thinkingDot = Color(0xFF81858C),
    thinkingLine = Color(0xFF43454A),
    searchSummaryText = Color(0xFFE5E5E5),
)

val LocalDsColors = androidx.compose.runtime.staticCompositionLocalOf { LightColors }
val LocalDsFontFamily = androidx.compose.runtime.staticCompositionLocalOf<
        androidx.compose.ui.text.font.FontFamily> {
    androidx.compose.ui.text.font.FontFamily.Default
}

object DsTheme {
    val colors: DsColors
        @androidx.compose.runtime.Composable get() = LocalDsColors.current
    val fontFamily: androidx.compose.ui.text.font.FontFamily
        @androidx.compose.runtime.Composable get() = LocalDsFontFamily.current
}

// The ref WebView resolves `Roboto, 'Noto Sans CJK SC', sans-serif` to the
// Noto Sans CJK SC face for latin AND CJK (measured: latin advances ~5% wider
// than Android's Roboto fallback). The SC face is bundled in assets because
// faces inside the system .ttc are not selectable via createFromFile.
@androidx.compose.runtime.Composable
fun DsTheme(colors: DsColors, content: @androidx.compose.runtime.Composable () -> Unit) {
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    val fontFamily = androidx.compose.runtime.remember {
        androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(
                path = "fonts/NotoSansSC-Regular.otf",
                assetManager = assets,
            )
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDsColors provides colors,
        LocalDsFontFamily provides fontFamily,
        androidx.compose.material3.LocalTextStyle provides
            androidx.compose.ui.text.TextStyle(fontFamily = fontFamily),
    ) { content() }
}
