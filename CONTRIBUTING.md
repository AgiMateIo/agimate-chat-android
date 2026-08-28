# Contributing to AgiMate for Android

Thanks for taking the time. Issues and pull requests are welcome.

## Setup

Android Studio, or the Gradle wrapper and a JDK 17 or newer (21 is what it is developed
on). The build needs the Android SDK with platform 37 and targets `minSdk 26`.

```bash
./gradlew assembleDevDebug     # points at a local backend
./gradlew assembleProdDebug    # points at https://api.agimate.io
./gradlew testDevDebugUnitTest
./gradlew lintDevDebug         # this is what checks the translations are complete
```

The app is a client: it needs a running AgiMate backend
([agimate-backend](https://github.com/AgiMateIo/agimate-backend)) to talk to. The `dev`
flavour takes the server address from a field on the sign-in screen, so a local stack
works — with one exception spelled out in the README: signing in through a provider needs
a registered redirect URI and will not work locally.

### Building without a Firebase project

The build refuses to start without `app/google-services.json`, on purpose — an app
silently built without half its notification delivery is worse than a build that stops and
says what is missing. To compile and test without a Firebase project of your own, copy the
stub CI uses:

```bash
cp .github/google-services.stub.json app/google-services.json
```

An app built on the stub has no FCM channel. It still runs, and the live feed still
arrives over the WebSocket — push is the second channel, not the only one. For real
notifications, put your own file in place; the README says where to get it.

## The backend contract is the contract

[`docs/android-app-spec.md`](docs/android-app-spec.md) is authoritative — the README only
points at it. A change to what the app sends or accepts belongs in that document in the
same pull request, and usually in the backend as well.

Before changing anything in `core/network` or `core/auth`, read the list under **What to
know before you change things** in the README. Every item there is a backend behaviour
that looks like over-caution in the code, and every one of them is covered by a test.

## The language of the repository

Code comments and everything under `docs/` are in **Russian**. The README, this file, and
commit subjects and bodies are in **English**. The split is deliberate: the reasoning
lives next to the code, written in the language it was thought in, while everything a
person meets on the way in — the front page, the history, the contract with contributors —
stays readable to anyone who lands here from outside.

User-facing text never goes in the code. It lives in `res/values/` (English, also the
fallback for any locale without its own folder) and `res/values-ru/`; models and view
models carry a `UiText` instead of a string. `MissingTranslation` in `lintDevDebug` is
what keeps the two in step.

## Commits

```
<type>: <object> — <delta>
```

The type is one of `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`. Feature or
refactor is decided by whether the change is **visible from outside** — to a person, to
the API, to an agent — not by how much code moved. The object is a noun and comes first:
the thing someone will grep the history for, not a verb. The delta is the result, not the
action (`per-purpose priority lists instead of a provider default model`, not `fix
provider models`); a replacement reads as "A instead of B".

Up to 72 characters, no trailing period, lower case after the colon, no scope in brackets
— name the area in words instead (`agent wizard`, `chat attachments`). A `+` in the
subject means the commit should be split. A body is optional: two to four bullets on *why
this way*, never a list of files.

The first thirty or so commits predate this convention and are not an example to follow.

## Contributor License Agreement

Contributors sign the
[CLA](https://github.com/AgiMateIo/agimate-backend/blob/master/CLA.md) once, on their
first pull request, by replying to a bot comment. One signature covers every AgiMate
repository.

## Security

Found a security problem? Do not open an issue — follow the
[security policy](https://github.com/AgiMateIo/.github/blob/main/SECURITY.md).
