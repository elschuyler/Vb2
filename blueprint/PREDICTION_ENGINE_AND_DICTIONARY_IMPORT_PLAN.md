# Prediction Engine, Bilingual Orchestration & HeliBoard Import Master Plan

This document is the definitive architectural master plan for integrating the native HeliBoard scoring engine (`Suggest.kt`, `DictionaryFacilitatorImpl.kt`, `WordComposer.java`, `com_android_inputmethod_latin_BinaryDictionary.cpp`), Bilingual Orchestration (English + French), the 3 Engine Modes, the Unified Privacy Vault, the Suggestion Bar Canvas lifecycle, HeliBoard ZIP Backup Ingestion, and the GitHub Actions CI pipeline.

---

## 1. Architectural Philosophy & Zero-Rewrite Guarantee

1. **Direct Suggest Hook**: The engine bridge (`TextEngineBridge`) connects directly to `Suggest.getSuggestedWords()`. We do NOT re-implement or rewrite the 12+ years of fine-tuned scoring, Euclidean touch-proximity calculations, and Damerau-Levenshtein edit-distance heuristics in Kotlin.
2. **Native Memory-Mapped Storage (`mmap`)**: Main binary dictionaries (`main_en.dict`, `main_fr.dict`) are accessed via zero-heap Linux `mmap()`, keeping the JVM heap footprint negligible on modern Android devices.
3. **Strict Zero-PII Hygiene**: All operations, dictionary ingestion, and fallback paths link to `LogKeeper` with zero keystroke, zero credential, and zero PII logging.
4. **Sensitive Field Hygiene**: Password inputs (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`), numeric fields (`TYPE_CLASS_NUMBER`, `PHONE`), and terminal/monospace editors completely bypass the dictionary engine (zero queries, zero background threads, zero learning).

---

## 2. High-Level Dataflow

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   VIANBOARD ENGINE DATAFLOW                                      │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                     TOUCH / KEY EVENT                                            │
│                                             │                                                    │
│                                             ▼                                                    │
│                             [ VianKeyboardView (2D Canvas) ]                                     │
│                     (Captures touch x, y coordinates & KeyData codes)                            │
│                                             │                                                    │
│                                             ▼                                                    │
│                                  [ TextEngineBridge ]                                            │
│              • Tracks WordComposer & Local Edit Epoch / Generation Counter                       │
│              • Inspects EditorInfo (Bypasses Passwords / Numbers)                                │
│              • Dispatches to Suggest.getSuggestedWords() asynchronously                          │
│                                             │                                                    │
│                        ┌────────────────────┴────────────────────┐                               │
│                        ▼                                         ▼                               │
│            [ Primary Dict: English ]                 [ Secondary Dict: French ]                  │
│            • Locale("en", "US") mmap                 • Locale("fr", "FR") mmap                   │
│            • Base Weight: 1.0x                       • Base Weight: 0.85x (Dynamic boost)        │
│            • Active / Always Ready                   • Dormant on IME close / On-demand          │
│                        │                                         │                               │
│                        ├────────────────────┬────────────────────┤                               │
│                        ▼                    ▼                    ▼                               │
│                 [ UserHistory ]       [ UserBinaryDict ]   [ Privacy Vault ]                     │
│                 (Learned n-grams &    (Personal words &    (Masked privacy pills:                │
│                  dynamic unlearning)   shortcuts)           123***NY / 🔒 Secret)                │
│                        │                    │                    │                               │
│                        └────────────────────┼────────────────────┘                               │
│                                             ▼                                                    │
│                                [ Suggestion Bar on Canvas ]                                      │
│                                                                                                  │
│                 Slot 0 (Left)          Slot 1 (Center)             Slot 2 (Right)                │
│             ┌────────────────────┐ ┌────────────────────┐ ┌───────────────────────────┐          │
│             │  Raw / Fallback    │ │ Bold Auto-Correct  │ │  Next-Word / Prediction   │          │
│             └────────────────────┘ └────────────────────┘ └───────────────────────────┘          │
│                        │                      │                         │                        │
│                        ▼                      ▼                         ▼                        │
│                 [ Single Tap ]: Commits text with auto-space & updates UserHistory               │
│                 [ Long Press (400ms) ]:                                                          │
│                   • Learned/User Word  ➔ "Delete / Purge" (UserHistory / UserBinaryDict)         │
│                   • Built-in Word      ➔ "Demote" (Dynamic frequency penalty / unlearning)       │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. The 7 Implementation Phases

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Phase 1: Core Engine Bridge, Bilingual Orchestration (En + Fr) & Canvas Wiring                   │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 2: HeliBoard Legacy Bug Fix Toggles (Settings Page: Text Engine)                          │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 3: Suggestion Bar Long-Press Popup (Delete vs Demote Scoring Penalty)                     │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 4: Three Text Engine Modes (Normal / Lite / Bare Bones) & Auxiliary Dict Cleanup          │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 5: Privacy Vault Unified as Partition of Personal Dictionary                              │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 6: Consolidated HeliBoard ZIP Backup Ingestion & LogKeeper Instrumentation                │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│  Phase 7: GitHub Actions CI Native Pipeline & Heavy Asset Automation                             │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### Phase 1: Core Engine Bridge, Bilingual Orchestration (En + Fr) & Canvas Wiring

- **Objective**: Establish the live connection between `VianBoardService`, `VianKeyboardView` (Canvas), and the native HeliBoard scoring engine (`Suggest.kt` and `DictionaryFacilitatorImpl.kt`).
- **Core Components**:
  1. `TextEngineBridge.kt`:
     - Holds references to `DictionaryFacilitatorImpl`, `Suggest`, and `WordComposer`.
     - Directly calls `Suggest.getSuggestedWords()`.
     - Filters out sensitive fields: checks `EditorInfo.inputType` against `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, and `TYPE_CLASS_NUMBER`. If matched, turns off composition and queries.
  2. **Bilingual 3-Way Language System & Spacebar Switcher**:
     - Mode 1: English Only (`en-US`).
     - Mode 2: French Only (`fr-FR`).
     - Mode 3: Bilingual (English Main + French Secondary).
     - **Spacebar Long-Press**: Triggers a fast modal/popup switcher to cycle between En, Fr, and En+Fr.
     - **Dormancy Rule**: When the keyboard closes or is minimized, the secondary language dictionary structures enter a dormant state; reopening defaults to clean English main, bringing French back on-demand when French characters/stems are typed.
  3. **Canvas Suggestion Bar Wiring**:
     - Binds `KeyboardLayout.kt` toolbar suggestion slots (`KeyType.SUGGESTION`, codes `-200`, `-201`, `-202`) to the top 3 candidates.
     - Slot 0: Raw typed string / fallback.
     - Slot 1: Auto-correction candidate (bold typeface).
     - Slot 2: Alternative or next-word prediction.
  4. **Composing Lifecycle & Word Resumption**:
     - Manages active underlining via `ic.setComposingText()`.
     - Tapping a slot calls `ic.commitText()` with automatic spacing and registers the committed pair in `UserHistoryDictionary`.
     - Implements word resumption state: backspacing into a committed word re-opens composition and re-evaluates candidates.

---

### Phase 2: HeliBoard Legacy Bug Fix Toggles (Settings Page: Text Engine)

- **Objective**: Fix the three notorious HeliBoard bugs and expose them as configurable toggles in a new "Text Engine" settings subpage (defaulted to ON).
- **Core Fixes**:
  1. **Bug A: Pasting/Appending Duplication (`hel` + tap `hello` ➔ `helhello`)**:
     - Root Cause: Inconsistent composing span lifecycle during suggestion tap or focus shift.
     - Fix: Atomic replacement protocol. If the target editor dropped the composing span, the engine calculates the expected composing length $L$, wraps the operation in `ic.beginBatchEdit()`, executes `ic.deleteSurroundingText(L, 0)` followed immediately by `ic.commitText(word, 1)`, and closes with `ic.endBatchEdit()`.
  2. **Bug B: Cursor Jumping Backward into Middle of Word**:
     - Root Cause: Asynchronous race condition where stale `onUpdateSelection` events rewind `WordComposer`'s cursor position during fast typing.
     - Fix: Atomic `localEditGeneration: Long` token. Incremented on every user keystroke, delete, or commit. Incoming `onUpdateSelection` events reporting stale cursor positions from older generations are ignored.
  3. **Bug C: Google AI Studio / Web DOM Typing Latency & Freezing**:
     - Root Cause: Web browsers (Chromium/Monaco/CodeMirror) dispatching full JavaScript DOM re-renders (`beforeinput`, `input`) on every composing character.
     - Fix:
       - Batch Edit Aggregation: All composition updates are strictly enclosed in `ic.beginBatchEdit()` ... `ic.endBatchEdit()`.
       - Lightweight Web Mode: If the target application is a web browser (Chrome, Firefox, Brave, etc.) or webview, suppresses redundant span style thrashing and stream-lines IPC calls.
  4. **Settings UI**:
     - Create `TextEngineSettingsActivity.kt` accessible from `SettingsActivity.kt`.
     - Toggle 1: "Atomic Word Replacement (Fix Duplication)" [Default: ON]
     - Toggle 2: "Cursor Sync Guard (Fix Cursor Jumping)" [Default: ON]
     - Toggle 3: "Web Editor Performance Mode (Fix Browser Lag)" [Default: ON]

---

### Phase 3: Suggestion Bar Long-Press Popup (Delete vs Demote Scoring Penalty)

- **Objective**: Implement on-canvas dictionary management via 400ms long-press on suggestion slots.
- **Workflow & Rules**:
  1. **Touch Detector**: `VianKeyboardView` detects long-press ($\ge 400\text{ms}$) on any `KeyType.SUGGESTION` slot.
  2. **Candidate Inspection**:
     - **Personal / Learned Word** (from `UserHistoryDictionary` or `UserBinaryDictionary`):
       - UI: Popup displays **"Delete / Purge"** (Trash icon).
       - Action: Permanently removes the unigram entry from `UserHistoryDictionary` and `UserBinaryDictionary`.
     - **Built-in Dictionary Word** (from `main_en.dict` or `main_fr.dict`):
       - UI: Popup displays **"Demote"** (Thumbs Down / Arrow Down icon).
       - Action: Does NOT blacklist the word. Instead, it applies a **frequency/scoring penalty** (lowering the word's unigram score in the unlearning layer of `UserHistoryDictionary`). The word will only reappear if the user explicitly types its exact prefix and no other words match.
  3. **Visual Feedback**: The long-press popup animates above the slot; upon selection, the candidate is immediately removed from the canvas strip and replaced with the next candidate.

---

### Phase 4: Three Text Engine Modes & Auxiliary Dict Cleanup

- **Objective**: Provide granular performance profiles and eliminate unnecessary background auxiliary clutter.
- **Auxiliary Cleanup**:
  - `ContactsBinaryDictionary` and `EmojiDictionary` lookups are permanently removed or disabled from the suggestion pipeline by default.
  - Eliminates `READ_CONTACTS` runtime permission requests and prevents emojis from displacing textual word suggestions.
- **The Three Engine Modes**:
  1. **Normal Mode**:
     - Full spatial proximity matrix compute (`com_android_inputmethod_keyboard_ProximityInfo.cpp`).
     - Damerau-Levenshtein edit-distance heuristics.
     - Full N-gram cross-word prediction & personalized history learning.
     - Dual-language concurrent queries.
     - Auxiliary background tasks & dictionary caching.
  2. **Lite Mode**:
     - **Top 5–9 candidate early-exit trie traversal**.
     - Only 3 candidates surfaced to suggestion bar.
     - Retains full spatial proximity matrix compute (`ProximityInfo.cpp`).
     - Retains Damerau-Levenshtein edit-distance heuristics.
     - Retains full N-gram cross-word prediction & personalized history learning.
     - Retains dual-language concurrent queries.
     - Retains input type recognition (password, numeric, terminal detection).
     - Retains dynamic learning & unlearning engine.
     - Eliminates all other background services, heavy syncs, and auxiliary background I/O.
  3. **Bare Bones Mode**:
     - No auxiliary services. Zero background I/O.
     - Top 3–5 candidate early-exit trie traversal.
     - Exact keypress codes only (zero Euclidean spatial proximity compute).
     - Main dictionary only (prefix completion only, skips cross-word N-grams).
- **Settings UI**:
  - Radio selector in `TextEngineSettingsActivity.kt`: `Normal | Lite | Bare Bones`.

---

### Phase 5: Privacy Vault Unified as Partition of Personal Dictionary & Password Field Vault Pills

- **Objective**: Decouple the Personal Dictionary from Android's vulnerable system-wide `UserDictionary.Words` content provider, unify privacy expansions directly into internal sandboxed dictionary partitions without running secondary background services, and surface Security Vault autofill pills on password fields.
- **The Vulnerability & Decoupling Mandate**:
  - *Vulnerability in Stock HeliBoard*: `PersonalDictionaryScreen.kt` reads/writes directly to `android.provider.UserDictionary.Words.CONTENT_URI`. Any third-party app with `READ_USER_DICTIONARY` can dump user dictionary contents.
  - *The Decoupling Fix*: Complete decoupling from Android's system `UserDictionary`. All personal dictionary entries and privacy vault shortcuts are stored inside the app's sandboxed private storage (`UserBinaryDictionary` / encrypted app-private SQLite).
  - *Zero Background Service Guarantee*: No separate Service, foreground notification, or polling daemon is required. The dictionary partition runs entirely in-memory and on-demand within the active IME lifecycle (`TextEngineBridge` / `DictionaryFacilitatorImpl`).
- **Architecture & Dual Partitioning**:
  1. **Unified Sandboxed Storage Structure**:
     - `UserBinaryDictionary` / Personal Dictionary stores all user entries with an `is_vault: Boolean` metadata partition flag.
     - **Partition 0 (Standard Words)**: Plaintext abbreviations & user words (e.g., `omw` ➔ `on my way`). Surface as normal suggestion pills.
     - **Partition 1 (Privacy Vault Shortcuts)**: Sensitive data (e.g., `myaddr` ➔ `123 Broadway, NY`, `taxid` ➔ `987-65-4321`).
  2. **Masked Privacy Pills on Canvas**:
     - When a vault shortcut matches typed input, the suggestion bar renders a masked privacy pill:
       - Text masked: `123***NY` or `🔒 Home Address`.
  3. **Password Field Security Vault Pills (Native Autofill Experience)**:
     - When `TextEngineBridge.isPasswordInputType(editorInfo)` detects a password field (`TYPE_TEXT_VARIATION_PASSWORD`, `WEB_PASSWORD`, `VISIBLE_PASSWORD`):
       - Standard dictionary word predictions are automatically disabled to prevent keystroke learning and snooping.
       - The Suggestion Bar dynamically displays native **`[ 🔒 Security Vault ]`** pills (or matched account entries like `[ 🔑 Master Pass ]`, `[ 🔑 Pin ]`).
       - Operates locally on the VianBoard canvas without requiring the user to configure VianBoard as their system-wide Android Autofill Provider in OS settings.
  4. **Authentication Gate (`VianPatternUnlockView`)**:
     - Tapping a masked privacy pill or password field vault pill checks `VaultSessionManager`.
     - If authenticated (`VaultSessionManager.isPrivacyUnlocked()` or `isSecurityUnlocked()`): Immediately commits the decrypted plaintext credential via `InputConnection.commitText()`.
     - If locked: Smoothly opens the `VianPatternUnlockView` 3x3 pattern unlock modal directly in `inputViewContainer` without leaving the keyboard. Correct entry commits the text and starts the session countdown timer (3m for security, 5m for privacy).
  5. **Settings Management**:
     - `SecurityVaultSettingsActivity.kt` provides the dedicated UI to add, edit, or delete the Privacy Vault partition of the Personal Dictionary.

---

### Phase 6: Consolidated HeliBoard ZIP Backup Ingestion & LogKeeper Instrumentation

- **Objective**: Lossless backup and restore of user dictionaries, history, and preferences from legacy HeliBoard installations.
- **Workflow**:
  1. **Archive Ingestion**:
     - Users select a HeliBoard `.zip` archive via Android Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT`).
  2. **Parser & Restore Pipeline**:
     - Unpacks and restores:
       - User dictionary words & shortcuts into `UserBinaryDictionary`.
       - Learned N-gram frequencies into `UserHistoryDictionary`.
       - Demoted / unlearned word scores.
       - Keyboard preferences and layout settings.
  3. **Diagnostic Logging (`LogKeeper`)**:
     - Logs archive integrity check, number of words imported, and dictionary build status.
     - Strict zero-PII/zero-keystroke hygiene: No imported words, addresses, or file paths are ever recorded in the log files.

---

### Phase 7: GitHub Actions CI Native Pipeline & Heavy Asset Automation

- **Objective**: Automate NDK native library compilation and binary dictionary packaging on CI runners.
- **Specifications**:
  1. **Target ABI Restriction**:
     - Restrict native compilation in `build.gradle.kts` to `arm64-v8a` and `armeabi-v7a` (with `arm64-v8a` optimized for modern 64-bit devices).
     - Eliminates unnecessary x86/x86_64 bloat, drastically reducing APK size and build times.
  2. **CI Pipeline (`.github/workflows/build.yml`)**:
     - Compiles `libjni_latinime.so` using Android NDK.
     - Verifies reproducible build output.
     - Packages native binary dictionaries (`main_en.dict`, `main_fr.dict`) into assets.
     - Generates signed/unsigned release test APKs without exposing secrets.

---

## 4. Progress Tracking & Phase Status

| Phase | Description | Status | Verification Target |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Core Engine Bridge, Bilingual Orchestration (En + Fr) & Canvas Wiring | ✅ COMPLETE | Words display on canvas suggestion bar; typing commits with auto-space; bilingual switcher works. |
| **Phase 2** | HeliBoard Legacy Bug Fix Toggles (Settings Page: Text Engine) | 📋 PLANNED | Toggles in settings; no double pasting; no cursor jumping; smooth typing in Google AI Studio. |
| **Phase 3** | Suggestion Bar Long-Press Popup (Delete vs Demote Scoring Penalty) | 📋 PLANNED | Long-press on learned word deletes it; long-press on built-in word demotes score. |
| **Phase 4** | Three Text Engine Modes (Normal / Lite / Bare Bones) & Aux Cleanup | 📋 PLANNED | Lite mode operates with 5–9 candidate early exit; Bare Bones has zero background I/O. |
| **Phase 5** | Privacy Vault Unified as Partition of Personal Dictionary & Password Field Vault Pills | 📋 PLANNED | Decoupled from system UserDictionary; zero background service; vault words appear masked; password fields show vault pills; pattern unlock required before committing plaintext. |
| **Phase 6** | Consolidated HeliBoard ZIP Backup Ingestion & LogKeeper | 📋 PLANNED | HeliBoard backup zip restores user words and history; zero PII in LogKeeper. |
| **Phase 7** | GitHub Actions CI Native Pipeline & Heavy Asset Automation | 📋 PLANNED | arm64-v8a/armeabi-v7a NDK build passes on CI; small APK size. |
