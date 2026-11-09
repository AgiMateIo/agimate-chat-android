# Agent Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Показать в приложении вью коннекторов агента — список и открытие HTML-страницы в sandbox-iframe с мостом вызовов тулов.

**Architecture:** Compose-экран содержит WebView с нашей доверенной обёрткой. Обёртка создаёт `<iframe sandbox="allow-scripts" src=…>` и пересылает JSON-RPC в нативный мост; протокол, проверки и вызовы API живут в Kotlin. Обёртка и документ вью отдаются двумя одноразовыми ответами `shouldInterceptRequest`; CSP вью приходит HTTP-заголовком.

**Tech Stack:** Kotlin 2.4.10, Compose (BOM 2026.08.00), AndroidX WebKit 1.18.0, OkHttp 5.4 / Retrofit 2.12, kotlinx.serialization, Hilt, JUnit4 + MockWebServer + Turbine, AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-11-09-android-agent-views-design.md`

## Global Constraints

- `minSdk 26`, `compileSdk 37`, JDK 17+.
- Весь HTTP — через существующий `@AuthedClient`; токен в WebView не попадает.
- `agentId` и `connectionId` берутся только из нативного состояния, никогда из сообщения страницы.
- Диалект протокола — MCP Apps `ui/*`; неизвестный запрос — `-32601`, внутренняя ошибка — `-32603`.
- CSP всегда самая строгая; внешних доменов нет.
- Пользовательские строки — только в `res/values` и `res/values-ru`; `lintDevDebug` ловит пропуски.
- Комментарии и `docs/` — по-русски; субъекты коммитов — по-английски, без `Co-Authored-By`, ≤72 символов.
- Проверки: `./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug`; R8 — `assembleProdRelease` (нужен `app/google-services.json`; копия стаба — `cp .github/google-services.stub.json app/google-services.json`).

## Review Focus

1. `id: 0` в запросе и нотификация без `id` — должны различаться, а не теряться. (Task 2)
2. Страница присылает свой `connectionId`/`agentId` — вызов обязан идти по connection открытой вью. (Task 7)
3. Страница пробует сеть (`fetch`, `<img src=https://…>`, навигация) — наружу не уходит ни один запрос. (Task 6)
4. Сообщение без токена или с чужим токеном — молча отбрасывается, ответа нет. (Task 5)
5. Результат тула с `isError: true` — отдаётся странице как успешный JSON-RPC result, не как error. (Tasks 1, 6)

---

### Task 1: Слой данных — DTO, API, репозиторий

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/data/views/ViewsDto.kt`
- Create: `app/src/main/java/ru/agimate/mobile/data/views/ViewsApi.kt`
- Create: `app/src/main/java/ru/agimate/mobile/data/views/ViewsRepository.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/data/ApiModule.kt`
- Test: `app/src/test/java/ru/agimate/mobile/data/views/ViewsRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AgentView(
      val connectionId: String,
      val connectorCode: String,
      val connectionName: String?,
      val uri: String,
      val tools: List<String>,
  ) {
      val title: String get() = connectionName?.takeIf { it.isNotBlank() } ?: connectorCode
  }

  data class AgentViewContent(val html: String, val mimeType: String)

  @Singleton
  class ViewsRepository @Inject constructor(private val api: ViewsApi) {
      suspend fun list(agentId: String): List<AgentView>
      suspend fun content(agentId: String, connectionId: String, uri: String): AgentViewContent
      suspend fun callTool(
          agentId: String,
          connectionId: String,
          name: String,
          arguments: JsonObject,
      ): JsonObject
  }
  ```
- `callTool` returning `JsonObject` — это ровно JSON-RPC `result`: `content`, `structuredContent` (если был), `isError`. Ключ `isError` присутствует всегда.

- [ ] **Step 1: Написать падающий тест репозитория**

`ViewsRepositoryTest` по образцу `AgentsRepositoryTest` (MockWebServer + `ApiJson` + Retrofit). Тесты и главные утверждения:

- `list parses views and titles an unnamed connection by its connector code` — ответ из двух строк, у второй `connectionName` отсутствует; утвердить `map { it.title } == listOf("Weather", "persist-memory")` и `tools`.
- `content sends uri as an encoded query parameter` — утвердить `request.url.queryParameter("connectionId") == "c1"`, `request.url.queryParameter("uri") == "ui://s/weather"`, вернувшийся `html`.
- `callTool sends the connection at the top level` — аргументы содержат `{"city":"Moscow","connectionId":"evil"}`; утвердить точное тело `{"connectionId":"c1","name":"get_weather","arguments":{"city":"Moscow","connectionId":"evil"}}`.
- `callTool keeps the mcp result verbatim` — ответ `{"response":{"content":[{"type":"text","text":"ok"}],"structuredContent":{"temp":21},"isError":true}}`; утвердить `result["isError"] == JsonPrimitive(true)` и наличие `structuredContent`; второй случай без `structuredContent` — ключа нет.
- `a 502 keeps the server message` — тело `{"error":{"message":"server did not answer"}}`; утвердить `ApiException.Server`, `code == 502`, `message` содержит текст сервера.

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.data.views.ViewsRepositoryTest"`
Expected: FAIL — `ViewsRepository` и DTO не существуют.

- [ ] **Step 3: Реализовать DTO, API, репозиторий и DI**

DTO (kotlinx.serialization):
```kotlin
@Serializable data class AgentViewDto(
    val connectionId: String, val connectorCode: String,
    val connectionName: String? = null, val uri: String,
    val tools: List<String> = emptyList(),
)
@Serializable data class AgentViewContentDto(
    val uri: String, val mimeType: String, val html: String,
    val permissions: JsonObject? = null, val prefersBorder: Boolean? = null,
)
@Serializable data class ViewToolCallRequest(
    val connectionId: String, val name: String, val arguments: JsonObject,
)
@Serializable data class ViewToolCallResultDto(
    val content: JsonElement? = null,
    val structuredContent: JsonElement? = null,
    @SerialName("isError") val isError: Boolean = false,
)
```
`ViewsApi`:
```kotlin
@GET("control/manage/agents/{agentId}/views/")
suspend fun views(@Path("agentId") agentId: String): ApiEnvelope<List<AgentViewDto>>

@GET("control/manage/agents/{agentId}/views/content")
suspend fun content(
    @Path("agentId") agentId: String,
    @Query("connectionId") connectionId: String,
    @Query("uri") uri: String,
): ApiEnvelope<AgentViewContentDto>

@POST("control/manage/agents/{agentId}/views/tools/call")
suspend fun callTool(
    @Path("agentId") agentId: String,
    @Body body: ViewToolCallRequest,
): ApiEnvelope<ViewToolCallResultDto>
```
Репозиторий использует `apiCall { … }.unwrap("…")`; в `callTool` собирает `JsonObject` через `buildJsonObject` — `content` и `structuredContent` кладутся только если не `null`, `isError` — всегда.

В `ApiModule` добавить `@Provides @Singleton fun viewsApi(@AuthedClient retrofit: Retrofit): ViewsApi`.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.data.views.ViewsRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/data/views app/src/main/java/ru/agimate/mobile/data/ApiModule.kt app/src/test/java/ru/agimate/mobile/data/views
git commit -m "feat: agent views api — endpoints, dto and repository"
```

---

### Task 2: Протокол JSON-RPC

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewProtocol.kt`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewProtocolTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ViewHostContext(
      val theme: String,          // "light" | "dark"
      val locale: String,         // BCP 47
      val variables: Map<String, String>,
  )

  @JvmInline
  value class HttpsUrl private constructor(val value: String) {
      companion object { fun parse(raw: JsonElement?): HttpsUrl? }
  }

  sealed interface ViewAction {
      data class Reply(val reply: JsonObject) : ViewAction
      data object Ready : ViewAction
      data class Resize(val height: Double) : ViewAction
      data class Call(val id: JsonElement, val name: String, val arguments: JsonObject) : ViewAction
      data class OpenLink(val id: JsonElement, val url: HttpsUrl) : ViewAction
      data object Ignore : ViewAction
  }

  object ViewProtocol {
      const val METHOD_NOT_FOUND = -32601
      const val INTERNAL_ERROR = -32603
      fun decode(raw: String, host: ViewHostContext): ViewAction
      fun initializeResult(protocolVersion: JsonElement?, host: ViewHostContext): JsonObject
      fun resultReply(id: JsonElement, result: JsonElement): JsonObject
      fun errorReply(id: JsonElement, code: Int, message: String): JsonObject
  }
  ```
- У `Call` **нет** полей `agentId`/`connectionId`: структурно страница не может выбрать чужую связку.

- [ ] **Step 1: Написать падающие тесты**

Тесты (JUnit4, чистые, без Android):
- `initialize answers with the host context and echoes the protocol version` — `theme`, `locale`, `displayMode == "inline"`, `availableDisplayModes == ["inline"]`, `hostCapabilities.serverTools` пуст, `styles.variables` совпадают, `protocolVersion == "2026-01-26"`.
- `initialized notification is ready` → `ViewAction.Ready`.
- `size changed with a usable height resizes` (`{"height": 320.5}` → `Resize(320.5)`); `size changed without a usable height is ignored` (`NaN`, `0`, отсутствие).
- `ping gets an empty result` → `Reply` с `result == {}`.
- `tools call keeps id zero and ignores a page connection` — `params` содержит `connectionId: "evil"`; утвердить `Call(JsonPrimitive(0), "get_weather", args)`, где `args` — только `arguments` из сообщения.
- `tools call without a name is an internal error` → `Reply` с `error.code == -32603`.
- `open link accepts https and rejects everything else` — `https://example.com/a` → `OpenLink`; `http:`, `javascript:`, `data:`, `https:` без хоста, не-строка → `-32603`.
- `an unknown request gets method not found` (`-32601`); `an unknown notification is ignored`.
- `malformed messages are ignored` — не JSON, `jsonrpc != "2.0"`, `method` не строка, массив.
- `result and error replies keep the id verbatim` — id `JsonPrimitive("abc")` и `JsonPrimitive(0)`.

- [ ] **Step 2: Запустить и убедиться, что тесты падают**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewProtocolTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать `ViewProtocol`**

Правила: нотификации разбираются первыми (`"id" in obj` + `id` — строка или число); `params` — объект или пустой; `initializeResult` кладёт `hostInfo {name:"agimate", version:"1"}`, `hostCapabilities.serverTools {}`, `hostContext` с темой, локалью, `displayMode:"inline"`, одним доступным режимом и `styles.variables`; `protocolVersion` добавляется, только если пришёл не `null`. `HttpsUrl.parse` использует `java.net.URI`, требует `scheme == "https"` и непустой `host`.

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewProtocolTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views/ViewProtocol.kt app/src/test/java/ru/agimate/mobile/core/views/ViewProtocolTest.kt
git commit -m "feat: mcp apps protocol — json-rpc reducer and replies"
```

---

### Task 3: CSP страницы

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewCsp.kt`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewCspTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object ViewCsp {
      fun build(): String
  }
  ```
- **Обновление после security review:** `withCsp`/meta-вставка удалены. Политика доставляется заголовком `Content-Security-Policy` в ответе `shouldInterceptRequest` (Task 6); regex-вставка `<meta>` в недоверенный HTML нейтрализуема разметкой и потому не является защитой.

- [ ] **Step 1: Написать падающие тесты**

- `policy is strict and has every directive` — точное сравнение:
  `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; base-uri 'none'; object-src 'none'`.

- [ ] **Step 2: Запустить и убедиться, что тесты падают**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewCspTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать `ViewCsp`**

Только `build()` с точной строкой политики. Никакой вставки в HTML.

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewCspTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views/ViewCsp.kt app/src/test/java/ru/agimate/mobile/core/views/ViewCspTest.kt
git commit -m "feat: view csp — strict policy injected into page markup"
```

Follow-up (security review, уже в истории): `fix: view csp — header-only policy instead of meta injection` — удаляет `withCsp` и его тесты; итоговое состояние — только `build()`.

---

### Task 4: Контекст хоста и CSS-переменные

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewHostStyle.kt`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewHostStyleTest.kt`

**Interfaces:**
- Consumes: `ViewHostContext` из Task 2.
- Produces:
  ```kotlin
  fun viewHostContext(colors: AgiColors, locale: String): ViewHostContext
  fun Color.toCssHex(): String
  ```

- [ ] **Step 1: Написать падающие тесты**

- `light theme maps every documented css variable` — для `LightColors` утвердить точный map из 11 ключей:
  `--color-background-primary` = `colors.background`, `--color-background-secondary` = `colors.surfaceMuted`, `--color-surface-primary` = `colors.surface`, `--color-text-primary` = `colors.textPrimary`, `--color-text-secondary` = `colors.textSecondary`, `--color-border-primary` = `colors.hairline`, `--color-accent-primary` = `colors.accent`, `--color-text-info` = `colors.accent`, `--color-text-danger` = `colors.danger`, `--font-sans` = `system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`, `--border-radius-md` = `8px`.
- `dark theme reports dark` — `theme == "dark"` для `DarkColors`.
- `locale is passed through` — `locale == "ru"`.
- `colors are hex` — каждое значение цвета матчится `^#[0-9A-F]{6}$`.

- [ ] **Step 2: Запустить и убедиться, что тесты падают**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewHostStyleTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

`toCssHex()`: `"#%06X".format(0xFFFFFF and toArgb())`. Радиус — `AgimateTokens.Radius.control.value.toInt()` и суффикс `px`. Тема — `if (colors.isDark) "dark" else "light"`.

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewHostStyleTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views/ViewHostStyle.kt app/src/test/java/ru/agimate/mobile/core/views/ViewHostStyleTest.kt
git commit -m "feat: view host style — app tokens as mcp apps variables"
```

---

### Task 5: Обёртка и мост

**Files:**
- Modify: `gradle/libs.versions.toml` — `webkit = "1.18.0"`; `androidx-test-core = "1.7.0"`.
- Modify: `app/build.gradle.kts` — `implementation(libs.androidx.webkit)`; `androidTestImplementation(libs.androidx.test.core)`; `androidTestImplementation(libs.okhttp.mockwebserver)`.
- Create: `app/src/main/assets/views/host.html`
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewWrapper.kt`
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewBridge.kt`
- Create: `app/src/debug/java/ru/agimate/mobile/core/views/ViewTestActivity.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewWrapperTest.kt`
- Test: `app/src/androidTest/java/ru/agimate/mobile/core/views/ViewBridgeInstrumentedTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object ViewWrapper {
      const val ORIGIN = "https://appassets.androidplatform.net"
      const val URL = "$ORIGIN/views/host.html"
      const val VIEW_URL = "$ORIGIN/views/page.html"
      fun build(hostHtml: String, viewUrl: String, token: String, background: String): String
      fun hostHtml(context: Context): String
  }

  enum class ViewBridgeMode { WEB_MESSAGE, JS_INTERFACE }

  class ViewBridge private constructor(
      val mode: ViewBridgeMode,
      val token: String,
      private val onHello: () -> Unit,
      private val onRpc: (JsonObject) -> Unit,
  ) {
      fun install(webView: WebView)
      fun send(message: JsonObject)   // оборачивает в {"type":"rpc","message":…}
      fun release()
      companion object {
          fun create(
              token: String,
              forceInterface: Boolean = false,
              onHello: () -> Unit,
              onRpc: (JsonObject) -> Unit,
          ): ViewBridge
      }
  }
  ```
- Обёртка (входящие): `{"type":"hello","token":"…"}`, `{"type":"rpc","token":"…","message":{…}}`.
- Обёртка (исходящие): `{"type":"rpc","message":{…}}`.

- [ ] **Step 1: Написать падающие тесты сборки обёртки**

`ViewWrapperTest`:
- `build replaces token, background and view url` — `ViewWrapper.VIEW_URL` и `#123456` присутствуют, токен подставлен; подстрок `__TOKEN__`, `__BACKGROUND__`, `__VIEW_URL__` не осталось.

- [ ] **Step 2: Написать инструментальный тест моста**

`ViewBridgeInstrumentedTest` (AndroidX Test, `ActivityScenario.launch(ViewTestActivity::class.java)`, WebView создаётся на UI-потоке). Обёртка грузится через `loadDataWithBaseURL`, а тестовый `WebViewClient.shouldInterceptRequest` отдаёт `ViewWrapper.VIEW_URL` с HTML фикстуры (и `403` на всё остальное) — в проде это делает Task 6:
- `bridge round-trips a request with id zero` — фикстура в iframe шлёт `{"jsonrpc":"2.0","id":0,"method":"ping"}`, натив отвечает `resultReply`, фикстура шлёт `test/reply` с результатом; `CountDownLatch.await(5, SECONDS)`.
- `bridge round-trips through the javascript interface too` — то же с `forceInterface = true`.
- `hello carries the token` — обёртка шлёт hello; утвердить, что `onHello` пришёл.
- `a message with a wrong token is dropped` — на `evaluateJavascript("AgimateHost.post('{\"type\":\"rpc\",\"token\":\"wrong\",\"message\":{}}')")` колбэк не вызывается за 500 мс.
- `the wrapper origin is accepted by the web message listener` — это и есть проверка origin; если тест не проходит, спайк провален: остановиться и пересмотреть Task 6.

- [ ] **Step 3: Запустить тесты и убедиться, что они падают**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewWrapperTest"`
Expected: FAIL.
Run (с подключённым эмулятором/устройством): `./gradlew connectedDevDebugAndroidTest`
Expected: FAIL — классов нет.

- [ ] **Step 4: Реализовать обёртку, сборку и мост**

`host.html` — минимальный документ с `__TOKEN__`, `__BACKGROUND__`, `__VIEW_URL__`:
- детект режима: `typeof AgimateHost.postMessage === "function"` → `onmessage` + `postMessage`; иначе `post` + глобальная `__agimateHostReceive`;
- при старте `send({type:"hello", token: TOKEN})`;
- создаёт `<iframe sandbox="allow-scripts" src=VIEW_URL>`;
- слушает `message`, проверяет `event.source === frame.contentWindow`, пересылает `{type:"rpc", token, message: event.data}`;
- на входящий `{type:"rpc", message}` делает `frame.contentWindow.postMessage(message, "*")`.

`ViewWrapper.build`: заменить токен, цвет и URL вью. HTML вью в обёртку не встраивается — его отдаёт второй ответ `shouldInterceptRequest` в Task 6.
`ViewBridge`:
- `create` выбирает `WEB_MESSAGE`, если `WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)`, иначе `JS_INTERFACE`; `forceInterface` — только для теста.
- `install`: `WebViewCompat.addWebMessageListener(webView, "AgimateHost", setOf(ViewWrapper.ORIGIN), listener)` с проверкой `isMainFrame`; либо `addJavascriptInterface(JsBridge(), "AgimateHost")`, где `JsBridge` — именованный `private inner class` с методом `@JavascriptInterface fun post(envelope: String)`, который вызывает `webView.post { handle(envelope) }`.
- Входящий envelope парсится `ApiJson`; `token` сверяется; `hello` → `onHello`, `rpc` + объект `message` → `onRpc`.
- `send`: listener — `replyProxy.postMessage(envelope.toString())`; interface — `webView.post { webView.evaluateJavascript("window.__agimateHostReceive(${JSONObject.quote(envelope.toString())})", null) }`.
- `release` снимает listener/интерфейс; повторный вызов безопасен.

`ViewTestActivity` — пустая `ComponentActivity`; зарегистрировать в `app/src/debug/AndroidManifest.xml`.

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.core.views.ViewWrapperTest"`
Expected: PASS.
Run: `./gradlew connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.agimate.mobile.core.views.ViewBridgeInstrumentedTest`
Expected: PASS. Если падает origin/listener — остановиться: это ворота выполнимости для Task 6.

- [ ] **Step 6: Коммит**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/assets/views/host.html app/src/main/java/ru/agimate/mobile/core/views/ViewWrapper.kt app/src/main/java/ru/agimate/mobile/core/views/ViewBridge.kt app/src/debug app/src/test/java/ru/agimate/mobile/core/views/ViewWrapperTest.kt app/src/androidTest
git commit -m "feat: agent view bridge — sandboxed host and json-rpc channel"
```

---

### Task 6: Заблокированный WebView-хост

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewWebView.kt`
- Create: `app/src/main/java/ru/agimate/mobile/core/views/ViewHost.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml` — добавить `views_tool_call_failed`.
- Test: `app/src/androidTest/java/ru/agimate/mobile/core/views/ViewHostSecurityTest.kt`
- Test: `app/src/androidTest/java/ru/agimate/mobile/core/views/ViewHostRoundTripTest.kt`

**Interfaces:**
- Consumes: `ViewProtocol`, `ViewCsp`, `viewHostContext`, `ViewWrapper`, `ViewBridge`.
- Produces:
  ```kotlin
  internal fun configureViewWebView(webView: WebView, background: Color)
  internal class ViewChromeClient : WebChromeClient()

  internal class ViewWebViewClient(
      private val wrapper: () -> String?,          // одноразовый HTML обёртки
      private val view: () -> String?,             // одноразовый HTML вью
      private val onExternalLink: (Uri) -> Unit,
      private val onRendererGone: () -> Unit,
  ) : WebViewClient()

  internal class ViewHostController(
      private val webView: WebView,
      private val hostHtml: String,
      private val content: String,
      private val hostContext: ViewHostContext,
      private val background: Color,
      /** Текст ошибки для страницы; серверное сообщение главнее общего. */
      private val toolErrorText: (Throwable) -> String,
      private val onCallTool: suspend (String, JsonObject) -> JsonObject,
      /** `null` — ссылка открыта; иначе — текст ошибки для страницы. */
      private val onOpenLink: (Uri) -> String?,
      private val onRendererGone: () -> Unit,
      private val scope: CoroutineScope,
  ) {
      fun start()
      fun release()
  }

  @Composable
  fun AgentViewHost(
      content: String,
      hostContext: ViewHostContext,
      background: Color,
      onCallTool: suspend (String, JsonObject) -> JsonObject,
      onOpenLink: (Uri) -> String?,
      onRendererGone: () -> Unit,
      modifier: Modifier = Modifier,
  )
  ```

- [ ] **Step 1: Написать падающие тесты безопасности**

`ViewHostSecurityTest`:
- `settings are locked down` — после `configureViewWebView(webView, background)`: JS включён; `allowFileAccess`, `allowContentAccess`, `domStorageEnabled`, `databaseEnabled`, `javaScriptCanOpenWindowsAutomatically`, `supportMultipleWindows` — false; `mixedContentMode == NEVER_ALLOW`.
- `the wrapper is served once with its own CSP header and then forbidden` — фейковый `WebResourceRequest`; первый `shouldInterceptRequest` возвращает HTML и `200`, заголовок обёртки содержит `frame-src https://appassets.androidplatform.net`; второй — `403`.
- `the view is served once with the strict CSP header and then forbidden` — запрос на `ViewWrapper.VIEW_URL`; первый ответ — HTML и `200`, заголовок `Content-Security-Policy` ровно `ViewCsp.build()`, `Cache-Control: no-store`; второй — `403`.
- `local data resources are not blocked` — запрос `data:image/png;base64,…` → `shouldInterceptRequest` возвращает `null`.
- `navigation is never performed inside` — `shouldOverrideUrlLoading` на `https://example.com` возвращает `true` и зовёт колбэк; на не-https — `true` без колбэка; на `ViewWrapper.URL` — `false` (это загрузка самой обёртки, её обслуживает перехват).
- `network access from the page sends nothing` — MockWebServer; фикстура с `<img src="${server.url("/img")}">`, `fetch("${server.url("/fetch")}")` и попыткой `new WebSocket(server.url("/ws").toString().replace("http", "ws"))`; после загрузки host — `server.requestCount == 0`.
- `a large page still initializes` — фикстура с ~5 МБ текста (потолок MCP-ресурса); `ui/initialize` доходит.

