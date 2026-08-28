# AgiMate for Android

A messenger for your own AI agents: the agent list reads as a contact list, and you talk to
them. Everything complicated — integrations, skills, access policies, model choice, billing —
lives in the admin interface and does not come here.

The backend contract is described in [docs/android-app-spec.md](docs/android-app-spec.md), the
history of changes in [CHANGELOG.md](CHANGELOG.md). Comments in the code and everything under
`docs/` are in Russian; see [CONTRIBUTING.md](CONTRIBUTING.md) for why.

## Building

```
./gradlew assembleDevDebug     # points at a local backend
./gradlew assembleProdDebug    # points at https://api.agimate.io
./gradlew test                 # unit tests
```

You need JDK 17 or newer (21 is what it is developed on) and the Android SDK with platform 37.
Gradle brings the rest.

### What the repository does not contain: `app/google-services.json`

Notifications travel over two channels, and the second one is FCM. Its project is named not by a
build field but by that file, so without it the build fails:

```
> File google-services.json is missing.
  The Google Services Plugin cannot function without it.
```

That is deliberate: an app silently built without half of its delivery is worse than a build that
stopped and said what it was missing.

There is no secret in the file — it goes into the APK whole — but it is not in git either: it ties
the build to one particular Firebase project, and a fork should have its own. Take it from the
Firebase console: project → "Project settings" → "Your apps" → the Android app with package
`ru.agimate.mobile` → "Download google-services.json", and put it in `app/`. One file covers both
flavours — they share an `applicationId`.

To compile and test without a Firebase project at all, copy the stub CI uses —
`cp .github/google-services.stub.json app/google-services.json`. An app built on it has no FCM
channel; the live feed over the WebSocket is unaffected.

### The RuStore push project

The other channel is RuStore, and its project id comes from `local.properties`:

```
rustore.projectId=...
```

There is no secret in it either — like the Firebase file, it ends up inside the APK — but it names
*your* console project, so the same rule applies: a fork brings its own. Empty is a working state,
not a breakage: push simply does not come up, and the live feed stays.

A flavour can take its own with `rustore.projectId.prod`, and eventually it will have to: a push
project holds one signing fingerprint, and while that fingerprint is the debug one, a release build
gets no token from it.

### Version

The version is set by hand in one place — `appVersionName` in `app/build.gradle.kts` — and it is
semver. `versionCode` is derived from it, three digits per field: `0.3.1` → `3001`, `1.0.0` →
`1000000`. Two numbers side by side drift apart sooner or later — you raise one and forget the
other, and the store tells you about it after the upload.

A store only requires `versionCode` to grow, which is why moving to the formula could lift it from
`3` straight into the thousands. The scheme closes exactly one road: builds split per ABI need free
trailing digits for the variant offset. We do not split, and if we ever do, the fields shift left.

### Release signing

A signing key is the app's identity forever: the store remembers the certificate of the first
upload, and every update has to be signed with the same one. So it lives outside the repository,
and the build takes the path and the passwords from `local.properties`:

```
release.storeFile=/path/to/agimate-mobile-release.jks
release.storePassword=...
release.keyAlias=agimate-mobile
release.keyPassword=...
```

Without those lines `assembleProdRelease` produces an **unsigned** APK instead of failing: a fork
cannot have our key, and there is no reason to break its build over that — signing can happen
later, with `apksigner`.

The release key's fingerprint is needed in two more places, and without it everything looks like it
works while staying silent: in the RuStore console for the push project (otherwise a release build
gets no token) and in `/.well-known/assetlinks.json`, once App Links come around.

## Languages

The app speaks Russian and English. The base resources — `app/src/main/res/values/` — are
**English**: they are also the fallback for any locale without a folder of its own. Russian lives in
`values-ru/`. The backend is built the same way: `messages.properties` is English,
`messages_ru.properties` overrides it.

