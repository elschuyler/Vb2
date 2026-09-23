# VIAN BOARD — PHASE IMPLEMENTATION PLAN

**Target Document:** `blueprint/PHASE_IMPLEMENTATION_PLAN.md`  
**Supercedes:** All previous informal plans  
**Strategy:** Small, easy-to-implement, meticulously verified sequential mini-phases starting with the **complete completion of the dictionary and prediction engine brain** before touching UI/card features.

---

## STRICT SCOPE EXCLUSIONS ACROSS ALL PHASES
- ❌ **NO Emoji Dictionaries & Emoji Search**: Kept strictly in the cold emoji palette view.
- ❌ **NO Contacts Dictionary & Address-Book Scraping**: Zero `READ_CONTACTS` permission, no background scraping.
- ❌ **NO Barebones Mode**: Only **Normal Mode** (default) and **Lite Mode**.
- ❌ **NO Security Vault in this Phase Plan**: Security Vault (KeePassDX engine) is deferred as specified.

---

## PHASE 1: DICTIONARY & PREDICTION ENGINE BRAIN COMPLETION (TOP PRIORITY)

*Objective:* Bring the native C++ LatinIME prediction engine to 100% operational maturity by connecting missing upstream components from the engine layer.

### 1.1 Proximity Grid Mapping (`ProximityInfo`)
- In `KeyboardLayout.kt`, extract the physical key bounding boxes `(x, y, width, height, charCode)` for the active layout.
- Generate a native `ProximityInfo` pointer using `ProximityInfo.setProximityInfoNative(...)`.
- Pass `nativeProximityInfo` to `Suggest.getSuggestedWords()` instead of `0L`, enabling 2D Euclidean spatial error correction across adjacent keys.
- Safely recycle and re-generate `ProximityInfo` when keyboard dimensions or orientations change.

### 1.2 N-Gram Context & Next-Word Prediction (`NgramContext`)
- Maintain an accurate `NgramContext` in `TextEngineBridge.kt` tracking the last 1st and 2nd committed words.
- Pass `prevWordCodePointArrays` and `isBeginningOfSentenceArray` through `Suggest.kt` into `BinaryDictionary.getSuggestionsNative()` and `getNgramProbabilityNative()`.
- On spacebar or punctuation, trigger native bigram/trigram next-word lookups so the C++ trie predicts the next word before keystrokes begin.

### 1.3 Native Traversal Session (`DicTraverseSession`)
- Connect `DicTraverseSession.kt` to maintain persistent native session memory during word composition instead of passing `traverseSession = 0L`.
- Initialize traverse session on new word start and reset on word commit/cursor jump.

### 1.4 Dynamic User Vocabulary Learning & Unlearning
- Complete `DictionaryFacilitatorImpl.addToUserHistory()` to record committed user words with timestamps and forgetting curves.
- Persist learned user words in an on-device local dynamic dictionary file (`user_history.dict`).
- Support word unlearning when words are explicitly deleted.

### 1.5 Dual-Language Scoring Arbitration (English + French)
- In Dual Language mode, evaluate both dictionaries and use `BinaryDictionaryUtils.calcNormalizedScore()` to mathematically score and rank candidates based on n-gram context and accents.

---

## PHASE 2: SUGGESTION BAR INTERACTION (DELETE & DEMOTE POPUP)

*Objective:* Fix the HeliBoard limitation by allowing direct management of candidates via long-press on the suggestion bar.

### 2.1 Suggestion Bar Long-Press Detection
- Add long-press touch listener on the 3 suggestion slots in `VianKeyboardView`.
- Position a floating context popup directly above the selected candidate.

### 2.2 Floating Context Actions
- **Similar Candidates**: Display related morphological variants.
- **Delete Button (`×` / Trash)**: Shown if the word originated from user-learned history, Personal Dictionary, or Privacy Vault. Tapping permanently purges it.
- **Demote / Downvote Button (`↓`)**: Shown if the word is from the system dictionary (English/French). Tapping records a frequency penalty in a local adjustments table, permanently demoting its rank so it stops hijacking the center auto-correct position.

---

## PHASE 3: LITE MODE, MEMORY TRIM & SPACEBAR BILINGUAL SWITCH

