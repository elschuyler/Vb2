# VIAN BOARD — MASTER ARCHITECTURAL FITMENT PLAN

**Target Document:** `blueprint/MASTER_FITMENT_PLAN.md`  
**Supercedes:** All temporary/partial plans (`blueprint/ENGINE_BRAIN_FITMENT_PLAN.md`, `blueprint/MATURE_ENGINE_PLAN.md`)  
**Scope:** Complete specification covering the C++ Dictionary/Prediction Engine, Primary/Secondary Bilingual Mechanics, Unified Partitioned Card Engine (Clipboard & Prompts), Security & Privacy Vaults, Disguise Pattern Unlock, and Memory Tiering.

---

## 1. EXPLICIT SCOPE EXCLUSIONS (STRICTLY PROHIBITED)

To maintain absolute zero-lag keystroke latency, battery endurance, strict privacy, and compact APK size:
1. ❌ **NO Emoji Dictionaries or Emoji Search**: No unicode metadata scanning or keyword searching in the typing pipeline. Emoji remain strictly in the cold emoji palette view.
2. ❌ **NO Contacts Lookup / Address-Book Scraper**: No `READ_CONTACTS` permission, no background scraping, zero contact name ingestion.
3. ❌ **NO Barebones Mode**: Strictly dropped. The app provides **Normal Mode** (default) and **Lite Mode** as a toggle inside Text Engine Settings.
4. ❌ **NO Unsolicited Cloud / Telemetry**: 100% offline, local on-device execution.

---

## 2. DICTIONARY & PREDICTION ENGINE ("THE BRAIN")

### A. Primary & Secondary Language Architecture
- **Primary Language (English)**: Eagerly initialized baseline on keyboard launch.
- **Secondary Language (French)**: Background loaded; provides diacritics (`é`, `è`, `ç`), common contractions (`c'est`, `j'ai`), and French vocabulary.
- **Language Switch via Spacebar**:
  - **Long-pressing the Spacebar** opens the **Language Selection Dialog / Cycle**, allowing the user to select Primary Only (English), Secondary Only (French), or Bilingual Mode (English + French).
  - Long-press spacebar can also immediately switch back to Bilingual Mode when only single language is active.

### B. Normal Mode vs. Lite Mode Toggle (Inside Text Engine Settings)
- **Normal Mode (Default)**:
  - Multi-word **Trigrams** active (looks back 2 words to predict the 3rd).
  - Physical touch **`ProximityInfo`** key hitboxes enabled for 2D Euclidean spatial error correction.
  - Both English and French dictionaries evaluated simultaneously with weighted scoring.
  - Continuous dynamic frequency learning with forgetting curves enabled.
  - Gesture / glide typing active.
- **Lite Mode**:
  - **Both Languages Still Active by Default**: Evaluates English and French.
  - **Trigrams STAY IN**: Multi-word predictions remain active.
  - **NO Gesture Typing**: Touch-drag trajectory processing, smoothing buffers, and continuous trace traversal are completely disabled/omitted.
  - **Memory Trim Safeguard (Long Timer / Deferred Reload)**:
    - When the Android OS triggers `onTrimMemory()` (low RAM warning), French is gracefully shed to retain English only.
    - **French does NOT aggressively reload** as soon as RAM recovers. It remains unloaded until either:
      1. A conservative **long debounce timer** expires, OR
      2. The **next time the keyboard is opened** (`onStartInputView`).
    - The user can also instantly force dual-language reload at any time via **Long-Press Spacebar**.

### C. Suggestion Candidate Long-Press Popup (The HeliBoard Fix)
- Long-pressing any word candidate in the suggestion bar opens a floating context popup with similar morphological candidates and two specialized actions:
  1. **Delete Button (`×` / Trash)**: Displayed if the word originated from the user-learned history, Normal Personal Dictionary, or Privacy Vault. Tapping permanently purges it.
  2. **Demote / Downvote Button (`↓`)**: Displayed if the word is from the **system dictionary** (English/French). Tapping applies a penalty weight in the local user adjustments table, lowering its rank so it stops hijacking the center auto-correct slot without completely blacklisting it.

