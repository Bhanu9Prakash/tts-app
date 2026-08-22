# Voice Composer

A private voice drafting layer between your thoughts and any text field on
Android.

Speak freely → compose privately → transform intentionally → review → commit.

It is not a dictation keyboard. Nothing is typed into another app as you speak.
Your words land in a private scratchpad that belongs to this app, you shape them
with voice commands, and only text you explicitly approve leaves.

**Think first. Commit second.**

---

## Start here: the ChatGPT question

The brief that produced this project asked, first, whether an Android app could
reuse the dictation already inside the official ChatGPT app, on an existing
ChatGPT subscription, with no separately billed API usage.

**It cannot.** Two independent reasons:

1. **The billing premise is false.** A ChatGPT Plus or Pro subscription does not
   include OpenAI API access or credits. They are separate products with
   separate billing.
2. **No supported interface exists.** Android's only documented mechanism for
   one app to provide speech recognition to another is `RecognitionService`, and
   it requires the *providing* app to declare itself a system recognition
   provider. ChatGPT is not known to do so, and OpenAI documents no such
   capability. Every other channel — share intents, deep links, App Actions,
   `ACTION_PROCESS_TEXT` — has no return path for this by construction.

Nothing here extracts ChatGPT tokens, intercepts its traffic, calls undocumented
endpoints, or impersonates its client. No such experiment was attempted.

`docs/FEASIBILITY.md` has the full investigation, the classification of every
mechanism, and — importantly — an honest record of which experiments could
**not** be run in the environment that produced this code, and why the
conclusion does not depend on them.

So the product is built on what actually works: on-device speech, deterministic
refinement, and optional bring-your-own-key cloud.

---

## What actually works today

Read this next to `docs/TEST_RESULTS.md`, which draws the line precisely.

**Tested on a JVM — 153 unit tests, all passing:**
the command parser and its false-activation resistance, the confirmation gate,
the scratchpad and its undo/redo, every deterministic transform, the
sensitive-field and sensitive-app policies, log redaction, clipboard clear
policy, model checksum verification, and archive extraction including zip-slip
and native-code defences.

**Tested on a real Android runtime — 14 instrumented tests on an API 34
emulator, run in CI on every push:** the Android Keystore (round trip, no
plaintext on disk, a fresh IV per encryption, key destruction), app-private
model storage and its install/delete lifecycle, and that the download guard
genuinely refuses an unpinned model.

**Compiles and packages, but not exercised:** the Compose UI, speech
recognition itself (it needs a microphone), Flow Mode's bubble and accessibility
service, and the BYOK provider (never called against the live API).

**Not implemented:** on-device LLM refinement, BYOK transcription, and history
persistence. See `docs/LIMITATIONS.md`.

---

## Two builds

You choose the trade-off, and you can verify the choice with `aapt2` rather than
trusting this README.

### `voice-composer-safe.apk` — the default

**Three permissions total:** `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`.

No overlay permission. No accessibility service — **not present in the APK at
all**, so it cannot be enabled. No foreground-service permission, which means
recording is structurally limited to when a Voice Composer screen is open.

Output is explicit Copy, plus text replacement through the system
text-selection menu.

### `voice-composer-enhanced.apk` — opt-in

Adds Flow Mode: a floating bubble that appears when you focus an editable field,
and direct insertion of approved text into that field.

This needs the overlay permission and an accessibility service. Android
accessibility services can technically read much of another app's on-screen
interface. This one is scoped as narrowly as the feature allows — it subscribes
only to focus and window-state events, never to text-change events, and reads a
field's type flags but never its contents — but **you are granting a capability
broader than the use**, and `docs/PERMISSIONS.md` says so in those terms.

Flow Mode is off by default even here. Enabling it takes three deliberate
actions.

---

## Privacy defaults

Every default is the private one:

History off. Raw audio retention off. Analytics off (no SDK present). Crash
reporting off (no SDK present). Automatic cloud fallback off. Preview required
on. Sensitive-app protection on. Transcription on-device. Refinement
deterministic.

**In the default configuration the app makes no network requests at all.**

The composer always shows the current pipeline and where each stage runs. It
distinguishes three states — on-device, leaves-the-device, and *not verified* —
because Android's default speech recogniser may run locally or in the cloud and
an app cannot tell which. Voice Composer will not call that "offline".

`docs/PRIVACY.md` has the detail.

---

## Fully local speech

