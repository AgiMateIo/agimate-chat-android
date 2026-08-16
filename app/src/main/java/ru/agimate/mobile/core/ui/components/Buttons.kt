package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.core.ui.theme.AgiTheme

/** Главное действие экрана. Заливка — угольная в светлой теме, золотая в тёмной. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val colors = AgiTheme.colors
    val active = enabled && !busy
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(
                color = if (active) colors.action else colors.action.copy(alpha = 0.35f),
                shape = AgiTheme.shapes.control,
            )
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = AgiTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = colors.onAction,
            )
        } else {
            Text(text = text, style = AgiTheme.typography.action, color = colors.onAction)
        }
    }
}

/** Второстепенное действие: контур по волосяной линии, без заливки. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = AgiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .border(BorderStroke(1.dp, colors.hairline), AgiTheme.shapes.control)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AgiTheme.spacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.size(AgiTheme.spacing.md))
        }
        Text(
            text = text,
            style = AgiTheme.typography.action,
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        )
    }
}
