# Permissions

Every permission the app requests, why it exists, and how to verify the list
yourself before installing.

---

## Safe flavour (`voice-composer-safe.apk`) — the default

**Three permissions. That is the entire list.**

| Permission | Why it exists | What happens without it |
|---|---|---|
| `RECORD_AUDIO` | Capturing dictation. The core function. | The app cannot dictate. Nothing else works. |
| `INTERNET` | Required **only** by optional subsystems: downloading a local model, and BYOK cloud providers. The fully local configuration makes no network calls. | Local configurations still work fully. BYOK and model download fail. |
| `ACCESS_NETWORK_STATE` | Checking connectivity before a cloud call, and honouring "use cloud only on Wi-Fi". | Cloud calls would fail less gracefully. |

### What the safe flavour deliberately does *not* request

| Not requested | Why it matters |
|---|---|
| `SYSTEM_ALERT_WINDOW` | No floating bubble. It cannot draw over other apps at all. |
| Any accessibility service | **No accessibility component exists in this APK.** It cannot be enabled, because there is nothing to enable. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Recording can only happen while a Voice Composer screen is in the foreground. This is a *structural* limit on background recording, not a policy promise. |
| `POST_NOTIFICATIONS` | Not needed, because there is no foreground service to make visible. |
| `QUERY_ALL_PACKAGES` | The app never enumerates installed apps. Sensitive-app checks use only the foreground package name it is already given. |
| `READ_/WRITE_EXTERNAL_STORAGE` | Models and all app data live in app-private storage. |
| `RECEIVE_BOOT_COMPLETED` | Nothing runs at boot. |

A note on `INTERNET`: Android has no way to declare "network access only if the
user opts in". The permission is either present or absent. It is present
because the optional subsystems need it, and the restraint is enforced in code
and stated here rather than claimed as a platform guarantee. If you want to be
certain no network traffic occurs, revoke network access for the app at the OS
level - the local configuration keeps working.

---

## Enhanced flavour (`voice-composer-enhanced.apk`) — opt-in

Everything above, plus:

| Permission | Why it exists |
|---|---|
| `SYSTEM_ALERT_WINDOW` | The floating microphone bubble. Requires the user to grant "Display over other apps". |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Flow Mode dictates while *another* app is in the foreground, which genuinely requires a foreground microphone service. |
| `POST_NOTIFICATIONS` | Makes that service visible. Android requires a foreground service to show a notification; this is a feature, not overhead. |
| `BIND_ACCESSIBILITY_SERVICE` (declared, granted by the user in system settings) | Detecting that an editable field took focus, and inserting approved text into it. |

### The accessibility permission, stated honestly

Flow Mode's accessibility service is scoped as narrowly as the feature allows:

**What it subscribes to** (`flow_accessibility_config.xml`):
- `typeViewFocused`, `typeViewAccessibilityFocused` — to know a field took focus.
- `typeWindowStateChanged`, `typeWindowsChanged` — to know which app is in front, so sensitive-app protection can hide the bubble.

**What it deliberately does not subscribe to, and the capability given up:**

| Not subscribed | What we give up | Why |
|---|---|---|
| `typeViewTextChanged` | Knowing what the user types in other apps | This is the event that would make the service a key logger. Bubble visibility does not need it. |
| `typeNotificationStateChanged` | Reading notification content | Never appropriate for this product. |
| `typeViewTextSelectionChanged`, `typeViewScrolled`, `typeViewClicked` | Finer interaction tracking | Not needed. |
| `canRequestFilterKeyEvents` | Intercepting key events | Would make the service a key logger. |
| `canPerformGestures` | Simulating taps and swipes | Not needed. |

**What it does read:** the focused node's *type flags* only — `isPassword`,
`inputType`, `isEditable`, and the static hint label used for OTP detection. It
does **not** read `node.text`, the field's actual contents. There is no
node-tree traversal. Nothing derived from an accessibility event is logged,
persisted, or transmitted; the only outputs are a visibility boolean and a
fixed reason code.

**What the permission could still do.** `canRetrieveWindowContent` is required
to perform `ACTION_SET_TEXT`, which is how approved text is inserted, so it
cannot be avoided in Flow Mode. An accessibility service holding it *could*
read the text of most on-screen views in most apps — including messages, and in
poorly-built apps, credentials.

This implementation does not, and the source is the whole of what it reads. But
**the user is granting a capability broader than the use**, and that is the
honest framing. Anyone unwilling to extend that trust should install the safe
APK, where no such component exists to grant it to.

Flow Mode is off by default even in the enhanced flavour. Enabling it requires
three separate deliberate actions: a Settings toggle, the accessibility grant in
system settings, and the overlay grant.

---

## Exported components audit

| Component | Type | Exported | Why | Protection | Abuse potential |
|---|---|---|---|---|---|
| `ui.ComposerActivity` | Activity | **Yes** | It is the launcher activity. | None needed — it opens the app's own UI with no input. | An app could launch our composer. It gains nothing: no state is readable and nothing commits without a tap. |
| `ui.ProcessTextActivity` | Activity | **Yes** | The system text-selection toolbar is the caller. This is required for `ACTION_PROCESS_TEXT`. | Input is bounded to 100,000 characters, treated strictly as content, seeded as a manual edit, and **never** parsed for commands — so a hostile caller cannot embed the activation phrase and make us act. | An app can pre-fill our composer with text. It cannot read our draft or cause a commit. |
| `integration.VoiceComposerTileService` | Service | **Yes** (required) | Quick Settings tiles must be exported for the system to bind. | `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` — only the system can bind. | None: the permission restricts the caller to the system. |
| `ui.SettingsActivity` | Activity | No | Internal. | Not exported. | None. |
| `flow.FlowAccessibilityService` *(enhanced only)* | Service | **Yes** (required) | Accessibility services must be exported for the system to bind. | `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` — only the system can bind, and only after the user grants it. | None from other apps. The risk is the permission itself, discussed above. |
| `flow.BubbleOverlayService` *(enhanced only)* | Service | No | Started only by our own components. | Not exported. | None. |

There are **no** exported `BroadcastReceiver`s and **no** `ContentProvider`s.
No transcription data, draft, or setting is reachable through any exported
component.

---

## Verifying the permission list yourself

Do not take this document's word for it. Check the APK.

### With `aapt2` (Android SDK build-tools)

```bash
aapt2 dump permissions voice-composer-safe.apk
```

### With `apkanalyzer` (Android SDK cmdline-tools)

```bash
apkanalyzer manifest permissions voice-composer-safe.apk
```

### With `aapt` (older SDKs)

```bash
aapt dump permissions voice-composer-safe.apk
```

### Android Studio

Build → Analyze APK… → select the APK → open `AndroidManifest.xml`.

### On a connected device, after installing

```bash
adb shell dumpsys package dev.voicecomposer.safe | grep -A 20 "requested permissions"
```

### Confirm the safe flavour really has no accessibility service

```bash
aapt2 dump xmltree --file AndroidManifest.xml voice-composer-safe.apk \
  | grep -i "accessibility\|SYSTEM_ALERT_WINDOW"
```

This should print nothing. **CI enforces the same two checks on every build**,
so a regression fails the build rather than shipping quietly — see
`.github/workflows/build.yml`.

### Expected output for the safe flavour

```
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
```

If you see anything else, do not trust the build.
