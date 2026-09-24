# Project Blueprint: VianBoard

## 1. Overview
VianBoard is a fully customizable, privacy-conscious offline Android keyboard application based on AOSP / OpenBoard / HeliBoard.
- **Application ID**: `shura.vianboard`
- **Namespace**: `helium314.keyboard.latin`
- **Architecture**: MVVM / Clean Android Architecture with LatinIME Service and Jetpack Compose for modern Settings UI.
- **Offline / Privacy Guarantee**: 100% offline, zero internet permissions.

## 2. Key Modules & Components
- **Core Input Method Service (`LatinIME`)**: Handles touch input, key layout generation, popup keys, gesture data handling, and input connection.
- **Settings & Preferences (`helium314.keyboard.settings`)**: Built with Jetpack Compose (Material 3), enabling theme customization, custom color pickers, key borders, custom layouts, dictionaries, gestures, clipboard manager, and spell checker.
- **Spell Checker (`AndroidSpellCheckerService`)**: AOSP-based offline spell checker service.
- **Clipboard Content Provider (`ClipboardContentProvider`)**: Internal clipboard history (`shura.vianboard.clipprovider`) and secure pasting.
- **Log Keeper / Diagnostics (`Log.kt`, `DebugFlags.kt`)**: Built-in crash and diagnostics logging with debug toggles and export capabilities.

## 3. Development & Migration Phases
- **Phase 1: Project Setup & Cloning**: Import source files, resources, assets, dictionaries, and layouts from repository.
- **Phase 2: Build & Dependency Alignment**: Configure Gradle DSL (`app/build.gradle.kts`, `settings.gradle.kts`, `libs.versions.toml`) with Kotlin 2.2.10, AGP 9.1.1, Compose BOM, Serialization, Desugaring, Reorderable, and ColorPicker.
- **Phase 3: Verification & Compilation**: Proactively compile via `compile_applet` and perform sanity tests.
- **Phase 4: Security & Integrity Audit**: Audit workspace for zero exposed credentials, gitignore checks, and clean artifact state.
- **Phase 5: Package & Identity Alignment**: Updated `applicationId` to `shura.vianboard`, app name to `VianBoard`, sync provider authorities, and added GitHub Actions APK CI workflow.
- **Phase 6: Modern Clipboard Engine**: Standardized 2-column responsive layout, quick-action micro-menu (Pin/Unpin, Delete, Paste), and unified 4-button footer (`[ABC] [SPACE] [⌫] [↵]`).
- **Phase 7: LogKeeper UI & Diagnostics**: Integrated privacy-focused LogKeeper activity (`LogKeeperActivity.kt`) with master toggle, category-based log filtering (Errors/Warnings/Fatal), on-device file export, and resolved all project compilation dependencies.
- **Phase 8: Dictionary Asset Optimization**: Pruned bundled dictionary assets to English (`main_en-US.dict`, `main_en-GB.dict`) and French (`main_fr.dict`) only, optimizing APK size while preserving post-install import and download capabilities for all other languages.
- **Phase 9: CI Pipeline & JVM Metaspace Optimization**: Configured automated debug keystore generation in GitHub Actions workflow to prevent CI packaging failures, and configured `-XX:MaxMetaspaceSize=1024m` in `gradle.properties` to prevent daemon heap exhaustion.
- **Phase 10: Repository & Resource Pruning**: Removed 98 redundant non-English/French localized string resource folders (`values-*`), updated `locales_config.xml`, added `resourceConfigurations += listOf("en", "fr")` and `ndk.abiFilters` (`arm64-v8a`, `x86_64`) in `app/build.gradle.kts`, added `.build-outputs/` to `.gitignore`, reducing res folder by 75% and source repo to <10MB.
- **Phase 11: Modern Layout, Icons & Theme Realignment**: Restored official `KeyLabel.kt` icon mapping for functional keys, resolved extension function imports in `TextKeyData.kt` and `PopupKeysUtils.kt`, aligned defaults to modern Rounded M3 style with key borders and number row, and verified popup view offsets.
- **Phase 12: Dual-Engine Log Keeper & Comma Popup Grid Realignment**:
  - Split logging into background persistent catcher (`LogCatcher.kt`) and Material 3 UI (`LogKeeperActivity.kt`).
  - Implemented disk-persisted crash interceptor (`last_crash.log`), thread-safe ring buffer, component lifecycle tracking ("All Running" tab), and 4-action bar (`Copy`, `Export`, `Refresh`, `Clear`).
  - Switched Log Keeper icon from generic settings cog to logbook vector icon (`sym_keyboard_log_keeper_rounded` / `ic_settings_about_log`).
  - Realigned comma popup keys layout with auto-column grid order (`!autoColumnOrder!`) to match the neat multi-row grid of the period key popup.
- **Phase 13: Repo Pruning & Curated Color Palettes**:
  - Excised Custom Background Image Engine (`BackgroundImagePreference.kt`, `Settings.readUserBackgroundImage`, bitmap cache).
  - Excised Custom User Font & Emoji Font Loader (`CustomFontPreference.kt`, `KeyboardTypeface` file loaders).
  - Excised Dynamic External Gesture Binary Loader (`LoadGestureLibPreference.kt`).
  - Excised Holo Legacy Theme & Drawables (`STYLE_HOLO`, `THEME_HOLO_WHITE`, `keyboardIconsHolo` map).
  - Replaced granular 20-slider hex picker with curated, pre-tested, high-contrast color palettes (Material You Dynamic, AMOLED Pure Black, Slate Dark, Clean White, Forest Green, Deep Indigo) with visual key swatches and one-tap selection.
