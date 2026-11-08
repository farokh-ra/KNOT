# Installing on a phone

The APK is a single universal build: no native libraries, so it runs on any arm64, arm32 or
x86 Android device with Android 8.0 (API 26) or newer. It is signed with APK Signature
Scheme v2 and v3, which is what every Android 8+ device verifies.

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the `.apk` file to the phone and open it from the phone's Files app.

## Samsung / Galaxy-specific checks

Samsung's One UI adds several gates that produce a generic "App not installed" and say
nothing useful. Check these in order:

1. **An older copy is installed.** The debug APK and the release APK share the package name
   but use different signing keys, so one cannot replace the other. Uninstall the app first
   (it is listed as **Knot**; earlier internal builds appear as **Two-Person Messenger**),
   then install again.
2. **Unknown sources is per-app.** Opening the APK from *My Files* requires *My Files* to be
   allowed: `Settings → Apps → My Files → Install unknown apps → Allow`. If you open it from
   Chrome or a browser, the permission belongs to that browser instead.
3. **Auto Blocker** (One UI 6.1+, `Settings → Security and privacy → Auto Blocker`) blocks
   sideloaded apps. Turn it off temporarily, install, then turn it back on.
4. **Play Protect scanning**: if the dialog offers "Install anyway" / "Scan app", choose
   install; Play Protect can also be paused in the Play Store settings.
5. **Free storage**: the installer needs roughly twice the APK size free.
6. **Transfer integrity**: do not let a messaging or cloud app "preview" the file. Transfer
   it by USB cable or download it directly to the phone, then confirm the size matches the
   value shown by your file manager or `ls -l`.

## "This app is unknown to Google Play" and the installer closes by itself

That prompt is Play Protect's normal sideload warning. Tapping *Install anyway* and then
seeing the installer sheet vanish with nothing installed means Play Protect (or Samsung's
Auto Blocker) aborted the install after the prompt. In order:

1. **Turn off real-time app scanning while installing.** Play Store → profile picture →
   Play Protect → gear icon → turn off *Scan apps with Play Protect*. Install the APK, then
   turn the setting back on.
2. **Turn off Auto Blocker** if this One UI version has it: Settings → Security and privacy →
   Auto Blocker → off. Re-enable after installing.
3. **Install from internal storage.** Copy the APK to `Internal storage/Download` and open
   it from *My Files*. Files kept inside a chat app's folder or on an SD card can make the
   installer abort silently.
4. **Install through adb instead**, which skips the OEM installer UI and prints the real
   error code:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   Settings → About phone → Software information → tap *Build number* seven times, then
   Settings → Developer options → USB debugging → on.
5. **Retry the dialog twice.** Play Protect sometimes blocks the first "Install anyway" and
   allows the second attempt.

If all of that fails, the message from step 4 is the only reliable diagnostic — capture it.

## Error messages and what they mean

| Message | Cause |
|---|---|
| *App not installed* (no detail) | existing copy signed with another key, blocked source, or Auto Blocker |
| *There was a problem parsing the package* | transfer corrupted/truncated the APK, or the file is not really an APK |
| *App not installed as app isn't compatible with your phone* | Android version older than 8.0 |
| *For your security, your phone is not allowed to install unknown apps from this source* | unknown-sources permission missing for the app that opened the file |
| *Blocked by Play Protect* | Play Protect scanning; choose "Install anyway" or pause scanning |

## Verifying what you are about to install

```bash
apksigner verify --verbose --print-certs app-release.apk
aapt2 dump badging app-release.apk
sha256sum app-release.apk
```

Expected: package `dev.twoperson.messenger`, versionCode `2`, versionName `1.1.0`,
`sdkVersion:'26'`, `targetSdkVersion:'34'`, signed by your local release certificate (the
build on this machine uses `CN=Knot Release, O=Knot, C=US`), and the same SHA-256 as the
checksum you recorded for your build. A different signing key means the app cannot upgrade
an existing install in place.

Upgrading in place from 1.0.0 is supported because the application ID and signing key are
unchanged. The first launch of 1.1.0 migrates the old single conversation into the `primary`
contact, keeping its history, invitation and pinned identity.

## After installing

The app needs no permissions to start. Camera permission is requested only when you tap
*Scan QR code*; you can always paste the invitation code instead.
