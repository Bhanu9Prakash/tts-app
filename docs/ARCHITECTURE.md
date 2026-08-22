# Architecture

## The organising idea

Everything security-critical lives in modules that have **no Android
dependency**. That is not an aesthetic preference — it has three concrete
consequences:

1. **The rules are executable.** The command parser, the confirmation gate, the
   sensitive-field policy, the redaction logic and the checksum verifier are
   plain Kotlin, so they are covered by 133 unit tests that run in seconds on
   any JDK. Security properties asserted in a document drift; security
   properties asserted in a test do not.
2. **They can be reviewed without an Android toolchain.** `./gradlew test` works
   with no Android SDK installed and no access to Google's Maven repository.
3. **They are hard to accidentally couple to the UI.** A rule that cannot import
   `android.*` cannot quietly start depending on view state.

The Android modules are then a thin shell: permissions, lifecycle, rendering,
and the providers that talk to the platform or the network.

---

## Module graph

```
                          ┌──────────┐
                          │   core   │   Scratchpad, ProcessingLocation
                          └────┬─────┘   No dependencies.
        ┌──────────┬───────────┼────────────┬─────────────┐
        │          │           │            │             │
   ┌────▼─────┐ ┌──▼───────┐ ┌─▼────────┐ ┌─▼────────┐ ┌──▼──────────┐
   │speech-api│ │refinement│ │ commands │ │ security │ │model-manager│
   │          │ │   -api   │ │          │ │          │ │             │
   └────┬─────┘ └──┬───────┘ └─┬────────┘ └─┬────────┘ └──┬──────────┘
        │          │           │            │             │
        │          └───────┬───┘            │             │
        │          ┌───────▼──────────┐     │             │
        │          │ refinement-local │     │             │
        │          │ transforms +     │     │             │
        │          │ CommandRouter    │     │             │
        │          └───────┬──────────┘     │             │
        └──────────────────┼────────────────┴─────────────┘
                           │
                     ┌─────▼─────┐
                     │    app    │   Android. Compose UI, providers,
                     │           │   tile, flavours.
                     └───────────┘
```

Everything above `app` is pure JVM. `app` is the only module that knows Android
exists.

| Module | Contains | Why it is separate |
|---|---|---|
| `core` | `Scratchpad`, `ProcessingLocation`, `PipelineDescriptor` | The private staging area and the privacy vocabulary. Depended on by everything. |
| `speech-api` | `TranscriptionProvider` and its event types | Lets transcription backends be swapped without touching callers. |
| `refinement-api` | `RefinementProvider`, request/result types | Same, for refinement. Deterministic and cloud providers implement one interface. |
| `commands` | `CommandParser`, intents, confidence tiers | The highest-risk logic in the product. Isolated so it is exhaustively testable. |
| `refinement-local` | `DeterministicTransforms`, `CommandRouter`, `MeaningPreservationCheck` | The offline floor. Works with no model, no key, no network. |
| `security` | Field/app policies, `SafeLog`, `Redaction` | Policy decisions expressed as pure functions over plain data. |
| `model-manager` | Catalogue, `ModelVerifier`, `ModelDownloadGuard` | Supply-chain rules that must hold before any byte is loaded. |
| `app` | Compose UI, `AndroidSpeechProvider`, `CredentialStore`, tile, flavours | Everything that genuinely needs Android. |

---

## The pipeline

```
Microphone
    │
    ▼
TranscriptionProvider ──── reports ProcessingLocation (ON_DEVICE / OFF_DEVICE / UNKNOWN)
    │
    ▼
Raw utterance
    │
    ▼
CommandParser ──── splits into (literal text, optional command + confidence)
    │
    ▼
CommandRouter
    ├── deterministic ──► DeterministicTransforms ──► Scratchpad
    ├── semantic ───────► RefinementProvider ──► MeaningPreservationCheck ──► Scratchpad
    └── externalising ──► AwaitingConfirmation (requires a tap)
    │
    ▼
Scratchpad  ◄── private. Nothing outside the app can observe this.
    │
    ▼
Preview
    │
    ▼
Explicit tap ──► Copy / Insert
```

