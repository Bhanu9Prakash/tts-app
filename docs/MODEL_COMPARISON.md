# Local model comparison

## Read this before the tables

**No performance figure in this document was measured by this project.**

The brief asked for benchmarks on real Android hardware: word error rate,
real-time factor, RAM, sustained battery, thermal throttling, accuracy on Indian
English, Hindi and Telugu code-switching, and stability across 30-second to
20-minute dictations.

None of that was possible. The environment that produced this source tree had no
Android device, no emulator with a microphone, and no network access to the
model hosts. Running those benchmarks requires all three.

So this document does two things and is explicit about which is which:

- **Published figures** — numbers reported by the projects themselves. Useful
  for selecting candidates. Not verification.
- **Structural analysis** — properties that follow from an engine's
  architecture and are true regardless of hardware (streaming vs. not, language
  coverage, licence, whether native code is downloaded at runtime).

`TEST_RESULTS.md` lists what would have to be run to turn this into a real
evaluation.

---

## The decision: Vosk

The engine actually integrated is **Vosk** (`com.alphacephei:vosk-android`,
Apache-2.0). The reasoning was structural rather than benchmark-driven, because
benchmarks were not available:

**1. It is the only maintained Android ASR runtime that publishes prebuilt
native libraries to Maven Central.** Everything else would require an NDK build
in this project's CI, or - far worse - downloading native code at runtime.
That second option is unacceptable here: `ModelInstaller` refuses to unpack
`.so` files precisely so that a "model" can never be native code. An engine
whose distribution model requires downloading binaries would have forced that
defence to be weakened.

**2. It streams.** Vosk is a Kaldi-style recogniser that emits partial results
while the user is still speaking. The product's whole interaction depends on
seeing the draft form as you talk. Whisper, by contrast, is an encoder-decoder
over 30-second windows: it transcribes a completed chunk, so partials require
chunking with an accuracy cost at the boundaries.

**3. It has the languages the brief actually asks about** — including a
dedicated **Indian English** model, in both small and large sizes, and Hindi.
For a product whose stated context is Indian English, an engine with an
accent-specific model matters more than a marginally better general-English
WER.

### What choosing Vosk gives up

Stated plainly, because it is a real trade:

- **Whisper is generally more accurate**, especially on hard audio, and is
  multilingual in one model. Vosk models are single-language.
- **No code-switching.** Vosk's per-language models cannot handle
  Hindi-inside-English or Telugu-inside-English, which the brief does ask for.
  Whisper's multilingual models handle it better. **No model in the catalogue
  claims to do this**, and `LIMITATIONS.md` says so.
- **No Telugu model** is offered.

If code-switching turns out to matter more than streaming, the
`TranscriptionProvider` interface is the seam to swap at: a whisper.cpp provider
would slot in beside the Vosk one without touching the composer, the command
router, or anything in `core`.

---

## The catalogue shipped in this build

`model-manager/src/main/kotlin/dev/voicecomposer/models/CandidateModels.kt`

| Tier | Model | Download | Streaming | Languages |
|---|---|---|---|---|
| Fast | `vosk-model-small-en-us-0.15` | ~40 MB | Yes | English (US) |
| Fast | `vosk-model-small-en-in-0.4` | ~36 MB | Yes | **English (Indian)** |
| High accuracy | `vosk-model-en-in-0.5` | ~1 GB | Yes | **English (Indian)** |
| Fast | `vosk-model-small-hi-0.22` | ~42 MB | Yes | Hindi |

The app recommends the Indian English model for an `en-IN` device locale and
the Hindi model for `hi`, falling back to US English.

**Published figures** for Vosk small models: roughly 40 MB on disk and a few
hundred MB of RAM while loaded, running faster than real time on modern phones.
Those are the project's own figures, not measurements taken here.

---

## Checksums: why none are pinned, and how that is enforced

This is the most important part of this document.

Every catalogue entry ships `sha256 = UNPINNED`, and `ModelDownloadGuard`
refuses to download any of them **before any network request is made**. A test
asserts this for every entry.

A checksum is worth something only if it was computed from an artifact the
publisher actually published. The model host was unreachable from the
environment this was written in, so the honest options were:

1. ship checksums that had never been verified against a real download, or
2. ship none, and make the app refuse to download until they are pinned.

We chose (2). A plausible-looking but unverified hash is *worse* than an absent
one, because it looks like a guarantee.

### The mechanism that fixes it

`.github/workflows/model-checksums.yml` runs `tools/verify-model-checksums.sh`,
which downloads each archive from its canonical URL and:

- **reports** the SHA-256 and real size for entries still marked `UNPINNED`;
- **asserts** the hash for entries already pinned, and **fails the job** if a
  published archive no longer matches.

So pinning the values does not just enable downloads — it converts that job from
a reporting tool into a standing supply-chain assertion, run on every push to
the catalogue and weekly on a schedule. An upstream substitution fails CI rather
than reaching users.

A second test asserts every checksum is either the `UNPINNED` sentinel or 64
lowercase hex characters, so a truncated or malformed hash pasted in later fails
the build rather than only surfacing as a failed download on someone's phone.

---

## What the app does with an archive once it has it

Worth stating alongside the checksum, because the checksum is not the only
defence — a compromised publisher account would give an attacker both the file
and the hash we pin against.

`ModelInstaller` therefore refuses, independently of any checksum:

- entries that resolve outside the target directory (zip slip), including deep
  traversal and absolute entry names;
- `.so`, `.dex`, `.apk`, `.jar`, `.sh`, `.dll`, `.dylib`, `.exe` entries — a
  speech model is data, and native code arriving in one means something is
  wrong;
