## Project Overview

Neko Assistant is an Android application that leverages Shizuku to create virtual displays and control applications with elevated privileges. The app uses Jetpack Compose for UI and AIDL for inter-process communication with a privileged service.

## CRITICAL: Do Not Modify

**NEVER modify `UserService.kt` under any circumstances.** This file is a Shizuku user service that extends `IUserService.Stub()` and runs with elevated privileges. It has a specific architecture that must not be changed:
- It extends `IUserService.Stub()` directly (NOT a regular Android Service or LifecycleService)
- It has special constructors required by Shizuku
- Any modifications will break the Shizuku integration
- If you need to make changes to service behavior, ask the user first

## Build and Development Commands

### Building
```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install debug APK
```

### Testing
```bash
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on connected device
```

### Code Quality
```bash
./gradlew lint                   # Run Android lint checks
./gradlew lintDebug              # Run lint on debug variant only
```

### Cleaning
```bash
./gradlew clean                  # Clean build artifacts
```

## Architecture

### Core Components

**MainActivity** (`MainActivity.kt`)
- Entry point of the application
- Manages Shizuku permission lifecycle using `useShizukuStatus()` composable
- Binds to `UserService` via Shizuku when permission is granted
- Provides UI for creating virtual displays and launching apps on them

**UserService** (`UserService.kt`)
- AIDL-based service that runs with elevated privileges via Shizuku
- Creates and manages virtual displays with system-level flags
- Captures display content using `ImageReader` and provides bitmaps
- Launches activities on virtual displays using reflection to access internal Android APIs
- Runs in a separate process (suffix: "service")

**AIDL Interface** (`IUserService.aidl`)
- Defines the contract between MainActivity and UserService
- Methods: `startDisplay()`, `getLastBitmap()`, `startActivity()`, `destroy()`

### System Wrappers

The `wrappers/` package contains utilities for accessing hidden Android APIs:

**Workarounds.kt**
- Initializes a fake `ActivityThread` to provide system context
- Required for accessing system services that expect proper Android framework initialization
- Creates `ConfigurationController` to satisfy internal framework requirements

**FakeContext.kt**
- Wraps system context with a fake package name (`com.android.shell`)
- Allows the service to operate as if it were a system component
- Singleton pattern to ensure consistent context across the application

**ServiceManager.kt**
- Provides access to system services via reflection
- Uses `android.os.ServiceManager.getService()` to retrieve service binders

**DisplayManager.kt**
- Wrapper for creating virtual displays using internal APIs
- Provides both mirroring and standalone virtual display creation

## Key Technical Details

### Shizuku Integration
- Requires Shizuku app to be installed and running on the device
- App requests permission via `Shizuku.requestPermission()`
- UserService is bound as a Shizuku user service with daemon mode disabled
- Service lifecycle is tied to Shizuku availability

### Virtual Display Creation
- Uses undocumented `DisplayManager` flags for advanced features:
  - `VIRTUAL_DISPLAY_FLAG_TRUSTED` (API 33+): Allows system-level trust
  - `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` (API 33+): Isolated display group
  - `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED` (API 33+): Display always unlocked
  - `VIRTUAL_DISPLAY_FLAG_OWN_FOCUS` (API 34+): Independent focus handling
  - `VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP` (API 34+): Device-level grouping
- Captures frames using `ImageReader` with RGBA_8888 format
- Bitmap sharing via AIDL using `Bitmap.asShared()`

### Activity Launch Mechanism
- Uses reflection to access `ActivityManagerNative.getDefault()`
- Calls `startActivityAsUser()` with virtual display ID in `ActivityOptions`
- Launches activities as `com.android.shell` package to bypass permission checks

## Dependencies

- **Shizuku API** (13.1.5): Provides privileged service execution
- **Jetpack Compose**: Modern UI toolkit (Material 3)
- **Kotlin**: Primary language (2.3.10)
- **Android SDK**: Targets API 36, minimum API 31

## Important Constraints

- Requires Android 12 (API 31) or higher due to API usage
- Must have Shizuku installed and granted ADB/root access
- Virtual display features require specific Android versions (flags vary by API level)
- AIDL interface uses explicit transaction codes for stability
- Service runs in separate process and must be properly cleaned up

## Development Notes

- When modifying AIDL, rebuild the project to regenerate stub classes
- Virtual display flags are version-dependent; check API level before using
- Reflection-based code is fragile across Android versions; test thoroughly
- Bitmap transfers via AIDL can be memory-intensive; use `asShared()` for efficiency
- The app uses `BuildConfig.DEBUG` to enable debuggable service mode
