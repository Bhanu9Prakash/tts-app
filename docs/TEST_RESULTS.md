# Test results

The brief asked for a clear distinction between implemented-and-tested,
implemented-but-not-device-tested, prototype, research finding, unsupported, and
unable-to-verify. This document draws those lines rather than blurring them.

---

## Summary

| Category | Count |
|---|---|
| **Implemented and tested** (unit tests executed, all passing) | 133 tests across 5 modules |
| **Implemented, compiles, not device-tested** | The entire Android layer |
| **Not implemented** | Local ASR runtime, on-device LLM, BYOK transcription |
| **Unable to verify** | Everything requiring a physical Android device |

**No functional claim in this repository rests on a test that was not actually
run.** Where something could not be tested, it says so.

---

## 1. Implemented and tested

133 unit tests, executed on JDK 21 locally and JDK 17 in CI. **All passing, zero
failures, zero skipped.**

Reproduce with `./gradlew test` — no Android SDK required.

| Module | Tests | What is covered |
|---|---|---|
| `commands` | 23 | Command parsing, false-activation resistance, confidence tiers, confirmation gate |
| `core` | 21 | Scratchpad undo/redo/history bounds, `ProcessingLocation` honesty rules |
| `refinement-local` | 39 | Deterministic transforms, command routing, meaning-preservation checks |
| `security` | 35 | Sensitive field policy, sensitive app policy, redaction and log safety |
| `model-manager` | 15 | SHA-256 verification, download guard, catalogue consistency |

### The tests that matter most

**Zero false activations across the ordinary-speech corpus.** Fifteen
utterances that resemble commands but are not, including the brief's own
example, "The command line interface has several commands." None executes a
command; none has its text altered.

The corpus also covers the harder near-misses: `"Her voice command over the room
was impressive"`, `"I told him voice command doesn't work on this phone"`, and
`"We should add a voice commander feature eventually"` — cases where the trigger
words genuinely appear in ordinary speech.

**Nothing escapes the scratchpad without a tap.** `CommandRouterTest` sweeps
every accepted phrasing of INSERT, COPY and CANCEL and asserts each returns
`AwaitingConfirmation`, never `Applied` — at HIGH confidence, not just when
uncertain.

**Unverified processing is never reported as local.** `ProcessingTest` asserts
`ProcessingLocation.UNKNOWN.mayLeaveDevice` is true, that no label for UNKNOWN
or OFF_DEVICE contains the word "locally", and that a pipeline with an
unverified transcription stage reports that data may leave the device.

**Reason codes carry no user content.** `SensitiveFieldPolicyTest` blocks a field
hinted `"Enter OTP sent to 9876543210"` and asserts the resulting reason code is
exactly `hint_matches_otp_pattern`, with the phone number absent.

**Exception messages never reach a log.** `RedactionTest` throws
`IllegalStateException("request body: my private dictated text")`, logs it via
`SafeLog.failure`, and asserts the output contains `IllegalStateException` and
not the message.

**Every shipped model refuses to download.** `ModelDownloadGuardTest` asserts
every catalogue entry fails the guard with `checksum_not_pinned`, and — as a
regression guard — that no entry carries a well-formed hash, which would fail
the build if someone pasted a plausible-looking one in without verifying it.

### Two real bugs these tests caught

Recorded because "all tests pass" means more when the tests have found
something.

1. **`Scratchpad.original` returned the wrong text.** It returned the first
   revision, which is the empty initial state, so the preview would have shown
   an empty "Original" beside the refined text. Fixed to return the last purely
   *dictated* revision — the accumulated raw dictation before any transformation
   — with a fallback for when history bounds age those revisions out.

2. **Compound instructions were silently narrowed.** "Rewrite this
   professionally, make it concise, and turn the concerns into bullet points"
   matched several semantic cues, and arbitrary length-ranking picked one,
   discarding the rest of the user's request. Now, when cues from more than one
   intent fire, the parser classifies it `CUSTOM` and passes the user's own
   words through untouched.

---

## 2. Implemented, compiles, not device-tested

The Android layer compiles and both flavours package into installable APKs in
CI. **None of it has been run on a device or emulator** — the environment that
produced this source tree had neither.

Everything in this table should be read as "written, type-checked, and reviewed;
behaviour unverified."