The language of the base resources is declared in `app/src/main/res/resources.properties`; AGP
builds `localeConfig` out of it, and that is what gives a single app its own language setting on
Android 13+. The `localeFilters` list in `app/build.gradle.kts` cuts the AndroidX translations into
languages we do not have: otherwise a German phone would show German system buttons and everything
of ours in English.

The language can be changed inside the app without touching the phone's. From Android 13 the choice
is kept by the system itself (`LocaleManager`), so it matches the "App language" item in the phone
settings and survives a reinstall; before Android 13 none of that exists, the choice is kept in
SharedPreferences, and the configuration is swapped in `attachBaseContext` of both the application
and the activity — see
[`AppLanguages`](app/src/main/java/ru/agimate/mobile/core/ui/locale/AppLanguage.kt). That storage is
device-protected: ordinary `SharedPreferences` do not open before the first unlock after a reboot,
and the choice is read on every process start, including one raised by a push on a locked phone.

To add a language: create `values-<code>/strings.xml`, add the code to `localeFilters` and to
`AppLanguage`. Completeness of the translations is checked by `./gradlew lintDevDebug`
(`MissingTranslation`).

There are no strings in the code: text meant for a person lives in resources, and models and view
models carry a [`UiText`](app/src/main/java/ru/agimate/mobile/core/ui/text/UiText.kt) — an intent to
show a string, resolved on the screen, where the locale is already known. The Russian strings left
in the code are log labels and exception messages, which nobody sees.

**Error text from the server is not translated.** `ApiException.of` shows it as it came: the server
knows things about a refusal that the client cannot — attachment limits with numbers, the reason a
file was rejected. The price is that in an English interface such text appears in the backend's
language. Our own wording is only for the answers where the server said nothing.

So that the backend has a chance to answer in the right language, every API request carries
`Accept-Language`: the chosen language first, the phone's preferences after it by descending weight
— see
[`AcceptLanguageInterceptor`](app/src/main/java/ru/agimate/mobile/core/network/AcceptLanguageInterceptor.kt).
The backend does not read it yet — it takes agent notices from `messages*.properties` by the
`agent.response.language` deployment setting — but without the header the language choice stays half
a decision. The file client does not send it: behind a presigned link there is file content, not
text for a person.

## Identity

Colours, radii and durations do not live in the app. The source is the identity repository, from
which `design/dist/AgimateTokens.kt` is copied here into
[`design/`](app/src/main/java/ru/agimate/mobile/design/AgimateTokens.kt). The file is generated and
never edited by hand: the next generation would overwrite it. A copy rather than a dependency is a
deliberate trade: copying is visible in the diff and cannot break the mobile build at an
unfortunate moment, but it means changing a token is an edit in three repositories. The third one,
the one that gets forgotten, is caught by `tools/check-tokens.sh` (path to the identity repository
in `AGIMATE_IDENTICA`, `../identica` by default).

Exactly one line is changed on the way in: `package`. The identity repository is a shared source
for several platforms and lives in a namespace of its own, and the app has no reason to carry a
second package root for one file. The substitution is mechanical — the check does it too, and
compares everything else — so "never edited by hand" still holds. The check cannot run in CI: the
identity repository is not there, and it would exit green having verified nothing. Run it locally
before touching the theme.

Product code reaches the tokens through the roles in `AgiColors`, `AgiShapes` and `AgiType`, never
directly. Where a role has to hand out a raw number rather than a `Shape` — the message bubble
needs a radius, one of its corners is cut — the role hands out the number.

The app's ground is assembled in
[`Modifier.backdrop()`](app/src/main/java/ru/agimate/mobile/core/ui/theme/Backdrop.kt): the gradient
underlay and two spots of light from the `accent-glow` and `aurora-tint` tokens. One gradient is not
enough — between its ends there are eleven brightness steps over the full height of the screen, and
the eye does not catch a fall like that without an edge to compare against; on the web it does not
work alone either, the landing page lays glowing layers over it. The centres of the spots are pushed
off the canvas, so there is neither a bright point nor an edge under the text.

