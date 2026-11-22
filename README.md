# BREAKFIELD · Android sampler + generative breakbeat instrument

**Status: 0.1 alpha source project. Not yet verified on a physical Android device.**

BREAKFIELD combines a multi-loop slice sampler, a Grids-inspired generative drum engine, a dedicated bass channel and a multi-touch granular microloop performer with free/fixed live-loop recording and overdubs.

## Build an installable APK

1. Open this folder in Android Studio (Android SDK 35 installed), or push it to a GitHub repository.
2. The included `.github/workflows/android-apk.yml` automatically builds a debug APK on pushes to `main`/`master` and on manual workflow dispatch.
3. Download the `BREAKFIELD-debug-apk` artifact from the successful GitHub Actions run and install `app-debug.apk` on your phone.
4. In Android Studio, use **Build > Build APK(s)** to compile locally.

The UI uses the user's PocketTracker R14 5×5 glyph patterns for headings, transport and buttons, rendered directly by Canvas (no font binary). Longer instructional text remains system typography for legibility. The PocketTracker source is GPL-3.0; assess combined-app licensing before distribution.

The source targets Android API 35 with minimum API 26. The project uses Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20. The GitHub Action installs Gradle itself, so no wrapper JAR is needed for CI.

## Adaptive portrait / landscape layout

BREAKFIELD supports a **portrait touch layout** with a fixed bottom six-mode dock,
full-width waveform, adaptive two-column variation and recording track selectors,
pinned play/click/tempo controls, and vertically scrolling detailed edits.
Landscape uses a left navigation rail and a wider performance workspace.

Use **ROTATE** in the header to switch and lock the other orientation. In
**SETTINGS → Screen orientation**, select Auto (follow device), Portrait (locked),
or Landscape (locked). The selection persists across app restarts. Rotation
rebuilds the layout without restarting the transport, audio engine or recorded
loops. Any currently held touch voices are released for safety during rotation.

## First run

- Press **PLAY** to start the shared transport. Toggle **CLICK** to hear the metronome.
- **LOOPS**: import multiple uncompressed PCM16 or float32 WAV clips. The app resamples imports to 48 kHz mono and estimates slice boundaries and rough tags. Touch the waveform to manually split a slice.
- **BOW**: hold one or more fingers on the waveform. X determines loop start; Y controls loop duration. Enable grain cloud for stochastic grains or disable for continuous microlooping. Use **Microfade** (0–100 ms) to smooth or deliberately expose clicks.
- **BEATS**: run a three-channel Grids-inspired pattern map, morphing with X/Y, chaos, density and swing. Tagged clips are triggered where available, with synthesised fallback drums.
- **BASS**: a scale-aware monophonic synth, four bass variation slots, probability, warp, shift and root/scale controls.
- **RECORD**: select phone mic or a recognised USB input, grant Android recording permission, choose one of four live tracks, free/fixed loop lengths, count-in and overdub controls. **SEND TO SLICER** imports the selected live track as a sample clip.

Use wired headphones for live input monitoring to avoid feedback. USB audio depends on the Android device, USB interface and the operating system's audio routing capabilities. Android's preferred-device selection is advisory: compare the **Input route** label with the chosen hardware after starting capture.

## Audio architecture

- 48 kHz stereo floating-point output via Android `AudioTrack` (streaming renderer in a dedicated `THREAD_PRIORITY_AUDIO` thread).
- 48 kHz mono microphone / USB input via `AudioRecord` with preference routing.
- Shared sample-clock step sequencer, metronome, count-in and fixed-loop durations.
- Four independently recordable live-loop tracks, each with overdub layers, track mute and undo; send an individual vocal track into the slicer for granular performance.
- Overdub undo removes the most recent committed overdub. Live tracks can run together as a layered vocal arrangement while you record into another track.
- A user-controlled microfade affects one-shot slices, grain envelopes and microloop seams. Zero milliseconds intentionally leaves hard boundaries.

## Known limitations of the alpha

This is a **functional first-pass source prototype**, not a polished production looper. It has not been compiled in this environment because the Android SDK/Gradle toolchain is not installed here. Hardware testing is essential. Specifically:

- No true pitch-independent multialgorithm time stretching yet. The advanced stretch algorithms and unified loop tempo matching are the next engine milestone.
- No sample/track project save/load, export WAV, MIDI output, proper effects or full editing history yet.
- Bass variation slots are in-memory only; the generator uses controlled stochastic note selection and a Grids-style X/Y gate map, not original Mutable Grids firmware or melody-training data.
- Input/output capture clocks can drift, and acoustic and USB interfaces introduce roundtrip latency. Loop-back calibration and recording latency compensation are planned.
- The audio engine is Kotlin-based for this alpha. The low-latency C++/Oboe renderer is planned for a later milestone after audio interaction tests.
- Recorded input and imported samples are mono, though the output mix and granular cloud are stereo.
- Count-in and fixed recording are scheduled by the output clock but input samples are buffered separately: sub-buffer alignment and drift still require measurement and correction on actual hardware.
- The app captures audio only while foregrounded and deliberately stops capture when backgrounded, avoiding microphone foreground-service complexity.

## Licensing

All audio algorithms and Grids-style patterns in this prototype are original code; no Mutable Instruments source or pattern table has been copied. Check GPL obligations if a later implementation incorporates Grids firmware or tables.
