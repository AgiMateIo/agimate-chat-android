package ru.agimate.mobile.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.agimate.mobile.R
import ru.agimate.mobile.core.ui.components.BrandMark
import ru.agimate.mobile.core.ui.components.PrimaryButton
import ru.agimate.mobile.core.ui.theme.AgiTheme

/**
 * Слайд интро. Порядок объявления — порядок показа.
 *
 * Три, а не пять: рассказ авансом человек терпит ровно до тех пор, пока помнит, зачем открыл
 * приложение. Одна мысль на слайд, и мысль о пользе, а не о возможности.
 */
private enum class Slide(@param:StringRes val titleRes: Int, @param:StringRes val bodyRes: Int) {
    Guide(R.string.onboarding_guide_title, R.string.onboarding_guide_body),
    Build(R.string.onboarding_build_title, R.string.onboarding_build_body),
    Talk(R.string.onboarding_talk_title, R.string.onboarding_talk_body),
}

private val ART_SIZE = 132.dp

/**
 * Рассказ о приложении перед входом.
 *
 * Показывается один раз и только тому, кто не вошёл: вошедшему интро поперёк живой сессии не
 * встанет, даже если отметка о просмотре потерялась вместе с данными приложения.
 *
 * Пропустить можно с любого слайда, кроме последнего — там та же дорога называется «Начать».
 * Свайп продублирован кнопкой намеренно: листают не все, а дойти до входа должен каждый.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AgiTheme.colors
    val slides = Slide.entries
    val pager = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()
    val reducedMotion = AgiTheme.reducedMotion
    val last = pager.currentPage == slides.lastIndex

    // Назад — на предыдущий слайд: интро читается как одна страница, у которой есть верх. С первого
    // слайда «назад» означает то же, что «Пропустить», а не выход из приложения: интро открывают и
    // повторно, ссылкой с экрана входа, и захлопнуть там приложение было бы неожиданным ответом.
    BackHandler {
        if (pager.currentPage > 0) {
            scope.launch { pager.go(pager.currentPage - 1, reducedMotion) }
        } else {
            onDone()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backdrop)
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = AgiTheme.spacing.sm),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (!last) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    style = AgiTheme.typography.action,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onDone)
                        .padding(horizontal = AgiTheme.spacing.md, vertical = AgiTheme.spacing.md),
                )
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            SlidePage(slides[page])
        }

        Dots(count = slides.size, current = pager.currentPage)

        Spacer(Modifier.height(AgiTheme.spacing.xl))

        PrimaryButton(
            text = stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next),
            onClick = {
                if (last) onDone() else scope.launch { pager.go(pager.currentPage + 1, reducedMotion) }
            },
            modifier = Modifier.padding(horizontal = AgiTheme.spacing.xl),
        )

        Spacer(Modifier.height(AgiTheme.spacing.xl))
    }
}

@Composable
private fun SlidePage(slide: Slide) {
    val colors = AgiTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AgiTheme.spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val art = Modifier.size(ART_SIZE)
        when (slide) {
            // Первый слайд — сам знак: продукт называет себя раньше, чем начинает рассказывать.
            Slide.Guide -> BrandMark(size = 96.dp, color = colors.accent)
            Slide.Build -> AgentWithSkills(art)
            Slide.Talk -> AgentSpeaksFirst(art)
        }

        Spacer(Modifier.height(AgiTheme.spacing.xxl))

        Text(
            text = stringResource(slide.titleRes),
            style = AgiTheme.typography.title,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(AgiTheme.spacing.md))

        Text(
            text = stringResource(slide.bodyRes),
            style = AgiTheme.typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Точки положения. Для чтения с экрана они пусты: то же самое уже сообщает сам пейджер, и вторая
 * озвучка «страница 2 из 3» только мешает.
 */
@Composable
private fun Dots(count: Int, current: Int) {
    val colors = AgiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(AgiTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .width(if (active) 20.dp else 6.dp)
                    .height(6.dp)
                    .background(
                        color = if (active) colors.accent else colors.hairline,
                        shape = AgiTheme.shapes.pill,
                    )
            )
        }
    }
}

/** Человек попросил систему не анимировать — прыгаем, а не едем. */
private suspend fun PagerState.go(page: Int, reducedMotion: Boolean) {
    if (reducedMotion) scrollToPage(page) else animateScrollToPage(page)
}
