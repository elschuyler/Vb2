# VianBoard Offline Voice Input (Whisper) Master Plan

---

## 1. Executive Summary & Goals
* **Target Architecture**: 100% offline, on-device voice typing engine integrated directly into VianBoard (`com.example.ime`), completely decoupled from Google Speech Services and legacy LatinIME.
* **Dual-Process Isolation**: Audio capture, VAD, and heavy native Whisper AI inference execute in an on-demand, isolated secondary process (`android:process=":voice"`). A native crash or out-of-memory (OOM) error in Whisper will never crash or disrupt the main keyboard process (`:root`).
* **Zero-Idle Resource Footprint**: The `:voice` process is only instantiated when the voice modal is opened. When closed, it unbinds, frees all native memory, and terminates. While voice typing is active, main keyboard touch handlers and candidate generators sleep.
* **Zero APK Bloat**: No heavy Whisper `.bin` model files are committed into the repository. Quantized GGML models (`.bin`) are imported via the Voice Settings SAF document picker and stored in `context.noBackupFilesDir/voice_models/`. Native `libwhisper.so` binaries are built via CMake on GitHub Actions runners.
* **Lightweight Word Improvement**: Phonetic replacements and user custom vocabulary stored in private `SharedPreferences` as JSON (replacing legacy SQLite/Room overhead).
* **Streaming Flow**: Recognized speech displays temporarily in a 2-line preview strip and is automatically committed into the active text field without requiring manual confirmation.

---

## 2. Process Separation & System Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        MAIN PROCESS (:root / VianBoardService)                         │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • VianBoardService: Main IME Canvas & Controller                                       │
│   └── Suspends/freezes canvas draw & touch processing while voice modal is mounted     │
│                                                                                        │
│ • VianVoiceModalView (Compact Height: ~160dp vs 260dp normal keyboard)                │
│   ├── Top: 2-Line Temporary Preview Strip (Glance words, then auto-commit)             │
│   ├── Middle:                                                                          │
│   │   ├── Mic Sensitivity Pill Button: Cycles [ 1x ] ➔ [ 2x ] ➔ [ 4x ] digital gain    │
│   │   └── Interactive Pulse Bar (State visualizer & Tap-to-Pause/Resume):              │
│   │       • 🟢 GREEN: Word caught / Active speech (RMS amplitude wave)                 │
│   │       • 🔵 BLUE:  Waiting / Listening idle                                         │
│   │       • 🔴 RED:   Error / Model missing / Mic permission denied                    │
│   │       • Tap Action: Toggle Pause ⮀ Resume                                          │
│   └── Bottom: Unified Tactile 4-Button Bar [ ABC ] [ Space ] [ ⌫ ] [ ↵ ]               │
│       • RULE: Tapping ANY bottom button immediately PAUSES the voice engine            │
│                                                                                        │
│ • VoiceInputConnection: Client IPC bridge with IBinder.DeathRecipient crash trap       │
│ • WordReplacementStore: Fast SharedPreferences JSON store with in-memory map           │
└────────────────────────────────────────┬───────────────────────────────────────────────┘
                                         │ Messenger IPC (Tokens & State)
                                         ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                      ISOLATED PROCESS (android:process=":voice")                       │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • VoiceInputService: On-Demand Lifecycle (Auto-terminates when unbonded or idle)       │
│ • AudioRecordPipeline: 16 kHz 16-bit mono PCM stream with 3-stage software gain        │
│ • EnergyVad: Dynamic noise-floor tracking & silence boundary detection                 │
│ • WhisperEngine: JNI wrapper calling libwhisper.so with greedy decoding + temperature  │
│ • VoiceModelManager: Model validator (0x67676d6c magic header check) in noBackupDir   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### A. Headless Engine Migration (Import from `vianboard` into `com.example.ime.voice`)
1. **`AudioRecordPipeline.kt`**:
   * Captures 16 kHz, 16-bit mono PCM audio from `AudioSource.VOICE_RECOGNITION`.
   * Digital gain multiplier (`1x`, `2x`, `4x`) with soft peak-clipping limiter.
   * Dispatches smoothed RMS amplitude updates every 40 ms for UI pulse rendering.
2. **`EnergyVad.kt`**:
   * Dynamic noise-floor tracking detecting start-of-speech and trailing silence endpoints.
