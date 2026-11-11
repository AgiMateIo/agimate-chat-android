# Inline-вью в переписке — дизайн фазы 2

- Статус: approved
- Дата: 2026-11-11
- Контракт: `agimate-backend` — `docs/decisions/connector-views.md`, раздел «Фаза 2 — inline»
- Предыдущая фаза: `docs/superpowers/specs/2026-11-09-android-agent-views-design.md`
- Дельта: спека «Inline-вью в переписке — что делать Android-приложению» (вставлена в задачу)
- Референс: `agimate-frontend` — `InlineToolCallView.tsx`, `useInlineToolViews.ts`,
  `AgentViewFrame.tsx`

## 1. Задача

Под строкой прогресса, которая называет вызовы тулов, показать свёрнутую карточку-триггер на
каждый вызов с вью и открыть ту же вью в карточке по тапу. Второго хоста, второго моста и второго
протокола не заводим: это та же `AgentViewHost` с дополнительными нотификациями и высотой.

Одновременно закрывается CSP: `AgentViewContentResponse.csp` несёт домены, разрешённые установкой.
Без этого вью с внешними ресурсами (Excalidraw) не работают ни в лаунчере, ни inline.

## 2. Решения, принятые по ревью

### 2.1 Один `ViewPolicy` вместо строки CSP

Политика страницы — это два неразрывных факта: заголовок `Content-Security-Policy` и точный
allow-list origin, которые WebView может достичь по сети. Раньше они жили в разных местах, и
ослабление одного без другого давало либо неработающую вью, либо дыру. Теперь:

```kotlin
data class ViewPolicy(val header: String, val allowedOrigins: Set<ViewOrigin>)

object ViewCsp {
    fun policy(domains: ViewExternalDomains?): ViewPolicy
}
```

- `ViewOrigin` нормализует `scheme + host + effective port` (443 — default) и отбрасывает всё, что
  не является точным `https`-origin: wildcard, пути, query, userinfo, мусор.
- `resourceDomains` идут в `script-src`, `style-src`, `img-src`, `font-src`, `media-src`;
  `connectDomains` — в `connect-src` как пара `https://host` и `wss://host` (по CSP3 `https` не
  матчит `wss`).
- `allowedOrigins` — объединение обоих списков; `ViewWebViewClient` пропускает запросы только на
  эти origin, всё остальное по-прежнему получает пустой `403`.
- `csp == null` или оба списка пусты — ровно сегодняшняя строгая политика.

Следствие, которое надо помнить: при непустом `csp` сеть перестаёт быть закрытой «независимо от
CSP». Точным гейтом остаётся заголовок; перехватчик — второй слой и разрешает только объединение
origin. Редиректы `shouldInterceptRequest` не видит после первого URL — их проверяет CSP.

### 2.2 Карточки — отдельные строки ленты

`LazyColumn` перевёрнут: меньший индекс — ниже на экране. Карточка «под группой» объявляется
**до** группы в списке и в обратном хронологическом порядке, чтобы сверху вниз читаться как
`toolCallIds` строки.

Карточка не живёт внутри `ProgressGroupRow`: у группы уже есть `clickable` на всю колонку и
`animateContentSize`, а LazyColumn композитит item целиком — все WebView большой группы жили бы,
пока виден любой её кусок. Отдельный item с ключом `v:{toolCallId}` даёт правильный цикл жизни:
WebView создаётся, когда карточка видима и раскрыта, и уничтожается при уходе с экрана.

`buildChatItems` не меняется. Поверх него появляется чистая `buildChatRows(items, inlineState,
agentId)`, которая разворачивает группу в `ChatRow.Card` (по одной на вызов с вью) и
`ChatRow.Message` (сама группа или пузырь). `ChatRow.Card` идёт перед группой.

### 2.3 `404`: карточка или её содержимое

Единое правило:

| Что ответило | Что происходит |
|---|---|
| вызов `404`/`403` | вью «сейчас нет»: карточка остаётся, раскрытая показывает компактное `Unavailable`; следующий сигнал или повторное раскрытие повторяют запрос |
| страница `404` | привязку отозвали: карточка снимается (`withdrawn`), пока сигнал с `viewUri` не вернёт её; дескриптор при этом сохраняется — по нему карточка и возвращается |
| страница `502` | карточка остаётся раскрытой с текстом ошибки и кнопкой повтора |
| `viewUri == null` в сигнале | карточка снимается, HTML и данные вызова освобождаются |

Это расходится с вебом только в одном месте: веб на `404` страницы оставляет пустую раскрытую
карточку, приложение её убирает. Осознанно: пустой раскрытый триггер — тупик, а сигнал вернёт
карточку, если привязка появится снова.

### 2.4 Старый бэкенд — только история

