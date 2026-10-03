# Verified versions

Checked against Google Maven, Maven Central and services.gradle.org on 2026-10-02 (Section 20.6).
All versions live in `gradle/libs.versions.toml`.

| Component | Version | Notes |
|---|---|---|
| Gradle wrapper | 8.14.3 | AGP 8.13 needs Gradle 8.13+ |
| Android Gradle Plugin | 8.13.2 | Newest 8.x. AGP 9.x exists but changes the Kotlin plugin model; 8.13 keeps Hilt/KSP setup standard |
| compileSdk / targetSdk | 36 | Installed platform on the build machine; current stable |
| minSdk | 26 | Section 1 |
| JDK target | 17 | Builds fine with JDK 17 (CI) or 21 (local) |
| Kotlin | 2.2.21 | |
| KSP | 2.2.21-2.0.5 | |
| Hilt (Dagger) | 2.57.2 | 2.59+ targets AGP 9 |
| androidx.hilt (navigation-compose, work) | 1.2.0 | |
| Compose BOM | 2025.10.01 | Material 3 |
| core-ktx | 1.17.0 | |
| activity-compose | 1.11.0 | |
| lifecycle | 2.9.4 | |
| navigation-compose | 2.9.5 | |
| Room | 2.8.3 | schema exported to `app/schemas` |
| WorkManager | 2.10.5 | |
| DataStore Preferences | 1.1.7 | |
| kotlinx-coroutines | 1.10.2 | |
| kotlinx-serialization-json | 1.9.0 | |
| OkHttp | 4.12.0 | logging interceptor is debug-only |
| Retrofit + kotlinx converter | 2.11.0 | |
| Coil (compose) | 2.7.0 | proof photo thumbnails |
| LeakCanary | 2.14 | debug only |
| JUnit / Turbine / MockK | 4.13.2 / 1.2.1 / 1.14.5 | |

## Secret storage

`EncryptedSharedPreferences` is deprecated, so `SecretStore` uses an AES-256-GCM key generated inside the
Android Keystore (non-exportable) and stores only the ciphertext in a private DataStore file.

## Groq (checked 2026-10-02)

- Endpoint: `https://api.groq.com/openai/v1/chat/completions` (OpenAI compatible), base URL in `BuildConfig`.
- Production chat models listed: `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `llama-3.3-70b-versatile`, `llama-3.1-8b-instant`.
- JSON Object Mode (`response_format: {"type":"json_object"}`) is available on all models; the prompt must mention JSON (all prompts do).
- Default model: `openai/gpt-oss-120b` (sent with `reasoning_effort: "low"`; other models get no reasoning field). Editable in Settings, which can also fetch the live model list.
- The public docs did not show free-tier chat limits; the daily cap defaults to 40 calls (Section 2). Check your org's real limits in the Groq console and adjust in Settings.
