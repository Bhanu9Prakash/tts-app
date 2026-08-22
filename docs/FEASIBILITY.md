# Feasibility investigation

This document answers the question the brief put first:

> Can an Android companion application securely invoke or reuse the
> speech-to-text/dictation capability already available inside the installed
> official ChatGPT Android application, using the user's existing authenticated
> ChatGPT login/subscription, without separately billed OpenAI API usage?

**Answer: No. There is no supported public interface for this, and the billing
premise is false independently of the technical question.**

Tier 1 is therefore unavailable, and the product is built on Tiers 2 and 3.

---

## 0. Read this first: what could and could not be verified

Being precise about the evidence matters more than the conclusion, so:

| Investigation | Could it be run here? | Why |
|---|---|---|
| Read OpenAI's official documentation directly | **No** | `platform.openai.com`, `help.openai.com`, `openai.com` and `community.openai.com` are all blocked by this environment's egress policy. Findings below rest on search-result summaries of those pages, not on pages fetched and read in full. |
| Inspect the ChatGPT APK's manifest | **No** | No Android device, no emulator, and `play.google.com` is blocked. The APK could not be obtained. |
| Query a live device for exported components | **No** | No device or ADB available. |
| Read Android platform documentation | **Yes** | `developer.android.com` is reachable. |
| Reason from Android's documented IPC model | **Yes** | This is what the conclusion actually rests on. |

The conclusion does not depend on the two experiments that could not be run.
Section 2 explains why: the Android platform question is decidable from the
platform's own documentation, and the answer does not change based on what is
inside the ChatGPT APK. Section 6 records the unrun experiments honestly
anyway, because the brief asked for an exact record of what was attempted.

---

## 1. The billing premise is false

Even if a technical channel existed, the cost premise behind Tier 1 does not
hold.

**A ChatGPT Plus or Pro subscription does not include OpenAI API access or API
credits.** They are separate products with separate billing systems. The
subscription pays for the ChatGPT applications; the API is billed per token
against a separate account balance.

This matters for the product in two ways:

1. Nothing in Voice Composer can charge transcription or refinement to a
   ChatGPT subscription. The BYOK path bills the user's own API key.
2. The app says so at the point where the user enters an API key, rather than
   letting them assume their existing subscription covers it.

*Confidence: high. Multiple independent secondary sources agree, and no source
found suggests otherwise. Not verified against `help.openai.com` directly,
because that host is blocked here.*

---

## 2. The Android platform question

The decisive question is not "what is inside the ChatGPT APK" but "what
mechanism does Android provide for App A to make App B perform speech
recognition and return the transcript". Android provides exactly one, and it
imposes a requirement on the *providing* app.

### 2.1 `RecognitionService` — the only documented mechanism

Android's documented way for one app to supply speech recognition to another is
`android.speech.RecognitionService`. For an app to be a recognition provider it
**must** declare, in its own manifest, a service with:

- an intent filter for the `android.speech.RecognitionService` action,
- the `android.intent.category.DEFAULT` category,
- a `meta-data` element pointing at an XML `<recognition-service>` resource.

An app that declares this becomes selectable as a system speech-recognition
provider, and other apps reach it through `SpeechRecognizer`.

This is the whole of the supported surface. There is no other API by which an
arbitrary app can invoke another app's dictation and receive text back.

So the feasibility question reduces to: **does the ChatGPT Android app declare a
`RecognitionService`?**

If it did, it would appear in Android's settings as a speech-recognition /
voice-input engine that users could select system-wide, and using it would need
no special integration from us at all — it would simply be one of the options
behind `SpeechRecognizer`. OpenAI documents no such capability, and ChatGPT is
not known to appear as a system recognition provider.

**Classification: 5 (Unknown, but bounded).** We could not inspect the APK from
this environment. But the outcome is bounded in a way that makes it safe to
build on: if ChatGPT *does* declare one, our existing `SpeechRecognizer`-based
provider already supports it with no code change, because that is what the API
is for. If it does not — which the evidence indicates — there is no supported
alternative. Either way, nothing further to build.

### 2.2 The other mechanisms the brief asked us to check

