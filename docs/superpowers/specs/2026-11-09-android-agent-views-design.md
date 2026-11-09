# Вью коннекторов в Android-приложении — дизайн

- Статус: draft
- Дата: 2026-11-09
- Репозиторий: `agimate-chat-android`
- Контракт: `agimate-backend` — `docs/decisions/connector-views.md`,
  `ManageAgentViewController`, `AgentViewService`
- Референс: `agimate-frontend` — `AgentViewsTab.tsx`, `AgentViewFrame.tsx`,
  `src/utils/mcp-view.ts`, `src/types/agent-views.ts`

## 1. Задача

Дать приложению просмотр «вью» агента — страниц, которые отдают привязанные
коннекторы. В вебе это вкладка **Views** на странице агента; рендер — HTML
документ в sandbox-iframe, кнопки страницы вызывают тулы коннектора от имени
агента через JSON-RPC-мост.

В приложении делаем тот же режим — **лаунчер**: список вью агента, открытие
одной выбранной вью на весь экран. Inline-режим (вью рядом с результатом
вызова в переписке) не делаем: на бэкенде он не реализован (фаза 2 решения).

## 2. Что такое вью (кратко, для контекста)

- Вью — MCP App: HTML5-документ, который коннектор отдаёт по `ui://…`.
  `ui://` — идентификатор MCP-ресурса, **не сетевой адрес**.
- Тул коннектора объявляет `_meta.ui.resourceUri`; бэкенд строит список вью из
  каталога тулов агента, то есть из привязок и ABAC. Модель вью не видит.
- Содержимое приходит как текст в JSON (`text/html;profile=mcp-app`), а не как
  страница с домена API. Это позволяет держать страницу без cookie и без
  доступа к API.
- Сеть вью не нужна: бэкенд не отдаёт `_meta.ui.csp` сервера, политика всегда
  самая строгая, внешних доменов нет.
- Кнопки страницы вызывают `tools/call`; вызов исполняется от имени агента
  через `AgentToolCallService.callAsAgent` — с ABAC и видимостью тула.

## 3. Пользовательский сценарий

1. В меню шапки переписки появляется пункт **«Вью»**, если известен агент.
2. Открывается экран со списком: карточки с названием, `uri`, чипами тулов и
   кнопкой «Открыть».
3. «Открыть» загружает страницу заново (без кеша) и показывает её на весь
   экран под тулбаром.
4. В тулбаре: «Назад» к списку, «Обновить» (перезагрузка страницы), название и
   `uri`.
5. Системный «назад» из открытой вью возвращает к списку, из списка — в
   переписку.
6. Состояния списка: загрузка, пусто (с объяснением), ошибка с повтором.
   Состояния страницы: загрузка, ошибка с текстом сервера и повтором.

## 4. Архитектура

```
Compose screen
└── WebView                         (наш host.html, origin appassets)
    └── iframe srcdoc=view.html     sandbox="allow-scripts"
         ↕ window.postMessage        (JSON-RPC 2.0, диалект MCP Apps ui/*)
    host.html  ↔  ViewBridge (Kotlin)  ↔  OkHttp/Retrofit  →  control-api
```

### Почему обёртка + iframe, а не прямой WebView

В top-level WebView `window.parent === window`: внутренние панели шлют запрос
`window.parent.postMessage` и получают его сами, из-за чего их `pending`-промис
может разрешиться до ответа хоста. Настоящий iframe внутри нашей обёртки
сохраняет модель веба: у страницы есть родитель, у документа непрозрачный
origin, а обёртка проверяет отправителя по `event.source === frame.contentWindow`.

Дополнительно мост остаётся снаружи песочницы: страница не может звать натив
напрямую, только через JSON-RPC, который натив валидирует и исполняет.

### Почему не серверная страница / Custom Tabs

HTML приходит как JSON, а не по URL; Custom Tabs не умеют мост к нативу;
хостинг страницы на отдельном origin потребовал бы новых эндпойнтов и
cookie-авторизации, что противоречит решению бэкенда не отдавать активный
контент с домена API.

### Обёртка `host.html`

Наш доверенный статический документ в `app/src/main/assets/views/host.html`.
Он собирается нативом (токен, цвет фона, HTML вью) и **отдаётся один раз**
через `WebViewClient.shouldInterceptRequest` по адресу
`https://appassets.androidplatform.net/views/host.html`, после чего загружается
через `loadUrl`. Так у обёртки есть стабильный https-origin, а HTML любой
разумной длины не упирается ни в Binder, ни в лимиты data-URL. Повторный
запрос того же адреса (например, iframe, перешедший по ссылке) получает пустой
ответ — одноразовость и есть защита токена.