При `toolCalls == null` в истории приложение по каждому `toolCallIds` один раз спрашивает
`…/tool-calls/{id}` — это совместимость. Правило «0 запросов на открытие сессии» относится к
строкам с дескрипторами: на текущем бэкенде fallback не выполняется, но `withToolCalls` оставляет
`null` и при сбое резолва, поэтому путь остаётся рабочим, а не мёртвым. Запросы дедуплицируются по
`toolCallId`; ответ сигнала главнее и поздний ответ fallback его не перетирает.

На живом ходу карточка ждёт `session.tool_call`: старый сервер, у которого нет дескрипторов, не
шлёт и это событие, поэтому живые карточки на нём появляются после перезагрузки истории. Живой
fallback веба (запрос по следующему сообщению) не переносим: он тянет за собой счётчики
`liveSeq`/`descriptorBackendRef` ради бэкенда, которого уже нет в работе.

### 2.5 Один хост, один загрузчик, один контроллер

- **`AgentViewHost`** получает необязательные `ViewCallData(input, result)` и `onResize`.
  Отправка `ui/notifications/tool-input`/`…/tool-result` живёт в `ViewHostController` рядом с
  WebView: `inputSent`, `lastResult` и таймер 100 мс принадлежат экземпляру хоста, поэтому
  пересоздание WebView (поворот, прокрутка) отправляет данные заново. Лаунчер и полный экран
  передают `toolCall = null` и `size-changed` не применяют.
- **`ToolCallViewSource`/`ToolCallViewLoader`** — один загрузчик «вызов + страница» для карточки и
  полного экрана. Карточка грузит два запроса параллельно (у неё есть дескриптор), полный экран —
  последовательно (в маршруте только id). Классификация `404/403/502` — одна на оба.
- **`InlineViewsController`** — обычный класс, не ViewModel: дескрипторы сигналов, состояния
  карточек, HTML, одна job и generation на карточку. `ChatViewModel` создаёт его, передаёт
  `agentId`, историю/сигналы и складывает его `StateFlow` в `ChatUiState.inlineViews`. Так
  `ChatViewModel` не растёт на полтысячи строк, а состояние карточек тестируется без Android.

## 3. Архитектура

```
ChatViewModel
├── InlineViewsController          состояние карточек, jobs, generation
│   └── ToolCallViewSource         интерфейс; ToolCallViewLoader поверх ViewsRepository
└── ChatUiState.inlineViews
     └── ChatScreen
          ├── buildChatRows        ChatRow.Card + ChatRow.Message
          └── InlineViewCard       AgentViewHost + ViewCallData
                                    └── ViewHostController (100 мс, input один раз, result при изменении)

AgentViewViewModel (полный экран)
└── ToolCallViewSource + AgentViewHost
```

Данные идут так:

- история: `WebchatMessageDto.toolCalls` → `ChatMessage.toolCalls` → `cardsFor` (без запросов);
- живой ход: `webchat.message.toolCallIds` → `ChatMessage.toolCallIds`, дескрипторы — только
  сигналом `session.tool_call` → `InlineViewsState.overrides`;
- тап: `ToolCallViewLoader` → `SessionToolCall.input/result` + `AgentViewContent`;
- мост: `ViewCallData` → `tool-input` (один раз) → `tool-result` (при появлении и изменении).

## 4. Состояния карточки

```kotlin
sealed interface InlineViewPhase {
    data object Collapsed
    data object Loading                 // два запроса в полёте, заглушка >= 320 dp
    data class AwaitingResult(content, call)  // страница есть, result ещё нет
    data class Ready(content, call)     // WebView смонтирован
    data class Failed(message)          // 502/сеть: текст + повтор
    data object Unavailable             // вызов 404/403: карточка остаётся
}
```

Переходы:

- `Collapsed → Loading` по тапу; `Loading → Ready|AwaitingResult|Failed|Unavailable`;
- `Collapsed` уничтожает WebView и освобождает HTML/данные вызова;
- сигнал с `viewUri == null` или страница `404` снимает карточку (`withdrawn`);
- сигнал с `viewUri != null` снимает `withdrawn` и возвращает карточку;
- повторный сигнал по раскрытой карточке: сменился адрес — полная перезагрузка, иначе догрузка
  только `tool-call` (результат мог обновиться); `Failed`/`Unavailable` — полная перезагрузка:
  держать нечего, страницы нет;
- `webchat.message` открытой сессии — догрузка только раскрытых карточек в `AwaitingResult`;
- ответ старой generation (свернули/сняли/переоткрыли) игнорируется.

## 5. Высота и прокрутка

- `size-changed.height` — CSS px; при viewport-мете обёртки 1 CSS px ≈ 1 dp.
- Кламп: `320 dp .. min(720 dp, 0.7 × screenHeightDp)`; верхний предел не меньше 320 dp
  (иначе ландшафт даёт `IllegalArgumentException`).
