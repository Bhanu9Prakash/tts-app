# Known limitations

Everything this project does not do, stated plainly.

---

## 1. Tier 1 (ChatGPT subscription) does not exist

Not "partially works" — it is unavailable. A ChatGPT subscription does not
include API access, and Android provides no supported way for an app to invoke
another app's dictation. `FEASIBILITY.md` has the full reasoning and the record
of which experiments could not be run in this environment.

---

## 2. Nothing has been run on an Android device

The environment that produced this repository had no device and no emulator. The
Android layer compiles and packages, and CI verifies claims about the built
APK's manifest, but **no runtime behaviour has been observed**.

Concretely unverified: whether speech recognition works across OEM recognisers,
whether the UI renders correctly, whether the Quick Settings tile behaves on API
34+, whether clipboard auto-clear fires, whether Keystore reports hardware
backing, whether the text-selection menu item appears in real apps, and whether
the accessibility service detects focus and inserts text as intended.

Treat the Android layer as reviewed code, not as a working product.

---

## 3. Local ASR is not implemented

This is the largest gap against the brief's preferred architecture.

**What exists:** the model catalogue with full metadata, SHA-256 stream
verification, and a download guard that refuses unpinned or non-HTTPS
artifacts — all unit-tested.

**What does not:** the inference runtime. Neither whisper.cpp nor sherpa-onnx is
compiled in, so there is nothing to hand a downloaded model to. Selecting "Local
model" in Settings falls back to the platform on-device recogniser rather than
presenting a dead option.

**What it would take:** an NDK build of the chosen runtime, JNI bindings, a
`TranscriptionProvider` implementation, audio capture and chunking, and the
device benchmarking in `MODEL_COMPARISON.md`.

### No model checksum is pinned

Every catalogue entry ships `sha256 = UNPINNED`, and the guard refuses to
download any of them.

This is deliberate. A checksum is worth something only if it was computed from
an artifact the publisher actually published, and this environment had no
network route to the model hosts. Shipping a plausible-looking but unverified
hash would be worse than shipping none, because it would look like a guarantee.

`tools/pin-models.sh` downloads each artifact, prints its SHA-256, and rewrites
the catalogue. A test asserts no entry carries a well-formed hash, so a
plausible one pasted in without verification fails the build.

---

## 4. On-device LLM refinement is not implemented

Gemini Nano via ML Kit GenAI is the right first choice — the OS provides and
updates the model, so the app downloads nothing. The dependency is not added and
`ProviderRegistry` returns null for that option.

It could never be the only path anyway: it requires AICore and a supported
chipset, which most Android devices do not have.

Bundling a local LLM was considered and rejected for this build. A 1–3 GB
download to make a WhatsApp message more concise is a poor trade, and small
models hallucinate in exactly the way that matters here — quietly changing what
the user meant.

**What works instead:** the deterministic provider handles bullets, cleanup,
capitalisation, filler removal, paragraph splitting and all the delete commands
with no model at all. That is why the product still satisfies its minimum
definition offline.

---

## 5. BYOK covers refinement only, and is untested against the live API

`OpenAiRefinementProvider` is implemented. BYOK *transcription* is not.

The refinement provider has **never been called against the OpenAI API** — this
environment had no network route to it. Request shape, model-id validity and
error handling are unverified.

The default model id is a conservative guess exposed as an editable setting,
not a hard-coded constant, because model availability changes and OpenAI's
documentation could not be reached from here to confirm the current catalogue.

---

## 6. Safe Mode cannot record in the background

By design. Safe Mode requests no foreground-service permission, so dictation
stops if you leave the app. That is a real functional limit accepted in exchange
for a three-permission APK, and it is structural rather than a policy promise.

Background and long-running dictation is an enhanced-flavour capability.

---

## 7. Direct insertion without accessibility is partial

`ACTION_PROCESS_TEXT` is a genuine, documented, permission-free way to return
replacement text to another app. Its limits are real:

- The user must **select text first**. It cannot type into an empty field.
- The calling app must send a non-readonly request for the replacement to apply.
- Not every app surfaces the menu item.

It is not a general "type into any field" mechanism. That is what Flow Mode's
accessibility service is for, and why Flow Mode exists as a separate opt-in
APK.

---

## 8. Sensitive-field and sensitive-app detection are heuristics

Neither can be complete, and the app does not claim otherwise.

**Fields:** `isPassword` and the password `InputType` variants are reliable
*when the app sets them*. An app collecting a PIN, OTP or card number in an
unflagged numeric field defeats detection. Autofill hints are also unavailable
on the accessibility path — `AccessibilityNodeInfo` has no equivalent of
`View.getAutofillHints()` — so that signal is genuinely absent there rather than
approximated.

**Apps:** no Android API classifies apps by sector. `ApplicationInfo.category` is
self-declared and usually unset. The curated denylist covers common banking,
payment, brokerage, password-manager and authenticator packages, and the token
heuristics catch some others, but new apps appear constantly. **The
user-editable block list is the only mechanism that can be complete.**

The architectural mitigation matters more than the heuristics: in Safe Mode the
app never reads or writes another app's fields at all, so classification is
never load-bearing.

---

## 9. An API key on a device is extractable

The Keystore means key material never leaves secure hardware, which defeats
casual extraction and offline attacks on a stolen device. It does **not** defeat
an attacker running code as this app, who can request decryption exactly as the
app does.

No Android API changes this. BYOK is optional, off by default, and the app is
fully functional without it. `ARCHITECTURE.md` describes a user-controlled relay
that keeps the key off the device entirely; it is **not implemented**.

---

## 10. Dictation history is not implemented

The setting exists and defaults off. No storage layer is written, so nothing is
persisted regardless of the toggle — the safe direction for an unimplemented
feature. If implemented, it must be encrypted locally and remain excluded from
backup.

---

## 11. The false-activation corpus is text-level

The 15-utterance corpus proves the parser does not misfire on *text* that
resembles a command. It does not model ASR mishearing — a recogniser rendering
"voice commander" as "voice command", or dropping a word in a noisy room.

Measuring that needs audio, a recogniser and a device. It is the gap most likely
to produce a real-world false activation, and it is untested.

---

## 12. Release APKs from CI are debug-signed

So that `assembleRelease` produces something installable. **This is not suitable
for distribution.** A real release must be signed with the publisher's own key —
see `BUILDING.md`.

---

## 13. Smaller gaps

- **No instrumented tests.** They would need a device to be meaningful.
- **No dependency verification metadata.** Gradle's `verification-metadata.xml`
  would be the next supply-chain step.
- **No licence file.** Add one before distributing.
- **Bluetooth headset and hardware-button activation** are researched in the
  brief but not implemented; the Quick Settings tile and launcher icon are the
  activation surfaces.
- **The pipeline banner is plain text**, not the diagram in the brief's mockups.
- **No diff view.** The preview shows Original and Refined side by side; a
  word-level diff is not implemented.
- **Compose UI is functional rather than polished.** No motion design, no
  configurable bubble size or opacity.