`ViewHostRoundTripTest`:
- `initialize and tools call go through` — фикстура шлёт `ui/initialize`, затем `tools/call` с именем `get_weather`; `onCallTool` на `get_weather` возвращает `{"content":[…],"isError":true}`; получив результат, фикстура шлёт второй `tools/call` с именем `report` и аргументом `{"result": <полученный результат>}`; тест утверждает, что в `report` пришёл `result.isError == true`.
- `open link goes to the host` — фикстура шлёт `ui/open-link` с `https://example.com`; колбэк получает адрес; ответ приходит.
- `a hostile tool error becomes a json-rpc error` — `onCallTool` бросает `ApiException.Offline`; `toolErrorText = { "tool failed" }`; фикстура получает `error.code == -32603` и `error.message == "tool failed"`.

- [ ] **Step 2: Запустить и убедиться, что тесты падают**

Run: `./gradlew connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.agimate.mobile.core.views.ViewHostSecurityTest,ru.agimate.mobile.core.views.ViewHostRoundTripTest`
Expected: FAIL — классов нет.

- [ ] **Step 3: Реализовать `configureViewWebView` и `ViewWebViewClient`**

Настройки — из спеки, раздел 7. `blockNetworkLoads` включается только если тест этого же шага подтвердит, что перехваченная загрузка обёртки не ломается; иначе сеть закрыта перехватом и CSP (в том числе для WebSocket — через `connect-src 'none'`). Клиент:
- `shouldInterceptRequest`: `data:`/`blob:`/`about:` → `null` (это локальные ресурсы страницы, CSP их разрешает); `ViewWrapper.URL` с `GET`, обёртка ещё не отдана → HTML обёртки и `200` с заголовками `Content-Security-Policy` (обёрточная политика: `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-src https://appassets.androidplatform.net`) и `Cache-Control: no-store`; `ViewWrapper.VIEW_URL` с `GET`, вью ещё не отдана → HTML вью и `200` с `Content-Security-Policy: ViewCsp.build()` и `Cache-Control: no-store`; иначе `403` с пустым телом. У обоих адресов отдельный одноразовый флаг.
- `shouldOverrideUrlLoading`: `ViewWrapper.URL` → `false` (загрузку обслуживает перехват); остальное всегда `true`, `https` — `onExternalLink(uri)`, не-https — молча.
- `onRenderProcessGone`: `onRendererGone()`, вернуть `true`.