- Высота меняется без анимации.
- Известное ограничение: iframe прибит к вьюпорту WebView, поэтому при превышении клампа
  страница скроллится внутри iframe, и нативный хост не видит её scroll position. Полноценная
  передача жеста родителю требует режима обёртки, где iframe не прибит к вьюпорту; это вынесено
  за пределы итерации. В этой итерации лента прокручивается вне карточки, а для полного
  просмотра есть кнопка разворота.

## 6. CSP

```
default-src 'none';
script-src 'unsafe-inline' <resource>;
style-src 'unsafe-inline' <resource>;
img-src data: blob: <resource>;
font-src data: <resource>;
media-src data: blob: <resource>;
connect-src <https://host wss://host | 'none'>;
frame-src 'none'; base-uri 'none'; object-src 'none'
```

- Политика уходит HTTP-заголовком из `ViewWebViewClient`; `<meta>` в чужой HTML не вставляется.
- `csp == null` — строгая политика, ровно сегодняшняя строка.
- `allowedOrigins` — точные origin; перехватчик пускает на них, остальное `403`.
- Домены нормализует бэкенд; клиент дополнительно валидирует origin и не добавляет wildcard,
  путей и своих доменов.

## 7. Полный экран

Маршрут `agent-view/{agentId}/{sessionId}/{toolCallId}` (сегменты кодируются). Экран сам
загружает вызов и страницу, показывает тот же `AgentViewHost` с теми же `tool-input`/`tool-result`,
игнорирует `size-changed`. Вызов без вью или `404` страницы — экран закрывается назад. Пока
результата нет, страница может быть смонтирована с одним `tool-input`; приход `session.tool_call`
догружает результат.

## 8. Тесты

- **Юнит:** DTO/маппинг `toolCallIds`/`toolCalls` (`null` vs `[]`), путь `tool-calls`, `csp`;
  разбор `session.tool_call`; `ViewCsp`/`ViewOrigin`; `ViewProtocol` нотификаций; `cardsFor` и
  порядок `buildChatRows`; кламп высоты; контроллер (0 запросов до тапа, гонки, идемпотентность,
  `404`-политика, detached result); загрузчик (параллельность, ошибки).
- **Инструментальные:** `shouldInterceptRequest` пускает разрешённый origin и режет остальные;
  CSP-заголовок содержит домены; `initialized` → задержка 100 мс → `tool-input` → `tool-result`;
  повтор результата — только `tool-result`; `size-changed` доходит до хоста.
- **Ручной прогон:** чеклист спеки §8; отдельно — прокрутка ленты с раскрытой карточкой и
  ограничение из §5.

## 9. Файлы

| Место | Что меняется |
|---|---|
| `core/views/ViewCsp.kt` | `ViewExternalDomains`, `ViewOrigin`, `ViewPolicy`, `policy(...)` |
| `core/views/ViewWebView.kt` | allow-list origin в `shouldInterceptRequest`, заголовок из политики |
| `core/views/ViewProtocol.kt` | `tool-input`, `tool-result` |
| `core/views/ViewHost.kt` | `ViewCallData`, `updateToolCall`, `onResize`, таймер 100 мс |
| `data/views/ViewsDto.kt` | дескриптор, `SessionToolCallDto`, `csp` |
| `data/views/ViewsApi.kt` | `GET …/sessions/{id}/tool-calls/{toolCallId}` |
| `data/views/ViewsRepository.kt` | `ToolCallDescriptor`, `SessionToolCall`, `toolCall(...)`, `csp` |
| `data/views/ToolCallViewLoader.kt` | `ToolCallViewSource`, загрузчик, классификация ошибок |
| `data/webchat/WebchatDto.kt` | `toolCallIds`, `toolCalls` |
| `data/webchat/WebchatModels.kt` | те же поля в `ChatMessage` |
| `core/realtime/RealtimeEvents.kt` | `session.tool_call`, разбор |
| `core/realtime/RealtimeClient.kt` | диспетчеризация события |
| `feature/chat/InlineViews.kt` | `InlineViewPhase`, `InlineViewsState.cardsFor`, кламп |
| `feature/chat/InlineViewsController.kt` | состояния, jobs, generation |
| `feature/chat/ChatItems.kt` | `ChatRow`, `buildChatRows` |
| `feature/chat/ChatViewModel.kt` | контроллер, сигналы, `callViewTool` |
| `feature/chat/ChatScreen.kt` | карточка, кнопка полного экрана |
| `feature/views/AgentViewScreen.kt`, `AgentViewViewModel.kt` | маршрут полного экрана |
| `navigation/MainGraph.kt` | маршрут и переходы |
| `feature/views/ViewsScreen.kt` | `policy` из `csp` лаунчера |
| `res/values`, `res/values-ru` | подписи карточки |