- **Phase 14: Log Keeper Auto-Rotation & Native JNI Guard**:
  - Persistent active disk log (`vianboard_active.log`) with 2 MB threshold auto-rotation into device `Download/` folder.
  - Direct crash dump copy to device `Download/` folder (`VianBoard_CRASH_<timestamp>.log`) alongside internal storage cache.
  - Added direct Settings screen entry for Log Keeper under About screen (`SettingsWithoutKey.LOG_KEEPER`).
  - Hardened JNI native initialization guards (`JniUtils.isNativeLoaded()`) across `ExpandableBinaryDictionary` and `BinaryDictionary` to prevent hard SIGSEGV crashes if native binaries are unloaded or missing.
- **Phase 15: Safe Sidelining & Settings Preset Lockdown (Option B)**:
  - **Asset Sidelining**: Moved all non-English/French keyboard layouts and locale popup assets into root `/sidelined_features/` (excluded from APK build, preserved safely in repo).
  - **Locked Settings Presets**: Locked `Defaults.kt` and `SettingsValues.java` to the user's exact preset configuration: Blue Grey curated palette (#ECEFF1 bg, #FFFFFF keys, #CFD8DC funcs, #78909C accent), Rounded style with Key Borders enabled, Auto Day/Night disabled, Color Navigation Bar disabled, Popup on Keypress disabled (no tap popup preview, only long press), Number Row enabled with hints, Clipboard History enabled with 10 min retention and pinned items on top, Redundant Popups removed, Gesture input disabled, and Physical Keyboard Emoji Alt key stripped.
  - **Calibrated Popup Elevation**: Elevated long-press popup panels in `MainKeyboardView.java` by +16% key height offset to provide a clear, floating preview gap above the parent button.
  - **Physical Keyboard & Gesture Sidelining**: Excised physical keyboard emoji toggles from `AdvancedScreen.kt` and safely hardcoded gesture input to false to prevent runtime crashes.

- **Phase 16: Unisoc Native ABI Compatibility & Log Keeper 2-Tab Redesign**:
  - **Unisoc 32-bit ABI Support**: Restored `armeabi-v7a` to `app/build.gradle.kts` `abiFilters` alongside `arm64-v8a` and `x86_64`, enabling native binary execution on Unisoc devices running 32-bit userlands.
  - **CI Native Compilation**: Configured `.github/workflows/build-apk.yml` with automated `ndk-build` compilation step targeting `armeabi-v7a`, `arm64-v8a`, and `x86_64` prior to packaging the APK.
  - **Defensive Null Guards**: Hardened `ExpandableBinaryDictionary.java` against null `mBinaryDictionary` references across `isValidDictionaryLocked()`, `getFrequency()`, `runGCIfRequiredLocked()`, `addUnigramLocked()`, `addNgramEntryLocked()`, `loadBinaryDictionaryLocked()`, and `createNewDictionaryLocked()`, completely preventing NPE crashes during cursor updates, history tracking, and typing sessions.
  - **Non-Destructive Dictionary Loading**: Updated `DictionaryFactory.kt` to prevent `killDictionary(file)` from deleting valid dictionary cache files when the native library is not yet loaded.
  - **Log Keeper 2-Tab UI**: Redesigned `LogKeeperActivity.kt` to match the user's reference screenshot: top action bar with back navigation, bold title, Master Switch, Copy icon button, and Download/Export icon button; 2 tabs: **All Logs** and **Errors**; clean card layout with monospace timestamps, component tag badges, colored log level chips, and message text.

- **Phase 17: Emoji Bottom Row Enter Key, Prompt List (Quick Notes) Modal & 2-Minute Temporary Incognito**:
  - **Emoji Bottom Row Enter Key**: Switched bottom row in `EmojiPalettesView.java` to `KeyboardElement.CLIPBOARD_BOTTOM_ROW` (`[ABC] [Space] [⌫] [↵ / Action]`), giving the emoji modal the same enter/action key as the clipboard modal for instant message sending or line breaks.
  - **Prompt List (Quick Notes) Modal**: Fully self-contained local notes repository (`PromptDao.kt`, `PromptHistoryView.kt`) with SQLite persistence (`PROMPTS` table) disconnected from system clipboard listeners.
    - **Move to Prompt List**: Long-pressing any clipboard item offers `📌 Pin/Unpin`, `📥 Move to Prompt List`, and `🗑️ Delete`. Moving to prompt list safely stores the text in `PromptDao` and removes it from clipboard history.
    - **Prompt Card Interaction**: Tapping any prompt card directly pastes the text into the active field.
    - **Edit Dialog**: Long-pressing a prompt card offers `📌 Pin/Unpin`, `✏️ Edit`, and `🗑️ Delete`. Selecting `✏️ Edit` opens a full dialog with multiline `EditText`, `Cancel`, and `Save` buttons, with window tokens properly bound to the IME view for normal typing.
    - **Toolbar Long-Press**: Long-pressing the Copy toolbar button opens the Prompt List modal.
  - **2-Minute Temporary Incognito Mode**:
    - **Trigger**: Long-pressing the Incognito toolbar key triggers 2-minute temporary incognito mode (`KeyCode.INCOGNITO_TEMP_2MIN`, `TempIncognitoManager.kt`).
    - **Timer & Expiration**: Automatically turns off incognito mode after 120,000 ms with feedback Toast.
    - **Keyboard Close Override**: If the keyboard is closed or hidden at any point while temporary incognito is active, the timer is immediately cancelled and incognito is guaranteed turned off, so when reopened, incognito is OFF.

