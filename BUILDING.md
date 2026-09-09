# Building TrashUSBcam

## Shared build policy

- Android Gradle Plugin **9.3.1** and Gradle **9.6.0**.
- **JDK 21** is the normal Gradle runtime. CI validates Gradle on JDK 17 and 21.
- Compilation and JVM unit tests use an explicit **JDK 17 toolchain**. Java/Kotlin bytecode targets remain **17**.
- The pinned Foojay toolchain resolver downloads a suitable JDK 17 on the first build if none is installed. Set up JDK 17 in advance for offline builds.
- Use the committed wrapper, which verifies the Gradle distribution with its published SHA-256 checksum.
- Android compile/target SDK remains **35**. The app's minimum Android version is unchanged.

The JDK that runs an editor's language server is separate from the JDK running Gradle and the compiler toolchain. In VS Code, select the build runtime through `java.import.gradle.java.home` (Red Hat Java) and `jdk.project.jdkhome` (Oracle Java). Oracle's Gradle runtime selection may also need to select the same registered JDK. Keep machine-specific JDK paths in local editor settings rather than committed Gradle properties.

## Local setup and validation

Install Android SDK Platform 35 and Build Tools 36.0.0. Configure the SDK location with `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir`.

On Windows, Android Studio's bundled JBR can supply JDK 21. Verify its version if Android Studio has been upgraded:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace
```

On Linux/macOS, set `JAVA_HOME` to a JDK 21 installation, then run:

```sh
./gradlew --version
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
```

The APK is under `app/build/outputs/apk/debug/` relative to the Gradle project. Lint reports are under `app/build/reports/`; JVM test reports are under `app/build/reports/tests/`.

CI uses the same wrapper and validation tasks, with a preinstalled JDK 17 compiler and a matrix of Gradle runtimes. Gradle setup validates wrapper JARs; the wrapper properties validate the downloaded distribution.

To investigate deprecation warnings, add `--warning-mode all` to the validation command. Resolve the reported source before upgrading to another major Gradle version.

The AGP 9 migration uses built-in Kotlin and keeps AUSBC 3.2.7, its dependency exclusions, and ARM ABI filters unchanged. Build checks cannot establish camera compatibility: physical USB/Wi-Fi preview, capture, recording/playback, and reconnect checks remain separate hardware validation.