HTML вью встраивается в обёртку как base64, а не как JS-строка: `</script>` в
разметке иначе закрыл бы скрипт обёртки.

Обёртка:

- при старте шлёт нативу `hello` с токеном;
- создаёт `<iframe sandbox="allow-scripts" srcdoc=…>` из уже встроенного HTML;
- пересылает нативу сообщения только от `frame.contentWindow` (JSON-RPC как
  есть, без разбора);
- получает от натива ответы и отправляет их в iframe через
  `frame.contentWindow.postMessage(reply, "*")`;
- других обязанностей у обёртки нет: весь протокол — в Kotlin.

`srcdoc`, а не `src`: страница — разметка, а `ui://` не адрес. `sandbox` без
`allow-same-origin` даёт непрозрачный origin: ни cookie, ни localStorage, ни
доступа к обёртке.

### Мост

Два канала, выбираются по поддержке WebView:

1. **`WebViewCompat.addWebMessageListener`** с origin-правилом на origin
   обёртки (`https://appassets.androidplatform.net`) и проверкой
   `isMainFrame`. Основной путь.
2. **`addJavascriptInterface`** — fallback для старых WebView. Объект с одним
   методом `@JavascriptInterface fun post(token: String, envelope: String)`.
   Ответы доставляются через `evaluateJavascript` с JSON-экранированием.

Токен генерируется на каждое открытие, передаётся обёртке в `render` и живёт
в её замыкании. Натив отклоняет сообщения без токена. Origin-правило и токен
вместе закрывают случай, когда JS-интерфейс виден и sandbox-iframe.

`WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)` решает, какой канал
включён. Если не поддерживается ни один (совсем старый WebView) — экран
показывает ошибку с объяснением, приложение не падает.

### Протокол — в Kotlin

Порт `mcp-view.ts` как чистые функции, без Android:

- `decode(message, host) → ViewAction`;
- `initializeResult(protocolVersion, host)`;
- `resultReply(id, result)`, `errorReply(id, code, message)`;
- `parseHttpsUrl(raw) → HttpsUrl?` (только `https:`).

## 5. Контракт API

Три эндпойнта control-api, все с авторизацией (обычный `@AuthedClient`):

| Метод | Путь | Ответ |
|---|---|---|
| GET | `control/manage/agents/{agentId}/views/` | `List<AgentViewDto>` |
| GET | `control/manage/agents/{agentId}/views/content?connectionId=&uri=` | `AgentViewContentDto` |
| POST | `control/manage/agents/{agentId}/views/tools/call` | `ViewToolCallResultDto` |

DTO:

```kotlin
data class AgentViewDto(
    val connectionId: String,
    val connectorCode: String,
    val connectionName: String? = null,
    val uri: String,
    val tools: List<String> = emptyList(),
)

data class AgentViewContentDto(
    val uri: String,
    val mimeType: String,
    val html: String,
    val permissions: JsonObject? = null,
    val prefersBorder: Boolean? = null,
)

data class ViewToolCallRequest(
    val connectionId: String,
    val name: String,
    val arguments: JsonObject,   // без дефолта: должен уехать всегда
)

data class ViewToolCallResultDto(
    val content: JsonElement? = null,
    val structuredContent: JsonElement? = null,
    @SerialName("isError") val isError: Boolean = false,
)
```

Семантика ошибок:

- `404` — вью больше нет у агента (привязка/тул/uri изменились);
- `502` — MCP-сервер не отдал страницу;
- `403` — тул не виден вью;
- `429` — лимит вызовов (`VIEW_CALL`, 120/мин на агента);
- ошибка самого тула — `200` с `isError: true`, странице отдаётся как результат.

Тексты ошибок сервера показываем как есть — это общая конвенция приложения
(`ApiException.of`).

## 6. Протокол

JSON-RPC 2.0 поверх `postMessage`, диалект `ui/*`. `id` — строка или число,
`0` — валидный id (проверка `"id" in message`, не truthiness).

| Сообщение | Действие хоста |
|---|---|
| `ui/initialize` (request) | ответ: эхо `protocolVersion`, `hostInfo`, `hostCapabilities.serverTools`, `hostContext` |
| `ui/notifications/initialized` | ничего |
| `ui/notifications/size-changed` | в MVP игнорируется (iframe на всю высоту) |
| `ping` | `{}` |
| `tools/call` | вызов API; из сообщения — только `name` и `arguments` |
| `ui/open-link` | только `https:`, открыть в Custom Tabs, ответ `{}` |
| неизвестный request | `-32601 Method not found` |
| неизвестная нотификация | игнор |
| битое сообщение | игнор (не рвать слушатель) |
| нет имени тула | `-32603 Missing tool name` |
| не-https ссылка | `-32603 Only https links can be opened` |