`ViewChromeClient`: JS-диалоги → `true`; `onPermissionRequest.deny()`; геолокация → запрет; `onShowFileChooser` → `false`; `onCreateWindow` → `false`.

`configureViewWebView` также выключает алгоритмическую тёмную тему (`WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)` при поддержке) и ставит `setBackgroundColor` цвета фона.

- [ ] **Step 4: Реализовать `ViewHostController` и `AgentViewHost`**

`start()`: собрать `ViewWrapper.build(hostHtml, ViewWrapper.VIEW_URL, token, background.toCssHex())`, создать `ViewBridge`, `install`, выставить клиентов (`ViewWebViewClient` получает `wrapper = { wrapperHtml }`, `view = { content }`, `onExternalLink = { uri -> onOpenLink(uri) }`), `loadUrl(ViewWrapper.URL)`.
`onRpc`: `ViewProtocol.decode(text, hostContext)`; `Reply` → `bridge.send`; `Call` → `scope.launch { runCatching { onCallTool(name, arguments) } … }`, успех — `resultReply(id, result)`, ошибка — `errorReply(id, INTERNAL_ERROR, toolErrorText(error))`; `OpenLink` → `onOpenLink(uri)`: `null` — `resultReply(id, {})`, иначе `errorReply(id, INTERNAL_ERROR, текст)`; `Resize`/`Ready`/`Ignore` — ничего.
`AgentViewHost` — `AndroidView(factory = { … }, onRelease = { controller.release(); it.destroy() })`, `rememberCoroutineScope()`, `remember { ViewWrapper.hostHtml(context) }`; `toolErrorText` собирается на месте: `{ e -> (e as? ApiException)?.text?.resolve(context) ?: context.getString(R.string.views_tool_call_failed) }`. Токен — `UUID.randomUUID().toString()` на экземпляр; перезагрузка пересоздаёт хост (ключ снаружи).

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.agimate.mobile.core.views.ViewHostSecurityTest,ru.agimate.mobile.core.views.ViewHostRoundTripTest`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views/ViewWebView.kt app/src/main/java/ru/agimate/mobile/core/views/ViewHost.kt app/src/androidTest/java/ru/agimate/mobile/core/views app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "feat: agent view host — one-shot wrapper and network lockdown"
```

