# VianBoard Security & Privacy Vault Master Plan

---

## 1. Executive Summary & Core Pillars

* **Target Architecture**: An ultra-secure, lightweight, offline three-tier security system built directly into VianBoard:
  1. **Pattern Unlock & Keyboard Disguise Gatekeeper**: On-demand gesture unlock supporting both a standard 3×3 pattern grid and a **normal keyboard visual illusion disguise** (traces across real key coordinates with tactile haptics, completely stealthy to bystanders).
  2. **Privacy Vault (Masked Quick Phrases / Personal Dictionary)**: Lightweight standalone engine modeled directly after HeliBoard's `PersonalDictionary` (preserving full compatibility for future dictionary/prediction integration). Typing shortcuts displays masked pills (e.g. `🔒 s••••••••@gmail.com`). Tapping unlocks a **5-minute session** and injects secrets directly via `InputConnection` with **zero clipboard traces**.
  3. **Security Vault (KDBX KeePass Engine & In-Keyboard Explorer)**: File-explorer UI inside the keyboard for credentials stored in standard KeePass `.kdbx` files. Master password required **only once on import**, then protected via hardware **Android KeyStore** and Pattern Unlock. Grants a **3-minute session**. Topbar provides both **`Close (✕)`** (preserves 3m session) and **`Lock (🔒)`** (hard session purge).
  4. **External KDBX Safe Sync Guardrail (Visual Diff & Permission)**: Zero silent background overwrites to external files. Any save or sync back to an external KDBX file requires explicit user permission and displays a color-coded visual diff preview (`[+] Added`, `[~] Modified`, `[-] Deleted` entries and folders) to prevent accidental data loss.
* **Isolation & Memory Hygiene**:
  - Independent volatile session timers (`privacyUnlockedUntilMs` [5m] vs `securityUnlockedUntilMs` [3m]). Unlocking one does **not** unlock the other.
  - In-memory secrets (passwords, TOTP seeds) handled as mutable `CharArray`/`ByteArray` and explicitly zeroed out after dispatch.
  - Zero clipboard leakage: no writing to `android.content.ClipboardManager` and no entries in VianBoard's internal `ClipboardHistoryManager`.
* **Zero-Leak Logging & Encrypted Backups**:
  - `LogKeeper` records only high-level operational events (success/failure counters); never writes secret text, patterns, or credentials.
  - VianBoard Backup & Restore mandates a user-defined password to encrypt vault payloads using AES-256-GCM.
* **Non-Disruptive Testing Hook**:
  - Long-pressing the `?123` / `1234` key on the main keyboard layout triggers the Security Vault gate directly, allowing early verification of the pattern modal and timer before the full KDBX tree is populated.

---

## 2. System Architecture & Interaction Flow