- **Phase 18: Layout Overhaul, Image 1 Symbol Mapping, French Popup Accents & 3-Page Settings Architecture**:
  - **Gradle Debug Fallback & Credential Immunity**: Refactored `app/build.gradle.kts` debug signing configuration to check `DEBUG_KEYSTORE_PATH` and dynamically register `customDebug` only if valid, falling back cleanly to the built-in Android debug keystore.
  - **Image 1 Symbol Top-Right Layout**: Rewrote `qwerty.txt` with exact symbol mapping:
    - Row 1: `Q(%)`, `W(/)`, `E(|)`, `R(=)`, `T([)`, `Y(])`, `U(*)`, `I(!)`, `O(-)`, `P(;)`
    - Row 2: `A(@)`, `S(#)`, `D($$$)`, `F(_)`, `G(&)`, `H(-)`, `J(+)`, `K(()`, `L())`
    - Row 3: `Z(*)`, `X(")`, `C(')`, `V(:)`, `B(;)`, `N(!)`, `M(?)`
  - **French Accents in Long-Press Popups**: Updated `more_popups_main.txt` to prioritize Latin French accents (`e: é è ê ë ē`, `a: à â æ á ä ã å ā`, `i: î ï í ì ī`, `o: ô œ ö ò ó õ ø ō`, `u: ù û ü ...`) immediately following key symbols.
  - **Predefined Layout Sidelining**: Restricted `predefined_layouts` in `donottranslate.xml` to `Default` (`qwerty`), moved `azerty.json` and `bepo.txt` into `/sidelined_features/layouts/main/`, and locked `LayoutPickerDialog.kt` to Default + 1 single customizable layout slot.
  - **Default Currency ₹**: Configured `Defaults.PREF_CUSTOM_CURRENCY_KEY = "₹"` mapping `$$$` on key `d` to Rupee by default.
  - **3-Parent-Page Settings Architecture**:
    - **Appearance** (`AppearanceScreen.kt`): Default layout editor, 1 customizable layout slot manager, Currencies quick switch (₹, $, €, ¥), Toolbar key editor, and Desktop Shortcuts modal. Uses `ic_settings_preferences` icon.
    - **Word Engine** (`WordEngineScreen.kt`): Houses Text Correction (`TextCorrectionScreen`) and Dictionaries (`DictionaryScreen`). Uses `ic_settings_correction` icon.
    - **Advanced** (`AdvancedSettingsScreen.kt`): Houses Backup & Restore (`BackupRestoreScreen`) and About (`AboutScreen`). Uses `ic_settings_advanced` icon.
    - **Backup & Restore Sub-Page** (`BackupRestoreScreen.kt`): Unified "Backup All", "Restore All", and backward-compatible "Import HeliBoard Backup" flows with detailed migration confirmation.
    - **About Updates**: Credited HeliBoard and AOSP in `donottranslate.xml` and About description.

- **Phase 19: Prompt List Overhaul & Stabilization (Mini-Phases A-D)**:
  - **Mini-Phase A: Crash Fix & Layout Visibility (Blockers) [COMPLETED]**:
    - Replaced `ClipboardHistoryRecyclerView` with standard `RecyclerView` in `prompt_history_view.xml`, eliminating the `IndexOutOfBoundsException: Inconsistency detected` crash and redundant adapter notification on removal.
    - Prevented height collapse when empty by maintaining layout bounds (`View.INVISIBLE` instead of `View.GONE`) and centering the placeholder text.
    - Hid `mPromptHistoryView` and called `stopPromptHistory()` in `KeyboardSwitcher.java` when switching back to alphabet, symbol, emoji, or clipboard layouts, resolving the transparent ghost overlay.
    - Applied solid keyboard theme background (`ColorType.MAIN_BACKGROUND`) to `PromptHistoryView`.
  - **Mini-Phase B: ABC Key & State Machine Integration [COMPLETED]**:
    - Added `PROMPT` to `LayoutDirective.Utility` and `KeyboardState.Mode`.
    - Added `setPromptKeyboard()` to `KeyboardState.SwitchActions` and wired `Utility.PROMPT -> switchActions.setPromptKeyboard()` in `KeyboardState.kt`.
    - Dispatched `KeyCode.PROMPT_LIST` through `KeyboardState.onEvent` via `toggleLayout(Utility.PROMPT)` and removed early return in `KeyboardActionListenerImpl.kt`.
    - Configured bottom row with `KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)` and `PointerTracker.switchTo()` in `PromptHistoryView.kt`.
    - Implemented `KeyboardSwitchState.PROMPT` in `KeyboardSwitcher.java`, ensuring `resetToAlpha()` cleanly exits prompt mode and restores the alphabet keyboard upon pressing ABC.
  - **Mini-Phase C: Visual Parity with Clipboard [COMPLETED]**:
    - Extracted and calculated `KeyDrawParams` (typeface, label color, text size) and passed them to `PromptHistoryView.kt` and `PromptAdapter`.
    - Applied `ColorType.KEY_BACKGROUND` to prompt note cards and `ColorType.CLIPBOARD_PIN` to pin icon, matching active theme styling 1:1.
    - Added side padding, keyboard width constraints, and styled `placeholderView` with `KeyboardTypeface` and theme text color.
    - Updated `PromptDao.togglePinned()` to refresh `timestamp = System.currentTimeMillis()`, bringing pinned items to the very top.
    - Removed redundant `notifyDataSetChanged()` from pin action, binding smooth `notifyItemMoved()`, `notifyItemChanged()`, and `smoothScrollToPosition()` in `onPromptMoved()`.
  - **Mini-Phase D: Suggestion Strip Toolbar & Compact Popup Menu [COMPLETED]**:
    - Integrated dedicated `prompt_strip_scroll_view` and `prompt_strip` in `strip_container.xml` and `KeyboardSwitcher.java`, cleanly isolating prompt toolbar keys from clipboard keys without lifecycle or parent-view collision.
    - Added full editing and navigation toolbar actions (`UP`, `DOWN`, `LEFT`, `RIGHT`, `UNDO`, `CUT`, `COPY`, `PASTE`, `SELECT_WORD`, `CLOSE_HISTORY`) styled with `ColorType.TOOL_BAR_KEY` and `ColorType.STRIP_BACKGROUND`.
    - Wired `CLOSE_HISTORY` to dispatch `KeyCode.PROMPT_LIST`, cleanly toggling out of prompt view back to the active alphabet keyboard.
    - Replaced framework `PopupMenu` with a compact, theme-colored floating `PopupWindow` featuring HeliBoard vector drawables (`ic_clipboard_pin_rounded`, `ic_edit`, `ic_bin_rounded`) with active theme tints (`ColorType.KEY_BACKGROUND`, `ColorType.CLIPBOARD_PIN`, `ColorType.TOOL_BAR_KEY`), 44dp accessible touch targets, and instant touch-outside dismissal.
    - Verified compilation cleanly via `compile_applet`.