| Mechanism | Can it return a ChatGPT transcript to us? | Classification |
|---|---|---|
| `RecognitionService` | Only if ChatGPT declares one. See 2.1. | 5 — Unknown, bounded |
| Explicit `Intent` to an exported activity | An activity returns data only via `setResult`, which the *receiving* app must choose to call with a documented result contract. OpenAI publishes no such contract. | 2 if a component exists — relying on it is forbidden by the brief |
| Deep links / App Links | Navigate to content inside ChatGPT. One-directional: no return channel. | 4 — structurally impossible |
| Sharesheet / `ACTION_SEND` | Sends text *to* ChatGPT. `ACTION_SEND` has no result contract. | 4 — structurally impossible |
| `ACTION_PROCESS_TEXT` | The **receiver** implements the handler and decides whether to return text. For ChatGPT to refine our text this way, ChatGPT would have to register a handler and return a result; neither is documented. | 5 for ChatGPT — but see 2.3, this is very useful in the other direction |
| Bound service / AIDL | Requires a published, stable AIDL interface from the provider. None exists. | 4 — structurally impossible |
| `ContentProvider` | Requires ChatGPT to export a provider with a documented URI contract. None exists, and reading its private data would be credential theft. | 4 / forbidden |
| App Actions / shortcuts | Let the *assistant* invoke app capabilities. Not a general app-to-app API, and not a transcript return channel. | 4 — structurally impossible |
| Android Assistant integration | The assistant role is a system-level role; an app cannot delegate to ChatGPT's dictation through it. | 4 — structurally impossible |
| IME integration | An IME types into the focused field. ChatGPT's app is not an IME, and an IME could not hand us a transcript from ChatGPT's session anyway. | 4 — structurally impossible |
| Reading ChatGPT's auth token / cookies | Blocked by the Android app sandbox, and explicitly forbidden by the brief. | 4 and forbidden |

### 2.3 A genuinely useful finding in the opposite direction

`ACTION_PROCESS_TEXT` is a dead end for *consuming* ChatGPT, but it is valuable
for Voice Composer to *implement*.

Because the receiver implements the handler, Voice Composer can register an
`ACTION_PROCESS_TEXT` activity. The user selects text in any app, picks Voice
Composer from the text-selection menu, dictates or transforms, approves, and we
return the replacement via `setResult`. The host app applies the edit itself.

This is a **documented, officially supported way to put approved text back into
another app with no accessibility permission and no overlay** — the thing the
brief wanted from Flow Mode, available in Safe Mode for the selected-text case.

It is implemented: `app/src/main/kotlin/dev/voicecomposer/ui/ProcessTextActivity.kt`.

Its limits are real and stated in `LIMITATIONS.md`: the user must select text
first, the calling app must send a non-readonly request, and not every app
surfaces the menu item.

### 2.4 The Apps SDK points the other way

OpenAI's Apps SDK lets third-party apps run **inside ChatGPT** as interactive
components in a conversation. It is a way for developers to reach ChatGPT's
users, not a way for an Android app to reach ChatGPT's capabilities.

It does not provide an Android-side API, and it does not expose ChatGPT's
dictation to a companion app.

**Classification: 4 — does not address this use case.**

### 2.5 ChatGPT's own dictation is not local either

Worth recording, because it removes the remaining motivation: when the ChatGPT
app transcribes an audio message, the recorded audio is sent to OpenAI's models
for transcription and the text is returned.

So even if Tier 1 had been achievable, it would have been a *cloud*
transcription path — strictly worse for privacy than the local Tier 2 the
product now leads with, and not the private option a user might assume.

---

## 3. Conclusion on Tier 1

**Tier 1 is unavailable.** Not "hard", not "undocumented but workable" —
unavailable through supported public interfaces.

The only supported mechanism (`RecognitionService`) requires the ChatGPT app to
opt in by declaring itself a system recognition provider, which it is not known
to do and which OpenAI does not document. Every other channel either has no
return path by construction, or would require reverse-engineering an
undocumented component — which the brief explicitly forbids relying on, and
which this project has not done.

Nothing in this repository extracts ChatGPT tokens, intercepts its traffic,
calls undocumented endpoints, bypasses certificate pinning, modifies its APK,
or impersonates its client. No such experiment was attempted.

