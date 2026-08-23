package ru.agimate.mobile.feature.createagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.AgentAvatar
import ru.agimate.mobile.core.ui.components.ErrorState
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.components.Skeleton
import ru.agimate.mobile.core.ui.text.resolve
import ru.agimate.mobile.core.ui.theme.AgiTheme
import ru.agimate.mobile.core.ui.theme.backdrop
import ru.agimate.mobile.data.agents.AgentPresetDto

@Composable
fun CreateAgentScreen(
    state: CreateAgentUiState,
    onBack: () -> Unit,
    onSelect: (AgentPresetDto) -> Unit,
    onBackToGallery: () -> Unit,
    onNameChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onToggleInstructions: () -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
) {
    val selected = state.selected
    if (selected == null) {
        PresetGallery(state = state, onBack = onBack, onSelect = onSelect, onRetry = onRetry)
    } else {
        ConfirmStep(
            state = state,
            preset = selected,
            onBack = onBackToGallery,
            onNameChange = onNameChange,
            onInstructionsChange = onInstructionsChange,
            onToggleInstructions = onToggleInstructions,
            onCreate = onCreate,
        )
    }
}

@Composable
private fun PresetGallery(
    state: CreateAgentUiState,
    onBack: () -> Unit,
    onSelect: (AgentPresetDto) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
    ) {
        StepHeader(title = stringResource(R.string.create_agent_title), onBack = onBack)

        when {
            state.loading -> Column(
                modifier = Modifier.padding(horizontal = AgiTheme.spacing.screen),
                verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
            ) {
                repeat(4) {
                    Skeleton(modifier = Modifier.fillMaxWidth(), height = 84.dp, shape = AgiTheme.shapes.card)
                }
            }

            state.error != null -> ErrorState(message = state.error.resolve(), onRetry = onRetry)

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = AgiTheme.spacing.screen,
                    end = AgiTheme.spacing.screen,
                    bottom = AgiTheme.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(AgiTheme.spacing.md),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.create_agent_role_hint),
                        style = AgiTheme.typography.secondary,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = AgiTheme.spacing.sm),
                    )
                }
                items(state.presets, key = { it.id }) { preset ->
                    PresetCard(preset = preset, onClick = { onSelect(preset) })
                }
            }
        }
    }
}

@Composable
private fun PresetCard(preset: AgentPresetDto, onClick: () -> Unit) {
    val colors = AgiTheme.colors
    val title = preset.title?.takeIf { it.isNotBlank() } ?: preset.name

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.hairline, AgiTheme.shapes.card)
            .clickable(onClick = onClick)
            .padding(AgiTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        // Иконок у пресетов в API нет. Вместо выдуманных картинок — тот же генерируемый знак, что
        // и у агентов: список ролей и список контактов тогда читаются как одна система.
        AgentAvatar(name = title, size = 44.dp)

        Spacer(Modifier.width(AgiTheme.spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AgiTheme.typography.subtitle,
                color = colors.textPrimary,
            )
            if (!preset.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preset.description,
                    style = AgiTheme.typography.secondary,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ConfirmStep(
    state: CreateAgentUiState,
    preset: AgentPresetDto,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onToggleInstructions: () -> Unit,
    onCreate: () -> Unit,
) {
    val colors = AgiTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .backdrop()
            .imePadding()
    ) {
        StepHeader(title = preset.title?.takeIf { it.isNotBlank() } ?: preset.name, onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AgiTheme.spacing.screen),
        ) {
            Text(
                text = stringResource(R.string.create_agent_name),
                style = AgiTheme.typography.caption,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(AgiTheme.spacing.xs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted, AgiTheme.shapes.control)
                    .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.md),
            ) {
                BasicTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    textStyle = AgiTheme.typography.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.lg))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.create_agent_instructions),
                    style = AgiTheme.typography.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (state.instructionsExpanded) R.string.create_agent_collapse
                        else R.string.create_agent_expand
                    ),
                    style = AgiTheme.typography.caption,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onToggleInstructions),
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.xs))

            // Показывать текст целиком нельзя — полторы тысячи знаков пугают. Спрятать совсем тоже
            // плохо: человек должен видеть, что агент настраиваем.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceMuted, AgiTheme.shapes.control)
                    .padding(AgiTheme.spacing.md),
            ) {
                if (state.instructionsExpanded) {
                    BasicTextField(
                        value = state.instructions,
                        onValueChange = onInstructionsChange,
                        textStyle = AgiTheme.typography.secondary.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                    )
                } else {
                    Text(
                        text = state.instructions,
                        style = AgiTheme.typography.secondary,
                        color = colors.textSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.createError != null) {
                Spacer(Modifier.height(AgiTheme.spacing.md))
                Text(
                    text = state.createError.resolve(),
                    style = AgiTheme.typography.secondary,
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(AgiTheme.spacing.xl))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(AgiTheme.spacing.screen)
        ) {
            PrimaryButton(
                text = stringResource(R.string.create_agent_submit),
                onClick = onCreate,
                enabled = state.name.isNotBlank(),
                busy = state.creating,
            )
        }
    }
}

@Composable
private fun StepHeader(title: String, onBack: () -> Unit) {
    val colors = AgiTheme.colors
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
            text = title,
            style = AgiTheme.typography.title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