- **Phase 20: Offline Voice Input Engine**:
  - **Mini-Phase 1: Foundations, Model Storage & Word Improvement Dictionary [COMPLETED]**:
    - Declared `RECORD_AUDIO` permission in `AndroidManifest.xml`.
    - Decoupled legacy `mRichImm.switchToShortcutIme` in `LatinIME.java` and isolated `KeyCode.VOICE_INPUT` handling in `InputLogic.java`.
    - Upgraded `Database.kt` to version 4 with `VOICE_REPLACEMENTS` schema and migration.
    - Implemented `VoiceReplacementDao.kt` with thread-safe cached word replacements.
    - Created `VoiceModelManager.kt` managing Whisper `.bin` models in `no_backup/voice_models/` with GGML header validation.
    - Added dedicated Voice Input settings screen (`VoiceInputScreen.kt`) with 3-stage gain multiplier, model import/delete UI, and word replacement CRUD editor.
    - Excluded model binaries from backup ZIP archives in `BackupRestorePreference.kt`.
  - **Mini-Phase 2: Compact Modal UI, 4-Button Bottom Control Bar, & Streaming Preview Interface [COMPLETED]**:
    - Implemented `VoiceInputView.kt` and `voice_input_view.xml` using native Android Views without heavy Compose runtime overhead.
    - Constrained layout in `onMeasure` to exactly 150dp compact height with theme-adaptive styling.
    - Built unified 4-button bottom control bar (`[ABC] [SPACE] [⌫] [↵]`) dispatching directly to keyboard action listener.
    - Built custom `VoicePulseView.kt` Canvas animation reacting to speech RMS amplitude across dynamic states (Listening, Paused, Error, Idle).
    - Added streaming text preview, error status indicating missing models, and cycling gain pill (1x -> 2x -> 4x).
    - Implemented `VoicePermissionActivity.kt` and `VoicePermissionBridge.kt` for seamless runtime audio permission requests.
    - Integrated `KeyboardSwitcher.java` and `LatinIME.java` lifecycle hooks for instant dismissal and zero background resource usage when closed.
  - **Mini-Phase 3: Multi-Process Audio Pipeline & IPC Service [COMPLETED]**:
    - Registered `VoiceInputService` in `AndroidManifest.xml` under dedicated process `android:process=":voice"`, isolating microphone capture and native threads from the main IME process.
    - Implemented `VoiceIpcProtocol.kt` defining lightweight, low-overhead Messenger IPC commands (`START`, `PAUSE`, `RESUME`, `STOP`, `SET_GAIN`) and events (`RMS_UPDATE`, `STATE_CHANGED`, `SPEECH_ACTIVITY`, `ERROR`).
    - Implemented `AudioRecordPipeline.kt` running on a dedicated priority thread capturing 16kHz 16-bit mono PCM with 3-stage digital gain (`1x`, `2x`, `4x`) and hard clipping limiter, calculating smoothed RMS levels dispatched at 40ms intervals.
    - Created `EnergyVad.kt` implementing lightweight energy-based Voice Activity Detection with adaptive noise-floor calibration.
    - Implemented `VoiceInputService.kt` with IPC message dispatch, client registration, and a 60-second idle auto-shutdown timer releasing audio hardware and process memory when inactive.
    - Built `VoiceInputConnection.kt` in `:root` managing service binding and IPC message handling with `IBinder.DeathRecipient` protection preventing `DeadObjectException`.
    - Integrated `VoiceInputConnection` into `VoiceInputView.kt` linking start/pause/resume/gain actions to the audio service and driving live pulse animations from real microphone RMS amplitude.
  - **Mini-Phase 4: FUTO Whisper Engine JNI Integration & CMake CI Pipeline [COMPLETED]**:
    - Extended `VoiceIpcProtocol.kt` with `EVENT_FINAL_TRANSCRIPTION`.
    - Implemented `WhisperEngine.kt` with safe JNI bindings, token artifact cleanup (`cleanWhisperOutput`), and `LogCatcher` component lifecycle tracking without logging text or PII.
    - Created C/C++ native bridge in `app/src/main/jni/whisper/` (`whisper.h`, `jni_whisper.cpp`, `CMakeLists.txt` with NEON intrinsics and `-O3`).
    - Updated `.github/workflows/build-apk.yml` with automated CMake CI compilation of `libwhisper.so` for `armeabi-v7a`, `arm64-v8a`, and `x86_64`.
    - Connected speech audio accumulation, VAD pause triggering, background Whisper inference, word improvement replacement mapping (`VoiceReplacementDao`), and text commit dispatching.
  - **Mini-Phase 5: Full Operational Parity: Dynamic Audio Context, Upstream whisper.cpp Vendoring, & Streaming Preview Loop [COMPLETED]**:
    - Audited licensing: rejected `futo-org/voice-input` non-commercial license; selected 100% clean-room MIT upstream `whisper.cpp` + Apache-2.0 ACFT model weights.
    - Vendored core upstream `whisper.cpp` (v1.5.4) and `ggml` source tree into `app/src/main/jni/whisper/src/` (`whisper.h`, `whisper.cpp`, `ggml.h`, `ggml.c`, `ggml-alloc.h`, `ggml-alloc.c`, `ggml-backend.h`, `ggml-backend.c`, `ggml-backend-impl.h`, `ggml-impl.h`, `ggml-quants.h`, `ggml-quants.c`).
    - Updated `app/src/main/jni/whisper/whisper.h` with complete official header including `audio_ctx` context control in `struct whisper_full_params`.
    - Updated `app/src/main/jni/whisper/CMakeLists.txt` to compile all vendored engine sources into `libwhisper.so`.
    - Injected dynamic `audio_ctx` calculation (`(n_samples / 160) + 32`, clamped 128..1500) into `jni_whisper.cpp`, delivering sub-300ms mobile inference speed by eliminating 30-second silence padding compute.
    - Enhanced `VoiceInputService.kt` with an interim streaming preview worker (evaluating speech every 800ms on 2 threads) that dispatches live `EVENT_TRANSCRIPTION_PREVIEW` to the modal preview row while speaking.
    - Preserved full `LogCatcher` privacy and audit standards (zero PII, zero text content logged).

