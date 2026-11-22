package io.github.lijiahua1024.dsnative.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lijiahua1024.dsnative.R
import io.github.lijiahua1024.dsnative.theme.DsTheme
import io.github.lijiahua1024.dsnative.theme.Strings

internal fun resolveSelectedCopySlice(text: String, start: Int, end: Int): String {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(0, text.length)
    val lo = minOf(s, e)
    val hi = maxOf(s, e)
    return if (lo < hi) text.substring(lo, hi) else text
}

/**
 * Full-screen message free-text selection view with immediate draggable handles,
 * "全选" (Select all) when partially selected, and "复制" (Copy) using formatted plain/LaTeX text.
 */
@Composable
fun TextSelectionOverlay(
    text: String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var backFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, androidx.compose.animation.core.tween(220, easing = io.github.lijiahua1024.dsnative.ui.TwoPhaseSpec.FadeInEase))
    }
    fun closeAnimated() {
        if (closing) return
        closing = true
        scope.launch {
            val s = (anim.value * (1f - backFrac)).coerceIn(0f, 1f)
            anim.snapTo(s)
            backFrac = 0f
            anim.animateTo(0f, androidx.compose.animation.core.tween(180))
            onClose()
        }
    }
    androidx.activity.compose.PredictiveBackHandler(enabled = !closing) { flow ->
        try {
            flow.collect { ev -> backFrac = ev.progress.coerceIn(0f, 1f) }
            closeAnimated()
        } catch (e: kotlinx.coroutines.CancellationException) {
            val s = backFrac
            scope.launch {
                androidx.compose.animation.core.animate(s, 0f, animationSpec = androidx.compose.animation.core.tween(150)) { v, _ -> backFrac = v }
            }
            throw e
        }
    }
    val c = DsTheme.colors
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember(text) { FocusRequester() }
    var value by remember(text) {
        mutableStateOf(TextFieldValue(text, selection = TextRange(0, text.length)))
    }
    LaunchedEffect(text) {
        keyboard?.hide()
        try { focusRequester.requestFocus() } catch (_: IllegalStateException) {}
    }

    val selStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val selEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val allSelected = selStart == 0 && selEnd == text.length
    val selectionColors = remember(c.brandText, c.brandPrimary) {
        TextSelectionColors(
            handleColor = c.brandText,
            backgroundColor = c.brandPrimary.copy(alpha = 0.22f),
        )
    }
    val eff = (anim.value * (1f - backFrac)).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = eff
                translationX = (1f - eff) * 40.dp.toPx()
            }
            .background(c.bgBase)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {},
    ) {
        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = Strings.get("close") }
                    .clickable { closeAnimated() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    Strings.get("close"),
                    tint = c.labelPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                Strings.get("androidSelectText"),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = c.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!allSelected) {
                Row(
                    Modifier.height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.bgLayer2)
                        .border(0.8.dp, c.borderL2, RoundedCornerShape(16.dp))
                        .clickable {
                            value = value.copy(selection = TextRange(0, text.length))
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_select_all),
                        null,
                        tint = c.labelPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        Strings.get("selectAll"),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.labelPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Row(
                Modifier.height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.buttonPrimaryFill)
                    .clickable {
                        val copied = resolveSelectedCopySlice(text, value.selection.start, value.selection.end)
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("selection", copied))
                        DsToast.show(Strings.get("copied"))
                        closeAnimated()
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    null,
                    tint = c.labelPrimaryForeground,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    Strings.get("copy"),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.labelPrimaryForeground,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = c.borderL1)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        if (next.text == text) {
                            value = next
                        }
                    },
                    readOnly = true,
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        color = c.labelPrimary,
                        fontFamily = DsTheme.fontFamily,
                    ),
                    cursorBrush = SolidColor(c.brandText),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
    }
}
