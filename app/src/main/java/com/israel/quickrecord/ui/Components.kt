@file:OptIn(ExperimentalMaterial3Api::class)

package com.israel.quickrecord.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.israel.quickrecord.R

@Composable
fun Ico(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Int = 24) {
    Icon(painterResource(id), contentDescription = null, modifier = modifier.size(size.dp), tint = tint)
}

/** A soft rounded card used for every block on every screen. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** An icon in a tinted circle, at the start of rows. */
@Composable
fun IconBadge(@DrawableRes id: Int, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Ico(id, tint = tint, size = 20) }
}

/** A tappable row: icon, title, current value or explanation, chevron. */
@Composable
fun NavRow(
    @DrawableRes icon: Int,
    title: String,
    value: String?,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!value.isNullOrEmpty()) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Ico(R.drawable.ic_chevron, size = 20)
    }
}

@Composable
fun SwitchRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A small rounded label. */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary, @DrawableRes icon: Int? = null) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Ico(icon, tint = color, size = 14)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

data class Choice<T>(val value: T, val label: String, val detail: String? = null)

/**
 * A setting with a few options: what it does, then the options, with the
 * recommended one marked.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    explanation: String,
    choices: List<Choice<T>>,
    current: T,
    recommended: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                for (c in choices) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .selectable(selected = c.value == current, role = Role.RadioButton) { onPick(c.value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = c.value == current, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                if (c.value == recommended) {
                                    Spacer(Modifier.width(8.dp))
                                    Tag("Recommended")
                                }
                            }
                            if (c.detail != null) {
                                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The subtle sign-off at the bottom of Home and Settings. */
@Composable
fun MadeInIsrael() {
    Text(
        "Made in Israel 🇮🇱",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
    )
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))

@Composable
fun RowGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

val Spaced8 = Arrangement.spacedBy(8.dp)
