package ru.agimate.mobile.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.locale.AppLanguage
import ru.agimate.mobile.core.ui.locale.AppLanguages
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.AppTheme
import ru.agimate.mobile.core.ui.theme.AppThemes

/**
 * Настройки приложения: язык, тема, адрес сервера.
 *
 * Экран открывается и до входа, и из профиля, поэтому свою ViewModel берёт сам, а не получает
 * состояние сверху: тянуть адрес сервера через корневую развилку и граф продукта пришлось бы
 * ради одного поля, которое видит только dev-сборка.
 *
 * Всё, что здесь настраивается, принадлежит телефону, а не аккаунту: настройки доступны и тому,
 * кто не вошёл, — иначе английский интерфейс на русском телефоне было бы нечем поправить до
 * первого входа.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val originError by viewModel.originError.collectAsStateWithLifecycle()
    val colors = AgiTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            // Клавиатура и навигационная полоса претендуют на один и тот же низ: берём большее из
            // двух, а не сумму. Два отдельных отступа дали бы под клавиатурой лишнюю полосу.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AgiTheme.spacing.sm, vertical = AgiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = colors.textPrimary,
                )
            }
            Spacer(Modifier.width(AgiTheme.spacing.xs))
            Text(
                text = stringResource(R.string.settings_title),
                style = AgiTheme.typography.title,
                color = colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgiTheme.spacing.screen),
        ) {
            SectionLabel(stringResource(R.string.language_title))
            LanguagePicker()

            Spacer(Modifier.height(AgiTheme.spacing.lg))

            SectionLabel(stringResource(R.string.theme_title))
            ThemePicker()

            if (viewModel.originEditable) {
                Spacer(Modifier.height(AgiTheme.spacing.xl))
                SectionLabel(stringResource(R.string.settings_origin_label))
                Spacer(Modifier.height(AgiTheme.spacing.xs))
                OriginField(
                    origin = origin,
                    onOriginChange = viewModel::changeOrigin,
                )
                originError?.let { error ->
                    Spacer(Modifier.height(AgiTheme.spacing.xs))
                    Text(
                        text = error.resolve(),
                        style = AgiTheme.typography.caption,
                        color = colors.danger,
                    )
                }
            }

            Spacer(Modifier.height(AgiTheme.spacing.xxl))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = AgiTheme.typography.caption,
        color = AgiTheme.colors.textTertiary,
    )
}

/**
 * Только для dev-сборки: с эмулятора бэкенд живёт на 10.0.2.2, с реального телефона — на LAN-IP
 * машины, и перебилживать приложение ради смены адреса незачем.
 */
@Composable
private fun OriginField(origin: String, onOriginChange: (String) -> Unit) {
    var value by remember(origin) { mutableStateOf(origin) }

    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = AgiTheme.typography.secondary,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onOriginChange(value) }),
    )
}

/**
 * Выбор языка интерфейса.
 *
 * Состояние держит не ViewModel: выбранный язык — это состояние системы (Android 13+) или
 * SharedPreferences, а не экрана, и пережить пересоздание оно должно само, без чьей-либо помощи.
 *
 * Пересоздать экран после выбора приходится только до Android 13 — дальше это делает система, и
 * своё `recreate()` дало бы второй перезапуск подряд, заметный морганием.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var current by remember { mutableStateOf(AppLanguages.current(context)) }

    ChoicePicker(
        current = current,
        entries = AppLanguage.entries,
        titleRes = { it.titleRes },
        onChoose = { language ->
            current = language
            if (AppLanguages.choose(context, language)) activity?.recreate()
        },
    )
}

/**
 * Выбор темы.
 *
 * Пересоздание нужно всегда: конфигурацию подменяет `attachBaseContext`, а он отрабатывает на
 * создании активности. Своей ветки «система сделает сама», как у языка, здесь нет — темы
 * приложения система не знает.
 */
@Composable
private fun ThemePicker() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var current by remember { mutableStateOf(AppThemes.current(context)) }

    ChoicePicker(
        current = current,
        entries = AppTheme.entries,
        titleRes = { it.titleRes },
        onChoose = { theme ->
            current = theme
            if (AppThemes.choose(context, theme)) activity?.recreate()
        },
    )
}

/** Строка настройки с выпадающим списком из трёх-четырёх значений. */
@Composable
private fun <T> ChoicePicker(
    current: T,
    entries: List<T>,
    titleRes: (T) -> Int,
    onChoose: (T) -> Unit,
) {
    val colors = AgiTheme.colors
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = AgiTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(titleRes(current)),
                style = AgiTheme.typography.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(titleRes(entry)),
                            style = AgiTheme.typography.body,
                        )
                    },
                    trailingIcon = {
                        // Галочка только у выбранного: список из трёх строк без неё не говорит,
                        // на чём человек стоит сейчас.
                        if (entry == current) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onChoose(entry)
                    },
                )
            }
        }
    }
}