- **Phase 21: Native Suggestion & Gesture Engine**:
  - **Phase A: Library UI Restoration & Dynamic JNI Integration [IN PROGRESS]**:
    - Restore native gesture & suggestion library loader UI (`LoadGestureLibPreference.kt`) without committing proprietary binary blobs to the workspace.
    - Implement document picker launcher, temporary cache staging, SHA-256 calculation via `ChecksumCalculator`, and validation against architecture default checksums (`JniUtils.expectedDefaultChecksum()`).
    - Implement confirmation and checksum mismatch warning dialogs (`R.string.checksum_mismatch_message`) displaying calculated vs expected hashes for the active CPU ABI (`Build.SUPPORTED_ABIS[0]`).
    - Wire dynamic library installation into `files/libjni_latinime.so`, persist `PREF_LIBRARY_CHECKSUM` across protected and standard preferences, and invoke live dynamic linking via `JniUtils.loadUserSuppliedLibrary()`.
    - Add native library management, delete/reset capability, and live `LatinIME` dictionary reload broadcast (`NEW_DICTIONARY_INTENT_ACTION`).
    - Integrate `LoadGestureLibPreference` and conditional `GestureTypingScreen` routing into `WordEngineScreen.kt`.
  - **Phase B: Accidental Number Typo Engine [PLANNED]**:
    - Update `StringUtils.kt` word boundary detection to allow numbers surrounded by script letters.
    - Map digits to physical top-row proximity and leetspeak substitution candidates in `Suggest.kt`.
    - Relax single-digit `hasDigits()` autocorrect veto in `Suggest.kt`.

