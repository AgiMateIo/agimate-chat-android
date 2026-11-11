# Inline Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Показать под строкой прогресса свёрнутые карточки вызовов, у которых есть вью, и открыть ту же вью в карточке по тапу — без второго хоста, моста и протокола; включить внешние домены из `AgentViewContentResponse.csp`.

**Architecture:** Один `ViewPolicy` держит CSP-заголовок и allow-list origin для перехватчика. Один `AgentViewHost` получает `ViewCallData` и сам шлёт `tool-input`/`tool-result` после `initialized`. Один `ToolCallViewLoader` грузит вызов и страницу для карточки и полного экрана. Состояние карточек живёт в `InlineViewsController`; `ChatScreen` разворачивает группы в `ChatRow.Card` отдельными элементами `LazyColumn`.

**Tech Stack:** Kotlin 2.4.10, Compose (BOM 2026.08.00), AndroidX WebKit 1.18.0, OkHttp 5.4 / Retrofit 2.12, kotlinx.serialization, Hilt, JUnit4 + MockWebServer + Turbine, AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-11-11-inline-views-design.md`

## Global Constraints

- `minSdk 26`, `compileSdk 37`, JDK 17+.
- Второго хоста/моста/протокола не заводить; `AgentViewHost`, `ViewHostController`, `ViewBridge`, `ViewProtocol`, `ViewWrapper` переиспользуются.
- `agentId`/`connectionId` — только из нативного состояния; из сообщения страницы берутся только `name` и `arguments`.
- Изоляция: `sandbox="allow-scripts"`, запрет файлов, окон, разрешений и навигации; внешняя сеть — только точные origin из `ViewPolicy`; CSP — только HTTP-заголовком.
- До тапа — ни данных вызова, ни страницы; 0 запросов `tool-calls` на открытие сессии.
- `404/403` вызова — молча `Unavailable`, карточка остаётся; `404` страницы — карточка снимается; `502` — ошибка с повтором.
- `tool-input` — один раз на экземпляр WebView; `tool-result` — после него и при изменении; задержка 100 мс после `initialized`.
- Высота: `320 dp .. min(720 dp, 0.7 × screenHeightDp)`, верхний предел не меньше 320; без анимации.
- Пользовательские строки — в `res/values` и `res/values-ru`; комментарии и `docs/` — по-русски; субъекты коммитов — по-английски, без `Co-Authored-By`, ≤72 символов.
- Проверки: `./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug`; инструментальные — только при подключённом устройстве.

## Review Focus

1. Сигнал `session.tool_call` пришёл раньше истории: дескриптор сигнала обязан пережить загрузку страницы и победить исторический. (Task 8)
2. Поздний ответ загрузчика после сворачивания/снятия карточки: HTML не возвращается в состояние, карточка не «оживает». (Task 8)
3. `404` вызова против `404` страницы и `viewUri == null` в сигнале: карточка остаётся, снимается или никогда не появляется. (Tasks 6, 8)
4. Разрешённый CSP-origin и запрещённый: перехватчик пускает только первый, CSP-заголовок содержит домены. (Task 5)
5. Ландшафт/сплит, где `0.7 × screenHeightDp < 320`: кламп не бросает исключение и не отдаёт высоту меньше минимума. (Task 7)

---

### Task 1: `ViewPolicy` — CSP и allow-list origin

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/core/views/ViewCsp.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/core/views/ViewWebView.kt:165`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewCspTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ViewExternalDomains(
      val resourceDomains: List<String> = emptyList(),
      val connectDomains: List<String> = emptyList(),
  )

  @JvmInline
  value class ViewOrigin(val value: String) {
      companion object {
          fun parse(raw: String): ViewOrigin?
          fun of(scheme: String?, host: String?, port: Int): ViewOrigin?
      }
  }

  data class ViewPolicy(val header: String, val allowedOrigins: Set<ViewOrigin>) {
      fun allows(scheme: String?, host: String?, port: Int): Boolean
  }

  object ViewCsp {
      fun policy(domains: ViewExternalDomains?): ViewPolicy
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `null domains keep today's strict header`() {
    assertEquals(
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; object-src 'none'",
        ViewCsp.policy(null).header,
    )
}

@Test fun `resource domains go to every resource directive`() {
    val policy = ViewCsp.policy(ViewExternalDomains(resourceDomains = listOf("https://esm.sh")))
    assertEquals(
        "default-src 'none'; script-src 'unsafe-inline' https://esm.sh; " +
            "style-src 'unsafe-inline' https://esm.sh; img-src data: blob: https://esm.sh; " +
            "font-src data: https://esm.sh; media-src data: blob: https://esm.sh; " +
            "connect-src 'none'; frame-src 'none'; base-uri 'none'; object-src 'none'",
        policy.header,
    )
}

@Test fun `connect domains include https and wss`() {
    val policy = ViewCsp.policy(ViewExternalDomains(connectDomains = listOf("https://api.example.com")))
    assertTrue(policy.header.contains("connect-src https://api.example.com wss://api.example.com"))
}

@Test fun `malformed origins are dropped`() {
    val policy = ViewCsp.policy(
        ViewExternalDomains(resourceDomains = listOf("http://esm.sh", "https://*.*", "esm.sh")),
    )
    assertEquals(ViewCsp.policy(null).header, policy.header)
}

@Test fun `allows matches the exact origin and treats 443 as default`() {
    val policy = ViewCsp.policy(
        ViewExternalDomains(resourceDomains = listOf("https://esm.sh")),
    )
    assertTrue(policy.allows("https", "esm.sh", -1))
    assertTrue(policy.allows("https", "esm.sh", 443))
    assertFalse(policy.allows("https", "esm.sh", 8443))
    assertFalse(policy.allows("http", "esm.sh", -1))
    assertFalse(policy.allows("https", "evil.example", -1))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.views.ViewCspTest'`
Expected: FAIL — `policy`/`ViewExternalDomains` unresolved.

- [ ] **Step 3: Implement the policy**

`ViewOrigin.parse` uses `java.net.URI`: only absolute `https`, non-null host, no userinfo, path `/` or empty, no query/fragment, no `*`; normalize host lowercase and drop port 443. `ViewOrigin.of` applies the same host/port rules to WebView request parts. `ViewCsp.policy` builds each directive with the canonical resource origins; for connect it emits `https://host[:port]` followed by `wss://host[:port]` for each connect origin. Empty domains return one cached strict policy.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.views.ViewCspTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views/ViewCsp.kt \
        app/src/main/java/ru/agimate/mobile/core/views/ViewWebView.kt \
        app/src/test/java/ru/agimate/mobile/core/views/ViewCspTest.kt
git commit -m "feat: view csp — one policy for the header and the network allow-list"
```

---

### Task 2: Data — descriptors, session tool call, csp

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/data/views/ViewsDto.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/data/views/ViewsApi.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/data/views/ViewsRepository.kt`
- Test: `app/src/test/java/ru/agimate/mobile/data/views/ViewsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ViewExternalDomains` from Task 1.
- Produces:
  ```kotlin
  data class ToolCallDescriptor(
      val toolCallId: String,
      val name: String,
      val connectionId: String?,
      val viewUri: String?,
  )

  data class SessionToolCall(
      val toolCallId: String,
      val name: String,
      val connectionId: String?,
      val viewUri: String?,
      val input: JsonObject,
      val result: JsonObject?,
  )

  data class AgentViewContent(
      val html: String,
      val mimeType: String,
      val externalDomains: ViewExternalDomains? = null,
  )

  suspend fun ViewsRepository.toolCall(sessionId: String, toolCallId: String): SessionToolCall
  ```
  `SessionToolCallDto.input` is `JsonObject?`; the model normalizes it to an empty object.
  `SessionToolCallDto.result` is `ViewToolCallResultDto?`; the model flattens it with the same
  `content`/`structuredContent`/`isError` rule as `callTool` (extract one private `toJson()`).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `toolCall parses input and result and keeps a missing view nullable`() = runTest {
    server.enqueue(ok("""{"response":{
        "toolCallId":"c2","name":"show_memory","connectionId":"k1",
        "viewUri":"ui://persist-memory/memory",
        "input":{"query":"all"},
        "result":{"content":[{"type":"text","text":"ok"}],"isError":false}}}"""))

    val call = repository.toolCall("s1", "c2")

    assertEquals("/control/manage/sessions/s1/tool-calls/c2", server.takeRequest().target)
    assertEquals("ui://persist-memory/memory", call.viewUri)
    assertEquals("k1", call.connectionId)
    assertEquals(buildJsonObject { put("query", "all") }, call.input)
    assertEquals(JsonPrimitive(false), call.result?.get("isError"))
}

@Test fun `toolCall without input gives an empty object and a null result`() = runTest {
    server.enqueue(ok("""{"response":{"toolCallId":"c1","name":"get_memory"}}"""))
    val call = repository.toolCall("s1", "c1")
    assertEquals(buildJsonObject { }, call.input)
    assertNull(call.result)
    assertNull(call.viewUri)
    assertNull(call.connectionId)
}

@Test fun `content carries the granted csp`() = runTest {
    server.enqueue(ok("""{"response":{"uri":"ui://x","mimeType":"text/html","html":"<html></html>",
        "csp":{"resourceDomains":["https://esm.sh"],"connectDomains":["https://api.example.com"]}}}"""))
    val content = repository.content("a1", "c1", "ui://x")
    assertEquals(listOf("https://esm.sh"), content.externalDomains?.resourceDomains)
    assertEquals(listOf("https://api.example.com"), content.externalDomains?.connectDomains)
}

@Test fun `content without csp has no external domains`() = runTest {
    server.enqueue(ok("""{"response":{"uri":"ui://x","mimeType":"text/html","html":"<html></html>"}}"""))
    assertNull(repository.content("a1", "c1", "ui://x").externalDomains)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.views.ViewsRepositoryTest'`
Expected: FAIL — `toolCall`, `SessionToolCall`, `externalDomains` unresolved.

- [ ] **Step 3: Implement DTOs, paths and mapping**

Add `ToolCallDescriptorDto`, `SessionToolCallDto`, `AgentViewCspDto`; add `csp` to `AgentViewContentDto`. Add the `@GET("control/manage/sessions/{sessionId}/tool-calls/{toolCallId}")` method to `ViewsApi` (the view resolves the call; keeping it here avoids `ViewsRepository` depending on `WebchatApi`). Map `csp` to `ViewExternalDomains` only when at least one list is non-empty.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.views.ViewsRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/data/views \
        app/src/test/java/ru/agimate/mobile/data/views/ViewsRepositoryTest.kt
git commit -m "feat: session tool call — descriptors, input and result behind one endpoint"
```

---

### Task 3: Data — tool call fields in chat messages

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/data/webchat/WebchatDto.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/data/webchat/WebchatModels.kt`
- Test: `app/src/test/java/ru/agimate/mobile/data/webchat/WebchatMessageFieldsTest.kt`

**Interfaces:**
- Consumes: `ToolCallDescriptorDto`/`ToolCallDescriptor` from Task 2.
- Produces:
  ```kotlin
  data class WebchatMessageDto(
      /* … existing fields … */
      val toolCallIds: List<String>? = null,
      val toolCalls: List<ToolCallDescriptorDto>? = null,
  )

  data class ChatMessage(
      /* … existing fields … */
      val toolCallIds: List<String>? = null,
      val toolCalls: List<ToolCallDescriptor>? = null,
  )
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `history maps tool call ids and descriptors by id`() {
    val message = ChatMessage.from(
        WebchatMessageDto(
            id = "m1", stream = "progress",
            toolCallIds = listOf("c1", "c2"),
            toolCalls = listOf(
                ToolCallDescriptorDto("c2", "show_memory", "k1", "ui://persist-memory/memory"),
            ),
        ),
    )
    assertEquals(listOf("c1", "c2"), message.toolCallIds)
    assertEquals("c2", message.toolCalls?.single()?.toolCallId)
    assertEquals("ui://persist-memory/memory", message.toolCalls?.single()?.viewUri)
}

@Test fun `empty descriptors stay an empty list and absent ones stay null`() {
    assertEquals(emptyList<ToolCallDescriptor>(), ChatMessage.from(WebchatMessageDto(id = "m1", toolCalls = emptyList())).toolCalls)
    assertNull(ChatMessage.from(WebchatMessageDto(id = "m1")).toolCalls)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.webchat.WebchatMessageFieldsTest'`
Expected: FAIL — fields unresolved.

- [ ] **Step 3: Add the fields and mapping**

Add the two fields to the DTO and `ChatMessage`; map them in `ChatMessage.from`. The key calculation and `copy` semantics stay untouched.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.webchat.WebchatMessageFieldsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/data/webchat app/src/test/java/ru/agimate/mobile/data/webchat
git commit -m "feat: chat messages — carry tool call ids and resolved descriptors"
```

---

### Task 4: Realtime — `session.tool_call` and live ids

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/core/realtime/RealtimeEvents.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/core/realtime/RealtimeClient.kt`
- Test: `app/src/test/java/ru/agimate/mobile/core/realtime/RealtimeEventParserTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class WebchatMessagePayload(/* … */ val toolCallIds: List<String>? = null)

  @Serializable
  data class SessionToolCallPayloadDto(
      val sessionId: String? = null,
      val toolCallIds: List<String>? = null,
      val toolCalls: List<ToolCallDescriptorDto>? = null,
  )

  sealed interface RealtimeEvent {
      data class ToolCall(val payload: SessionToolCallPayloadDto) : RealtimeEvent
  }

  internal object RealtimeEventParser {
      fun parse(type: String?, payload: JsonElement?): RealtimeEvent?
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `webchat message carries tool call ids`() {
    val event = RealtimeEventParser.parse(
        "webchat.message",
        ApiJson.parseToJsonElement("""{"sessionId":"s1","toolCallIds":["c2"]}"""),
    )
    assertEquals(listOf("c2"), (event as RealtimeEvent.Message).payload.toolCallIds)
}

@Test fun `session tool call carries descriptors and keeps null as null`() {
    val event = RealtimeEventParser.parse(
        "session.tool_call",
        ApiJson.parseToJsonElement("""{"sessionId":"s1","toolCallIds":["c2"],
            "toolCalls":[{"toolCallId":"c2","name":"show_memory","connectionId":"k1",
            "viewUri":"ui://persist-memory/memory"}]}"""),
    ) as RealtimeEvent.ToolCall
    assertEquals("s1", event.payload.sessionId)
    assertEquals("ui://persist-memory/memory", event.payload.toolCalls?.single()?.viewUri)

    val unresolved = RealtimeEventParser.parse(
        "session.tool_call",
        ApiJson.parseToJsonElement("""{"sessionId":"s1","toolCallIds":["c2"],"toolCalls":null}"""),
    ) as RealtimeEvent.ToolCall
    assertNull(unresolved.payload.toolCalls)
}

@Test fun `unknown and malformed events are null`() {
    assertNull(RealtimeEventParser.parse("future.event", ApiJson.parseToJsonElement("""{}""")))
    assertNull(RealtimeEventParser.parse("session.tool_call", JsonNull))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.realtime.RealtimeEventParserTest'`
Expected: FAIL — parser unresolved.

- [ ] **Step 3: Extract the parser and add the event**

Move the existing `when (type)` from `RealtimeClient.dispatch` into `RealtimeEventParser.parse`; keep the `payload!!` guards but return `null` instead of throwing. Add the `session.tool_call` branch and `RealtimeEvent.ToolCall`; add the `describe()` branch.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.realtime.RealtimeEventParserTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/core/realtime app/src/test/java/ru/agimate/mobile/core/realtime
git commit -m "feat: realtime — session tool call signal with view descriptors"
```

---

### Task 5: Host — call data, resize, network allow-list

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/core/views/ViewProtocol.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/core/views/ViewHost.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/core/views/ViewWebView.kt`
- Test: `app/src/test/java/ru/agimate/mobile/core/views/ViewProtocolTest.kt`
- Test: `app/src/androidTest/java/ru/agimate/mobile/core/views/ViewHostSecurityTest.kt`
- Test: `app/src/androidTest/java/ru/agimate/mobile/core/views/ViewHostRoundTripTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class ViewCallData(val input: JsonObject, val result: JsonObject?)

  object ViewProtocol {
      fun toolInputNotification(arguments: JsonObject): JsonObject
      fun toolResultNotification(result: JsonObject): JsonObject
  }

  fun AgentViewHost(
      content: String,
      policy: ViewPolicy = ViewCsp.policy(null),
      hostContext: ViewHostContext,
      background: Color,
      onCallTool: suspend (String, JsonObject) -> JsonObject,
      onOpenLink: (Uri) -> String?,
      onRendererGone: () -> Unit,
      modifier: Modifier = Modifier,
      toolCall: ViewCallData? = null,
      onResize: (Double) -> Unit = {},
  )

  class ViewHostController(
      /* … existing … */
      policy: ViewPolicy = ViewCsp.policy(null),
      toolCall: ViewCallData? = null,
      onResize: (Double) -> Unit = {},
  ) {
      fun updateToolCall(data: ViewCallData?)
  }
  ```

- [ ] **Step 1: Write the failing protocol and host tests**

`ViewProtocolTest`: assert `toolInputNotification` is
`{"jsonrpc":"2.0","method":"ui/notifications/tool-input","params":{"arguments":{...}}}` without an
`id`, and `toolResultNotification` puts the result object itself in `params`.

`ViewHostSecurityTest`: with a permissive policy, `shouldInterceptRequest` returns `null` for
`https://esm.sh/mod.js` and `403` for `https://evil.example/mod.js`; the view response header contains
`script-src 'unsafe-inline' https://esm.sh` and `connect-src https://api.example.com wss://api.example.com`.

`ViewHostRoundTripTest`: a fixture sends `ui/notifications/initialized` and reports every host
notification it receives. Construct the controller with `ViewCallData(input, result)`; assert the
fixture saw `tool-input` then `tool-result`, exactly one input, and that `updateToolCall` with a new
result sends only a second `tool-result`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.views.ViewProtocolTest'`
Expected: FAIL — notification builders unresolved.
Run (если есть устройство): `./gradlew connectedDevDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.agimate.mobile.core.views.ViewHostSecurityTest`
Expected: FAIL — `policy` unresolved.

- [ ] **Step 3: Implement the notifications and host state**

`ViewProtocol` builders create id-less notifications. `ViewHostController` keeps `initialized`,
`inputSent`, `lastResult`, `initialSendDone` and a `sendJob`; on `ViewAction.Ready` it schedules
`sendCallData()` after 100 ms; `updateToolCall` stores the latest data and, after the first send,
sends only a changed result; `release` cancels the timer. `dispatch` forwards `Resize` to `onResize`.
`ViewWebViewClient` takes `policy: () -> ViewPolicy = { ViewCsp.policy(null) }`, serves the view with
`policy().header`, and returns `null` for any request whose origin is in `policy().allowedOrigins`;
everything else stays `403`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.core.views.ViewProtocolTest'`
Expected: PASS.
Run on a device: the two instrumented classes above; expected PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/core/views app/src/test/java/ru/agimate/mobile/core/views app/src/androidTest/java/ru/agimate/mobile/core/views
git commit -m "feat: view host — tool call data, size changes and policy-driven network access"
```

---

### Task 6: `ToolCallViewLoader` — one loader for both surfaces

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/data/views/ToolCallViewLoader.kt`
- Create: `app/src/main/java/ru/agimate/mobile/data/views/ViewsModule.kt`
- Test: `app/src/test/java/ru/agimate/mobile/data/views/ToolCallViewLoaderTest.kt`

**Interfaces:**
- Consumes: `ViewsRepository.toolCall`, `ViewsRepository.content` from Task 2.
- Produces:
  ```kotlin
  sealed interface ToolCallView {
      data class Ready(val call: SessionToolCall, val content: AgentViewContent) : ToolCallView
      data object NoView : ToolCallView
      data object PageGone : ToolCallView
      data class CallFailed(val message: UiText) : ToolCallView
      data class PageFailed(val message: UiText) : ToolCallView
  }

  sealed interface ToolCallRefresh {
      data class Updated(val call: SessionToolCall) : ToolCallRefresh
      data object Gone : ToolCallRefresh
      data object Failed : ToolCallRefresh
  }

  interface ToolCallViewSource {
      suspend fun load(agentId: String, sessionId: String, descriptor: ToolCallDescriptor): ToolCallView
      suspend fun load(agentId: String, sessionId: String, toolCallId: String): ToolCallView
      suspend fun refreshCall(sessionId: String, toolCallId: String): ToolCallRefresh
  }

  @Singleton
  class ToolCallViewLoader @Inject constructor(private val views: ViewsRepository) : ToolCallViewSource
  ```

- [ ] **Step 1: Write the failing tests**

Use MockWebServer + a real `ViewsRepository` and a dispatcher that answers the call and content
paths independently.

```kotlin
@Test fun `descriptor load requests the call and the page`() = runTest {
    // call 200 with viewUri, content 200
    val result = loader.load("a1", "s1", descriptor("c2", "k1", "ui://v"))
    assertTrue(result is ToolCallView.Ready)
    val paths = requests().map { it.url.encodedPath }
    assertEquals(2, paths.size)
    assertTrue(paths.contains("/control/manage/sessions/s1/tool-calls/c2"))
    assertTrue(paths.contains("/control/manage/agents/a1/views/content"))
}

@Test fun `call 404 is NoView`() = runTest { /* call 404 */ assertEquals(ToolCallView.NoView, ...) }
@Test fun `page 404 is PageGone`() = runTest { /* call 200, content 404 */ assertEquals(ToolCallView.PageGone, ...) }
@Test fun `page 502 is PageFailed with the server text`() = runTest { /* content 502 */ }
@Test fun `call 502 is CallFailed with the server text`() = runTest { /* call 502 */ }
@Test fun `a running call keeps a null result`() = runTest { /* call 200 without result */ assertEquals(null, (result as Ready).call.result) }
@Test fun `sequential load fetches the call before the page`() = runTest { /* toolCallId overload */ }
@Test fun `refresh 404 is Gone and refresh 200 is Updated`() = runTest { }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.views.ToolCallViewLoaderTest'`
Expected: FAIL — loader unresolved.

- [ ] **Step 3: Implement the loader**

Parallel overload: `coroutineScope { async { capture { views.toolCall(...) } } ... }` where `capture`
rethrows `CancellationException`; if the call is `NotFound`/`Forbidden` → `NoView`; a call without
`viewUri`/`connectionId` → `NoView`; a page `NotFound` → `PageGone`; a page `Forbidden` → `NoView`;
other errors → `CallFailed`/`PageFailed` with `toApiException().text`. Sequential overload fetches
the call first and reuses the same page classification. `refreshCall` maps `NotFound`/`Forbidden` to
`Gone`, success to `Updated`, anything else to `Failed`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.data.views.ToolCallViewLoaderTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/data/views app/src/test/java/ru/agimate/mobile/data/views
git commit -m "feat: tool call view — one loader for the inline card and the full screen"
```

---

### Task 7: Pure inline state, row order and height clamp

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/feature/chat/InlineViews.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatItems.kt`
- Test: `app/src/test/java/ru/agimate/mobile/feature/chat/InlineViewsTest.kt`
- Test: `app/src/test/java/ru/agimate/mobile/feature/chat/ChatItemsTest.kt`

**Interfaces:**
- Consumes: `ToolCallDescriptor`, `AgentViewContent`, `SessionToolCall`, `ChatItem.ProgressGroup`.
- Produces:
  ```kotlin
  data class InlineCardRef(val toolCallId: String, val descriptor: ToolCallDescriptor)

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
      val revision: Int = 0,
  )

  data class InlineViewsState(
      val overrides: Map<String, ToolCallDescriptor> = emptyMap(),
      val withdrawn: Set<String> = emptySet(),
      val cards: Map<String, InlineCardState> = emptyMap(),
  ) {
      fun cardsFor(group: ChatItem.ProgressGroup): List<InlineCardRef>
  }

  sealed interface ChatRow {
      val key: String
      data class Message(val item: ChatItem) : ChatRow
      data class Card(val ref: InlineCardRef) : ChatRow
  }

  fun buildChatRows(items: List<ChatItem>, inline: InlineViewsState, agentId: String?): List<ChatRow>
  fun clampInlineViewHeight(requested: Double, screenHeightDp: Int): Int
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `cards follow line order and id order without duplicates`() { /* two lines, three ids, one duplicate */ }
@Test fun `a signal descriptor wins over the history one`() { /* override in overrides */ }
@Test fun `no view, no connection or withdrawn means no card`() { /* viewUri null, connectionId null, withdrawn */ }

@Test fun `cards are emitted before the group in reverse order`() {
    val rows = buildChatRows(listOf(group(c1, c2)), InlineViewsState(), agentId = "a1")
    assertEquals(listOf("v:c2", "v:c1", "p:g"), rows.map { it.key })
}

@Test fun `an unknown agent renders no cards`() {
    assertTrue(buildChatRows(listOf(group(c1)), InlineViewsState(), agentId = null).none { it is ChatRow.Card })
}

@Test fun `height clamps to 320 and to 70 percent of the screen`() {
    assertEquals(320, clampInlineViewHeight(100.0, screenHeightDp = 800))
    assertEquals(560, clampInlineViewHeight(2000.0, screenHeightDp = 800))
    assertEquals(720, clampInlineViewHeight(2000.0, screenHeightDp = 2000))
    assertEquals(320, clampInlineViewHeight(2000.0, screenHeightDp = 300))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.InlineViewsTest' --tests 'ru.agimate.mobile.feature.chat.ChatItemsTest'`
Expected: FAIL.

- [ ] **Step 3: Implement the pure state**

`cardsFor` iterates `group.lines` (already chronological) and each line's `toolCallIds`, looks up
`overrides[id]` first and then `line.toolCalls` by id, skips null view/connection and `withdrawn`, and
dedupes by id. `buildChatRows` for a group emits `cardsFor(group).asReversed()` as `ChatRow.Card`,
then the group as `ChatRow.Message`; other items pass through. `clampInlineViewHeight` returns
`requested.coerceIn(320.0, max(320, min(720, (screenHeightDp * 0.7).toInt())))`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.InlineViewsTest' --tests 'ru.agimate.mobile.feature.chat.ChatItemsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/chat app/src/test/java/ru/agimate/mobile/feature/chat
git commit -m "feat: inline views — descriptor precedence, row order and height clamp"
```

---

### Task 8: `InlineViewsController` — the one state machine

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/feature/chat/InlineViewsController.kt`
- Test: `app/src/test/java/ru/agimate/mobile/feature/chat/InlineViewsControllerTest.kt`

**Interfaces:**
- Consumes: `ToolCallViewSource` from Task 6; `InlineViewsState` from Task 7;
  `SessionToolCallPayloadDto` from Task 4.
- Produces:
  ```kotlin
  class InlineViewsController(
      private val sessionId: String,
      private val source: ToolCallViewSource,
      private val scope: CoroutineScope,
  ) {
      val state: StateFlow<InlineViewsState>
      fun setAgentId(agentId: String?)
      fun onSignal(payload: SessionToolCallPayloadDto)
      fun onSessionMessage()
      fun expand(descriptor: ToolCallDescriptor)
      fun collapse(toolCallId: String)
      fun retry(descriptor: ToolCallDescriptor)
      fun onRendererGone(toolCallId: String)
      fun clear()
  }
  ```

- [ ] **Step 1: Write the failing tests**

Use a fake `ToolCallViewSource` with `CompletableDeferred`s and a call log.

```kotlin
@Test fun `nothing is loaded before expansion`() = runTest { /* onSignal + onSessionMessage → no calls */ }
@Test fun `expand loads and reaches Ready`() = runTest { /* complete with Ready */ }
@Test fun `a null result stays AwaitingResult and the next message refreshes it`() = runTest { }
@Test fun `collapse cancels a late response and frees the content`() = runTest { }
@Test fun `page 404 withdraws the card and a later signal revives it`() = runTest { }
@Test fun `call 404 leaves the trigger in Unavailable`() = runTest { }
@Test fun `page 502 is Failed and retry loads again`() = runTest { }
@Test fun `signal viewUri null discards the card`() = runTest { }
@Test fun `duplicate signals do not start a second load`() = runTest { }
@Test fun `expand without agent id does nothing`() = runTest { }
@Test fun `renderer gone turns a ready card into Failed`() = runTest { }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.InlineViewsControllerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement the controller**

One `Job` and one generation counter per `toolCallId`. `expand` sets `Loading`, increments the
generation and launches `source.load(agentId, sessionId, descriptor)`; the result is applied only if
the card is still expanded and the generation matches. `Ready` becomes `AwaitingResult` when
`call.result == null`. `NoView` → `Unavailable`; `PageGone` → `withdraw` (remove card, add to
`withdrawn`); `CallFailed`/`PageFailed` → `Failed`. `collapse` cancels, clears the card and bumps the
generation; `withdraw` additionally adds to `withdrawn` and removes the override. `onSignal` updates
`overrides`, discards on null `viewUri`, clears `withdrawn`, and refreshes an expanded card (full
reload when the address changed, otherwise `refreshCall`). `onSessionMessage` calls `refreshCall`
only for expanded `AwaitingResult` cards without an in-flight request. `refreshCall` `Gone` →
`Unavailable`, `Updated` → update the call and move to `Ready` when a result appeared, `Failed` →
leave the phase untouched. `clear` cancels every job and resets to `InlineViewsState()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.InlineViewsControllerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/chat app/src/test/java/ru/agimate/mobile/feature/chat
git commit -m "feat: inline views — card state machine with generations and signal precedence"
```

---

### Task 9: ChatViewModel integration

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatScreen.kt` (signature only in this task)

**Interfaces:**
- Consumes: `InlineViewsController`, `ToolCallViewSource`, `ViewsRepository`.
- Produces:
  ```kotlin
  data class ChatUiState(/* … */ val inlineViews: InlineViewsState = InlineViewsState())

  class ChatViewModel(
      /* … existing … */
      private val views: ViewsRepository,
      private val viewSource: ToolCallViewSource,
  ) {
      fun expandView(descriptor: ToolCallDescriptor)
      fun collapseView(toolCallId: String)
      fun retryView(descriptor: ToolCallDescriptor)
      fun onViewRendererGone(toolCallId: String)
      suspend fun callViewTool(connectionId: String, name: String, arguments: JsonObject): JsonObject
  }
  ```

- [ ] **Step 1: Add the integration test surface**

There is no ViewModel harness; the controller tests cover the state machine. Add a small
`ChatUiStateTest` asserting `inlineViews` defaults to an empty `InlineViewsState` and that
`copy(inlineViews = …)` is reflected (guards the state wiring).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.ChatUiStateTest'`
Expected: FAIL — field unresolved.

- [ ] **Step 3: Wire the controller**

Create `inlineViews` in `init` with `viewModelScope`; collect its state into `_state`; call
`setAgentId` in `learnAgent`; handle `RealtimeEvent.ToolCall` in `observeLiveEvents` only for this
session; call `onSessionMessage()` for every live `webchat.message` of this session; call `clear()`
in `onCleared`. `callViewTool` uses `knownAgentId` and the descriptor's connection. Add the new
constructor parameters.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.chat.ChatUiStateTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/chat app/src/test/java/ru/agimate/mobile/feature/chat
git commit -m "feat: chat viewmodel — inline views state wired into the conversation"
```

---

### Task 10: ChatScreen — the card and the feed rows

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/feature/chat/ChatScreen.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/navigation/MainGraph.kt`
- Create: `app/src/main/java/ru/agimate/mobile/feature/views/ViewLinkOpener.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/views/ViewsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-ru/strings.xml`

**Interfaces:**
- Consumes: `buildChatRows`, `InlineViewsState`, `ViewCallData`, `ViewCsp.policy`.
- Produces:
  ```kotlin
  fun ChatScreen(
      /* … existing … */
      onExpandView: (ToolCallDescriptor) -> Unit,
      onCollapseView: (String) -> Unit,
      onRetryView: (ToolCallDescriptor) -> Unit,
      onOpenFullScreenView: (String) -> Unit,
  )

  @Composable
  fun rememberViewLinkOpener(): (Uri) -> String?
  ```

- [ ] **Step 1: Add the strings**

`chat_view_show`, `chat_view_hide`, `chat_view_full_screen`, `chat_view_unavailable`,
`chat_view_retry`, `chat_view_failed` — in English in `values` and Russian in `values-ru`.

- [ ] **Step 2: Implement the rows and the card**

Compute `rows` with `remember(state.items, state.inlineViews, state.agentId)`. Render
`ChatRow.Message` with the existing bubble/group/separator branches and `ChatRow.Card` with
`InlineViewCard`. The card header has the name, show/hide label, chevron and a full-screen button;
the body switches on the phase: loading/awaiting → placeholder `heightIn(min = 320.dp)`, ready →
`AgentViewHost` with `policy`, `toolCall = ViewCallData(call.input, call.result)`, `revision` as the
key and `onResize` clamped by `clampInlineViewHeight`, failed → text + retry, unavailable → a compact
line. Collapsed renders only the header. Pass `onCallTool` from the ViewModel, the shared link
opener, and `onViewRendererGone`.

- [ ] **Step 3: Wire `MainGraph`**

Pass the four callbacks to `ChatScreen`; `onOpenFullScreenView` stays a no-op until Task 11.

- [ ] **Step 4: Verify the build**

Run: `./gradlew assembleDevDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature app/src/main/java/ru/agimate/mobile/navigation app/src/main/res
git commit -m "feat: chat screen — inline view cards under the progress group"
```

---

### Task 11: Full-screen route

**Files:**
- Create: `app/src/main/java/ru/agimate/mobile/feature/views/AgentViewScreen.kt`
- Create: `app/src/main/java/ru/agimate/mobile/feature/views/AgentViewViewModel.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/navigation/MainGraph.kt`
- Test: `app/src/test/java/ru/agimate/mobile/feature/views/AgentViewViewModelTest.kt`

**Interfaces:**
- Consumes: `ToolCallViewSource`, `ViewsRepository`, `AgentViewHost`, `ViewCsp.policy`.
- Produces:
  ```kotlin
  const val AGENT_VIEW = "agent-view/{agentId}/{sessionId}/{toolCallId}"
  fun agentView(agentId: String, sessionId: String, toolCallId: String): String

  data class AgentViewUiState(
      val loading: Boolean = true,
      val content: AgentViewContent? = null,
      val call: SessionToolCall? = null,
      val error: UiText? = null,
      val close: Boolean = false,
      val revision: Int = 0,
  )
  ```

- [ ] **Step 1: Write the failing ViewModel tests**

```kotlin
@Test fun `a ready view fills content and call`() = runTest { /* fake source Ready */ }
@Test fun `no view and a gone page close the screen`() = runTest { /* NoView, PageGone */ }
@Test fun `a page failure shows the server text and retry reloads`() = runTest { }
@Test fun `callTool uses the agent and connection from native state`() = runTest { /* MockWebServer */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.views.AgentViewViewModelTest'`
Expected: FAIL.

- [ ] **Step 3: Implement the ViewModel and screen**

The ViewModel reads the three `SavedStateHandle` arguments, calls
`source.load(agentId, sessionId, toolCallId)` (sequential), maps `NoView`/`PageGone` to
`close = true`, and other failures to `error`. `retry` reloads with `revision + 1`. The screen
renders loading, `ErrorState` with retry, or `AgentViewHost` with `policy`, `toolCall`, and
`onResize = {}`; the back button and system back pop. Add the route to `MainGraph` and navigate from
`ChatScreen`'s `onOpenFullScreenView` with `Routes.agentView(agentId, sessionId, toolCallId)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.views.AgentViewViewModelTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/views app/src/main/java/ru/agimate/mobile/navigation app/src/test/java/ru/agimate/mobile/feature/views
git commit -m "feat: full-screen view — same host, same call data, back to the chat"
```

---

### Task 12: Launcher CSP, docs, final verification

**Files:**
- Modify: `app/src/main/java/ru/agimate/mobile/feature/views/ViewsViewModel.kt`
- Modify: `app/src/main/java/ru/agimate/mobile/feature/views/ViewsScreen.kt`
- Modify: `app/src/test/java/ru/agimate/mobile/feature/views/ViewsViewModelTest.kt`
- Modify: `docs/superpowers/specs/2026-11-09-android-agent-views-design.md:388-395`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add the launcher csp test**

In `ViewsViewModelTest`, answer `/views/content` with a `csp` object and assert
`opened.csp == ViewExternalDomains(listOf("https://esm.sh"), emptyList())`; answer without `csp` and
assert `null`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDevDebugUnitTest --tests 'ru.agimate.mobile.feature.views.ViewsViewModelTest'`
Expected: FAIL — `csp` unresolved.

- [ ] **Step 3: Carry `csp` through the launcher**

Add `csp: ViewExternalDomains? = null` to `OpenedView`; set it from `AgentViewContent.externalDomains`
in `fetch`; pass `policy = ViewCsp.policy(opened.csp)` to `AgentViewHost` in `ViewsScreen`.

- [ ] **Step 4: Update the docs and CHANGELOG**

In the phase-1 design §11, replace the «inline и внешние CSP-домены вне scope» wording with a
reference to the phase-2 spec. Add one terse CHANGELOG line per the project convention.

- [ ] **Step 5: Run the full verification**

Run: `./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug`
Expected: BUILD SUCCESSFUL.
If a device is attached: `./gradlew connectedDevDebugAndroidTest` — expected PASS (or report the
device absence honestly).
Then walk the spec's §8 checklist on the dev stand; record the nested-scroll limitation from design
§5.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ru/agimate/mobile/feature/views app/src/test/java/ru/agimate/mobile/feature/views docs CHANGELOG.md
git commit -m "feat: agent views launcher — external domains from the page csp"
```
