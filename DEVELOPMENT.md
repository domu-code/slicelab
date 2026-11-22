# BREAKFIELD design and implementation roadmap

## Product intent

BREAKFIELD is an APK-installable Android live sampler and generative music instrument. References include tactile multi-mode sample performers, simple touch loopers and Grids-style generative gates. The app must be original and independently editable, with an accessible performance screen and comprehensive editor for every instrument mode.

## Core building blocks

- **Transport:** shared high-resolution sample clock, BPM, swing, subdivisions, metronome mute/level, count-in, free/fixed recording, quantised recording actions, phrase sync, clock-division and future MIDI sync.
- **Sources:** multiple imported samples, phone microphone, Android-compatible USB audio interfaces, internal resampling of the master/track buses. Save as clips and route to the slicer.
- **Looper:** at least four simultaneous live-loop tracks with mute, solo, record, overdub, independent durations, layers, overdub feedback, selective undo and redo; eventually enable unlimited track presets/scene groups.
- **Sample model:** transient detection, manual/editable slice boundaries, start/end microfades, crossfades, tags (kick/snare/hat/bass/melodic), per-tag probability, mono/choke/poly policies and independent region envelopes.
- **Performance modes:** Slicer, Looper, Granular/Bow, Tape, Scratch, Arpeggiator, Keyboard and Loop Player. Audio samples and metadata remain shared between modes.
- **Granular bow:** X = start position, Y = microloop duration. Each touch owns an independent voice; exposed grain-size/density/randomness/position jitter, seam microfade, pitch, direction, pan, envelope, drift, scan, freeze and per-voice automation. Zero microfade must remain available for intentional clicks.
- **Generative drums:** interchangeable Grids-inspired interpolated rhythm maps, Euclidean, probability and manually programmed gate streams; 5-tag routing; accent/chaos/density/rhythm transform sources; saved variations and tempo-synced morphing.
- **Generative bass:** dedicated independent sound engine, root/scale, weighted note probabilities, pattern warp/shift, slide, duration and velocity, four recallable slots. Grids may drive gates and the scale-aware pitch stage; optionally follow or detach from the drum Grids map.
- **Mixing:** track-level inputs/outputs, independent sends, latency compensation, on-device loop-back calibration, mixer monitoring, effects and internal resampling.
- **Persistence:** non-destructive project format with source samples, tagged slices, source-device preferences (no absolute ephemeral IDs), pattern slots, recorded overdubs, automation and per-mode state.

## Design principles

1. Keep a record operation accessible in one or two touches, with advanced controls hidden in Edit panels.
2. Free-length loops must never be automatically rounded unless the user opts into snapping/quantisation.
3. Fixed-length capture should be measured in transport beats/bars and should close automatically.
4. A metronome click is separate from the clock: turning the click off never stops scheduling.
5. The input source may be preferred but Android determines actual routing. Display the actual routed device where possible.
6. Default microphone monitoring OFF. Warn when using speakers to avoid feedback.
7. Editable audio operations should be reversible, with zero-hard-cut always possible and nonzero fades configurable.
8. Do not use original Mutable Instruments Grids code or data without checking its licensing obligations.

## Milestones

- **0.1 alpha (this source archive):** Android UI, metronome, multi-WAV import, estimated slicing/tags/manual splits, Grids-style 3-lane trigger morphing, five tag probabilities, choke policies, scale-aware bass generator and four variation slots, 4 live mic/USB loop tracks, free/fixed lengths, overdub/undo, granular X/Y/microfade and randomness. This is an untested hardware prototype.
- **0.2:** Verify APK and audio routing across representative Android phones and USB interfaces; calibrate roundtrip latency, timestamp capture, correct drift, improve underrun handling, add track stop/launch quantisation and pre-roll, persistent projects and WAV export.
- **0.3:** Port rendering to C++/Oboe, introduce true independent pitch/time processing (WSOLA, phase-vocoder, granular stretch, resampling) and accurate multi-loop tempo matching; add per-mode deep editor with gesture/numeric editing and automation.
- **0.4:** All eight performance modes, recordable gestures, step editor, Grids morph automation, expanded bass synthesizer, MIDI, effects and multitrack scene/preset management.
- **0.5:** Export stems, package sample libraries/projects, 64-bit release signing, device QA and accessibility refinement.

## Adaptive layouts (v0.1.1)

- Auto / portrait lock / landscape lock stored in SharedPreferences.
- Portrait: thumb-reachable 2 × 3 bottom mode dock, waveform scaled to available
  screen height, full-width XY interaction, fixed transport and BPM slider,
  scrollable editing, two-column four-slot and four-track controls.
- Landscape: six-mode vertical rail and large scrollable instrument workspace.
- Handles orientation / screen-size changes without destroying the playback
  engine. Held gestures end safely during orientation change.
- Readable system text for long descriptions; PocketTracker 5×5 typography is
  retained for functional buttons, headings, transport and tempo.