### D. Native Build Architecture: Remote GitHub Actions CI
- Heavy C++ native libraries (`libjni_latinime.so`, `libwhisper.so`) are compiled on **GitHub Actions CI** using `ndk-build` and CMake, strictly targeting `arm64-v8a` and `armeabi-v7a`.
- Local development utilizes the graceful JNI fallback in `BinaryDictionary.kt` to allow unit testing and verification without NDK bloat.

---

## 3. PERSONAL DICTIONARY & PRIVACY VAULT ARCHITECTURE

Both partitions share the same fast underlying lookup trie and database schema `(phrase, shortcut, language, weight)`, but are strictly separated by visibility, security, and rendering:

```
┌────────────────────────────────────────────────────────────────────────┐
│                      PERSONAL DICTIONARY ENGINE                        │
├───────────────────────────────────┬────────────────────────────────────┤
│   PARTITION 1: NORMAL DICTIONARY  │     PARTITION 2: PRIVACY VAULT     │
├───────────────────────────────────┼────────────────────────────────────┤
│ • Visible to system & other apps  │ • 100% Sandboxed (internal SQLite) │
│ • Standard HeliBoard Settings CRUD│ • Settings -> Security -> Privacy  │
│ • Unmasked, standard expansion    │ • Masked by default (s***@gmail)   │
│ • Regular phrase shortcuts        │ • Timed Session Unlock via Pattern │
└───────────────────────────────────┴────────────────────────────────────┘
```

### Partition 1: Normal Personal Dictionary (HeliBoard / System Compatible)
- **Visibility**: Uses the standard Android `UserDictionary` interface so other apps and system services can view and edit entries just like standard HeliBoard.
- **UI**: Standard HeliBoard-style Settings screen with CRUD (Word/Phrase, Shortcut, Language, Weight/Importance).
- **Typing Behavior**: When typing a shortcut (e.g. `addr`), the full phrase appears openly in the suggestion bar.

### Partition 2: Privacy Vault (Sandboxed & Masked with Timed Unlock)
- **Visibility**: **100% Sandboxed**. Stored in VianBoard's private internal storage. No other app has read permissions.
- **UI**: Configured in **Settings -> Security -> Privacy Vault** with identical CRUD fields `(phrase, shortcut, language, weight)`.
- **Intelligent Masking (Default State)**:
  - When typing a privacy shortcut, the candidate appears **masked** in the suggestion bar to protect against shoulder-surfing and screen recordings (e.g., `s********9` or preserving domains `s***@gmail.com`).
- **Timed Session Unlock (Session TTL)**:
  - Entering the pattern unlock temporarily **unlocks** the Privacy Vault for a configurable window (e.g., 1 min, 5 mins).
  - While unlocked, Privacy Vault suggestions display in **clear unmasked text** for rapid continuous entry.
- **Auto-Relock Triggers**:
  - Automatically relocks on timer expiry, keyboard dismissal (`onFinishInputView`), or manual lock toggle.

---

## 4. SECURITY VAULT & DISGUISE PATTERN UNLOCK

### A. Security Vault (In-Keyboard KeePassDX Engine)
- **Identity**: Native, in-keyboard credential and password vault engine inspired by KeePassDX.
- **Access**: Long-pressing `?123` / symbols opens the Pattern Unlock.
- **Data Model**: Encrypted records containing:
  - `Title`, `Username / Identity`, `Password / Secret`, `URL / Package`, `Notes`, `TOTP Secret`.
- **Zero-Switch Autofill**: Tap to insert username, tap to insert password, or generate and commit TOTP codes directly into any app without switching away from the keyboard.
- **Ephemeral Lifecycle**: AES-GCM encrypted database. Keys wiped from memory immediately upon keyboard collapse.

### B. Disguise Pattern Unlock Mode
- Configured in **Settings -> Security -> Pattern Unlock**.
- When **Disguise Mode** is enabled:
  - The pattern unlock screen does **NOT** look like a security gate.
  - It visually disguises itself as the **normal main QWERTY keyboard**.
  - A hidden pattern can be drawn across the key canvas, OR the **top-right corner button** acts as the disguised toggle / close unlock button.
  - Prevents onlookers from realizing that a secure vault or private dictionary is being unlocked.

---

## 5. UNIFIED PARTITIONED CARD ENGINE (CLIPBOARD & PROMPT LIST)