*Objective:* Implement lightweight resource preservation, OS memory safeguards, and quick bilingual toggling.

### 3.1 Spacebar Language Selection
- Long-pressing the spacebar opens the language selector (English, French, or Bilingual Mode).
- When a single language is active, long-pressing spacebar allows instant switching back to Bilingual Mode.

### 3.2 Lite Mode Toggle (Inside Text Engine Settings)
- Default: **Normal Mode**.
- In **Lite Mode**:
  - Both English and French dictionaries remain active.
  - Multi-word trigram predictions remain active.
  - Gesture / swipe typing processing is completely disabled.

### 3.3 Memory Trim Safeguard (Long Timer / Next Open)
- Listen to Android OS `onTrimMemory()` in `VianBoardService`.
- On low-memory signals, gracefully shed the French dictionary and retain English only.
- French does NOT reload immediately upon RAM recovery. It remains unloaded until either:
  1. A conservative long debounce timer expires, OR
  2. The next time the keyboard is opened (`onStartInputView`).
  3. The user forces reload via spacebar long-press.

### 3.4 Cold Surfaces Demotion
- Ensure Symbols (`?123`) and More Symbols (`=/<`) layouts are generated cold on-demand rather than pre-warmed in memory.

---

## PHASE 4: UNIFIED PARTITIONED CARD ENGINE (CLIPBOARD & PROMPT LIST)

*Objective:* Unify duplicate storage and view classes into a single lightweight partitioned engine while preserving the current visual card design.

### 4.1 Single Partitioned Data Store
- Merge `ClipboardStorage` and `QuickNotesStorage` into a single `TextCardStorage` partitioned by `EntryType` (`CLIPBOARD` vs `PROMPT`).
- "Move to Prompt List" is an instant atomic tag toggle in the same table (zero serialization roundtrips).

### 4.2 Unified Reusable Modal (`VianCardModalView`)
- Retain existing card elevation, rounded corners, search filter, and selection toolbar.
- **Clipboard Mode**:
  - 2-column grid.
  - Cards formatted to **4 lines max**.
  - Context popup actions: *Pin*, *Move to Prompt List*, *Delete*.
- **Prompt List Mode**:
  - 2-column grid.
  - Cards formatted to **2 lines max**.
  - Context popup actions: *Pin*, *Edit*, *Delete*.
  - **Special Add Card**: The very first card in Column 1 is a compact 1-line **Add Button Card** with a centered `+` icon.

---

## PHASE 5: PARTITIONED PERSONAL DICTIONARY & PRIVACY VAULT

*Objective:* Create a dual-partitioned personal dictionary with public system compatibility and a sandboxed, masked, timed-unlock privacy vault.

### 5.1 Partition 1: Normal Personal Dictionary (HeliBoard Compatible)
- Standard HeliBoard-style Settings screen with CRUD (Word/Phrase, Shortcut, Language, Weight).
- Compatible with the Android `UserDictionary` provider so system and external apps can read it.
- Expands shortcuts normally in the suggestion bar.

### 5.2 Partition 2: Privacy Vault (Sandboxed & Masked)
- 100% sandboxed internal SQLite (zero external app access).
- Configured in **Settings -> Security -> Privacy Vault** with identical CRUD schema.
- **Smart Masking**: Appears masked in the suggestion bar by default (e.g. `s***@gmail.com`, `v********8`).
- **Timed Session Unlock**: Unlocking via pattern opens a timed window (e.g. 1–5 min) where suggestions display in clear text for fast typing before auto-relocking.
- Auto-locks on timer expiry, keyboard dismissal, or manual lock.

### 5.3 Disguise Pattern Unlock Mode
- Configurable in Settings -> Security -> Pattern Unlock.
- Disguises the unlock gate as the normal QWERTY keyboard, with the top-right corner paste button acting as the disguised close/unlock action.

---

## VERIFICATION & BUILD DISCIPLINE
- Every phase must compile cleanly (`compile_applet`) and pass unit tests (`gradle :app:testDebugUnitTest`).
- Native binaries target strictly `arm64-v8a` and `armeabi-v7a` on GitHub Actions CI.
- Telemetry events for all new subsystems must log to `LogKeeper` under appropriate `LogTags` (`ENGINE`, `DICT`, `JNI`, `IME`).
