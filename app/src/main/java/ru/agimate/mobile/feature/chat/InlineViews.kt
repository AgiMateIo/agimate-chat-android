package ru.agimate.mobile.feature.chat

import ru.agimate.mobile.core.ui.text.UiText
import ru.agimate.mobile.data.views.AgentViewContent
import ru.agimate.mobile.data.views.SessionToolCall
import ru.agimate.mobile.data.views.ToolCallDescriptor

/** Минимум, который нужен карточке: идентификатор вызова и его дескриптор. */
data class InlineCardRef(
    val toolCallId: String,
    val descriptor: ToolCallDescriptor,
)

/**
 * Состояние одной карточки.
 *
 * `Collapsed` не хранит ни страницу, ни данные вызова: свернули — освободили. `Ready` держит и то,
 * и другое, пока карточка раскрыта; этого достаточно, чтобы пересоздать WebView после прокрутки
 * или поворота без нового запроса.
 */
sealed interface InlineViewPhase {
    data object Collapsed : InlineViewPhase
    data object Loading : InlineViewPhase
    data class AwaitingResult(val content: AgentViewContent, val call: SessionToolCall) : InlineViewPhase
    data class Ready(val content: AgentViewContent, val call: SessionToolCall) : InlineViewPhase
    data class Failed(val message: UiText) : InlineViewPhase
    data object Unavailable : InlineViewPhase
}

data class InlineCardState(
    val descriptor: ToolCallDescriptor,
    val phase: InlineViewPhase = InlineViewPhase.Collapsed,
    /** Ключ хоста: растёт при перезагрузке страницы, чтобы WebView пересоздался. */
    val revision: Int = 0,
)

/**
 * Всё, что лента знает про inline-вью.
 *
 * [overrides] — дескрипторы из `session.tool_call`; они главнее исторических, потому что отражают
 * текущее состояние привязок. [withdrawn] — карточки, снятые ответом страницы `404`; сигнал с
 * `viewUri` возвращает карточку. [cards] — состояния раскрытых карточек; свёрнутые сюда не
 * попадают, их достаточно вывести из сообщений.
 */
data class InlineViewsState(
    val overrides: Map<String, ToolCallDescriptor> = emptyMap(),
    val withdrawn: Set<String> = emptySet(),
    val cards: Map<String, InlineCardState> = emptyMap(),
) {
    /**
     * Карточки группы в порядке строк и `toolCallIds` — это хронологический порядок группы.
     *
     * Идентификатор склеивается только по `toolCallId`: массив дескрипторов может быть короче
     * массива идентификаторов, и индекс здесь ничего не значит. Повтор id в группе — не повод
     * показать вторую карточку.
     */
    fun cardsFor(group: ChatItem.ProgressGroup): List<InlineCardRef> {
        val seen = HashSet<String>()
        return group.lines.flatMap { line ->
            line.toolCallIds.orEmpty().mapNotNull { id ->
                if (!seen.add(id)) return@mapNotNull null
                val descriptor = overrides[id]
                    ?: line.toolCalls?.firstOrNull { it.toolCallId == id }
                    ?: return@mapNotNull null
                if (descriptor.viewUri == null || descriptor.connectionId == null) return@mapNotNull null
                if (id in withdrawn) return@mapNotNull null
                InlineCardRef(id, descriptor)
            }
        }
    }
}

/** Минимальная высота кадра: лента не должна прыгать, пока едут данные и страница. */
const val INLINE_VIEW_MIN_DP = 320

/** Потолок высоты кадра: больше — уже не карточка, а экран. */
const val INLINE_VIEW_MAX_DP = 720

/**
 * Высота кадра в dp из запрошенной страницей высоты в CSS px.
 *
 * При viewport-мете обёртки 1 CSS px ≈ 1 dp. Верхний предел не опускается ниже минимума: в
 * ландшафте 70% экрана меньше 320 dp, и `coerceIn` с перевёрнутым диапазоном бросил бы исключение.
 */
fun clampInlineViewHeight(requested: Double, screenHeightDp: Int): Int {
    val ceiling = minOf(INLINE_VIEW_MAX_DP, (screenHeightDp * 0.7).toInt())
        .coerceAtLeast(INLINE_VIEW_MIN_DP)
    return requested.toInt().coerceIn(INLINE_VIEW_MIN_DP, ceiling)
}