---

### Task 7: ViewModel экрана

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/feature/views/ViewsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml` — добавить `views_crashed`.
- Test: `app/src/test/java/ru/agimate/mobile/feature/views/ViewsViewModelTest.kt`

**Interfaces:**
- Consumes: `ViewsRepository`, `AgentView`, `AgentViewContent`.
- Produces:
  ```kotlin
  data class OpenedView(
      val view: AgentView,
      val loading: Boolean = true,
      val content: String? = null,
      val error: UiText? = null,
      val revision: Int = 0,
  )

  data class ViewsUiState(
      val loading: Boolean = true,
      val views: List<AgentView> = emptyList(),
      val error: UiText? = null,
      val opened: OpenedView? = null,
  )

  @HiltViewModel
  class ViewsViewModel @Inject constructor(
      private val repository: ViewsRepository,
      savedState: SavedStateHandle,
  ) : ViewModel() {
      val agentId: String
      val agentName: String
      val state: StateFlow<ViewsUiState>
      fun load()
      fun open(view: AgentView)
      fun reload()
      fun close()
      fun onRendererGone()
      suspend fun callTool(view: AgentView, name: String, arguments: JsonObject): JsonObject
  }
  ```

- [ ] **Step 1: Написать падающие тесты**

По образцу `CreateAgentViewModelTest` (MockWebServer + реальный репозиторий + `SavedStateHandle(mapOf("agentId" to "a1", "agentName" to "Луна"))`, `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`):
- `load fills the list` — две вью, `loading == false`.
- `load error is shown` — 500 → `error != null`, `loading == false`.
- `open loads the page` — `open(view)`, дождаться `opened?.content != null`; `revision == 0`.
- `open error is shown` — 404 → `opened?.error != null`.
- `reload increments the revision even for identical html` — два одинаковых ответа; после `reload()` `revision == 1` и HTML тот же.
- `close clears the opened view`.
- `callTool uses the opened view connection and not the page argument` — аргументы содержат `{"connectionId":"evil"}`; утвердить, что тело запроса содержит `"connectionId":"c1"` (из `view.connectionId`).
- `renderer gone shows an error and retry reloads` — `onRendererGone()` → `opened?.error`; `reload()` очищает ошибку.

- [ ] **Step 2: Запустить и убедиться, что тесты падают**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.feature.views.ViewsViewModelTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать ViewModel**

`agentId` — `checkNotNull(savedState["agentId"])`; `agentName` — `savedState.get<String>("agentName").orEmpty()`. В `init` — `load()`. `open`/`reload` отменяют предыдущий запрос содержимого; ошибка — `ApiException.text`. `reload` увеличивает `revision` только при успехе. `callTool` — `repository.callTool(agentId, view.connectionId, name, arguments)`.

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew testDevDebugUnitTest --tests "ru.agimate.mobile.feature.views.ViewsViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/views/ViewsViewModel.kt app/src/test/java/ru/agimate/mobile/feature/views/ViewsViewModelTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "feat: views viewmodel — list, open, reload and tool calls"
```