The showcase — intro, sign-in, waiting for approval — has a ground of its own,
`Modifier.auroraBackdrop()`: three ellipses with periods of 13, 17 and 21 seconds, rotating rather
than shifting, the periods without a noticeable common multiple. Under the message feed there is
none of that, on purpose: a crawling spot would keep changing the contrast of the text. With
animations turned off in the system, the endless animation never starts.

Product code is entitled only to the roles in
[`AgiColors`](app/src/main/java/ru/agimate/mobile/core/ui/theme/Color.kt), not to particular paints.
Four of the roles are not in the tokens: bubbles, muted fills and third-plane text — because a brand
book does not cover a messenger, and they are mixed out of the existing roles; `accentText` —
because the turquoise on the dark theme has a contrast of 4.32 against the background, below the
threshold for small type.

The mark is drawn in code —
[`BrandMark`](app/src/main/java/ru/agimate/mobile/core/ui/components/BrandMark.kt) — with the same
outlines as the reference file in the identity repository. The component takes one paint and derives
the facet from it with alpha: on an accent plate the caller passes white, and there is nothing
lighter than white. The facet is a gradient of its own for each of the four shapes, with the light
always from the top left; turning it is not allowed, the single angle of light is the one thing that
binds the shapes into one object. Below 40 dp the gradient does not read, and the mark is drawn in
one flat paint — that is how the status-bar icon and the monochrome launcher layer are made. Editing
the mark happens in the identity repository, not here.

The app icon is a gradient slab with a warm highlight in the far corner and the white mark, faceted,
on top ([`ic_launcher_background`](app/src/main/res/drawable/ic_launcher_background.xml),
[`ic_launcher_foreground`](app/src/main/res/drawable/ic_launcher_foreground.xml)). It is the only
place where a warm paint reaches into the identity, and it stays under the mark. The paints here are
duplicated as hex: the resource draws the launcher in its own process and cannot reach the tokens.
The dark theme values were taken — it is the primary one in the brand book, and switching the icon
along with the phone's theme is dishonest: people look for it by how it looks.

The type scale is our own: the tokens do not have one, the product lives on the Tailwind scale, and
nobody has yet decided how to reconcile them. The typeface is shared — IBM Plex Sans and Mono,
variable files, licence in [`licenses/`](licenses/IBM-Plex-OFL-1.1.txt).

The theme can be changed in the app settings without touching the phone's. It is applied by
substituting `uiMode` in `attachBaseContext` — not only the palette depends on it but also the
choice of `values-night` resources, which is where the window background comes from. The splash
background stays the system one: it is drawn before the process starts, and with a choice opposite
to the system's a cold start would flash.

## Flavours

| | `dev` | `prod` |
|---|---|---|
| Address | `http://10.0.2.2:8000` | `https://api.agimate.io` |
| Address editable from the UI | yes, a field on the sign-in screen | no |
| Cleartext HTTP | allowed | forbidden |

Both share an `applicationId`, on purpose: two installed copies would claim the same
`agimate://auth` scheme, and coming back from the browser would run into an app chooser.

`api.*` and not `www.*`: `www.agimate.io` serves the site, which sends the request on to a localised
path (a 307 to `/ru/...`) where there is no API — the app used to get a 404 on the profile and show
"not found". The backend's OAuth is set up for that host too: the `redirect_uri` in the answer from
`/user/oauth2/authorization/*` points at `api.agimate.io`.

From the emulator a local stack is reachable at `10.0.2.2`; from a real phone you need to put the
machine's LAN address into the "Server" field on the sign-in screen.

**Signing in through a provider will not work against a local stack.** The OAuth provider redirects
to `{baseUrl}/login/oauth2/code/{provider}`, and no such address is registered with Google or
Yandex. That path is checked on a prod build.

Signing in **with a password** does work against a local stack: there is no browser in it, and
nothing to register with a provider. Registration and "forgot password" run into the stack's mail —
without it both requests answer 503, and the app hides both doors until a restart.