`hostContext`:

```json
{
  "theme": "light",
  "locale": "ru",
  "displayMode": "inline",
  "availableDisplayModes": ["inline"],
  "styles": { "variables": { "…": "…" } }
}
```

CSS-переменные — из токенов приложения, тот же набор, что в вебе:
`--color-background-primary`, `--color-background-secondary`,
`--color-surface-primary`, `--color-text-primary`, `--color-text-secondary`,
`--color-border-primary`, `--color-accent-primary`, `--color-text-info`,
`--color-text-danger`, `--font-sans`, `--border-radius-md`.
Значение радиуса — `AgimateTokens.Radius.control` в px; шрифт — системный
стек. Тему берём из `AgiColors.isDark`, язык — из
`resources.configuration.locales[0]`.

`prefersBorder` и `permissions` в MVP игнорируются: вью занимает весь экран,
камера/микрофон не выдаются.

## 7. Безопасность

### WebView

```
javaScriptEnabled = true
allowFileAccess = false
allowContentAccess = false
allowFileAccessFromFileURLs = false
allowUniversalAccessFromFileURLs = false
domStorageEnabled = false
databaseEnabled = false
mixedContentMode = MIXED_CONTENT_NEVER_ALLOW
javaScriptCanOpenWindowsAutomatically = false
supportMultipleWindows = false
mediaPlaybackRequiresUserGesture = true
geolocationEnabled = false
cacheMode = LOAD_NO_CACHE
saveFormData = false
```

- `WebViewClient.shouldOverrideUrlLoading` — никогда не навигировать внутри;
  `https` открывать в Custom Tabs, остальное блокировать.
- `shouldInterceptRequest` — единственная разрешённая загрузка: одноразовый
  ответ с обёрткой по её адресу. Всё остальное, включая повторный запрос
  адреса обёртки, — пустой `403`. Это и есть блокировка сети: наружу не уходит
  ни один запрос, независимо от CSP.
- `blockNetworkLoads` включается только если спайк покажет, что он не мешает
  перехвату запроса обёртки; иначе достаточно `shouldInterceptRequest`.
- `WebChromeClient`: JS-диалоги глушим, `onPermissionRequest.deny()`,
  геолокацию запрещаем, файловый chooser не открываем, `onCreateWindow` —
  `false`.
- `onRenderProcessGone` — показать ошибку и пересоздать WebView, не ронять
  процесс приложения.
- `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`.
- Алгоритмическую тёмную тему WebView выключаем: цвета приходят из
  `hostContext`, двойное затемнение не нужно.

### CSP

Политика ставится первым `<meta>` в `<head>` вью (или перед документом, если
`<head>` нет), как в вебе. Бэкенд `csp` не отдаёт, поэтому политика всегда
самая строгая:

```
default-src 'none';
script-src 'unsafe-inline';
style-src 'unsafe-inline';
img-src data: blob:;
font-src data:;
media-src data: blob:;
connect-src 'none';
frame-src 'none';
base-uri 'none';
object-src 'none'
```

Обёртка остаётся без CSP: это наш статический документ, а srcdoc-документ
наследует CSP родителя — лишний слой только запутает.

### Мост и данные

- Токен на открытие; без него сообщение отбрасывается.
- `agentId`/`connectionId` — из нативного состояния открытой вью, никогда из
  сообщения страницы.
- Токен авторизации в WebView не попадает: все вызовы — через OkHttp с
  обычным auth-интерцептором.
- `evaluateJavascript` с данными страницы — только через JSON-экранирование.
- R8: keep-правило для класса и метода JS-моста.

### Сеть

Ни вью, ни обёртка не ходят в сеть. Проверяется инструментальным тестом:
MockWebServer как «внешний» адрес, страница пробует `fetch`/`img`, счётчик
запросов остаётся нулевым.

## 8. UI и навигация

- `Routes.AGENT_VIEWS = "agent-views/{agentId}?agentName={agentName}"`.
- В `ChatUiState` добавляется `agentId: String?`; пункт меню показывается,
  когда он известен, и передаёт id в навигацию.