3. **`VoiceIpcProtocol.kt`**:
   * Token-based `Messenger` protocol for inter-process communication:
   * Commands: `MSG_START_LISTENING`, `MSG_PAUSE`, `MSG_RESUME`, `MSG_STOP`, `MSG_SET_GAIN`, `MSG_SET_TEMPERATURE`.
   * Events: `MSG_RMS_UPDATE`, `MSG_SPEECH_STATE` (Caught / Waiting / Error), `MSG_TRANSCRIPTION_PREVIEW`, `MSG_TRANSCRIPTION_COMMIT`.
4. **`WhisperEngine.kt`**:
   * JNI interface into `libwhisper.so` with dynamic `audio_ctx` calculation (`(samples/160)+32`) for sub-300ms mobile ARM latency.
   * Supports configurable `temperature` (default `0.0` for deterministic fast decoding up to `0.6`).
   * Strips hallucination tags (`[BLANK_AUDIO]`, `[MUSIC]`, etc.).
5. **`VoiceModelManager.kt`**:
   * Stores imported GGML models in `context.noBackupFilesDir.absolutePath + "/voice_models/"`.
   * Binary magic header validation (`0x67676d6c`) to reject invalid/corrupted files.
6. **`VoiceInputService.kt`**:
   * Declared in `AndroidManifest.xml` with `android:process=":voice"`.
   * Completely on-demand: spins up on `bindService()`, terminates on `unbindService()` or 60s idle timeout.
7. **`VoiceInputConnection.kt`**:
   * Client bridge living in `com.example.ime.voice`.
   * Registers `IBinder.DeathRecipient`. If `:voice` crashes due to C++ SIGSEGV or OOM, the keyboard catches it gracefully, resets UI to Red Error state, and stays alive.

---

### B. Lightweight Word Improvement (`WordReplacementStore.kt`)
* **Storage**: Private `SharedPreferences` file (`vian_voice_replacements.xml`).
* **Format**: Serialized JSON array of `Pair<String, String>` (Target ➔ Replacement).
* **Speed**: Loaded into an in-memory `LinkedHashMap<String, String>` on modal open.
* **Execution**: Text passing from Whisper is scrubbed with whole-word regex matching before being dispatched to the preview and `InputConnection`.

---

### C. New Compact Voice Modal (`VianVoiceModalView.kt`)
* **Height**: Compact form factor (~160dp) set dynamically via `getVoiceModalHeight()` in `VianBoardService`.
* **Theme Harmony**: Uses authentic VianBoard canvas colors (`#ECEFF1`), squircle shapes, and system insets.
* **Layout Structure**:
  1. **Top (2-Line Temporary Preview Strip)**:
     * Shows recognized words in real-time as a temporary glance.
     * Automatically commits text to `InputConnection.commitText(word + " ", 1)` as speech pauses or segments finish.
     * Text rolls over/clears automatically.
  2. **Middle (Interactive Sensitivity & Pulse Controls)**:
     * **Sensitivity Pill Button**: Displays `[ 1x ]` / `[ 2x ]` / `[ 4x ]`. Tap cycles gain in real-time.
     * **Interactive Pulse Bar**:
       * 🟢 **Green**: Active speech / word caught (live pulsing wave animated by RMS level).
       * 🔵 **Blue**: Waiting for speech / listening idle.
       * 🔴 **Red**: Error / model missing / mic permission denied.
       * **Tap Action**: Tap pulse bar toggles between **Pause** (audio stopped, mic muted) and **Resume** (listening resumes).
  3. **Bottom (Unified Tactile 4-Button Bar)**:
     * Reuses `ModalBottomBarView`: `[ ABC ]` `[ Space ]` `[ ⌫ ]` `[ ↵ ]`.
     * **Strict Rule**: Tapping ANY of these 4 buttons immediately **pauses** voice input.
       * `[ ABC ]`: Closes modal and returns to main keyboard.
       * `[ Space ]`: Commits space to editor; voice paused.
       * `[ ⌫ ]`: Sends backspace to editor; voice paused.
       * `[ ↵ ]`: Sends Enter/Action to editor; closes modal.

---

