package ru.agimate.mobile.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.theme.AgiTheme

/** Поле формы входа. Отдельным компонентом ради одного: их четыре штуки на трёх экранах. */
@Composable
fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    onImeAction: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = AgiTheme.typography.secondary) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        textStyle = AgiTheme.typography.body,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
        ),
    )
}

/**
 * Пароль с глазком.
 *
 * Глазок не роскошь: пароль набирают на телефоне, вслепую и с автозаменой, и без возможности
 * посмотреть на набранное человек ошибается ровно там, где ошибку не покажут — вход и смена
 * пароля отвечают одинаково на «не тот пароль» и на «опечатка».
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
    onImeAction: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = AgiTheme.typography.secondary) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        textStyle = AgiTheme.typography.body,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
            onGo = { onImeAction?.invoke() },
        ),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector =
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.password_hide else R.string.password_show
                    ),
                    tint = AgiTheme.colors.textSecondary,
                )
            }
        },
    )
}
