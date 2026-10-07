# Mentat

A single-user, offline-first Android app for learning many short skills. A Groq-hosted LLM drafts an editable
skill path **once**; from then on deterministic Kotlin code plans your day, tracks logs, schedules recall checks,
spots struggles and asks you what to do. The AI never grades you, never schedules and never decides levels.
All data stays on the phone; the only network traffic is to Groq, and only when you tap an AI button.

Built from `MENTAT_BUILD_SPEC.md` (v3.0). Gaps in the spec and how they were filled: `docs/DESIGN_DECISIONS.md`.
Library versions: `docs/VERSIONS.md`.

## Install the APK

1. Copy `app-debug.apk` to the phone (or download the `mentat-debug-apk` artifact from GitHub Actions and unzip it).
2. Open it and allow "Install unknown apps" for your file manager or browser.
3. Open Mentat: paste your Groq key (optional at first), fill the short profile form, allow reminders,
   and add a starter skill or create your own.
4. OnePlus / OxygenOS: Settings > Apps > Mentat > Battery > Don't optimize, allow background activity,
   and lock the app in recents so reminders arrive on time.

## Build

Requirements: JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest   # 52 JVM tests for the deterministic engine, validators and agent loop
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point `sdk.dir` at your Android SDK (Android Studio creates it).

### Release build (signed, R8-shrunk, about 2.5 MB)

The release key lives outside the project in `C:\Users\Neil baranwal\.mentat-keys\`
(`mentat-release.jks` + `release-signing.properties`). Back up both files: every future update must be
signed with this exact key, or Android refuses to install it over the existing app.

```bash
set -a; . ~/.mentat-keys/release-signing.properties; set +a
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

The release build has no StrictMode crash, no LeakCanary, no HTTP logger and no Diagnostics screen.
Debug and release builds use different keys, so they cannot update each other: export your data
(More > Export / Import) before switching, uninstall, install the other build, then import.

### Stable signing for CI (Section 15.2)

To make CI produce update-compatible builds, put the same release keystore in the secrets below
(`base64 -w0 mentat-release.jks` for `KEYSTORE_B64`, alias `mentat`, password from the properties file).
To create a fresh key instead:

```bash
keytool -genkeypair -v -keystore mentat.jks -alias mentat -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 mentat.jks   # paste into the GitHub secret KEYSTORE_B64
```

Add the secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them CI signs with a
fresh debug key (updates will then need an uninstall). Never commit the `.jks`.

## How it works

| Layer | What lives there |
|---|---|
| `ui/` | Compose screens: Today, Skills, Inbox, Journal, More (Goals, Schedule, Chat, Settings, Export/Import, Diagnostics) |
| `vm/` | One `@HiltViewModel` per screen, `StateFlow` state, `SavedStateHandle` for typed text |
| `domain/` | Pure Kotlin (no Android imports, enforced by a Gradle task): planner, weakness engine, levels, recall scheduler, energy analyzer, combo matcher, show-and-tell, journal writer, `.ics` parser, validators, safety filter, agent runner, prompts |
| `data/` | Room (19 tables, schema exported), DataStore settings, Keystore-backed `SecretStore`, Groq client with rate limiter and circuit breaker, repositories, backup |
| `system/` | Notifications, inexact alarms for the next 7 days, boot receiver, daily worker, network monitor, StrictMode |

Every AI output goes: JSON parse (unknown keys rejected) > schema + cross-field rules > safety filter > up to 2
repairs > manual form > saved as a proposal > **you accept, edit or reject** > commit.

## Spec phases

Phases 1-7 are implemented. Phase 8 (diet module, A7) and A1 (chat onboarding) were cut as the spec's scope
control allows; the A1/A7 prompts are kept in `Prompts.kt`. Phase exit tests that need real-world use (14-day
dogfooding, 7 days of alerts on the phone, live agent quality runs) are yours to run; the debug-only
Diagnostics screen shows the Groq call counter for the Rule 6 gate.
