package io.github.lijiahua1024.dsnative.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size

@Composable
internal fun NativeHeaderIconButton(
    contentDescription: String?,
    sizeDp: Float,
    offsetXDp: Float,
    offsetYDp: Float,
    wrapperContentDescription: String? = null,
    iconRes: Int,
    iconSizeDp: Float,
    iconTint: Color,
    checkable: Boolean = false,
    checked: Boolean = false,
    iconContentDescription: String? = contentDescription,
    onClick: () -> Unit,
) {
    val currentOnCheckedChange = rememberUpdatedState(onClick)
    AndroidView(
        modifier = Modifier.offset(x = offsetXDp.dp, y = offsetYDp.dp).size(sizeDp.dp),
        factory = { viewContext ->
            DsHeaderControlView(viewContext).also { control ->
                control.iconView = DsHeaderIconView(viewContext).also { iconView ->
                    control.addView(iconView)
                }
            }
        },
        update = { control ->
            control.setContentDescriptionCompat(wrapperContentDescription)
            control.setCheckableState(checkable)
            control.setCheckedState(checked)
            control.setOnClickListener { currentOnCheckedChange.value() }
            control.iconView?.let { iconView ->
                iconView.setContentDescriptionCompat(iconContentDescription)
                iconView.setImageResourceCompat(iconRes)
                iconView.layoutIcon(iconSizeDp)
                iconView.setColorFilterCompat(iconTint.toArgb())
            }
        },
    )
}

private class DsHeaderIconView(context: android.content.Context) : android.view.View(context) {
    init {
        setContentDescription(null)
        isClickable = false
        isFocusable = false
        setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES)
    }

    fun setContentDescriptionCompat(description: String?) {
        setContentDescription(description)
    }

    fun setImageResourceCompat(resource: Int) {
        iconDrawable = androidx.core.content.ContextCompat.getDrawable(context, resource)
    }

    fun setColorFilterCompat(color: Int) {
        iconDrawable?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.view.View::class.java.name
        info.isClickable = false
        info.isFocusable = false
        info.isCheckable = false
        info.isChecked = false
    }

    private var iconDrawable: android.graphics.drawable.Drawable? = null

    fun layoutIcon(sizeDp: Float) {
        val target = (sizeDp * resources.displayMetrics.density).toInt()
        iconDrawable?.setBounds((width - target) / 2, (height - target) / 2, (width + target) / 2, (height + target) / 2)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        iconDrawable?.draw(canvas)
    }
}

private class DsHeaderControlView(context: android.content.Context) : android.widget.FrameLayout(context) {
    var iconView: DsHeaderIconView? = null
    private var checkableState = false
    private var checkedState = false

    init {
        setContentDescription(null)
        isClickable = true
        isFocusable = true
    }

    fun setContentDescriptionCompat(description: String?) {
        setContentDescription(description)
    }

    fun setCheckableState(value: Boolean) {
        if (checkableState != value) {
            checkableState = value
        }
    }

    fun setCheckedState(value: Boolean) {
        checkedState = value
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.view.View::class.java.name
        info.isCheckable = checkableState
        info.isChecked = if (checkableState) checkedState else false
    }
}

