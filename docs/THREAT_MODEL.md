# Threat model

Scope: the Voice Composer Android app, the text a user dictates into it, and
any credentials they choose to give it.

Out of scope: the security of a device that is already fully compromised (see
"Residual risk that cannot be engineered away" at the end), and the security of
third-party services the user opts into.

The likelihood ratings assume an ordinary user on a non-rooted device with
apps installed from a mainstream store.

---

## Risk register

| # | Risk | Likelihood | Impact | Mitigation | Residual risk |
|---|---|---|---|---|---|
| 1 | **Malicious keyboard (IME) observes dictated text** | Medium | High | Voice Composer is not an IME and does not type into other apps in Safe Mode. Text reaches an IME only if the user pastes it. | An IME with focus sees text the user pastes. Unavoidable: that is what an IME is. Users concerned about this should audit their keyboard. |
| 2 | **Accessibility abuse by Voice Composer itself** | Low | High | Safe flavour contains no accessibility service *at all* - verifiable with `aapt`. Enhanced flavour subscribes only to focus and window-state events, never `TYPE_VIEW_TEXT_CHANGED`; reads only field type flags, never `node.text`; no node-tree traversal; nothing from an event is logged, stored or transmitted. | An accessibility service with `canRetrieveWindowContent` *could* read most on-screen text. We do not, but the user grants a capability broader than the use. This is stated plainly at the consent screen. Users who reject the trade-off should install the safe APK. |
| 3 | **Accessibility abuse by a different malicious app** | Low | Critical | Outside our control. | Not mitigable by us. Noted so users understand the permission class they are being asked about. |
| 4 | **Clipboard snooping by another app** | Medium | High | Copy is explicit. Auto-clear defaults to 1 minute. Clip marked `EXTRA_IS_SENSITIVE` (API 33+) to suppress previews and cross-device sync. Android 10+ blocks background clipboard reads. | The app the user pastes into sees the text, by definition. A foreground app can read the clipboard while focused. Auto-clear narrows the window; it does not close it. |
| 5 | **We clobber the user's clipboard** | Medium | Low | Auto-clear verifies the clipboard still contains exactly what we wrote before clearing. | None material. |
| 6 | **Clipboard history accumulates dictated text** | Medium | Medium | We keep no clipboard history and have no clipboard *read* path in the codebase. | OS-level or third-party clipboard managers may retain it. Outside our control; documented. |
| 7 | **Dictating into a password field** | Medium | High | Safe Mode never writes to other apps' fields, so the risk does not arise. Flow Mode blocks on `isPassword`, password `InputType` variants, and re-checks immediately before insertion, since focus can move between approval and write. | Detection is only as good as the app's own flags. An app collecting a password in an unflagged field defeats it. **Stated, not papered over.** |
| 8 | **Dictating into a PIN / OTP field** | Medium | High | Numeric-password `InputType`, plus hint-text heuristics for OTP/CVV/2FA phrasing, matched on word boundaries. | Heuristic. An OTP box with no flags and a hint like "Code" is not reliably detectable. |
| 9 | **Operating inside a banking / brokerage / payment app** | Medium | High | Sensitive Apps Protection on by default: curated package denylist, whole-segment token heuristics, and a user-editable list. All local; no package list is transmitted. | **Cannot be complete.** No Android API classifies apps by sector; `ApplicationInfo.category` is self-declared and usually unset. The user-editable list is the only mechanism that can be exhaustive. |
| 10 | **Operating inside a password manager** | Low | Critical | Curated denylist covers the common managers; same limits as #9. | Same as #9. |
| 11 | **Microphone abuse / unintended recording** | Low | High | Recording starts only from an explicit user action. Safe Mode has no foreground-service permission, so it *cannot* record while the app is backgrounded - a structural limit, not a policy. Android's mic indicator is always shown. | Enhanced flavour can record with the app backgrounded, which is the point of Flow Mode; it must show a persistent notification. |
| 12 | **Raw audio retained on disk** | Low | High | No audio is ever written to disk. Retention setting defaults off; the platform recogniser path never exposes raw audio to us. | None for the implemented paths. A future local-ASR path must maintain this. |
| 13 | **Interim transcription leaking to another app** | Medium | High | Partials exist only in the app's own memory and are rendered only in our UI. Nothing is written outward before an explicit tap. Enforced by structure: the only outward calls are `copyToClipboard` and `commitInsert`, both tap-only. Covered by `CommandRouterTest`. | None known. |
| 14 | **A voice command silently exfiltrates the draft** | Medium | High | INSERT, COPY and CANCEL always require a tap, at every confidence level. Unit-tested exhaustively across every accepted phrasing. | A user who taps without reading has approved it. That is consent, not a bug. |
| 15 | **Ordinary speech misread as a command** | Medium | Medium | Two-word reserved phrase, segment-boundary requirement for free-form instructions, confidence tiers, uncertain input preserved as text. Zero false activations across the 15-utterance ordinary-speech corpus. | Corpus is small and text-level; it does not model ASR mishearing. See `TEST_RESULTS.md`. |
| 16 | **Network interception of BYOK traffic** | Low | High | HTTPS only; system trust store; no custom trust configuration and no cleartext traffic permitted. | A device with an attacker-installed root CA can intercept. We do not pin certificates - pinning would break corporate proxies and is not obviously net-positive here. |
| 17 | **Dictated text in logs** | Medium | High | `SafeLog` has **no API that accepts free user text**. Fields are scrubbed and truncated; exception *messages* are dropped, only type names kept. In release the sink is null. Unit-tested. | A future contributor could call `android.util.Log` directly. Mitigation is review plus the absence of a convenient unsafe path. |
| 18 | **Dictated text in crash reports** | Low | High | No crash-reporting SDK is integrated. Setting defaults off. | If one is added later, it must scrub via `Redaction`. |
| 19 | **Dictated text or keys in Android backup** | Medium | High | `backup_rules.xml` and `data_extraction_rules.xml` exclude credentials, history, scratchpad and models, for both cloud backup and device transfer. | Exclusions are path-based; a new persistence path must be added to both files. |
| 20 | **API key extraction from the device** | Low | High | Keystore-generated AES-GCM key, hardware-backed where available; ciphertext only in app storage; excluded from backup; deleting the last credential deletes the key. | **An attacker with code execution as this app can ask the Keystore to decrypt, exactly as we do.** Hardware backing protects key material, not plaintext the app is entitled to obtain. See the closing section. |
| 21 | **API key in source, resources or build config** | Low | High | No key is compiled in. `defaultConfig` injects no secrets. BYOK is entered at runtime. | None. |
| 22 | **Malicious intent to an exported component** | Medium | Medium | Only two exported components (see `PERMISSIONS.md`). `ProcessTextActivity` treats incoming text strictly as content: length-bounded, seeded as a manual edit, and **never** run through the command parser - so a hostile caller cannot embed the activation phrase to make us act. | An app can open our composer with text. It cannot read our state or make us commit anything. |
| 23 | **Model file tampering / supply chain** | Low | Critical | SHA-256 verified before load; size checked first; catalogue compiled in, never fetched, so an endpoint cannot substitute both file and hash; HTTPS enforced; runtime recorded per model so a file cannot be loaded by the wrong engine. **Downloads are refused outright while a checksum is unpinned** - which is the state of every entry in this build. | A compromised publisher account could publish a bad file *and* we would pin its hash at release time. Reproducible-build verification would be the next step. |
| 24 | **Malicious native library disguised as a model** | Low | Critical | Models live in app-private storage, are never marked executable, and the runtime field constrains which engine may open a file. | Depends on the eventual ASR runtime treating input as data. Not yet exercised - no runtime is bundled. |
| 25 | **Third-party dependency compromise** | Low | High | Dependency surface is deliberately small: AndroidX/Compose, coroutines. No analytics, ad, crash or networking SDK. Networking uses `HttpURLConnection` from the platform. | Transitive AndroidX surface remains. Gradle verification metadata would be the next step. |
| 26 | **Update supply-chain compromise** | Low | High | Built in CI from a public workflow; APK SHA-256 printed in the build summary and published with the artifact. | Users must actually check the checksum. Instructions are in `README.md`. |
| 27 | **Overlay abuse (tapjacking) against us** | Low | Medium | The bubble never displays draft text and never carries a commit action - approval happens in a normal activity window. | A hostile overlay could cover our Insert button. Standard Android exposure; not specific to us. |
| 28 | **Our overlay abusing others** | Low | Medium | Overlay exists only in the enhanced flavour, requires explicit permission, is `FLAG_NOT_FOCUSABLE`, and shows only a mic handle. | User can revoke the permission; Safe Mode is unaffected. |
| 29 | **Cloud fallback leaking audio after a local failure** | Medium | Critical | Automatic fallback defaults **off**. A failing local backend produces an error, never a silent switch. Providers are rebuilt per dictation so a stale cloud provider cannot outlive a switch to local. | User can enable fallback; the pipeline banner then shows data leaves the device. |
| 30 | **User believes "local" when it is not** | High | High | `ProcessingLocation.UNKNOWN` is a first-class value grouped with `OFF_DEVICE` for every privacy decision. The default `SpeechRecognizer` reports UNKNOWN, never "offline", because Android exposes no way to tell. Unit-tested. | The user must read the banner. It is always visible and not collapsible. |

