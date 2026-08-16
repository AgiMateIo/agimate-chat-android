package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Аватарок у агентов в API нет и не будет — рисуем сами, детерминированно из имени.
 *
 * Палитра намеренно узкая и приглушённая: восемь пар «подложка/буквы» в одной тональности с
 * интерфейсом. Случайный цвет из полного круга превратил бы список контактов в радугу, а он должен
 * читаться как один список, а не как набор наклеек.
 */
private val LightAvatarPalette = listOf(
    Color(0xFFE7E2D4) to Color(0xFF6B6046),
    Color(0xFFDEE5DC) to Color(0xFF4C6350),
    Color(0xFFE2E1EA) to Color(0xFF545A76),
    Color(0xFFEEE0D8) to Color(0xFF7A5647),
    Color(0xFFDCE5E8) to Color(0xFF45606B),
    Color(0xFFE9E2E6) to Color(0xFF6E5361),
    Color(0xFFE4E7D8) to Color(0xFF5D6941),
    Color(0xFFEDE3D2) to Color(0xFF7C6432),
)

private val DarkAvatarPalette = listOf(
    Color(0xFF35322A) to Color(0xFFC9BC9C),
    Color(0xFF2A342D) to Color(0xFFA6C1AC),
    Color(0xFF2D2F39) to Color(0xFFAFB6D0),
    Color(0xFF372E29) to Color(0xFFD0AE9B),
    Color(0xFF29343A) to Color(0xFFA2C0CC),
    Color(0xFF352E33) to Color(0xFFC6AAB8),
    Color(0xFF313527) to Color(0xFFB6C494),
    Color(0xFF37301F) to Color(0xFFD3BC88),
)

/** Стабильный хеш: `String.hashCode` меняться не обязан, а цвет агента меняться не должен. */
private fun stableHash(value: String): Int {
    var hash = 0
    for (char in value) {
        hash = hash * 31 + char.code
        hash = hash and 0x7FFFFFFF
    }
    return hash
}

fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Composable
fun AgentAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    dimmed: Boolean = false,
) {
    val palette = if (AgiTheme.colors.isDark) DarkAvatarPalette else LightAvatarPalette
    val (background, foreground) = palette[stableHash(name) % palette.size]
    val alpha = if (dimmed) 0.45f else 1f

    Box(
        modifier = modifier
            .size(size)
            // Скруглённый квадрат, а не круг: круги в списке читаются как «люди», а здесь агенты.
            .background(background.copy(alpha = alpha), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = foreground.copy(alpha = alpha),
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.Medium,
            style = AgiTheme.typography.subtitle,
        )
    }
}
