package ru.agimate.mobile.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Строка списка для незаконченного сообщения: слово «черновик» акцентом, дальше сам текст.
 *
 * Акцентом, а не красным: красный в палитре означает ошибку, а черновик — не ошибка, это просто
 * то, куда человек собирался вернуться. Одной строкой с разными кусками, а не двумя `Text` —
 * иначе обрезание многоточием пришлось бы делить между ними руками.
 */
@Composable
fun draftLine(preview: String): AnnotatedString {
    val label = stringResource(R.string.chat_draft_label)
    val accent = AgiTheme.colors.accent
    return buildAnnotatedString {
        withStyle(SpanStyle(color = accent)) { append(label) }
        append(": ")
        append(preview)
    }
}