Voice Composer bundles [Vosk](https://alphacephei.com/vosk/) — a streaming
on-device recogniser, Apache-2.0, with prebuilt native libraries. Download a
model once and dictation works with **no network at all**, including on
aeroplane mode.

Models offered: small English (US), small and large **Indian English**, and
**Hindi**. The Model Manager states each one's download size before you tap,
and lets you delete it afterwards.

Two things about model downloads worth knowing:

- **Nothing downloads without an explicit tap.** There is no setting that can
  pre-approve it.
- **Every archive is SHA-256 verified before it is unpacked**, and unpacking
  refuses path traversal, native libraries and zip bombs. Models whose checksum
  is not pinned are not downloadable *at all* — which, in this build, is all of
  them. See `docs/MODEL_COMPARISON.md` for why that is deliberate and how to
  pin them.

---

## Voice commands

Say the reserved phrase, then your instruction:

> I checked Groww and Zerodha and Zerodha appears better for API access.
> **Voice command.** Rewrite this professionally, make it concise, and turn the
> concerns into bullet points.

The trigger is two words, `voice command`, precisely so ordinary speech does not
fire it. "The command line interface has several commands" does nothing — and
there is a test corpus asserting that, along with harder cases like "Her voice
command over the room was impressive."

Structural commands never reach a language model. Bullets, cleanup, deletions,
line breaks and undo/redo are handled by pure string functions: instant,
offline, free, and incapable of hallucinating.

**Insert, Copy and Cancel always require a tap.** Saying "voice command, insert"
highlights the button; it does not press it. This holds at every confidence
level.

The trigger phrase is configurable in Settings.

---

## Building

```bash
# Unit tests. No Android SDK required.
./gradlew test

# APKs. Requires an Android SDK.
./gradlew assembleSafeDebug assembleEnhancedDebug
./gradlew assembleSafeRelease assembleEnhancedRelease
```

The `:app` module is skipped automatically when no Android SDK is detected, so
the pure-JVM modules build and test anywhere. `docs/BUILDING.md` has the detail,
including why the repository is laid out the way it is.

Prebuilt APKs and their SHA-256 checksums are produced by
[CI](.github/workflows/build.yml) and attached to each run as artifacts.

---

## Verify before you install

Do not take this README's word for the permission list.

```bash
# What does it actually request?
aapt2 dump permissions voice-composer-safe.apk

# Expected, and nothing else:
#   uses-permission: name='android.permission.RECORD_AUDIO'
#   uses-permission: name='android.permission.INTERNET'
#   uses-permission: name='android.permission.ACCESS_NETWORK_STATE'

# Confirm the safe build really has no accessibility service or overlay:
aapt2 dump xmltree --file AndroidManifest.xml voice-composer-safe.apk \
  | grep -i "accessibility\|SYSTEM_ALERT_WINDOW"
# (should print nothing)

# Check the download matches what CI built:
sha256sum -c SHA256SUMS.txt
```

CI runs the same two checks against the packaged APK on every build, so a
regression fails the build rather than shipping quietly.

`docs/PERMISSIONS.md` explains every permission and lists alternatives to
`aapt2` (`apkanalyzer`, Android Studio's APK Analyzer, `adb`).

---

## Installing

```bash
adb install -r voice-composer-safe.apk
```

Or transfer the APK to the device and open it, allowing installation from
unknown sources.

The two flavours have different application IDs (`dev.voicecomposer.safe` and
`dev.voicecomposer`), so both can be installed side by side.

Release APKs from CI are signed with a debug key so they are installable. **A
real release must be signed with your own key** — see `docs/BUILDING.md`.

---

## Documentation

| Document | What it covers |
|---|---|
| [`FEASIBILITY.md`](docs/FEASIBILITY.md) | The ChatGPT investigation, the capability matrix, and the exact record of experiments including those that could not be run |
| [`ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module layout, the pipeline, and why the security logic has no Android dependency |
| [`PRIVACY.md`](docs/PRIVACY.md) | Defaults, what leaves the device and when, logging, backup, clipboard |
| [`PERMISSIONS.md`](docs/PERMISSIONS.md) | Every permission justified, exported-component audit, verification instructions |
| [`MODES.md`](docs/MODES.md) | Safe Mode vs Enhanced Flow Mode, feature by feature, and how to choose |
| [`THREAT_MODEL.md`](docs/THREAT_MODEL.md) | 30 risks with likelihood, impact, mitigation and residual risk |
| [`MODEL_COMPARISON.md`](docs/MODEL_COMPARISON.md) | Local ASR and refinement candidates, and why no checksum is pinned |
| [`TEST_RESULTS.md`](docs/TEST_RESULTS.md) | What was tested, what was not, and what could not be |
| [`LIMITATIONS.md`](docs/LIMITATIONS.md) | What does not work, and what it would take |
| [`BUILDING.md`](docs/BUILDING.md) | Reproducible build instructions |

---

## Licence

Not yet specified. Add one before distributing.
