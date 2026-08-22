# Known limitations

Everything this project does not do, stated plainly.

---

## 1. Tier 1 (ChatGPT subscription) does not exist

Not "partially works" — it is unavailable. A ChatGPT subscription does not
include API access, and Android provides no supported way for an app to invoke
another app's dictation. `FEASIBILITY.md` has the full reasoning and the record
of which experiments could not be run in this environment.

---

## 2. Most of the Android layer has still never run

An emulator job now covers the Android Keystore, app-private model storage and
clipboard writes (see `TEST_RESULTS.md`). Everything else in the Android layer
compiles and packages but **has not been observed running**.

Concretely unverified: whether speech recognition works at all in practice,
whether the Compose UI renders and survives rotation and process death, whether
the Quick Settings tile behaves on API 34+, whether the text-selection menu item
appears in WhatsApp or Gmail, and whether the accessibility service detects focus
and inserts text as intended.

No real handset has run this. Treat the untested parts as reviewed code, not as
a working product.

---

## 3. Local ASR is implemented, but never heard a word

`VoskTranscriptionProvider` is real: Vosk is compiled in
(`com.alphacephei:vosk-android`, Apache-2.0, prebuilt native libraries), audio
streams from `AudioRecord` into the recogniser, and partial results are emitted
as the user speaks. The download, checksum-verification and install path is
complete and covered by unit and emulator tests.

**What has not happened is recognition.** That needs a microphone and a
downloaded model, and the environment that produced this code had neither. So
accuracy, latency, real-time factor, partial-result cadence, long-dictation
stability, memory and battery are all unmeasured. `MODEL_COMPARISON.md` lists
what a real evaluation would have to cover.

### No model checksum is pinned, so no model can be downloaded

Every catalogue entry ships `sha256 = UNPINNED`, and the guard refuses all of
them before any network request.

This is deliberate. A checksum is worth something only if it was computed from
an artifact the publisher actually published, and the model host was unreachable
from the environment this was written in. Shipping a plausible-looking but
unverified hash would be worse than shipping none, because it would look like a
guarantee.

**To make the app usable:** run the `Model checksums` workflow
(`.github/workflows/model-checksums.yml`), which downloads each archive and
prints its real SHA-256, then paste those into `CandidateModels.kt`. That same
job then runs on every push and *fails* if a published archive stops matching —
so pinning converts it from a reporting tool into a supply-chain assertion.

**Known snag:** on the runs attempted so far, `alphacephei.com` was slow enough
from GitHub's runners that the job made no progress for the better part of an
hour before being capped. The script now gives up after ten minutes per file
and reports, rather than hanging. If the host stays unreachable from CI, run
`tools/verify-model-checksums.sh` from any machine that can reach it — the
output is the same, and it is the values that matter, not where they were
computed.

Until that is done, the app falls back to the Android platform recogniser, and
the pipeline banner correctly stops claiming "local".

### Hindi is Hindi, not code-switching

The Hindi model recognises Hindi. It does **not** handle Hindi-inside-English or
Telugu-inside-English code-switching, which the brief asks about — Vosk's
single-language models cannot do that, and no model in the catalogue claims to.
Telugu is not offered at all.

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

- **Emulator tests cover only three areas** (Keystore, model storage, clipboard
  writes). The UI, speech, and Flow Mode have no instrumented coverage.
- **`isHardwareBacked()` is unverified.** A CI emulator has no TEE or StrongBox,
  so the code path that reports hardware backing has never returned true in a
  test.
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