**This is not a reason to stop.** Tier 2 is the primary implementation.

---

## 4. Feasibility matrix

"Verified" below means *verified by this project, in this environment*. Most
Android runtime behaviour could not be verified because there was no device —
see `TEST_RESULTS.md`, which does not blur this line.

| Capability | Status | Method | Security | Verified? |
|---|---|---|---|---|
| Reuse ChatGPT subscription for transcription | **No** | No supported interface; billing separate | n/a | Yes — by platform analysis (§2) |
| Invoke official ChatGPT dictation | **No** | Would require ChatGPT to declare a `RecognitionService` | n/a | Bounded unknown (§2.1) |
| Reuse ChatGPT for text refinement | **No** | No documented result contract | n/a | Yes — by platform analysis |
| Android on-device transcription | **Yes** | `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+) | Runs locally; API fails rather than falling back to cloud | Implemented, **not device-tested** |
| Android default transcription | **Yes** | `SpeechRecognizer` | **Location unverifiable** — reported as UNKNOWN, never as "offline" | Implemented, **not device-tested** |
| Local ASR | **Yes** | Vosk (`com.alphacephei:vosk-android`), streaming, on-device | Fully local; the only provider that reports ON_DEVICE unconditionally | Implemented and compiles; **recognition not exercised** (needs a microphone) |
| Gemini Nano / on-device OS refinement | **Not in this build** | ML Kit GenAI would be the route | Would be local, device-dependent | **Not implemented** |
| Downloadable local LLM | **Not in this build** | — | — | **Not implemented** |
| OpenAI BYOK transcription | **Not in this build** | — | Audio would leave device | **Not implemented** |
| OpenAI BYOK refinement | **Yes** | `chat/completions` with the user's key | Draft leaves device; clearly labelled | Implemented, **not tested against live API** |
| Explicit clipboard output | **Yes** | `ClipboardManager` + auto-clear | Safest output path | Implemented; clear policy **unit-tested**, write path **emulator-tested** |
| Direct insertion without Accessibility | **Yes, partially** | `ACTION_PROCESS_TEXT` (§2.3) | No extra permission | Implemented, **not device-tested**; works only for selected text |
| Flow-like bubble | **Yes** | `TYPE_APPLICATION_OVERLAY` | Needs overlay permission; enhanced flavour only | Implemented, **not device-tested** |
| Automatic text-field detection | **Yes** | AccessibilityService focus events | Needs accessibility permission; enhanced flavour only | Implemented, **not device-tested** |
| Password isolation | **Partial** | InputType/isPassword flags + OTP hint heuristics | Reliable only when the app sets the flags | Policy logic **unit-tested**; device behaviour not tested |
| Banking-app isolation | **Partial** | Curated denylist + token heuristics + user list | Cannot be complete; no Android API classifies apps | Policy logic **unit-tested**; device behaviour not tested |

---

## 5. Backend comparison

Cost, offline and privacy columns are properties of the design. Quality and
latency columns are **published figures from the projects themselves, not
measurements taken by this project** — see `MODEL_COMPARISON.md`.

| Backend | Recurring cost | Offline | Audio leaves device | Text leaves device | Quality | Latency | Storage |
|---|---|---|---|---|---|---|---|
| Existing ChatGPT subscription | n/a | n/a | n/a | n/a | n/a | n/a | n/a — **not available** |
| Android on-device speech | None | Yes | No | No | Device-dependent | Low | 0 (OS language packs) |
| Android default speech | None | **Unverifiable** | **Unverifiable** | No | Generally good | Low | 0 |
| Local ASR Fast (Vosk small en-US) | None | **Yes** | No | No | Good | Streaming, faster than real time | ~40 MB |
| Local ASR Fast (Vosk small en-IN) | None | **Yes** | No | No | Tuned for Indian English | Streaming | ~36 MB |
| Local ASR High Quality (Vosk en-IN) | None | **Yes** | No | No | Best local for Indian English | Streaming | ~1 GB |
| Local ASR (Vosk small Hindi) | None | **Yes** | No | No | Hindi only, no code-switching | Streaming | ~42 MB |
| OpenAI API | Per token, own key | No | **Yes** | Yes | High | Network-bound | 0 |

| Refinement backend | Recurring cost | Offline | Privacy | Quality | RAM | Storage |
|---|---|---|---|---|---|---|
| Deterministic | None | Yes | Fully local | Exact for structural edits; cannot do semantics | Negligible | 0 |
| Gemini Nano / OS model | None | Yes | Local | Good for short rewrites | OS-managed | 0 (OS-provided) |
| Local small LLM | None | Yes | Local | Variable; hallucination risk | 1–3 GB | 0.5–2 GB |
| OpenAI API | Per token, own key | No | **Draft leaves device** | Best | n/a | 0 |
| ChatGPT subscription | n/a | n/a | n/a | n/a | n/a | **Not available** |

---

## 6. Exact record of ChatGPT integration experiments

The brief asked for a precise record. Two of these are recorded as *not run*
rather than as failures, because claiming to have run them would be false.

| # | Experiment | How it would be invoked | Component | Ran? | Result |
|---|---|---|---|---|---|
| 1 | Does a ChatGPT subscription grant API access? | Documentation review | n/a | **Yes** (via search summaries; primary hosts blocked) | **No.** Separate products, separate billing. |
| 2 | Does OpenAI publish an Android SDK exposing ChatGPT capabilities to other apps? | Documentation review | n/a | **Yes** | **No.** The Apps SDK runs apps *inside* ChatGPT — opposite direction. |
| 3 | Is there a documented Android mechanism to borrow another app's dictation? | Android platform docs | `RecognitionService` | **Yes** | **Yes, one** — but it requires the *provider* to declare it. |
| 4 | Does the ChatGPT APK declare a `RecognitionService`? | `aapt dump badging` / manifest inspection | Manifest | **NOT RUN** | No device, no emulator, `play.google.com` blocked. Bounded either way — see §2.1. |
| 5 | Does the ChatGPT app expose exported activities with a result contract? | Manifest inspection on device | Manifest | **NOT RUN** | Same reason. Would be Classification 2 even if found, which the brief forbids relying on. |
| 6 | Can a Sharesheet/`ACTION_SEND` flow return a transcript? | Android platform docs | `Intent` | **Yes** | **No.** `ACTION_SEND` has no result contract; one-directional by construction. |
| 7 | Can `ACTION_PROCESS_TEXT` retrieve refined text from ChatGPT? | Android platform docs | `Intent` | **Yes** | **No** for ChatGPT (receiver must implement and return). **Yes** for us to implement — see §2.3. |
| 8 | Token / cookie / traffic interception | — | — | **NOT ATTEMPTED — PROHIBITED** | Forbidden by the brief and out of scope. Deliberately not investigated. |

---

## 7. Decision

```
Can ChatGPT officially expose reusable transcription?
                          |
                         NO
                          |
        Documented in this file, with the platform
        reason and the unrun experiments recorded
                          |
                          v
        Build Tier 2 as the primary product:
        on-device speech + deterministic refinement,
        with Tier 3 BYOK strictly optional
```

Everything in `MINIMUM_ACCEPTABLE_PRODUCT` (brief section 63) is reachable
without any ChatGPT integration, without any API key, and without a recurring
fee. That is what this repository builds.

---

## Sources

- [What is ChatGPT Plus? — OpenAI Help Center](https://help.openai.com/en/articles/6950777-what-is-chatgpt-plus) *(host blocked from this environment; consulted via search summaries)*
- [Managing Billing Settings on ChatGPT Web and Platform — OpenAI Help Center](https://help.openai.com/en/articles/9039756-managing-billing-settings-on-chatgpt-web-and-platform) *(same)*
- [Introducing apps in ChatGPT and the new Apps SDK — OpenAI](https://openai.com/index/introducing-apps-in-chatgpt/) *(same)*
- [Voice Dictation FAQ — OpenAI Help Center](https://help.openai.com/en/articles/12168547-voice-dictation-faq) *(same)*
- [`RecognitionService` — Android Developers](https://developer.android.com/reference/android/speech/RecognitionService)
- [`SpeechRecognizer` — Android Developers](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Create deep links — Android Developers](https://developer.android.com/training/app-links/create-deeplinks)