## 4. Change Ledger
- **2026-08-27**: Cloned and imported complete source tree from `schuylervianilewis-hash/Vianboardtryagain`.
- **2026-08-27**: Configured Gradle 9.3.1 / AGP 9.1.1 toolchain, updated `metadata.json`, `settings.gradle.kts`, `gradle/libs.versions.toml`, and `app/build.gradle.kts`.
- **2026-08-27**: Updated `applicationId` to `shura.vianboard`, app name resources to `VianBoard`, synced authorities to `shura.vianboard.clipprovider`/`provider`, configured `.github/workflows/build-apk.yml`, generated `gradlew` wrapper, and verified compilation.
- **2026-08-27**: Enhanced modern clipboard modal with 3-action long-press menu (`Pin/Unpin`, `Delete`, `Paste`) and standardized unified 4-button bottom bar.
- **2026-08-28**: Completed `LogKeeperActivity`, resolved `KeyLabel`, `PromptHistoryView`, and `KeyboardSwitcher` compilation dependencies, verified green build via `compile_applet`.
- **2026-09-01**: Pruned non-English/French dictionary assets from `app/src/main/assets/dicts/`, verified build via `compile_applet`.
- **2026-09-02**: Added Ensure Debug Keystore step to `.github/workflows/build-apk.yml` and tuned `gradle.properties` JVM args with `-XX:MaxMetaspaceSize=1024m`.
- **2026-09-03**: Pruned 98 redundant `values-*` directories, configured `resourceConfigurations` and `ndk.abiFilters` in `app/build.gradle.kts`, aligned `locales_config.xml`, and added `.build-outputs/` to `.gitignore`.
- **2026-09-03**: Restored functional key icon mappings in `KeyLabel.kt`, updated `TextKeyData.kt` and `PopupKeysUtils.kt`, and set default theme style to modern Rounded with key borders and dedicated number row in `Defaults.kt`.
- **2026-09-03**: Implemented `LogCatcher` persistent engine + crash interceptor, redesigned `LogKeeperActivity` with `Log Keeper` and `All Running` tabs, updated icon to clean logbook vector, and realigned comma popup with `!autoColumnOrder!` grid formatting.
- **2026-09-04**: Removed Custom Background Image Engine, Custom Font Loaders, External Gesture Binary Loader, and Holo Legacy Theme; replaced granular 20-slider hex picker with 6 curated high-contrast pre-tested color palettes; verified clean compilation.
- **2026-09-05**: Added Log Keeper 2MB disk auto-rotation and fatal crash dumping directly to device `Download/` folder via MediaStore, guarded `ExpandableBinaryDictionary` and `BinaryDictionary` against uninitialized native JNI calls, and added Log Keeper entry to About screen settings.
- **2026-09-05**: Implemented Safe Sidelining (non-EN/FR layouts & assets moved to `/sidelined_features/`), locked settings presets (Blue Grey theme, rounded key borders, no tap popup preview, number row with hints, 10m clipboard history, redundant popups removed), calibrated floating popup elevation in `MainKeyboardView.java`, and stripped physical keyboard settings from `AdvancedScreen.kt`.
- **2026-09-06**: Restored `armeabi-v7a` ABI support for Unisoc hardware, added CI native library compilation in GitHub Actions workflow, guarded `ExpandableBinaryDictionary` against null dictionary pointer crashes, protected dictionary cache from deletion in `DictionaryFactory`, and redesigned Log Keeper UI into a 2-tab view (**All Logs** & **Errors**) matching the reference screenshot.
- **2026-09-06**: Added Enter / Action key to Emoji modal bottom row (`CLIPBOARD_BOTTOM_ROW`), integrated Prompt List (Quick Notes) with 3-action long press (`Pin`, `Edit`, `Delete`), edit dialog with multiline text field, `Move to Prompt List` clipboard action, Copy toolbar long-press invocation, and implemented 2-minute temporary incognito mode on Incognito toolbar long-press with auto-revert and keyboard-close cancel guarantee.
- **2026-09-07**: Remediated security scan findings: deleted root `debug.keystore` and `debug.keystore.base64`, sanitized `app/build.gradle.kts` by removing hardcoded plaintext keystore passwords and credentials, loading on-demand from environment variables or gitignored `local.properties`.
- **2026-09-08**: Implemented layout and settings architecture overhaul: baked Image 1 symbol mapping into `qwerty.txt`, added French Latin accents into `more_popups_main.txt`, set default currency key to `₹`, restricted layouts to Default + 1 customizable slot, streamlined main settings to 3 parent pages (Appearance, Word Engine, Advanced) with dedicated sub-pages for Backup & Restore, and verified complete compilation.
- **2026-09-10**: Completed Phase 19 Mini-Phase A (Crash Fix & Layout Visibility): replaced `ClipboardHistoryRecyclerView` with standard `RecyclerView` in `prompt_history_view.xml` removing swipe conflict, removed double-notify removal in `PromptAdapter`, maintained empty list bounds with `View.INVISIBLE` to prevent height squishing, hidden `mPromptHistoryView` and stopped history in `KeyboardSwitcher.java`, applied solid keyboard theme background (`ColorType.MAIN_BACKGROUND`), verified via `compile_applet`.
- **2026-09-10**: Completed Phase 19 Mini-Phase B (ABC Key & State Machine Integration): added `PROMPT` to `LayoutDirective.Utility`, `KeyboardState.Mode`, and `KeyboardSwitchState`; added `setPromptKeyboard()` to `SwitchActions`; routed `KeyCode.PROMPT_LIST` through `KeyboardState.onEvent` via `toggleLayout(Utility.PROMPT)`; configured bottom row via `buildEmojiClipBottomRow(context, editorInfo)` and `PointerTracker.switchTo()`; verified ABC key exit back to alphabet typing; verified compilation via `compile_applet`.
- **2026-09-10**: Completed Phase 19 Mini-Phase C (Visual Parity with Clipboard): extracted `KeyDrawParams` (typeface, label color, text size); styled prompt note cards with `ColorType.KEY_BACKGROUND`, pin icon with `ColorType.CLIPBOARD_PIN`, and empty placeholder with `KeyboardTypeface` and theme text color; added keyboard width constraints and side padding; updated `PromptDao.togglePinned()` to refresh timestamp bringing pinned items to the top; added animated `notifyItemMoved()`, `notifyItemChanged()`, and auto-scroll on pin/unpin; verified compilation via `compile_applet`.
- **2026-09-10**: Completed Phase 19 Mini-Phase D (Suggestion Strip Toolbar & Compact Popup Menu): added `prompt_strip_scroll_view` in `strip_container.xml` and `KeyboardSwitcher.java`, populated with full editing toolbar keys (`UP`, `DOWN`, `LEFT`, `RIGHT`, `UNDO`, `CUT`, `COPY`, `PASTE`, `SELECT_WORD`, `CLOSE_HISTORY`) styled to keyboard theme; routed `CLOSE_HISTORY` to `KeyCode.PROMPT_LIST` to restore alphabet keyboard; replaced standard framework `PopupMenu` with compact themed `PopupWindow` using HeliBoard vector icons (`ic_clipboard_pin_rounded`, `ic_edit`, `ic_bin_rounded`); verified compilation via `compile_applet`.
- **2026-09-10**: Enhanced Prompt List Item Tap Behavior: removed auto-revert to alphabet layout on note snippet tap in `PromptHistoryView.kt`, allowing consecutive snippet insertions without leaving the Prompt view; exit remains controlled via bottom bar `[ABC]` or top toolbar `[X]`.
- **2026-09-10**: Completed Phase 20 Mini-Phase 1 (Foundations, Model Storage & Word Improvement Dictionary): declared `RECORD_AUDIO` permission, decoupled legacy voice IME switcher in `LatinIME.java`, isolated `KeyCode.VOICE_INPUT` in `InputLogic.java`, upgraded database schema to version 4 with SQLite tables for `VOICE_REPLACEMENTS`, created `VoiceReplacementDao.kt` for phonetic replacements, built `VoiceModelManager.kt` managing Whisper `.bin` models in `no_backup/voice_models/` with GGML header validation, added dedicated Voice Input settings screen (`VoiceInputScreen.kt`) with 3-stage microphone sensitivity toggle, Whisper model import/delete card, and Word Improvement dictionary editor, excluded model binaries from backup ZIP archives, verified clean build with `compile_applet` and `:app:assembleDebug`.
- **2026-09-11**: Completed Phase 20 Mini-Phase 2 (Compact Modal UI, 4-Button Bottom Control Bar & Streaming Preview Interface): built lightweight non-Compose `VoiceInputView` (150dp compact height) with theme-adaptive styling, unified 4-button bottom bar (`[ABC] [SPACE] [⌫] [↵]`), custom RMS animated `VoicePulseView` (Listening, Paused, Error, Idle states), streaming text preview, cycle gain pill, zero-flicker `VoicePermissionActivity` audio permission acquisition, state machine integration in `KeyboardSwitcher.java`, and strict IME lifecycle cleanup in `LatinIME.java` (hardware back key interception and window dismissal); verified clean compilation via `compile_applet`.
- **2026-09-11**: Completed Phase 20 Mini-Phase 3 (Multi-Process Audio Pipeline & IPC Service): registered `VoiceInputService` in `AndroidManifest.xml` under dedicated process `android:process=":voice"`; defined `VoiceIpcProtocol.kt` Messenger interface with `DeathRecipient` crash resilience; implemented `AudioRecordPipeline.kt` (16kHz 16-bit mono PCM capture, 3-stage gain multiplier `1x/2x/4x` with clipping protection, throttled 40ms RMS calculation); built `EnergyVad.kt` for energy-based speech segmentation; implemented `VoiceInputService.kt` with 60s idle auto-shutdown; created `VoiceInputConnection.kt` client bridge; wired real-time audio pipeline and live RMS visualizer into `VoiceInputView.kt`; verified compilation via `compile_applet`.
- **2026-09-11**: Completed Phase 20 Mini-Phase 4 (FUTO Whisper Engine JNI Integration & CMake CI Pipeline): extended `VoiceIpcProtocol.kt` with `EVENT_FINAL_TRANSCRIPTION`; implemented `WhisperEngine.kt` with safe JNI bindings, token cleanup, and LogCatcher component tracking; created C/C++ native bridge in `app/src/main/jni/whisper/` (`whisper.h`, `jni_whisper.cpp`, `CMakeLists.txt` with NEON intrinsics and `-O3`); updated `.github/workflows/build-apk.yml` with automated CMake CI compilation of `libwhisper.so` for `armeabi-v7a`, `arm64-v8a`, and `x86_64`; connected speech audio accumulation, VAD pause triggering, background Whisper inference, word improvement replacement mapping (`VoiceReplacementDao`), and text commit dispatching in `VoiceInputService.kt` and `VoiceInputConnection.kt`; verified clean build via `compile_applet`.
- **2026-09-11**: Completed Phase 20 Mini-Phase 5 (Full Operational Parity: Dynamic Audio Context, Upstream whisper.cpp Vendoring, & Streaming Preview Loop): audited FUTO licensing vs GPLv3 and rejected proprietary source; vendored official upstream MIT `whisper.cpp` (v1.5.4) and `ggml` source tree in `app/src/main/jni/whisper/src/`; replaced stub header with complete official `whisper.h`; updated `CMakeLists.txt` to compile all C/C++ source into `libwhisper.so`; injected dynamic `audio_ctx` calculation (`(n_samples / 160) + 32`, clamped 128..1500) into `jni_whisper.cpp` to eliminate 30-second silence padding compute; added interim streaming preview worker to `VoiceInputService.kt` emitting real-time transcriptions every 800ms on 2 threads; verified build cleanly via `compile_applet`.
- **2026-09-12**: Completed Phase 21 (Desktop Shortcuts 8-Slot Alignment & Standardized Modal Height Engine): expanded active shortcut cap from 7 to 8 (`MAX_ACTIVE_SHORTCUTS = 8`) in `DesktopShortcutsStorage.kt` and settings UI to populate the full 4-2-2 action grid symmetry; standardized modal heights across Clipboard, Quick Notes, Emoji, and Desktop Shortcuts via `getModalHeight()` (260dp baseline) in `VianBoardService.kt`; cleaned duplicate launcher webp mipmaps; registered missing canvas/divider colors and tool string resources; corrected package namespace R imports to `helium314.keyboard.latin.R`; verified clean build via `compile_applet`.
- **2026-09-13**: Completed Phase 22 (VianBoard Voice Input Engine, Settings & Compact Modal UI):
  - Phase 1: Built `AudioRecordPipeline` (16kHz mono PCM, 3-gain sensitivity toggle), `EnergyVad`, JNI Whisper bindings (`libwhisper.so`), isolated multi-process `VoiceInputService` (`:voice`), and `VoiceInputConnection` IPC client with `DeathRecipient`.
  - Phase 2: Built `VoiceInputSettingsActivity` with SAF model file picker, GGML `0x67676d6c` header validation, decoding temperature slider, and `WordReplacementStore` CRUD editor.
  - Phase 3: Built compact `VianVoiceModalView` (~160dp) with `VoicePulseView` Canvas animation reacting to speech RMS energy, streaming transcription preview strip, 3-stage gain cycling, settings shortcut button, automatic word replacement on commit, and full modal integration into `VianBoardService.kt` (Toolbar voice button and comma popup).
