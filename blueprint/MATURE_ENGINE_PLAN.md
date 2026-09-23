# VIAN BOARD — MATURE ENGINE RECONNECTION & CI/CD PLAN

**Target Document:** `blueprint/MATURE_ENGINE_PLAN.md`  
**Supercedes:** `blueprint/BATCH_IMPORT_PLAN.md` (obsolete Kotlin engine re-write)  
**Core Strategy:** 
1. **Preserve Mature Upstream Code:** Strictly do **NOT** rewrite the mature AOSP / HeliBoard C++ dictionary traversal and prediction engine in Kotlin. Preserve the battle-tested JNI code paths (`app/src/main/jni`).
2. **Target ABIs Strictly Restricted:** Restrict native compilation to **only `arm64-v8a` and `armeabi-v7a`** (cutting out x86/x86_64 bloat and halving build times).
3. **CI/CD via GitHub Actions:** Delegate heavy native compilation and release APK generation to a GitHub Actions workflow targeting ARM v8a and v7a.
4. **Log Keeper Centralization:** Connect all engine lifecycles, JNI library loads, dictionary accesses, and error traces directly into the on-device `LogKeeper` and `LogViewerActivity`.
5. **Clean Deprecation:** Identify and delete all obsolete temporary batch rewrite files that are no longer needed.

---

## 1. ARCHITECTURAL DECISION RECORD (ADR)

| Decision | Previous Approach | New Strategic Direction | Rationale |
|---|---|---|---|
| **Engine Architecture** | Rebuild entire dictionary & suggestion engine in pure Kotlin (Batches 2 & 3). | **Preserve Mature C++ / JNI Upstream Engine (`app/src/main/jni`)**. | Decades of mature edge-case handling (gesture math, proximity matrices, spatial penalty curves) are already battle-tested. Rewriting in Kotlin is high risk and causes loss of mature code. |
| **Target ABIs** | All ABIs or unspecified. | **Only `arm64-v8a` & `armeabi-v7a`**. | Physical Android devices are almost exclusively ARM. Dropping x86/x86_64 cuts APK size significantly and eliminates unneeded CI build time. |
| **Build & Packaging** | Local container building fat APKs. | **GitHub Actions CI/CD Pipeline**. | Automates NDK compilation, Gradle packaging, and APK artifact publishing cleanly on remote runners. |
| **Diagnostics & Telemetry** | Scattered logs or adb logcat reliance. | **Centralized `LogKeeper` connected to `LogViewerActivity`**. | On-device diagnostics for JNI library loading, dictionary status, and performance metrics directly accessible via keyboard shortcuts or settings. |

---

## 2. OBSOLETE FILES AUDIT & DELETION LIST

The following files created during previous attempts to rewrite or stub the engine in Kotlin are obsolete and will be cleaned up or superseded:

### Obsolete Blueprint Files
1. `blueprint/BATCH_IMPORT_PLAN.md`: Obsoleted by this document (`MATURE_ENGINE_PLAN.md`). To be deleted.

### Obsolete / Redundant Temporary Stubs
2. Temporary standalone test stubs or redundant Kotlin trie prototypes that bypass the native JNI engine will be phased out or updated to test the mature interfaces.

---

## 3. IMPLEMENTATION PHASES

### Phase 1: Native Build & ABI Configuration (arm64-v8a & armeabi-v7a only)
- Update `app/build.gradle.kts`:
  - Configure `ndk.abiFilters` strictly to `listOf("arm64-v8a", "armeabi-v7a")`.
  - Configure `externalNativeBuild` pointing to `app/src/main/jni/Android.mk`.
- Update `app/src/main/jni/Application.mk`:
  - Set `APP_ABI := arm64-v8a armeabi-v7a`.
  - Set `APP_PLATFORM := android-21`.
  - Set `APP_STL := c++_static`.
- Verify resource and package alignments under `com.example`.

### Phase 2: Log Keeper Integration & Observability
- Create/Connect `com.example.logger.LogKeeper`:
  - Thread-safe circular ring buffer capturing categorized log events:
    - `[JNI]`: Library load status (`libjni_latinime.so`), symbols resolution.
    - `[DICT]`: Asset dictionary loading (`main_en-US.dict`, `main_fr.dict`), memory mapping, and word count.
    - `[ENGINE]`: Suggestion queries, latency benchmarks, auto-correction decisions.
    - `[IME]`: Subtype switches, input connection commits, selection changes.
    - `[ERROR]`: Uncaught exceptions and fallbacks.
- Wire `LogViewerActivity` to live-stream from `LogKeeper` with filter chips (`All`, `JNI`, `Engine`, `IME`, `Errors`) and single-tap copy/export.

### Phase 3: Mature Engine Connection & JNI Bridge
- Connect Kotlin/Java engine coordinator (`DictionaryFacilitator`, `Suggest`, `WordComposer`) to the mature native LatinIME binary dictionary bridge (`BinaryDictionary`).
- Ensure graceful fallback: If native library is absent during JVM unit testing, fall back to safe interface mocks or asset verification without crashing.
- Support spatial proximity scoring and dictionary frequency lookups directly using the mature native methods.

### Phase 4: GitHub Actions CI/CD Pipeline (`.github/workflows/build-apk.yml`)
- Create automated workflow triggered on `push` and `workflow_dispatch`:
  - **Runner**: `ubuntu-latest`.
  - **JDK**: Java 17 (Temurin).
  - **Android SDK & NDK**: Install standard Android NDK (r25c or r26b) and CMake.
  - **Build Command**: `./gradlew assembleDebug assembleRelease` (restricted strictly to ARM v8a & v7a).
  - **Artifact Upload**: Upload generated APKs (`app-debug.apk` / `app-release-unsigned.apk`) to GitHub Actions Run artifacts.
- Fast, reproducible, and cloud-hosted.

---

## 4. VERIFICATION CHECKLIST

- [x] `app/build.gradle.kts` and `Application.mk` strictly contain only `arm64-v8a` and `armeabi-v7a`.
- [x] No Kotlin rewrites of binary dictionary traversal or scoring algorithms; mature AOSP/HeliBoard C++ engine preserved.
- [x] `LogKeeper` actively records native library loading and dictionary operations with dedicated LogTags.
- [x] `LogViewerActivity` displays recorded logs cleanly with category filtering (ALL, JNI, DICT, ENGINE, IME, ERROR).
- [x] `.github/workflows/build-apk.yml` is committed, valid YAML, targeting exclusively ARM v8a and v7a.
- [x] Old obsolete `BATCH_IMPORT_PLAN.md` and duplicate workflows are removed.
