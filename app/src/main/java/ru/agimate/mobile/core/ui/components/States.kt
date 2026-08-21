package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import ru.agimate.mobile.core.ui.theme.AgiTheme
import androidx.compose.ui.res.stringResource
import ru.agimate.mobile.R

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AgiTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.dp,
            color = AgiTheme.colors.accent,
        )
    }
}

/**
 * Пустое состояние — не заглушка, а объяснение: что здесь будет и что для этого сделать.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AgiTheme.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = AgiTheme.typography.subtitle,
            color = AgiTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AgiTheme.spacing.sm))
        Text(
            text = description,
            style = AgiTheme.typography.secondary,
            color = AgiTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(AgiTheme.spacing.xl))
            action()
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryText: String = stringResource(R.string.action_retry),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AgiTheme.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = AgiTheme.typography.body,
            color = AgiTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AgiTheme.spacing.lg))
        SecondaryButton(text = retryText, onClick = onRetry, modifier = Modifier.width(200.dp))
    }
}

/** Скелетон: мягкая пульсация вместо спиннера там, где известна форма будущего содержимого. */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shape: Shape = AgiTheme.shapes.control,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    Box(
        modifier = modifier
            .height(height)
            .background(AgiTheme.colors.surfaceMuted.copy(alpha = alpha), shape)
    )
}