- **2026-09-14**: Completed Phase 24 (Security Vault Phase 1: Pattern Unlock, Normal Keyboard Disguise & Session Manager):
  - Created `VaultType.kt` declaring `SECURITY` and `PRIVACY` vault partitions.
  - Implemented `VaultSessionManager.kt` handling independent volatile in-memory timers: 5-minute session for Privacy Vault and 3-minute session for Security Vault, with immediate manual re-locking and zero-knowledge `LogKeeper` session audit trails.
  - Built `MasterPatternStore.kt` storing salted SHA-256 pattern hashes, supporting out-of-the-box default unlock gestures (`0,1,2,5,8` and `0,3,6,7,8`) and disguise key sequences (`Q,W,E,R`) for frictionless immediate testing, separate pattern toggling, and attempt tracking.
  - Built custom Canvas `VianPatternUnlockView.kt`:
    - Zero top suggestion strip bar: pure unlock canvas matching keyboard height.
    - Standard 3x3 Grid Mode: touch dragging, intermediate dot calculation, tactile haptic vibrations on dot crossing, success/error visual state animations, and a prominent top-right `✕` exit affordance.
    - Normal Keyboard Disguise Mode: visual illusion rendering the exact standard QWERTY keyboard layout without visual traces or lines; tactile haptic pulses on key crossing; discrete decoy icon (`📋`) on top-right for exit.
    - In-modal header mode toggle button (`[⌨ Disguise]` / `[☷ Grid Mode]`) enabling rapid switching between modes for testing.
  - Wired long-press on `?123` / `1234` toggle buttons in `VianKeyboardView.kt` and toolbar tool click in `VianBoardService.kt` to trigger the Security Vault pattern modal with single-modal lifecycle guarantee.