---

### Task 8: Экран, маршрут и меню чата

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/feature/views/ViewsScreen.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/navigation/MainGraph.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Consumes: `ViewsViewModel`, `AgentViewHost`, `viewHostContext`, `CustomTabs`.
- Produces:
  ```kotlin
  const val AGENT_VIEWS = "agent-views/{agentId}?agentName={agentName}"
  fun agentViews(agentId: String, agentName: String): String
  ```

- [ ] **Step 1: Добавить строки**

RU (кроме уже добавленных в Tasks 6 и 7 `views_tool_call_failed` и `views_crashed`): `views_title` «Вью», `views_hint` (текст из веба: страницы подключений агента, работают изолированно, кнопки зовут инструменты от имени агента), `views_empty` (вью приходят от подключения; MCP-подключение покажет их после проверки), `views_open` «Открыть», `views_reload` «Обновить», `views_back` «Все вью», `views_loading` «Загружаем страницу…», `views_load_error` «Не удалось загрузить страницу», `views_unsupported` «Это устройство не умеет показывать вью», `views_no_browser` «Не нашли браузер, чтобы открыть ссылку», `chat_menu_views` «Вью».
EN — те же ключи по-английски (копия из `messages/dashboard/en.json`).

- [ ] **Step 2: Добавить `agentId` в состояние чата**