Consolidates `ClipboardStorage` and `QuickNotesStorage` into a single, lightweight data store and shared modal while preserving current visual styling.

### A. Data Layer: Single Store, Partitioned
```
┌─────────────────────────────────────────────────────────┐
│               UNIFIED TEXT CARD STORAGE                 │
├────────────────────────────┬────────────────────────────┤
│ Partition: CLIPBOARD       │ Partition: PROMPT          │
│ • Auto-captured clips      │ • Saved prompt templates   │
│ • Timestamped history      │ • Custom pinned notes      │
└────────────────────────────┴────────────────────────────┘
```
- **"Move to Prompt List"** is an instant atomic partition tag switch: `type = PROMPT` (no cross-file serialization).

### B. UI Layer: Unified Card Modal (Preserving Current Visual Styling)
- **Visual Design**: Preserves the existing card styling (elevation, background, rounded corners, search bar, selection toolbar).
- **Clipboard Mode**:
  - 2-column grid.
  - Cards formatted to **4 lines max**.
  - Context popup actions: **Pin**, **Move to Prompt List**, **Delete**.
- **Prompt List Mode**:
  - 2-column grid.
  - Cards formatted to **2 lines max**.
  - Context popup actions: **Pin**, **Edit**, **Delete**.
  - **Special Add Card**: The very first card in Column 1 is a compact 1-line **Add Button Card** with a centered `+` icon to quickly create new prompts.

---

## 6. MEMORY TIERING ARCHITECTURE: WARM CORE VS. COLD SURFACES

Guarantees 0ms latency for the user's primary daily workflow, while leaving all secondary features completely frozen.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   TIER 1: WARM CORE (Always in RAM)                    │
├───────────────────────┬───────────────────────┬────────────────────────┤
│ Main QWERTY Layout    │ Number / PIN Keypad   │ Unified Card Modal     │
│ (Instant text typing) │ (Instant digit entry) │ (Clipboard & Prompts)  │
└───────────────────────┴───────────────────────┴────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│              TIER 2: COLD ON-DEMAND (Loaded Only When Tapped)          │
├───────────────────────┬───────────────────────┬────────────────────────┤
│ Symbols & More Symbols│ Security Vault Modal  │ Voice Engine (Whisper) │
│ Desktop Shortcuts     │ Emoji Palette (3,000+)│ Secondary Dict (if trm)│
└───────────────────────┴───────────────────────┴────────────────────────┘
```

- **Warm Surfaces**: Main Alpha Layout, Number/PIN keypad, and Unified Card Modal (Clipboard/Prompt List).
- **Cold Surfaces**: Symbols (`?123`) & More Symbols (`=/<`), Security Vault, Voice Engine (Whisper + VAD), Desktop Shortcuts, and Emoji Palette.

---

## 7. IMPLEMENTATION ROADMAP & VERIFICATION CHECKLIST

- [ ] **Engine Scope**: Enforce complete exclusion of emoji and contacts lookups in `Suggest.kt`.
- [ ] **Proximity Info**: Bind `KeyboardLayout` key rects into `ProximityInfo` for Normal mode.
- [ ] **Bilingual & Spacebar**:
  - Long-press spacebar triggers language switch / return to bilingual mode.
  - Lite mode trims French on low RAM and reloads only on long timer or next keyboard session.
- [ ] **Suggestion Long-Press**: Implement floating candidate popup with **Delete** (learned/personal words) and **Demote (`↓`)** (system words).
- [ ] **Partitioned Personal Dictionary**:
  - Partition 1 (Normal): System/HeliBoard compatible with standard CRUD.
  - Partition 2 (Privacy Vault): Sandboxed internal storage with smart masking (`s***@gmail.com`) and timed pattern unlock.
- [ ] **Security Vault & Disguise Mode**:
  - KeePassDX credential schema (User, Pass, URL, OTP).
  - Disguise mode rendering pattern unlock as normal keyboard with top-right corner close action.
- [ ] **Unified Card Modal**:
  - Single partitioned storage (`CLIPBOARD` vs `PROMPT`).
  - 4-line clipboard cards vs 2-line prompt cards + `+` Add Card in Column 1.
- [ ] **Cold Symbols**: Demote Symbols and More Symbols layouts to Tier 2 Cold on-demand.
