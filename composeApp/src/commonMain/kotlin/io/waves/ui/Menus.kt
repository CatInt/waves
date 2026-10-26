package io.waves.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The app's drop-down menu: a rounded card, optionally headed by [title]; scrolls past [MenuMaxHeight]. */
@Composable
internal fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 200.dp).heightIn(max = MenuMaxHeight),
        scrollState = scrollState,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
            )
        }
        content()
    }
}

/** A row of [AppMenu]: [selected] is tinted with a check mark; [destructive] actions are in the error colour. */
@Composable
internal fun AppMenuItem(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(12.dp)
    val content = when {
        selected -> colors.onSecondaryContainer
        destructive -> colors.error
        else -> colors.onSurface
    }
    DropdownMenuItem(
        text = {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Medium else null)
        },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp).clip(pill)
            .then(if (selected) Modifier.background(colors.secondaryContainer, pill) else Modifier),
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp)) }
        } else null,
        colors = MenuDefaults.itemColors(textColor = content, leadingIconColor = content, trailingIconColor = content),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

/** An [AppMenu] of [options] with the [selected] one tinted and scrolled into view. */
@Composable
internal fun <T> ChoiceMenu(
    expanded: Boolean,
    title: String?,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(expanded) {
        val index = options.indexOf(selected)
        if (expanded && index > 2) {
            scroll.scrollTo(with(density) { (MenuItemHeight * (index - 2)).roundToPx() })
        }
    }
    AppMenu(expanded, onDismiss, title, scroll) {
        options.forEach { option ->
            AppMenuItem(label(option), onClick = { onSelect(option); onDismiss() }, selected = option == selected)
        }
    }
}

/** Material's menu item height, for scrolling the selected option into view. */
private val MenuItemHeight = 48.dp
private val MenuMaxHeight = 360.dp