The router **decides**; it never **acts**. It returns a `RouterOutcome` and the
caller executes. That separation is what makes "nothing escapes without
approval" a testable claim rather than a convention.

---

## Three design decisions worth explaining

### 1. `ProcessingLocation` has three values, not two

Android's default `SpeechRecognizer` may run locally or in the cloud depending
on device, OEM and installed language packs, and an app cannot in general tell
which. Modelling this as `isLocal: Boolean` would force a lie in one direction
or the other.

So `UNKNOWN` is a first-class value, and `mayLeaveDevice` groups it with
`OFF_DEVICE`. The unverified case is treated as the unsafe case everywhere, and
the UI renders it as "Not verified — may leave this device", never as "offline".

The only provider permitted to report `ON_DEVICE` from the platform recogniser
is the one built through `AndroidSpeechProvider.createOnDevice`, which uses
`createOnDeviceSpeechRecognizer` — an API that *fails* rather than falling back
to a network backend. That failure mode is precisely what makes the claim
honest.

### 2. The confirmation gate is structural, not conditional

`INSERT`, `COPY` and `CANCEL` return `requiresConfirmation = true`
unconditionally, and `CommandRouter` checks that flag *before* dispatch. There
is no branch on which a voice command alone moves text out of the scratchpad.

The `when` in `applyDeterministic` still lists those three actions explicitly and
returns `Rejected`, so if someone later adds a new externalising action, the
compiler forces them to confront the gate rather than letting it acquire a
silent direct path.

### 3. Deterministic-first routing

Structural edits — bullets, deletions, cleanup, line breaks — are handled by
pure string functions. They are instant, offline, free, and cannot hallucinate.
`CommandRouter` checks the deterministic provider before falling through to a
model, so "turn this into bullet points" never reaches an LLM.

This is what lets the product satisfy its minimum-viable definition with no
model, no key and no network.

---

## Flavours

| | `safe` | `enhanced` |
|---|---|---|
| Accessibility service | **Not present in the APK** | Present, opt-in, off by default |
| `SYSTEM_ALERT_WINDOW` | **Not requested** | Requested for the bubble |
| Foreground service | **Not requested** | Requested; Flow Mode needs it |
| Total permissions | 3 | 7 |
| Output | Copy, plus `ACTION_PROCESS_TEXT` replacement | Also direct insertion into the focused field |

The split is structural, not a runtime toggle. A user can verify the safe APK
has no accessibility component with `aapt2 dump xmltree` — no trust in this
source tree required. CI enforces the same check on every build.

---

## The optional relay (not implemented, described for completeness)

The strongest answer to "an API key on a device is extractable" is to keep the
key off the device:

```
Voice Composer ──► user-controlled relay ──► OpenAI API
   (short-lived        (holds the real
    device token)       API key)
```

The user runs the relay; it holds the real key and mints short-lived,
scope-limited tokens for the device. A stolen device then yields a token that
expires and can be revoked, not a long-lived credential.

This is **not implemented**. It is described here because the brief asked it to
be evaluated, and because it is the honest answer to the residual risk in
`THREAT_MODEL.md` #20. It must never be mandatory: the app works fully with no
network at all.

---

## What is deliberately not here

- **No dependency injection framework.** The graph is small; `ProviderRegistry`
  is a factory with a comment explaining why it does not cache.
- **No persistence layer.** History is off by default and unimplemented in this
  build; nothing writes drafts to disk.
- **No analytics, crash-reporting, or networking SDK.** BYOK uses the platform's
  `HttpURLConnection`. Nothing to scrub beats scrubbing.
- **No `Flow`/coroutines dependency in the pure modules.** The provider
  interfaces use suspend functions and callbacks so those modules stay free of
  a coroutines dependency; the Android layer adapts them.
