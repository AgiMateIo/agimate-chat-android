package ru.agimate.mobile.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Ответ агента приходит markdown'ом: списки, жирный, код, ссылки, таблицы.
 *
 * Заголовки принудительно приземлены: дефолтные `display*` в пузыре мессенджера выглядят как крик,
 * а разница между h1 и h3 внутри одного ответа должна быть заметной, но небольшой.
 */
@Composable
fun MarkdownText(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val base = AgiTheme.typography.body.copy(color = textColor)
    val mono = AgiTheme.typography.mono.copy(color = textColor)

    Markdown(
        content = content,
        modifier = modifier,
        colors = markdownColor(
            text = textColor,
            codeBackground = AgiTheme.colors.surfaceMuted,
            inlineCodeBackground = AgiTheme.colors.surfaceMuted,
            dividerColor = AgiTheme.colors.hairline,
            tableBackground = AgiTheme.colors.surfaceMuted,
        ),
        typography = markdownTypography(
            h1 = base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            h2 = base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            h3 = base.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
            h4 = base.copy(fontWeight = FontWeight.Medium),
            h5 = base.copy(fontWeight = FontWeight.Medium),
            h6 = base.copy(fontWeight = FontWeight.Medium),
            text = base,
            paragraph = base,
            ordered = base,
            bullet = base,
            list = base,
            code = mono,
            inlineCode = mono,
            quote = base.copy(color = AgiTheme.colors.textSecondary),
            table = base,
            textLink = TextLinkStyles(
                style = base.copy(
                    color = AgiTheme.colors.accent,
                    textDecoration = TextDecoration.Underline,
                ).toSpanStyle()
            ),
        ),
    )
}