- `ViewsScreen`:
  - шапка с «назад» и названием агента;
  - список карточек (`LazyColumn`);
  - открытая вью — тот же маршрут, внутреннее состояние; `BackHandler`
    закрывает вью;
  - `key(revision)` вокруг WebView: «Обновить» пересоздаёт документ.
- Переиспользуем `ErrorState`, `EmptyState`, `Skeleton`, `SecondaryButton`,
  `backdrop`, `AgiTheme`.
- Строки RU/EN: `views_title`, `views_hint`, `views_empty`, `views_open`,
  `views_reload`, `views_back`, `views_loading`, `views_load_error`,
  `views_tool_call_failed`, `views_unsupported`, `chat_menu_views`.

## 9. Состояние и данные

```kotlin
data class ViewsUiState(
    val loading: Boolean = true,
    val views: List<AgentView> = emptyList(),
    val error: UiText? = null,
    val opened: OpenedView? = null,
)

data class OpenedView(
    val view: AgentView,
    val loading: Boolean = true,
    val content: String? = null,
    val error: UiText? = null,
    val revision: Int = 0,
)
```

- Список грузится при открытии экрана; кеша нет.
- Содержимое грузится при каждом открытии и каждой перезагрузке.
- `callTool(connectionId, name, arguments)` — `suspend`-функция ViewModel,
  вызывается из `rememberCoroutineScope` хоста; ошибки возвращаются мосту.
- Параллельные вызовы разрешены, ответы сопоставляются по JSON-RPC `id`.
- При уходе с экрана WebView уничтожается, незавершённые вызовы отменяются.
- Поворот экрана пересоздаёт Activity: страница инициализируется заново и
  перечитывает данные. Несохранённый ввод страницы при этом теряется —
  известное ограничение MVP; `saveState`/`restoreState` — отдельная задача.

## 10. Тестирование

- **Юнит:** `ViewProtocol` (вся таблица, неизвестные методы, битый JSON,
  `id = 0`, `isError`), `ViewCsp`, `parseHttpsUrl`, сборка `hostContext`.
- **Репозиторий:** MockWebServer — список, содержимое, вызов, `isError`,
  404/502, пустой конверт.
- **ViewModel:** Turbine — загрузка, ошибка, открытие, перезагрузка,
  ревизии, закрытие.
- **Инструментальные:** фикстура-вью делает `ui/initialize`, `tools/call`,
  `ui/open-link`; проверяются ответы, блокировка сети, отказ сообщению без
  токена, запрет навигации.
- **Ручной прогон:** dev-стенд, внутренние панели `persist-memory` и `sheets`
  (лежат в classpath бэкенда), внешняя MCP-вью на `servereverything.dev`.

## 11. Вне scope

- Inline-вью в переписке (нет на бэкенде).
- Внешние домены и `_meta.ui.csp` сервера.
- Камера, микрофон, геолокация, файлы.
- Скачивание файлов и `ui/message` / `sampling` / `resources/read`.
- Локализованные названия внутренних коннекторов (нужен каталог коннекторов).
- Сохранение состояния WebView при повороте.

## 12. Риски и что проверить спайком

1. `shouldInterceptRequest` отдаёт обёртку по
   `https://appassets.androidplatform.net/views/host.html`, и
   `addWebMessageListener` принимает этот origin на minSdk 26.
2. Поведение `srcdoc` + `sandbox` + CSP-meta на старых системных WebView.
3. `blockNetworkLoads` не мешает перехвату запроса обёртки; если мешает —
   отключаем и полагаемся на перехват.
4. Одноразовость ответа обёртки: повторный запрос с любого фрейма — пустой
   ответ.
5. Старые WebView без `WEB_MESSAGE_LISTENER` — fallback на JS-интерфейс и его
   keep-правило.
6. Наследование CSP в srcdoc и реальное отсутствие сетевых запросов.
7. Большой HTML (близко к 5 МБ) доезжает до iframe без обрезки.

## 13. Этапы

1. Спайк WebView: origin, listener, srcdoc, CSP, блокировка сети, fallback.
2. Слой данных: DTO, API, репозиторий, тесты.
3. Протокол: `ViewProtocol`, `ViewCsp`, `hostContext`, тесты.
4. Хост: `host.html`, `ViewBridge`, `ViewHost`, инструментальные тесты.
5. Экран и навигация: ViewModel, список, открытая вью, меню, строки.
6. Обвязка: tool calls, Custom Tabs, перезагрузка, R8, `onRenderProcessGone`.
7. Ручной прогон на dev-стенде и реальном устройстве, CHANGELOG.