```
                                  [ User Typing in Active App ]
                                                │
                 ┌──────────────────────────────┴──────────────────────────────┐
                 │                                                             │
                 ▼                                                             ▼
┌──────────────────────────────────┐                        ┌─────────────────────────────────────┐
│      Privacy Vault Match         │                        │    Long-Press [?123 / 1234] Key     │
│ User types shortcut e.g. "mymail"│                        │  (Direct Security Vault Trigger)    │
└────────────────┬─────────────────┘                        └──────────────────┬──────────────────┘
                 │                                                             │
                 ▼                                                             │
┌──────────────────────────────────┐                                           │
│   Suggestion Strip Masked Pill   │                                           │
│      "🔒 s••••••••@gmail.com"    │                                           │
└────────────────┬─────────────────┘                                           │
                 │                                                             │
                 │ (Tap Pill)                                                  │
                 ▼                                                             ▼
┌──────────────────────────────────┐                        ┌─────────────────────────────────────┐
│  Check 5-Min Privacy Session     │                        │   Check 3-Min Security Session      │
│  Valid? ──► Inject directly      │                        │   Valid? ──► Open Explorer Modal    │
└────────────────┬─────────────────┘                        └──────────────────┬──────────────────┘
                 │ (Expired / Locked)                                          │ (Expired / Locked)
                 └──────────────────────────────┬──────────────────────────────┘
                                                │
                                                ▼
                             ┌─────────────────────────────────────┐
                             │        Pattern Unlock Modal         │
                             │  • No top suggestion strip          │
                             │  • Standard 3×3 Dot Grid OR         │
                             │    Normal Keyboard Disguise         │
                             │  • Haptic pulses on key crossing    │
                             │  • Discrete '✕' / Decoy icon exit   │
                             └──────────────────┬──────────────────┘
                                                │
                        ┌───────────────────────┴───────────────────────┐
                        │ (Success for Privacy)                         │ (Success for Security)
                        ▼                                               ▼
     ┌─────────────────────────────────────┐         ┌─────────────────────────────────────┐
     │      Direct Input Injection         │         │      Security Vault Explorer        │
     │  • Replaces trigger word            │         │  • Topbar: [Lock 🔒] and [Close ✕]  │
     │  • commitText() directly            │         │  • Foldable folders, Filter pills   │
     │  • Zero clipboard trail             │         │  • TOTP circular countdown ring     │
     │  • 5-min timer activated            │         └──────────────────┬──────────────────┘
     └─────────────────────────────────────┘                            │ (Tap Entry)
                                                                        ▼
                                                     ┌─────────────────────────────────────┐
                                                     │     Chosen Item / Entry Modal       │
                                                     │  • Compact Height (~180dp–200dp)    │
                                                     │  • Top: Tiny stacked [Lock] & [Back]│
                                                     │    + Title/Username read-only bubble│
                                                     │  • Symbol buttons: 👤 🔑 ⏱️ 📎       │
                                                     │  • Bottom 4: [ABC] [ ] [⌫] [↵]      │
                                                     └─────────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### A. Pattern Unlock & Visual Disguise (`VianPatternUnlockView`)
1. **Modal Geometry & Atmosphere**:
   - Matches keyboard canvas height (~240dp–260dp).
   - **No Top Suggestion Strip**: Completely removed during unlock to avoid leaking visual context and give full height to the canvas.
2. **Dual Presentation Modes**:
   - **Mode 1: Standard 3×3 Dot Matrix**: Clean, spacious vector circles. Connecting line dynamically drawn following touch.
   - **Mode 2: Normal Keyboard Disguise (Visual Illusion)**:
     - Visually identical to the standard QWERTY alphabet keyboard.
     - To a bystander, the user appears to be on the normal typing screen.
     - Hidden secret pattern is defined by dragging across a sequence of keys (e.g. `Q ➔ W ➔ E ➔ S ➔ Z`).
     - **Haptics**: Discrete tactile pulse (`HapticFeedbackConstants.KEYBOARD_TAP`) fires precisely as the finger center intersects each key boundary.
     - No visual trace line is rendered in disguise mode (preserves 100% visual stealth).
3. **Exit / Cancel Affordances**:
   - Standard Mode: Visible `✕` in top-right corner to exit immediately.
   - Disguise Mode: Discrete decoy icon (e.g. language globe or clipboard icon in toolbar corner) triggers cancel.
4. **Independent Session Engine (`VaultSessionManager`)**:
   - `privacyUnlockedUntilMs`: Volatile timestamp (`SystemClock.elapsedRealtime() + 300_000L`).
   - `securityUnlockedUntilMs`: Volatile timestamp (`SystemClock.elapsedRealtime() + 180_000L`).
   - Purely in-memory; killed on process termination, keyboard hide, or manual lock.

---

### B. Privacy Vault (`PrivacyVaultRepository` & Masking Engine)
1. **Schema (Compatible with HeliBoard Personal Dictionary)**:
   - Built cleanly as an independent copy of HeliBoard's dictionary schema:
     ```kotlin
     data class PrivacyEntry(
         val id: Long = 0,
         val shortcut: String,       // e.g. "mymail", "myphone", "ssn"
         val secretPhrase: String,   // e.g. "schuylervianilewis@gmail.com"
         val maskingType: MaskType,  // EMAIL, PHONE, GENERIC
         val locale: String? = null, // Future-proof for multi-locale dictionary
         val frequency: Int = 250,
         val timestamp: Long = System.currentTimeMillis()
     )
     ```
2. **Dynamic Masking Engine**:
   - `EMAIL`: First char + `••••••` + domain (e.g., `s••••••••••••@gmail.com`).
   - `PHONE`: Country code/first 2 digits + `••••••` + last 2 digits (e.g., `+1••••••01`).
   - `GENERIC`: First 2 chars + `••••••` + last 2 chars (e.g., `Sc••••••is`).
3. **Trigger & Auto-Suggestion**:
   - As the user types, candidate tokens preceding the cursor are matched against active `shortcut` keys.
   - When a match occurs, the suggestion strip surfaces a distinct pill:
     `[ 🔒 s••••••••••••@gmail.com ]`.
4. **Insertion**:
   - If session is active (within 5 minutes): deletes the trigger keyword and calls `InputConnection.commitText(secretPhrase, 1)`.
   - If session expired: prompts Pattern Unlock modal $\rightarrow$ on success, saves 5-min session and commits text.
   - Never touches Android `ClipboardManager` or `ClipboardHistoryManager`.

---

### C. Security Vault & KDBX Engine (`SecurityVaultRepository`)
1. **Lightweight KDBX Engine (Pure Kotlin / Minimalist)**:
   - Clean, lightweight reader/parser for KDBX v3/v4 files (AES-256 / ChaCha20 + Argon2/PBKDF2).
   - Avoids the monolithic 100k-line KeePassDX native C++ overhead.
   - **First-Time Setup Flow (3 Options)**:
     1. *Create New Vault*: Initializes a clean, encrypted `vault_master.kdbx` in `context.filesDir/vault/`.
     2. *Import Existing KDBX*: SAF file picker to load an existing `.kdbx` database. Prompts for master password **only once on import**.
     3. *Demo / Template Vault*: Instantiates sample folders (Personal, Work, Banking) for instant testability.
2. **Two-File Atomic Transaction Safety**:
   - Master active file: `vault_master.kdbx`.
   - Staging/delta file: `vault_staging.kdbx.tmp`. All writes and modifications stage to this file before atomic rename, preventing database corruption if IME is killed mid-write.
3. **RFC 6238 TOTP Engine**:
   - In-memory HmacSHA1 / HmacSHA256 TOTP calculation over standard 30-second time steps (`System.currentTimeMillis() / 1000 / 30`).
   - Circular countdown progress ring drawn with Android `Canvas.drawArc()`, smoothly turning amber/red as time expires.
4. **External KDBX Safe Sync Guardrail (Visual Diff & Explicit Permission)**:
   - **Zero Silent Overwrites**: The internal working database never writes back to an external `.kdbx` file (on Google Drive, SD card, or local documents) without an explicit user review and permission action.
   - **Lightweight Diff Engine (`KdbxDiffEngine`)**:
     - Computes the delta between the linked external baseline file and the internal modified vault.
     - Categorizes changes into:
       - `[+] ADDED`: New entries or folders created in VianBoard.
       - `[~] MODIFIED`: Existing entries where username, password, URL, notes, or TOTP seed changed (with specific field-level bullet indicators).
       - `[-] DELETED`: Entries or folders removed or moved to trash.
   - **Visual Diff Confirmation Sheet (`ExternalSaveDiffDialog`)**:
     - Presented before any external file write operation.
     - Displays target file path, total delta count, and color-coded change items (Green for added, Amber for modified, Red for deleted).
     - Sensitive values (passwords) are masked by default (`[Updated: ••••••••]`) with a reveal toggle for manual verification.
     - Two distinct actions:
       - `[ Keep Internal Only ]`: Preserves changes in VianBoard's local working copy without touching the external file.
       - `[ Confirm & Write (🔒) ]`: Gated by pattern/device biometric, writes changes to an adjacent temporary SAF file (`filename.tmp`), verifies file integrity, and atomically commits.

---

### D. In-Keyboard Security Vault UI Modals

#### 1. In-Keyboard Vault Explorer Modal (`VianSecurityVaultExplorerView`)
* **Topbar**:
  - Left: **`[ 🔒 Lock ]`** button (hard-locks immediately: zeroes session keys and resets 3-minute timer to 0).
  - Center: Horizontal scroll filter & sort pills (`All`, `Folders`, `Recent`, `A-Z`). (No search bar).
  - Right: **`[ ✕ Close ]`** button (quick exit: closes modal, but **maintains 3-minute session timer**).
* **Content Tree**:
  - Foldable group folders (e.g. `📁 Personal`, `📁 Work`).
  - Entries displaying: **Title**, **Username**, and a mini **animated circular TOTP timer ring** (if 2FA seed exists).
  - Tapping an entry opens the Chosen Item Modal.

#### 2. Chosen Item / Entry Modal (`VianSecurityEntryDetailView`)
* **Height**: Compact height (noticeably **smaller than normal keyboard height**, ~180dp–200dp).
* **Top Header**:
  - Left: Two tiny stacked symbol-only buttons:
    1. `[ 🔒 Lock ]` (kill session)
    2. `[ ⬅ Back ]` (return to vault explorer)
  - Right: Rounded display-only bubble showing `Title` and `Username` (read-only indicator).
* **Action Rows (Symbol-Only Buttons)**:
  - `👤` **Username**: Direct commit to input.
  - `🔑` **Password**: Direct commit to input.
  - `⏱️` **TOTP**: Animated circular countdown ring showing seconds left; tap commits 6-digit TOTP code.
  - `📎` **Attachment**: Symbol-only button (popup for KDBX attachments, prepared for later integration).
* **Bottom Bar**: Standard 4 buttons (`[ ABC ]`, `[ Space ]`, `[ ⌫ Backspace ]`, `[ ↵ Enter ]`).

---

### E. Settings: Security Hub (`SecuritySettingsActivity`)
1. **Entry Gatekeeper**:
   - **Fresh Install**: No pattern configured yet $\rightarrow$ accessible directly (or requires device PIN/fingerprint if enabled).
   - **Configured State**: Double-authentication required to view settings:
     1. Device Biometric / Lock Screen PIN.
     2. Fullscreen Settings Master Pattern (can be distinct from in-keyboard pattern).
2. **Sections in Security Settings**:
   - **1. Lock & Access Control**:
     - Configure Settings master pattern.
     - Configure In-Keyboard pattern(s) (Shared vs Separate for Privacy & Security).
     - Pattern presentation selector: Standard 3×3 Grid vs Normal Keyboard Disguise.
     - Biometric unlock toggle (allow phone fingerprint/face to bypass pattern).
     - Fallback selection (Device Screen Lock vs Master Password).
   - **2. Privacy Vault (Personal Dictionary CRUD)**:
     - Add / Edit / Delete privacy shortcuts and phrases.
     - Masking preview (`v••••••@gmail.com`).
     - One-tap import from HeliBoard user dictionary.
   - **3. Security Vault (KDBX Management)**:
     - 3-option welcome banner on empty state (Create New, Import KDBX, Demo Vault).
     - Active database status (file name, entry count, last modified).
     - Full CRUD for credentials and folders.
     - Search bar and A-Z / Category sort.
     - Export / Unlink database.

---

### F. LogKeeper & Encrypted Backup Integration
1. **Strict Zero-Knowledge Logging**:
   - LogKeeper is strictly forbidden from recording secrets, passwords, usernames, TOTP seeds, or pattern swipe points.
   - Whitelisted events:
     - `LogKeeper.logEvent("SecurityVault", "Vault unlocked via pattern (session 3m)")`
     - `LogKeeper.logWarning("SecurityVault", "Pattern unlock failed (attempt X/5)")`
     - `LogKeeper.logEvent("SecurityVault", "Vault session hard-locked by user")`
     - `LogKeeper.logEvent("PrivacyVault", "Masked phrase committed for shortcut: [masked]")`
     - `LogKeeper.logEvent("SecurityVault", "KDBX file imported successfully")`
2. **Password-Protected Encrypted Backup**:
   - In Backup & Restore, if Privacy Vault or Security Vault items are checked for export:
     - The user **must enter a backup password**.
     - The archive is encrypted with AES-256-GCM using PBKDF2/Argon2 key derivation.
     - Restoring prompts for that password before writing back to internal storage.

---

## 4. Implementation Phasing & Milestones (7 Sequential Phases)

*Architecture Mandates across ALL phases:*
- **Lightweight & On-Demand**: No permanent background memory footprint; modals and crypto engines instantiated lazily and garbage-collected immediately upon dismissal.
- **Strictly Single-Modal**: `VianBoardService` enforces exactly one modal active at a time (`dismissActiveModal()` before displaying any new view).
- **LogKeeper Connected**: High-level zero-knowledge operational logs (counters, success/failure); zero secrets, patterns, or plaintexts logged.
- **Backup & Restore Connected**: All schemas and configurations designed for password-protected AES-256-GCM backup export.

---

### Phase 1: Pattern Unlock, Keyboard Disguise & Testing Trigger
* **Focus**: The core security gatekeeper, gesture views, session timers, and keyboard launch hook.
* **Deliverables**:
  - `VaultSessionManager`: In-memory volatile timers (independent 5m Privacy / 3m Security).
  - `VianPatternUnlockView`:
    - Zero top suggestion strip (full canvas height).
    - Mode 1: Standard 3×3 vector dot matrix with dynamic trace line and clean top-right `✕` exit.
    - Mode 2: **Normal Keyboard Disguise (Visual Illusion)** looking identical to standard QWERTY layout; traces across real key centers with tactile vibration haptics on boundary crossing; discrete decoy icon exit.
  - Keystore-backed pattern hash storage (`MasterPatternStore`).
  - **Testing Hook**: Wire long-press on `?123` / `1234` key on the alphabet keyboard to launch the Pattern Unlock modal $\rightarrow$ on valid pattern swipe, display Toast: `"Security Vault Unlocked (Session: 3m)"`.
* **LogKeeper**: Logs `Vault unlock attempted`, `Unlock success`, `Unlock failed (attempt X/5)`, `Session reset`. (Zero coordinates/patterns logged).
* **Backup & Restore**: Pattern salt/hash structure prepared for encrypted backup export.

---

### Phase 2: Privacy Vault Engine & Masked Suggestion Pills
* **Focus**: HeliBoard-compatible personal dictionary, dynamic phrase masking, and direct text injection.
* **Deliverables**:
  - `PrivacyVaultRepository`: Standalone schema modeled cleanly after HeliBoard's `PersonalDictionary` (`id`, `shortcut`, `secretPhrase`, `maskingType`, `locale`, `frequency`).
  - Dynamic masking engine (`EMAIL`: `s••••••@gmail.com`, `PHONE`: `+1••••••01`, `GENERIC`: `Sc••••••is`).
  - Typing scanner in `VianBoardService`: Recognizes shortcuts preceding the cursor and surfaces a masked pill in the suggestion strip: `[ 🔒 s••••••••@gmail.com ]`.
  - Pill tap interaction: Checks 5-minute session timer. If active $\rightarrow$ replaces shortcut and commits secret directly via `InputConnection.commitText()`. If expired $\rightarrow$ pops Pattern Unlock modal $\rightarrow$ on success, starts 5-min timer and injects secret. **Zero clipboard traces**.
* **LogKeeper**: Logs `Privacy pill surfaced`, `Privacy phrase injected (shortcut: [masked])`. Never logs secrets.
* **Backup & Restore**: Privacy entries serialized to secure JSON payload ready for backup archive.

---

### Phase 3: Settings Security Hub & Pattern / Privacy CRUD
* **Focus**: Settings Security page, authentication gates, and management interfaces.
* **Deliverables**:
  - Rebuild `SecurityVaultSettingsActivity` into the central Security Control Center.
  - Entry gate: Phone Biometric/PIN + Settings Master Pattern (unlocked on fresh install; locked once configured).
  - **Section 1 (Lock & Access Control)**: Set master pattern, toggle shared vs. separate patterns for vaults, select Standard vs. Keyboard Disguise presentation, biometric bypass toggle, timeout sliders.
  - **Section 2 (Privacy Vault CRUD)**: Full management of privacy shortcuts and secrets with live masking preview, search, and sort.
* **LogKeeper**: Logs `Security Settings access granted/denied`, `Pattern updated`, `Privacy entry added/deleted`.
* **Backup & Restore**: Settings activity provides direct export/import hooks for privacy dictionary.

---

### Phase 4: Security Vault Core: Lightweight KDBX4 Engine & TOTP Generator
* **Focus**: Pure-Kotlin KeePass reader/writer, key envelopment, and RFC 6238 TOTP calculator.
* **Deliverables**:
  - Lightweight pure-Kotlin KDBX v3/v4 engine (AES-256 / ChaCha20 + Argon2/PBKDF2), avoiding heavy 100k-line C++ KeePassDX native dependencies.
  - Master password required **only once on initial import**, then enveloped using hardware **Android KeyStore**.
  - RFC 6238 TOTP calculator (30s step, 6-digit code, remaining time calculation).
  - Two-file atomic safety (`vault_master.kdbx` + `vault_staging.kdbx.tmp`).
  - First-time 3-option setup card in Settings: **Create New Vault**, **Import Existing KDBX**, and **Demo / Template Vault**.
* **LogKeeper**: Logs `KDBX loaded (entries: N, groups: G)`, `KDBX import success/fail`, `Argon2 key derivation elapsed ms`.
* **Backup & Restore**: KDBX file representation structured for password-protected AES-256 backup inclusion.

---

### Phase 5: In-Keyboard Security Vault Explorer & Entry Detail Modals
* **Focus**: In-keyboard credential navigation and compact entry injection view.
* **Deliverables**:
  - Replace Phase 1 test toast with full on-demand modal flow.
  - `VianSecurityVaultExplorerView`:
    - Topbar: `[Lock 🔒]` (hard session purge) and `[Close ✕]` (quick exit, keeping 3m session).
    - Foldable folders, filter/sort pills (`All`, `Folders`, `Recent`, `A-Z`), no search bar.
    - Entries show Title, Username, and animated circular TOTP timer ring.
  - `VianSecurityEntryDetailView`:
    - Compact height (noticeably **smaller than normal keyboard height**, ~180dp–200dp).
    - Topbar: Tiny stacked `[Lock]` & `[Back]` buttons + rounded read-only Title/Username bubble.
    - Action buttons: Large symbol-only buttons (`👤`, `🔑`, `⏱️` with circular timer, `📎`). Tapping commits value directly.
    - Bottom bar: Standard generic 4 buttons (`[ABC]`, `[Space]`, `[⌫]`, `[↵]`).
* **LogKeeper**: Logs `Vault explorer opened`, `Vault hard-locked by user`, `Credential username/password/TOTP injected`.
* **Backup & Restore**: Active credential changes immediately synced to staging repository.

---

### Phase 6: External KDBX Diff Engine & Permission Confirmation Sheet
* **Focus**: Visual diff safety guardrail preventing accidental data loss or silent external overwrites.
* **Deliverables**:
  - `KdbxDiffEngine`: Computes delta between internal working copy and linked external file (`[+] Added`, `[~] Modified` with field-level bullets, `[-] Deleted`).
  - `ExternalSaveDiffDialog`:
    - Bottom sheet/dialog displaying target file URI and color-coded diff summary.
    - Sensitive fields masked by default with reveal toggle.
    - Actions: `[ Keep Internal Only ]` vs. `[ Confirm & Write (🔒) ]`.
    - Atomic SAF write (`filename.tmp` $\rightarrow$ swap).
* **LogKeeper**: Logs `External sync diff: +A, ~M, -D entries`, `External write confirmed/cancelled`.
* **Backup & Restore**: External sync state preserved across backups.

---

### Phase 7: Full Encrypted Backup & Restore Integration
* **Focus**: Password-protected backup roundtrip for vaults and settings.
* **Deliverables**:
  - Connect to `BackupRestoreSettingsActivity`.
  - When exporting VianBoard backup with vaults selected: enforces setting a backup password.
  - Encrypts vault archive using AES-256-GCM (PBKDF2 key derivation).
  - Restoring validates password, decrypts payload, verifies database checksums, and restores Privacy Vault dictionary entries and pattern settings.
* **LogKeeper**: Logs `Encrypted backup created (size: B)`, `Backup restored successfully`, `Backup decryption failed (invalid password)`.
* **Backup & Restore**: End-to-end verified with roundtrip import/export test.

---

## 5. Verification & Test Plan (By Phase)

* **Phase 1 Verification (Pattern Unlock, Disguise & Long-Press Trigger)**:
  - Long-press `?123` on alphabet keyboard.
  - Verify Pattern Unlock modal opens with no suggestion strip.
  - Verify `✕` button cancels cleanly back to typing.
  - Switch to Normal Keyboard Disguise in Settings; verify keyboard looks identical to normal typing layout and vibrates on each key crossed.
  - Draw valid pattern; verify 3-minute timer starts and Toast shows: `"Security Vault Unlocked (Session: 3m)"`.
* **Phase 2 Verification (Privacy Vault & Masked Pills)**:
  - Add test shortcut `mymail` $\rightarrow$ `schuylervianilewis@gmail.com`.
  - Type `mymail` in any text box; verify suggestion pill shows `🔒 s••••••••••••@gmail.com`.
  - Tap pill; verify pattern unlocks, shortcut is replaced with full email, and clipboard is completely empty.
* **Phase 3 Verification (Settings Security Hub & CRUD)**:
  - Open Settings $\rightarrow$ Security; verify device biometric/PIN and pattern prompt appear before granting access.
  - Test adding, editing, and deleting privacy dictionary entries with live masking preview.
* **Phase 4 Verification (KDBX Engine & Setup Cards)**:
  - Open Security Settings; verify 3 welcome cards (Create New, Import KDBX, Demo Vault).
  - Create or import database; verify master password prompt appears only once.
  - Verify TOTP generation matches external authenticator apps.
* **Phase 5 Verification (In-Keyboard Explorer & Detail View)**:
  - Long-press `?123` to enter Security Vault explorer; verify folder collapsible state.
  - Verify `[Close ✕]` exits but keeps 3m session alive (re-entry within 3m skips pattern).
  - Verify `[Lock 🔒]` hard-exits and immediately requires pattern on re-entry.
  - Open an entry; verify compact modal height (< standard keyboard), tap `👤` / `🔑` / `⏱️` to confirm direct text insertion.
* **Phase 6 Verification (External Save Diff & Permission)**:
  - Modify a password or add a credential in the internal vault.
  - Trigger sync to external linked KDBX file.
  - Verify `ExternalSaveDiffDialog` appears with exact green `[+]` added, amber `[~]` modified, or red `[-]` deleted badges.
  - Verify tapping "Keep Internal Only" cancels write.
  - Verify tapping "Confirm & Write" writes to external storage safely without corruption.
* **Phase 7 Verification (Encrypted Backup & Restore)**:
  - Export backup with vaults selected; verify password prompt appears and unencrypted data is never written.
  - Clear app data or test restore on fresh install; verify correct password decrypts vaults and restores all entries and settings.
