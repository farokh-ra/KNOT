# Build and verification

## Toolchain used for the release build

| Tool | Version |
|---|---|
| JDK | Temurin 21.0.13+9 (JDK 17+ also works) |
| Gradle | 8.11.1 (wrapper) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Android SDK platform | 34 |
| Build tools | 34.0.0 |
| Target / min SDK | 34 / 26 |

The build was performed on Windows with Git Bash; `local.properties` points at the SDK and
`JAVA_HOME` at the JDK. Nothing else is required: no NDK, no Android Studio, no Node.

## Commands

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:testDebugUnitTest      # JVM test suite
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

## Signing

The release key is local and git-ignored:

- `signing/release.jks` (RSA 4096, validity 30 years, alias `twoperson`)
- `signing/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`

Both are excluded by `.gitignore` and must never be committed or shared. If
`signing/keystore.properties` is missing, `assembleRelease` produces an unsigned APK rather
than silently signing with a debug key.

To create a fresh key:

```bash
keytool -genkeypair -keystore signing/release.jks -storetype JKS -alias twoperson \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Two-Person Messenger, OU=Android, O=Two Person Messenger, L=Local, ST=Local, C=US"
```

Then write the four properties. Reusing the same key matters: a different key means the
installed app cannot be upgraded in place.

## Verifying an APK

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs <apk>
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging <apk>
sha256sum <apk>
```

Expected: `package: name='dev.twoperson.messenger' versionCode='1' versionName='1.0.0'`,
`sdkVersion:'26'`, `targetSdkVersion:'34'`, and a verified v2/v3 signature with the local
certificate.

## Release configuration facts

- Release signer: `CN=Two-Person Messenger, OU=Android, O=Two Person Messenger, L=Local, ST=Local, C=US`
  (the certificate name predates the rename to Knot and is intentionally unchanged — a new key
  would make the app a different install and lose pairing and history),
  RSA 4096
- Signing schemes: APK Signature Scheme v2 + v3, set explicitly in `app/build.gradle.kts`
  (`enableV1Signing = false`, `enableV2Signing = true`, `enableV3Signing = true`); v2 is what
  every API 24+ device verifies, so a v1 JAR signature would only add bytes
- Permissions in the built APK: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
  `CHANGE_WIFI_MULTICAST_STATE`, `CAMERA` (plus the AndroidX-generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`)
- No prebuilt APK is committed. Record the SHA-256 of your own build and keep it alongside the
  artifact you distribute.
- Installation troubleshooting: `docs/INSTALL.md`

## Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The debug and release builds share the same application ID and different signing keys, so
uninstall the debug build before installing the release build (or vice versa).