В `ChatUiState` добавить `val agentId: String? = null`; `agentKnown` заменить вычисляемым `val agentKnown: Boolean get() = agentId != null`. В начальном состоянии — `agentId = savedState.get("agentId")`; в `learnAgent` — `copy(agentId = agentId)`.

- [ ] **Step 3: Добавить маршрут и экран**

`Routes.AGENT_VIEWS` + `agentViews(...)`; в `MainGraph` — `composable` с `hiltViewModel<ViewsViewModel>()`, аргументы `agentId` и `agentName`. `ViewsScreen`:
- список: карточка с `title`, `uri` моноширинным, чипами `tools`, нажатие открывает;
- пусто/ошибка — `EmptyState`/`ErrorState`;
- открытая вью: тулбар (назад к списку, название, `uri`, «Обновить») и `key(opened.revision) { AgentViewHost(...) }`;
- `BackHandler(enabled = state.opened != null) { onCloseOpened() }`;
- `onCallTool = { name, args -> viewModel.callTool(opened.view, name, args) }`;
- `onOpenLink` — `CustomTabs.open(context, uri, LightColors.background.toArgb(), DarkColors.background.toArgb())`; успех — `null`, отказ — `context.getString(R.string.views_no_browser)`;
- `hostContext = viewHostContext(AgiTheme.colors, LocalContext.current.resources.configuration.locales[0].toLanguageTag())`;
- `background = AgiTheme.colors.background`;
- `onRendererGone = viewModel::onRendererGone`.

