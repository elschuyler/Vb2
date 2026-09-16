# Master Plan: VianBoard Clean Rebuild Architecture

## 1. Purpose & Protocol (Anti-Bloat Directive)
As an application matures through iterative updates, residual code paths and architectural layering can introduce bloat, friction, and memory overhead. 
This Master Plan dictates the ideal, clean structure, package layout, and terminology for a clean rebuild of VianBoard from scratch.

When a full rebuild is triggered:
1. Move the current repository into `/reference/`.
2. Re-initialize the project root with the clean architecture outlined below.
3. Surgically import and adapt logic from `/reference/` matching these exact standards.

---

## 2. Target Architecture Overview
- **Application ID**: `shura.vianboard`
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)
- **Programming Language**: 100% Kotlin (eliminating legacy Java bridging).
- **Core Input Engine**: Clean IME service lifecycle with coroutine-based asynchronous processing.
- **UI & Layout Engine**: Modern Canvas & Jetpack Compose hybrid (Compose for Settings & UI modals, hardware-accelerated Canvas for key matrix rendering).
- **Local Persistence**: Room SQLite database with type-safe DAOs for Clipboard and Prompts.
- **Diagnostics**: Ring-buffer zero-overhead Log Keeper with disk-rotation and MediaStore crash dumping.

---

## 3. Package & Directory Structure

```
shura.vianboard/
├── core/
│   ├── ime/
│   │   ├── VianImeService.kt            # Core InputMethodService entry point
│   │   ├── InputConnectionHelper.kt     # Safe text committing & cursor manipulation
│   │   └── StateMachine.kt              # Sealed class keyboard states (Alphabet, Symbols, Utility)
│   ├── keyboard/
│   │   ├── KeyboardView.kt              # High-performance hardware-accelerated key canvas
│   │   ├── KeyDrawParams.kt             # Unified theme metrics, typography, and colors
│   │   ├── KeyLayoutParser.kt           # Asset layout loader (JSON / text layouts)
│   │   └── PointerTracker.kt            # Multitouch tracking and gesture/glide resolution
│   └── text/
│       ├── WordComposer.kt              # Composing text manager
│       ├── SuggestionEngine.kt          # Offline unigram/ngram dictionary facilitator
│       └── AutoCorrection.kt            # Punctuation & auto-capitalization logic
├── data/
│   ├── db/
│   │   ├── VianDatabase.kt              # Single Room database for user data
│   │   ├── clipboard/
│   │   │   ├── ClipboardEntity.kt       # Entity: id, text, timestamp, isPinned
│   │   │   └── ClipboardDao.kt          # CRUD queries with Flow updates
│   │   └── prompt/
│   │       ├── PromptEntity.kt          # Entity: id, title, content, isPinned, sortOrder
│   │       └── PromptDao.kt             # CRUD queries with Flow updates
│   └── preferences/
│       ├── AppPreferences.kt            # DataStore / SharedPreferences typed wrapper
│       └── Defaults.kt                  # Immutable baseline defaults
├── ui/
│   ├── components/
│   │   ├── Modals/
│   │   │   ├── ClipboardModal.kt        # On-demand lazy clipboard view
│   │   │   ├── PromptModal.kt           # On-demand lazy prompt notes view
│   │   │   └── EmojiModal.kt            # Emoji palette grid with search
│   │   ├── Popups/
│   │   │   ├── KeyPopupView.kt          # Floating long-press accent/symbol popup
│   │   │   └── CompactActionMenu.kt     # Unified 3-action context popup (Pin, Edit, Delete)
│   │   └── Toolbar/
│   │       ├── ToolbarStrip.kt          # Adaptive suggestion / utility action strip
│   │       └── ToolbarActions.kt        # Quick actions: Nav arrows, Undo, Prompts, Incognito
│   ├── settings/
│   │   ├── SettingsActivity.kt          # Single-activity Jetpack Compose host
│   │   ├── navigation/                  # Type-safe Compose navigation routes
│   │   └── screens/
│   │       ├── AppearanceScreen.kt      # Themes, key borders, number row, layouts, currency
│   │       ├── WordEngineScreen.kt      # Correction, gestures, offline dictionaries
│   │       ├── AdvancedScreen.kt        # Backup & restore, temporary incognito, About
│   │       └── LogKeeperScreen.kt       # 2-tab diagnostics: All Logs & Errors
│   └── theme/
│       ├── ColorPalettes.kt             # Curated presets (Material You, AMOLED, Slate, etc.)
│       └── KeyboardTheme.kt             # Design tokens: radii, elevations, margins, fonts
└── diagnostics/
    ├── LogCatcher.kt                    # Global exception and runtime log buffer
    └── CrashReporter.kt                 # Download/ folder crash dump exporter
```

---

## 4. Key Architectural Standards

1. **State Machine Driven**:
   - Keyboard states are strictly represented as a Kotlin sealed hierarchy (`KeyboardState: Alphabet, Symbols, Utility(Clipboard, Prompt, Dpad, Numpad)`).
   - No view may bypass the state machine; transitions automatically clean up and hide sibling views.

2. **Zero-Overhead On-Demand Modals**:
   - Modals (Clipboard, Prompt, Emoji) never load in background or allocate views until explicitly requested.
   - When exiting a modal, all adapters and DB observers are detached (`adapter = null`) immediately.

3. **Single Design Token Pipeline**:
   - Typography, key corner radii, background colors, and active key styles are calculated in one place (`KeyDrawParams`) and distributed to all views.
   - Modals share the exact same card background, font family, and elevation as physical keyboard keys.

4. **Credential & Privacy Immunity**:
   - Zero hardcoded keystores or secrets in build scripts.
   - 100% offline; zero internet permissions declared in manifest.
   - Log Keeper logs strictly operational metadata (component, stack trace, timestamp); zero user input, PII, or clipboard content.
