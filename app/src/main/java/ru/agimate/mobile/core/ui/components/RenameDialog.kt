package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Переименование чего угодно одной строкой. Поле открывается с текущим именем, а не пустым:
 * человек правит, а не сочиняет заново.
 *
 * @param key что переименовывают — по нему поле переживает поворот и не путает два разных объекта
 * @param maxLength предел сервера; дальше него ввод просто не идёт — узнавать о нём по `400` после
 *                  «Сохранить» хуже всего. `null` — сервер длину не ограничивает
 */
@Composable
fun RenameDialog(
    key: String,
    title: String,
    initial: String,
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int? = null,
) {
    val colors = AgiTheme.colors
    var value by rememberSaveable(key) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = colors.surface,
        title = {
            Text(text = title, style = AgiTheme.typography.subtitle, color = colors.textPrimary)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (maxLength == null || it.length <= maxLength) value = it },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = AgiTheme.typography.body,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(AgiTheme.spacing.sm))
                    Text(text = error, style = AgiTheme.typography.caption, color = colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = !busy && value.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    style = AgiTheme.typography.action,
                    color = if (busy || value.isBlank()) colors.textTertiary else colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = AgiTheme.typography.action,
                    color = colors.textSecondary,
                )
            }
        },
    )
}