| Component | Status | What device testing would need to establish |
|---|---|---|
| `AndroidSpeechProvider` | Compiles | Whether recognition works across OEM recognisers; whether `createOnDeviceSpeechRecognizer` succeeds on real devices; partial-result cadence; Indian English accuracy |
| Composer UI (Compose) | Compiles | Rendering, rotation, process death and recreation, IME interaction |
| `ProcessTextActivity` | Compiles | Whether the selection menu item appears in WhatsApp, Gmail, Docs, Chrome; whether replacement text is actually applied |
| Quick Settings tile | Compiles | Tile placement, `startActivityAndCollapse` on API 34+ |
| `ClipboardWriter` | Compiles | Auto-clear timing; whether `EXTRA_IS_SENSITIVE` suppresses previews as expected |
| `CredentialStore` | Compiles | Keystore behaviour; whether hardware backing is reported correctly; key deletion |
| `OpenAiRefinementProvider` | Compiles | **Never called against the live API** — the build environment had no network route to OpenAI. Request shape, model id validity and error handling are unverified |
| `FlowAccessibilityService` | Compiles | Whether focus events fire as expected across apps; whether `ACTION_SET_TEXT` inserts correctly; whether password detection holds on real login screens |
| `BubbleOverlayService` | Compiles | Overlay positioning, drag, rotation, keyboard show/hide, foreground/background transitions |

### Verified by CI, not by a device

**All four APKs build.** `assembleSafeDebug`, `assembleEnhancedDebug`,
`assembleSafeRelease` and `assembleEnhancedRelease` all complete, including R8
minification and resource shrinking on the release variants. So the Android
layer is known to compile, link, and package — including the enhanced flavour's
accessibility service and overlay.

Two further claims are mechanically verified on every build, because CI
inspects the packaged artifact rather than trusting the source:

- The safe APK does not request `SYSTEM_ALERT_WINDOW`.
- The safe APK declares no accessibility service.

Both are asserted with `aapt2` against the built APK, and the build fails if
either regresses. Every APK's SHA-256, size and full permission list is printed
to the job summary of each run.

What this does **not** establish is that any of it behaves correctly at
runtime. Compiling and packaging is a much weaker claim than working.

---

## 3. Not implemented

Stated plainly rather than left to be discovered:

| Feature | Status | Why |
|---|---|---|
| Local ASR (whisper.cpp / sherpa-onnx) | **Not implemented** | The inference runtime is not bundled. The catalogue, checksum verifier and download guard are implemented and tested; the engine that would consume a model is not. Selecting "Local model" falls back to the platform recogniser rather than presenting a dead option. |
| Gemini Nano / ML Kit GenAI refinement | **Not implemented** | Dependency not added; `ProviderRegistry` returns null for that option. |
| Bundled local LLM | **Not implemented** | Deliberate — see `MODEL_COMPARISON.md`. |
| BYOK transcription (OpenAI audio) | **Not implemented** | Only BYOK *refinement* is implemented. |
| Dictation history persistence | **Not implemented** | The setting exists and defaults off; no storage layer is written. Nothing is persisted, which is the safe direction for an unimplemented feature. |
| Instrumented (device) tests | **Not written** | Would require a device or emulator to be meaningful. |

---

## 4. Unable to verify

These are the brief's own test requirements that could not be run here, with
the reason for each. They are not marked as passing.

### Requires a physical device or emulator

Permissions (granted / denied / revoked mid-session), recording lifecycle,
long-dictation stability at 30 s through 20 min, airplane-mode behaviour,
low-memory behaviour, corrupt-model handling in a live runtime, rotation,
screen-off, process recreation, and every integration test against WhatsApp,
Gmail, Docs, Slack, browsers and notes apps.

### Requires a device *and* model files

Every ASR quality measurement: word error rate on general English, Indian
English, technical vocabulary, Indian names, financial terms, and
Hindi-in-English / Telugu-in-English code-switching; punctuation quality;
real-time factor; RAM; battery; thermal throttling. `MODEL_COMPARISON.md` lists
what a real evaluation would have to cover.

### Requires network access that this environment did not have

Any call to the OpenAI API. Any model download, and therefore any real SHA-256
checksum — which is why every catalogue entry ships `UNPINNED` and the app
refuses to download.

### A limit of the command tests worth naming

The false-activation corpus is **text-level**. It proves the parser does not
misfire on text that resembles a command. It does **not** model ASR mishearing —
a recogniser rendering "voice commander" as "voice command", or dropping a word
in a noisy room. Measuring that needs audio, a recogniser and a device, and it
is the gap most likely to produce a real-world false activation.

---

## 5. How to reproduce

```bash
# Unit tests. No Android SDK needed.
./gradlew test

# Per-module HTML reports:
#   <module>/build/reports/tests/test/index.html

# With an Android SDK installed, build both flavours:
./gradlew assembleSafeDebug assembleEnhancedDebug
```

CI runs the unit tests with `ANDROID_HOME` blanked, so the claim that the
security-critical logic builds and passes with no Android SDK is checked on
every push rather than asserted here.