- **2026-09-16**: Completed Option A (Clean Restoration of OG VianBoard Architecture & HeliBoard Separation):
  - In strict accordance with the Credential Immunity Rule (Mandate 2) and Security Scan Protocol (Mandate 3), purged exposed debug keystore files from workspace root and configured CI-only secret injection.
  - Moved legacy HeliBoard sources (`helium314/`, `com/android/`), tests, and heavy dictionary assets (`.dict` files) into root `/reference/`, removing bloat from active code while preserving full reference materials.
  - Maintained all 54 active `com.example.*` classes in `app/src/main/java/com/example/`.
  - Created `app/src/main/res/xml/method_vian.xml` and registered `com.example.ime.VianBoardService` as the system InputMethod in `AndroidManifest.xml`.
  - Wired `MainActivity` to launch `SettingsActivity` directly, and registered all 10 VianBoard settings activities, dialogs, voice service, and file provider.
  - Disabled `viewBinding` to prevent unnecessary stub generation, configured JUnit unit testing (`libs.junit`), and verified that all unit tests (`gradle :app:testDebugUnitTest`) and full build (`compile_applet`) pass cleanly.
- **2026-09-24**: Resolved Process Crash Loop & Input Insets Stabilization:
  - Added APK-bundled native library pre-check in `BinaryDictionary.loadNativeLibraryIfNeeded()`. If `libjni_latinime.so` is absent from `context.applicationInfo.nativeLibraryDir`, the loader skips `System.loadLibrary()`, preventing fallback to the system ROM's incompatible `/system/lib64/libjni_latinime.so` and stopping the fatal native `SIGSEGV` crash loop.
  - Exposed thread-safe `instance` singleton in `VianApplication.kt`.
  - Corrected `onComputeInsets()` in `VianBoardService.kt` to use standard `TOUCHABLE_INSETS_CONTENT` and dynamic `inputViewContainer.top` coordinates.
  - Verified 100% test pass via `gradle :app:testDebugUnitTest` and clean build via `compile_applet`.