### D. New Voice Settings Page (`VoiceInputSettingsActivity.kt`)
* Accessible from `SettingsActivity` ➔ **Voice Input**.
* **Three Focused Cards**:
  1. **Model Import**:
     * Current model status (Filename, file size in MB, or "No model imported").
     * `[ Import Model (.bin) ]` button launching Android SAF file picker (`OpenDocument`).
     * Validates GGML magic header and moves to `noBackupFilesDir`.
  2. **Whisper Temperature**:
     * Selector / slider (`0.0` to `0.6`).
     * Explanatory text: *"0.0 is fastest and most deterministic for typing."*
  3. **Word Improvement (Custom Vocabulary)**:
     * List of custom rules (e.g. `hear` ➔ `here`, custom names).
     * `[ + Add Replacement ]` dialog with *Original* and *Replacement* inputs.
     * Delete button for each rule.

---

### E. Standard Runtime Audio Permission (`VoicePermissionActivity.kt`)
* Transparent, zero-flicker activity triggered when mic permission is needed.
* Calls standard Android system dialog: `ActivityCompat.requestPermissions(..., RECORD_AUDIO)`.
* Returns result cleanly to `VianBoardService` to mount the voice modal once granted.

---

### F. CI/CD & Build Pipeline Integration
* **Local Workspace**: Zero binary bloat; no `.so` or `.bin` files committed to git.
* **GitHub Actions (`.github/workflows/build-apk.yml`)**:
  * Automatically downloads Android NDK and compiles `libwhisper.so` using CMake across `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
  * Drops compiled `.so` files into `app/src/main/jniLibs/` right before `gradle :app:assembleRelease`.

---

## 4. Implementation Phases

```
Phase 1: Headless Engine Migration & Core IPC
  ├── Extract & adapt AudioRecordPipeline, EnergyVad, WhisperEngine to com.example.ime.voice
  ├── Implement VoiceIpcProtocol & VoiceModelManager
  ├── Declare VoiceInputService with android:process=":voice" in AndroidManifest.xml
  └── Implement VoiceInputConnection with DeathRecipient in com.example.ime.voice

Phase 2: Word Replacement Store & Settings Page
  ├── Create WordReplacementStore backed by SharedPreferences JSON
  ├── Build VoiceInputSettingsActivity (Model card + Temperature + Word replacement CRUD)
  ├── Register VoiceInputSettingsActivity in AndroidManifest.xml and link in SettingsActivity
  └── Implement VoicePermissionActivity for standard Android permission dialog

Phase 3: Compact Modal UI & Dynamic Audio Visualizer
  ├── Build VianVoiceModalView with ~160dp height and KeyboardTheme styling
  ├── Implement 2-line temporary preview strip with automated streaming commit
  ├── Implement sensitivity pill button (1x / 2x / 4x cycle)
  ├── Implement interactive Canvas pulse bar (Green/Blue/Red states + tap-to-pause/resume)
  └── Wire ModalBottomBarView with immediate-pause on any button tap

Phase 4: VianBoardService Integration & Verification
  ├── Add showVoiceModal() and getVoiceModalHeight() in VianBoardService
  ├── Freeze/sleep main keyboard touch & canvas while voice modal is mounted
  ├── Wire ToolbarTool.VOICE and Comma popup "Voice" to launch Voice Modal
  ├── Verify compilation with compile_applet
  └── Create comprehensive on-device QA testing guide
```

---

## 5. Risk Mitigation & Edge Cases

| Potential Risk | Technical Consequence | Engineered Mitigation |
| :--- | :--- | :--- |
| **Native Whisper Crash** | SIGSEGV / SIGABRT crashes process | Isolated `:voice` process crashes; `IBinder.DeathRecipient` in `:root` catches it, sets UI to red, keyboard stays intact. |
| **Out-of-Memory (OOM)** | Large model exceeds mobile RAM | Model storage restricted to tiny/base quantized models; memory validated on import; `:voice` killed on modal dismiss. |
| **Zero-Model Fresh Install** | User opens mic without model | Modal displays informative warning card: *"No model loaded. Tap here to open Voice Settings."* |
| **Audio Collision / Incoming Call** | Mic contention with phone call | `AudioManager.OnAudioFocusChangeListener` pauses voice pipeline and transitions pulse bar to Blue/Amber paused state. |
| **Stale Voice Process** | Battery drain from background audio | `VoiceInputService` enforced 60s idle watchdog; unbind calls `stopSelf()` immediately freeing audio resources. |