- [ ] **Step 4: Добавить пункт меню чата**

В `ChatHeader` после «Файлы» добавить пункт `chat_menu_views`, видимый при `state.agentId != null`; `onClick` — `onOpenViews(state.agentId)`. Пробросить `onOpenViews: (String) -> Unit` через `ChatScreen`; в `MainGraph` — `navController.navigate(Routes.agentViews(id, state.agentName))`.

- [ ] **Step 5: Собрать и проверить вручную**

Run: `./gradlew lintDevDebug assembleDevDebug`
Expected: PASS, `MissingTranslation` нет.

Ручной прогон: dev-сборка → агент с привязанными `persist-memory` и `sheets` → чат → меню → «Вью». Проверить: список, открытие, кнопки панели (память/таблицы), «Обновить», «Назад», поворот экрана. В логах бэкенда вызовы должны иметь `initiator = VIEW`.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/views app/src/main/java/ru/agimate/mobile/navigation/MainGraph.kt app/src/main/java/ru/agimate/mobile/feature/chat/ChatViewModel.kt app/src/main/java/ru/agimate/mobile/feature/chat/ChatScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "feat: views screen — agent views list and sandboxed viewer"
```

---

### Task 9: Release-обвязка и полная проверка

**Files:**
- Modify: `app/proguard-rules.pro`
- Modify: `app/src/main/java/ru/agimate/mobile/AgiMateApp.kt`

**Interfaces:** нет новых.

- [ ] **Step 1: Добавить keep-правило для JS-моста**

```proguard
-keepclassmembers class ru.agimate.mobile.core.views.ViewBridge$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
```
Класс `JsBridge` — приватный вложенный класс `ViewBridge`; правило должно совпадать с фактическим именем (проверить после реализации Task 5).

- [ ] **Step 2: Включить отладку WebView только в debug**

В `AgiMateApp.onCreate`: `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`.

- [ ] **Step 3: Полная проверка**

Run: `./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug`
Expected: PASS.
Run: `cp .github/google-services.stub.json app/google-services.json` (если файла нет), затем `./gradlew assembleProdRelease`
Expected: PASS — R8 не вырезал мост и не сломал сборку.
Run (с устройством): `./gradlew connectedDevDebugAndroidTest`
Expected: PASS.

- [ ] **Step 4: Коммит**

```bash
git add app/proguard-rules.pro app/src/main/java/ru/agimate/mobile/AgiMateApp.kt
git commit -m "chore: agent views — r8 keep rules and release build"
```

---

### Task 10: Документация и CHANGELOG

**Files:**
- Modify: `docs/android-app-spec.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Обновить `docs/android-app-spec.md`**