## Where things live

```
core/network/    Retrofit, the {response}/{error} envelope, a tolerant date parser,
                 origin substitution, Authorization, token refresh on 401
core/auth/       three ways in — a provider with PKCE, a password, a letter; linking
                 a provider to an open account; the token store (Keystore),
                 single-flight refresh, session state and the "awaiting approval" gate
core/realtime/   Centrifugo: the personal channel and the conversation channel
core/ui/         theme, colour and typography roles, shared components
core/onboarding/ the mark that the tour of the app has already been read
core/share/      files out and in: clipboard, "share", saving to shared storage,
                 a camera shot as an attachment
design/         identity tokens: a copy of a generated file, never edited by hand
data/            DTOs, models and repositories: contacts, conversations, messages,
                 files, presets, agents, profile, devices
feature/         screens: onboarding, login, authmethods, settings, pending,
                 contacts, sessions, chat, files, createagent, profile
navigation/      the root fork and the graph inside the product
```

## What to know before you change things

Several places look like over-caution, but each of them answers a specific behaviour of the backend.
All of them are covered by tests.

- **A trailing slash is significant.** `GET …/sessions/` and `POST …/sessions` are different routes;
  an extra slash gives a 404 that is easy to read as "no data". The paths are pinned by
  `WebchatApiPathsTest`.
- **Time arrives in four shapes** — with a zone, without one, separated by a space, without
  fractional seconds. `ServerTime` parses it; a zoneless value is taken as UTC.
- **Token refresh is single-threaded.** Five parallel 401s must produce one request to `/refresh`:
  rotation is a conditional write, and the losers get a 409. A network error means a retry with the
  **same** token within a minute, a 403 means signing in again. See `TokenRefresher` and its test.
- **Signing in with a password must announce itself as `NATIVE` in an explicit field in the body.**
  Our `Json` is built with `encodeDefaults = false` and does not serialise a default value, and the
  server reads that silence as "web": the refresh would go into a cookie the app does not have, and
  the body would arrive with a `null`. Hence `@EncodeDefault` on the field and a test that looks at
  the request body itself.
- **Password length is counted in UTF-8 bytes.** The hashing algorithm reads 72 bytes and says
  nothing about the rest: forty Cyrillic letters are already past the edge while looking like a
  short password by character count. See `PasswordRules` and its test.
- **Sign-in and provider linking come back from the browser through the same door.** A parameter
  tells them apart: `code` is a sign-in, `link_proof` is a linking. The linking proof is spent
  immediately on return: its five minutes of life are meant for the trip from the callback to the
  request, not for deliberation.
- **A 403 from control-api is not a sign-out.** For an account with the `GUEST` role, that is how
  any request to `/control` answers, and it means "awaiting approval". Only a 403 on
  `/user/oauth2/refresh` signs you out.
- **Deduplication is by `messageId`.** Delivery is at-least-once, plus your own message comes back
  as an echo — the optimistically shown one collapses into it (`mergeLiveMessage`).
- **A read receipt sends the row `id`, not the `messageId`.** Otherwise a 400.
- **The stop button cancels the session, not the run.** Cancelling a single run would let the next
  one in that conversation's queue start.
- **The contact list is not re-sorted on the client.** The sort key is the server's and cannot be
  reconstructed between pages.

## Coming back from the browser

For now, the `agimate://auth` scheme. An App Link (`https://www.agimate.io/app/auth`) is supported
by the code and turned on by a flag; what that needs from the infrastructure is in
[docs/app-links.md](docs/app-links.md).

## What the app does not have, and should not

Integrations and connections, skills, access policies, model choice and keys, agent commands, task
boards, tables, billing, worker settings. If a screen feels incomplete without them, the screen is
about something else.

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Security problems go
through the [security policy](https://github.com/AgiMateIo/.github/blob/main/SECURITY.md), not
through an issue.

## License

[Apache 2.0](LICENSE), copyright holder in [NOTICE](NOTICE).
