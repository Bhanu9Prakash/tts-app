# Safe Mode vs Enhanced Flow Mode

Two builds, two trade-offs. Safe is the default and the security baseline.

---

## Comparison

| Feature | Safe Mode | Enhanced Flow Mode |
|---|---|---|
| Microphone dictation | Yes | Yes |
| Floating bubble | No | Yes (opt-in) |
| Automatic bubble on text focus | No | Yes (opt-in) |
| Accessibility permission required | **No — the service is not in the APK** | Yes, for Flow Mode |
| Can technically inspect other apps' UI text | **No — no such capability exists in the build** | **Yes** — see below |
| Direct insertion into a focused field | No | Yes, after approval |
| Text replacement via selection menu | Yes (`ACTION_PROCESS_TEXT`) | Yes |
| Explicit Copy / Paste | Yes | Yes |
| Password-field protection | Not applicable — never touches other apps' fields | Heuristic; blocks on password flags |
| Banking-app protection | Not applicable — never activates in other apps | Denylist + heuristics + user list |
| Background dictation | **No** — no foreground-service permission | Yes |
| Works offline | Yes | Yes |
| Preview required before commit | Yes | Yes |
| Total permissions | **3** | 7 |

---

## The row that matters

**"Can technically inspect other apps' UI text"** is the honest reason the split
exists.

Flow Mode's accessibility service needs `canRetrieveWindowContent` in order to
call `ACTION_SET_TEXT` — that is the only documented way to insert text into
another app's field. A service holding that capability *could* read the text of
most on-screen views in most apps.

This implementation does not. It subscribes only to focus and window-state
events, never to `TYPE_VIEW_TEXT_CHANGED`; it reads a focused field's type flags
but never `node.text`; it does no node-tree traversal; and nothing derived from
an accessibility event is logged, stored or transmitted.

But **the capability is broader than the use**, and no amount of careful coding
changes what the permission grants. `PERMISSIONS.md` states this in the same
terms.

The safe flavour resolves it differently: there is no accessibility component in
the APK, so there is nothing to grant. That is a structural guarantee you can
check with `aapt2` rather than a behavioural one you have to trust:

```bash
aapt2 dump xmltree --file AndroidManifest.xml voice-composer-safe.apk \
  | grep -i "accessibility\|SYSTEM_ALERT_WINDOW"
# prints nothing
```

CI runs the same check on every build and fails if it ever regresses.

---

## What Safe Mode gives up

Being straight about the cost, since the recommendation is to use it:

1. **No bubble.** Activation is the Quick Settings tile or the launcher icon.
2. **No direct insertion into an arbitrary field.** Output is Copy, or text
   replacement when you have selected text and chosen Voice Composer from the
   selection menu.
3. **No background dictation.** Recording stops if you leave the app. This is
   structural — without the foreground-service permission the app *cannot*
   record in the background, which is also why that permission is absent.

For the intended workflow — think, dictate, shape, review, paste — this costs
one paste.

---

## Choosing

**Use Safe Mode if** you want the smallest possible permission surface, you are
comfortable pasting, or you would rather verify a guarantee than trust an
implementation.

**Use Enhanced Flow Mode if** you dictate into text fields constantly and the
extra tap is a real cost to you, and you accept that you are granting a
capability broader than the feature uses.

Flow Mode is off by default even in the enhanced build. Turning it on takes
three deliberate actions: the Settings toggle, the accessibility grant in system
settings, and the overlay grant.

You can install both APKs side by side — they have different application IDs.