Добавить раздел «Вью коннекторов» в API-часть: три эндпойнта, форма ответов, ошибки 404/502/403/429, `isError` внутри результата. В разделе экранов — пункт про вью из меню чата: список, просмотр, песочница, обновление. В разделе «Чего в приложении быть не должно» — оговорка: нативных экранов коннекторов по-прежнему нет; вью — это отданные бэкендом страницы в песочнице. В чек-лист — пункт «Вью: сеть и файлы заблокированы, токен в WebView не попадает».

- [ ] **Step 2: Добавить запись в CHANGELOG**

В `## [Unreleased]` → `### Added`:
`- В меню чата — вью коннекторов: список и просмотр страниц в песочнице.`

- [ ] **Step 3: Проверить, что ничего не разъехалось**

Run: `./gradlew lintDevDebug testDevDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Коммит**

```bash
git add docs/android-app-spec.md CHANGELOG.md
git commit -m "docs: agent views — app spec and changelog"
```

---

## Self-review

- Спека покрыта: API (Task 1), протокол (Task 2), CSP (Task 3), тема/локаль (Task 4), обёртка/мост (Task 5), WebView и сеть (Task 6), состояние (Task 7), UI/навигация (Task 8), R8/сборка (Task 9), документы (Task 10).
- Review Focus: (1) id 0 — Task 2; (2) чужой connection — Task 7; (3) сеть — Task 6; (4) токен — Task 5; (5) `isError` — Tasks 1 и 6.
- Имена и типы сквозные: `ViewHostContext`, `ViewAction`, `ViewBridge`, `ViewHostController`, `AgentViewHost`, `ViewsViewModel`.
- Вне scope: inline, внешние домены, permissions, сохранение состояния WebView при повороте.