---

## Residual risk that cannot be engineered away

Three things are worth stating in plain terms rather than burying in a table.

**1. A long-lived API credential on a user device is extractable by a
sufficiently privileged attacker.** The Android Keystore means the key material
never leaves secure hardware, and that genuinely defeats casual extraction and
offline attacks on a stolen device. It does not defeat an attacker running code
as this app, because such an attacker can simply request decryption. No Android
API changes this. The mitigations that actually matter are architectural: BYOK
is optional and off by default, the app is fully functional without it, and
users should scope and rotate keys. A user-controlled relay - which keeps the
key off the device entirely - is the stronger option and is described in
`ARCHITECTURE.md`.

**2. Sensitive-field and sensitive-app detection are heuristics, and heuristics
fail.** Android provides no reliable universal signal for either. The honest
mitigation is not better heuristics but a different architecture: **in Safe
Mode, Voice Composer never reads or writes another app's fields at all**, so
classification cannot fail dangerously because it is never load-bearing. Flow
Mode trades that guarantee for convenience, which is exactly why it is opt-in,
in a separate APK, behind an explicit consent screen.

**3. Nothing here protects a compromised device.** Root access, a hostile
accessibility service, or a malicious IME all defeat app-level controls. The
threat model assumes an intact Android security model.

---

## What we deliberately did not build

Recorded because omissions are design decisions:

- **No token extraction, traffic interception, or client impersonation** of any
  third-party app. Not attempted; see `FEASIBILITY.md`.
- **No certificate pinning.** It would break corporate proxies and, given the
  user supplies their own key and can revoke it, is not clearly net-positive.
- **No clipboard reading.** Not "we promise not to" - there is no read path.
- **No analytics or crash SDK.** Nothing to scrub is better than scrubbing.
- **No `QUERY_ALL_PACKAGES`.** Sensitive-app checks use the foreground package
  name we already receive; we never enumerate installed apps.
