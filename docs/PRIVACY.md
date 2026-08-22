# Privacy architecture

## The one-sentence version

Speech becomes text in a buffer that belongs to this app, and nothing reaches
any other app, or the network, until you tap a button.

---

## Defaults

Every default is the private one. This is the state you get without changing a
setting.

| Setting | Default | Effect |
|---|---|---|
| Dictation history | **Off** | Nothing is written to disk. |
| Raw audio retention | **Off** | No audio is ever stored. |
| Analytics | **Off** | No analytics SDK is even present. |
| Crash reporting | **Off** | No crash SDK is present. |
| Debug logging | **Off** | Release builds discard log events entirely. |
| Preview before commit | **On** | You see the text before it can go anywhere. |
| Sensitive apps protection | **On** | Stays out of banking, payment, brokerage, password-manager and authenticator apps. |
| Accessibility integration | **Off**, and absent from the safe APK | No Flow Mode unless you opt in. |
| Automatic cloud fallback | **Off** | A failing local model errors; it does not silently send your audio away. |
| Transcription backend | Android on-device | The API that refuses to fall back to a server. |
| Refinement backend | Deterministic only | No model, no network. |
| Clipboard auto-clear | 1 minute | Narrows the window for clipboard snooping. |

---

## What leaves the device, and when

**In the default configuration: nothing.** No network request is made at all.

Data leaves only if you deliberately choose a backend that requires it. Two
things enforce that rather than merely promising it:

1. **The pipeline banner is always visible** at the top of the composer, is not
   collapsible, and renders the actual processing location of each stage.
2. **`ProcessingLocation` has three values.** `ON_DEVICE`, `OFF_DEVICE`, and
   `UNKNOWN` — and `UNKNOWN` is grouped with `OFF_DEVICE` for every privacy
   decision in the app.

That third value matters. Android's default `SpeechRecognizer` may be served
locally or by a cloud backend depending on your device, its manufacturer and
your installed language packs, and **an app cannot tell which**. Rather than
guess, Voice Composer reports it as "Not verified — may leave this device" and
treats it as if it does.

The only backend permitted to claim `ON_DEVICE` from the platform recogniser is
the one using `createOnDeviceSpeechRecognizer`, which *fails* rather than
falling back to a network service. That failure mode is what makes the claim
truthful.

---

## The private scratchpad

Dictated text lands in an in-memory `Scratchpad` owned by the app process.

- **Interim transcription never leaves it.** Partial results render only in our
  own UI. No other app sees a word until you commit.
- **Only two methods send text outward** — `copyToClipboard` and `commitInsert`
  — and both are reachable only from tap handlers. The transcription path and
  the command path cannot call them.
- **Voice cannot commit.** "Voice command, insert" does not insert. It
  highlights the Insert button and waits for a tap. Same for Copy and Cancel.
  This holds at every confidence level and is verified by tests that sweep every
  accepted phrasing.
- **Cancel really discards.** It returns a fresh scratchpad; the text is not
  recoverable through undo, and no "recently cancelled" buffer is kept.
- **History is bounded**, so a long session cannot accumulate unbounded dictated
  text in memory.

---

## Logging

Production logs contain no dictated text — and the mechanism is structural, not
a policy.

`SafeLog` has **no API that accepts arbitrary user text**. Events are a fixed
identifier plus typed non-content fields (counts, durations, enum names, reason
codes). String fields are scrubbed for credential patterns and truncated to a
length summary. Exception *messages* are never logged — only the exception type
— because messages routinely echo request bodies.

In release builds the log sink is null, so events are constructed and discarded.

Reason codes are deliberately content-free. When a field is blocked for looking
like an OTP box, the log records `hint_matches_otp_pattern` — never the hint
text. There is a test asserting exactly that.

---

## Backup

Excluded from both cloud backup and device-to-device transfer:

- BYOK credentials (the ciphertext, and the Keystore key never leaves the device
  anyway)
- Dictation history, if you enabled it
- Any persisted scratchpad
- Downloaded model files — re-downloadable, and backing them up would bypass
  checksum verification on restore

Configured in `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml`.

---

## Clipboard

Copy is always explicit. The clip is marked `EXTRA_IS_SENSITIVE` on Android 13+,
which suppresses the content preview and cross-device sync. Auto-clear defaults
to one minute, and only clears if the clipboard still holds exactly what we
wrote — so it never destroys something you copied from elsewhere in the
meantime.

Voice Composer **never reads the clipboard** and keeps no clipboard history.
There is no clipboard read path in the codebase at all.

Being straight about the limits: the app you paste into necessarily sees the
text, a foreground app can read the clipboard while focused, and an OS-level or
third-party clipboard manager may retain a copy. Auto-clear narrows the window;
it does not make the copy private.

---

## BYOK credentials

If you supply an OpenAI API key, it is encrypted with an AES-GCM key generated
inside the Android Keystore — hardware-backed where the device supports it. Only
ciphertext is stored. Deleting the last credential deletes the Keystore key, so
any surviving ciphertext becomes permanently undecryptable.

**What that does not do:** it does not make the key unextractable. An attacker
running code as this app can ask the Keystore to decrypt, exactly as the app
does. Hardware backing protects the *key material*, not the *plaintext the app
is entitled to obtain*. No Android API changes this, and any product claiming
otherwise is overselling.

The mitigations that actually matter are architectural: BYOK is optional and off
by default, the app is fully functional without it, and you should scope and
rotate the key. `ARCHITECTURE.md` describes a user-controlled relay that keeps
the key off the device entirely.

Also worth stating plainly: **the OpenAI API is billed separately from a ChatGPT
subscription.** A Plus or Pro subscription does not cover API usage. See
`FEASIBILITY.md`.

---

## What is not collected

Not "we promise not to" — these capabilities are absent:

- No analytics SDK, no crash-reporting SDK, no advertising SDK.
- No clipboard reading.
- No `QUERY_ALL_PACKAGES`; installed apps are never enumerated.
- No notification access.
- No contacts, location, camera, or file-system access.
- No account, device identifier, or telemetry of any kind.
- In Flow Mode: no keystroke capture (`TYPE_VIEW_TEXT_CHANGED` is not
  subscribed to), no screen scraping, no reading of the focused field's
  contents, no accessibility event text logged or transmitted.

Verify the first several yourself with `aapt2 dump permissions` — see
`PERMISSIONS.md`.