- archives exceeding a total size or entry-count bound (zip bomb).

Extracted files are marked non-executable, and a failed extract deletes the
partial install. All of this is unit-tested, and the traversal and native-code
defences are additionally tested on a real Android filesystem.

---

## Candidates considered

| Engine | Streaming | Android distribution | Languages | Why not chosen |
|---|---|---|---|---|
| **Vosk** | **Yes** | **Prebuilt AAR on Maven Central** | Per-language, incl. Indian English + Hindi | **Chosen** |
| Whisper (whisper.cpp) | No — 30s windows | Needs an NDK build | Multilingual in one model, handles code-switching | No streaming; NDK build; still the best fallback if code-switching matters more |
| sherpa-onnx / Zipformer | Yes | No Maven Central artifact (checked: 404) | Mostly per-language | Would need an NDK build or runtime binary download |
| NVIDIA Parakeet | Yes | No maintained Android path | English | Would need bespoke export work before it could even be evaluated |
| Moonshine | Yes | Small ecosystem | English | Promising for short-form; less Android tooling. Worth revisiting |
| Cloud ASR | Yes | n/a | Many | Contradicts the primary tier. Available as Tier 3 |

**Published figures, for reference only** (from the projects themselves, not
measured here):

- Whisper on Android: `tiny.en` ~75 MB / ~390 MB RAM; `base.en` ~142 MB /
  ~500 MB RAM; `small` ~466 MB / ~1 GB RAM. Quantised variants cut this
  substantially. `medium` and above are generally too memory-hungry for phones.
- sherpa-onnx streaming Zipformer on a Pixel 6 with NNAPI: RTF ~0.035, ~352 MB
  RAM, ~2% battery/hour. Note how little that tells you about a mid-range device
  with no NPU — which is exactly the gap real benchmarking would close.

---

## Local refinement models

The same honesty applies, with an extra reason for caution.

### Gemini Nano via ML Kit GenAI — the right first choice

**Why it is preferred over bundling an LLM:** the model is provided and updated
by the OS through AICore, so the app downloads nothing, stores nothing, and
inherits the platform's own update path. For the short rewrites this product
needs — proofreading, tone changes, summarising — that is a much better fit than
shipping a multi-gigabyte model.

**Availability is the catch.** It requires AICore and a supported chipset
(optimised MediaTek Dimensity, Qualcomm Snapdragon, and Google Tensor
platforms). It is not present on most Android devices, so it can never be the
only refinement path.

**Not integrated in this build.** The ML Kit GenAI dependency is not added, and
`ProviderRegistry` returns null for the device-AI option. See `LIMITATIONS.md`.

### Bundled small LLMs (llama.cpp, LiteRT-LM, ExecuTorch, MLC)

Not shortlisted for this build, for a reason worth stating: a 1–3 GB download
and 1–3 GB of RAM to make a WhatsApp message more concise is a poor trade for
most users, and small models hallucinate in ways that are particularly bad here
— quietly changing what the user meant to say.

The product's answer is to need an LLM as rarely as possible: `CommandRouter`
sends structural edits to `DeterministicTransforms`, so bullets, cleanup,
deletions and line breaks never reach a model at all.

### The deterministic provider — always available

Not a model. Pure string functions. Instant, offline, free, zero hallucination
risk, and it handles bullets, punctuation, capitalisation, filler removal,
paragraph splitting and the delete commands.

It is the reason the product satisfies its minimum definition with no model, no
key and no network.

### The guardrail on any semantic rewrite

Whatever provides semantic refinement, its output passes
`MeaningPreservationCheck` before the user sees it. That rejects empty output,
rewrites that collapse a long draft to a fragment, runaway expansion, and leaked
assistant preambles ("Sure, here is the rewritten version:") — the
characteristic failure modes of small models. A rejected rewrite leaves the
draft untouched and tells the user.

---

## What real benchmarking would have to cover

For each candidate, on at least one low-end and one mid-range Android device:

**Performance:** download size, installed size, peak RAM, cold-start latency,
first-token latency, real-time factor, CPU load, whether NNAPI/GPU/NPU
acceleration engages, battery drain per hour of dictation, thermal throttling
under sustained load.

**Stability:** 30 s, 2 min, 5 min, 10 min and 20 min dictations; behaviour under
memory pressure; recovery from interruption by a phone call.

**Accuracy (WER against a reference transcript):** general English; Indian
English; technical vocabulary (Kotlin, API, Android, ONNX); Indian names;
financial terms (Groww, Zerodha, RSI, WTI, options); Hindi-in-English and
Telugu-in-English code-switching; punctuation quality; acronyms; numbers; URLs.

**Command recognition:** activation-phrase recognition rate and false-activation
rate *through the ASR*, in quiet and noisy conditions, across accents — the
thing the current text-level test corpus cannot measure.

Until that is done, the tables above are candidate selection, not evaluation.

---

## Sources

- [whisper.cpp — ggml-org](https://huggingface.co/ggerganov/whisper.cpp)
- [sherpa-onnx pretrained models — k2-fsa](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html)
- [Zipformer transducer models — k2-fsa](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)
- [Gemini Nano — Android Developers](https://developer.android.com/ai/gemini-nano)
- [Overview of the ML Kit GenAI APIs — Google for Developers](https://developers.google.com/ml-kit/genai)
- [The latest Gemini Nano with on-device ML Kit GenAI APIs — Android Developers Blog](https://android-developers.googleblog.com/2025/08/the-latest-gemini-nano-with-on-device-ml-kit-genai-apis.html)
