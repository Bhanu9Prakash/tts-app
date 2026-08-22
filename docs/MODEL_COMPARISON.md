# Local model comparison

## Read this before the tables

**No figure in this document was measured by this project.**

The brief asked for benchmarks on real Android hardware: word error rate,
real-time factor, RAM, sustained battery, thermal throttling, accuracy on Indian
English, Hindi and Telugu code-switching, and stability across 30-second to
20-minute dictations.

None of that was possible. The environment that produced this source tree had no
Android device, no emulator, and no network access to the model hosts. Running
those benchmarks requires all three.

So this document does two things and is explicit about which is which:

- **Published figures** — numbers reported by the projects themselves or by
  third-party write-ups. Useful for selecting candidates. Not verification.
- **Structural analysis** — properties that follow from a model's architecture
  and are true regardless of hardware (streaming vs. non-streaming, language
  coverage, licence).

`TEST_RESULTS.md` lists what would have to be run to turn this into a real
evaluation.

---

## Candidates and why they were shortlisted

### Whisper (via whisper.cpp) — chosen as the primary family

**Structural properties (verifiable without a device):**
- Encoder-decoder over 30-second windows. **Not a streaming architecture** — it
  transcribes a completed chunk. Partial results during a long dictation require
  chunking, with the accuracy cost that implies at chunk boundaries.
- `small` and above are multilingual, which matters for the Hindi and Telugu
  code-switching the brief asks about. The `.en` variants are English-only.
- MIT licensed, weights and runtime.
- `.bin` model files are data consumed by a C++ runtime — no native library is
  downloaded, which matters for the supply-chain rules in `THREAT_MODEL.md` #24.

**Published figures:**

| Model | Download | Reported runtime RAM | Reported speed |
|---|---|---|---|
| tiny.en | ~75 MB (q5_1: ~32 MB) | ~390 MB | Redmi Note 12, tiny-q8_0: ~1.4× real-time |
| base.en | ~142 MB (q5_1: ~60 MB) | ~500 MB | Pixel 8, base-q4_0: ~1.2× real-time |
| small | ~466 MB (q5_1: ~190 MB) | ~1.0 GB | Galaxy S23, small-q4_0: ~0.9× real-time |
| medium / large | — | — | Generally considered too memory-hungry for phones |

Quantised variants (`q5_1`, `q4_0`) are what the catalogue points at: they cut
download and memory substantially at a modest accuracy cost, which is the right
trade on a phone.

### sherpa-onnx / Zipformer transducer — chosen as the streaming option

**Structural properties:**
- A transducer, so it **is** a streaming architecture: genuine partial results
  as the user speaks, which Whisper cannot provide natively.
- Apache-2.0.
- Ships ONNX model files plus a runtime.

**Published figures:**
- Pixel 6, streaming Zipformer with NNAPI: RTF ~0.035, ~352 MB RAM, ~2%
  battery/hour.
- iPhone 15 Pro, streaming Zipformer EN: RTF ~0.054, ~45 MB RAM.
- int8 quantisation: ~48% size reduction at comparable accuracy; int4: ~73%
  smaller with minor degradation for streaming ASR.

**Caveat:** RTF figures from a Pixel 6 with NNAPI tell you little about a
mid-range device with no NPU. This is exactly the gap real benchmarking would
close.

### Considered and not shortlisted

| Candidate | Why not |
|---|---|
| NVIDIA Parakeet | Strong accuracy, but Android deployment is not a maintained, documented path. Would need bespoke export work before it could be evaluated. |
| Moonshine | Promising for short-form on-device ASR; smaller ecosystem and less Android tooling than the two above. Worth revisiting. |
| Vosk | Mature Android support, but generally weaker accuracy and punctuation than Whisper or Zipformer for this use case. |
| Cloud-only ASR | Contradicts the product's primary tier. Available as Tier 3. |

---

## The catalogue shipped in this build

`model-manager/src/main/kotlin/dev/voicecomposer/models/CandidateModels.kt`

| Tier | Model | Download | Streaming | Languages |
|---|---|---|---|---|
| Fast | Whisper tiny.en (q5_1) | ~32 MB | No | English |
| Balanced | Whisper base.en (q5_1) | ~60 MB | No | English |
| High accuracy | Whisper small (q5_1) | ~190 MB | No | Multilingual (en, hi, te, …) |
| Streaming | Zipformer transducer EN | ~350 MB | **Yes** | English |

### Every entry is marked `UNPINNED`, and downloads are refused

This is the most important thing in this document.

A SHA-256 checksum is worth something only if it was computed from an artifact
the publisher actually published. This source tree was produced with no access
to the model hosts, so the honest options were:

1. ship checksums that had never been verified against a real download, or
2. ship none, and make the app refuse to download until they are pinned.

We chose (2). A plausible-looking but unverified hash is *worse* than an absent
one, because it looks like a guarantee.

`ModelDownloadGuard.check()` refuses any model whose checksum is `UNPINNED` or
malformed, **before any network request is made**. There is a test asserting
that every catalogue entry currently fails this check, and a second test
asserting that no entry carries a well-formed hash — which would fail the build
if someone pasted a plausible hash in later without verifying it.

To pin them, run `tools/pin-models.sh`, which downloads each artifact from its
canonical URL, prints the SHA-256, and rewrites the catalogue.

### The runtime is not bundled

Neither whisper.cpp nor sherpa-onnx is compiled into this build. The catalogue,
the verifier and the download guard are implemented and tested; the inference
engine is not integrated. Selecting "Local model" in Settings falls back to the
Android on-device recogniser rather than presenting a dead option.

See `LIMITATIONS.md`.

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
