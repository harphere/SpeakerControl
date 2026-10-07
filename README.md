# MicControl v1.0.0 — Android 16 LSPosed prototype

Standalone module; package `dev.chet.miccontrol`. Requires a rooted phone with a legacy Xposed API compatible LSPosed/Vector installation supporting System Framework hooks. No microphone recording, network permission, boot-time state change, or global permission-check bypass is installed. Intended for the owner's deliberate Tasker automation.

## GitHub Actions
1. Extract this ZIP. Upload the **contents of MicControl**, including `.github`, to a GitHub repository root. Do not upload just the ZIP. GitHub's browser uploader can omit hidden folders; use git or create `.github/workflows/build.yml` manually if needed.
2. Actions → Build MicControl APK → Run workflow (or push to main/master).
3. Download `MicControl-v1.0.0-APK` from the completed run's Artifacts, extract it, and install `app-debug.apk`.
4. Enable MicControl in LSPosed. Select **System Framework** (package `android`), then reboot.
5. Open MicControl. Press STATUS. Then test DISABLE and ENABLE with audio only. If your issue is the QS Microphone access switch, check the privacy checkbox and repeat.

The APK is debug-signed. GitHub runner debug keys can change between builds; a future update may need uninstall/reinstall. There is no Gradle wrapper in this archive: the workflow installs pinned Gradle 8.11.1. Local builds need JDK 17, Android SDK platform 35 and Gradle 8.11.1, then `gradle :app:assembleDebug :app:lintDebug`. compile/target 35 does not prevent operation on Android 16/API 36; hidden service calls are reflected inside system_server.

## Tasker → Send Intent
Action: `dev.chet.miccontrol.ENABLE`
Package: `android`
Target: **Broadcast Receiver**
Extra: `TOKEN:8257bc6cf4e3d8decaa9e2ed2b4d495e740b4b31ea36f14a`

Use `dev.chet.miccontrol.DISABLE`, `.TOGGLE`, or `.STATUS` for the other commands. Leave Class, Data and MIME type blank.

By default only AudioManager mute is changed. To also change the software privacy switch add typed boolean extra `privacy:true` (Tasker must send a Boolean, not a String). For shell the equivalent is `--ez privacy true`.

ENABLE = audio unmute; with privacy:true, software privacy OFF (Microphone access ON).
DISABLE = audio mute; with privacy:true, software privacy ON (Microphone access OFF).
TOGGLE uses observed effective audio mute state, so hardware/policy blocks may keep it muted.
STATUS reports effective audio mute plus software/hardware privacy; `null` means the privacy query failed, not that the microphone is allowed.

For Tasker status replies, add `reply_package:net.dinglisch.android.taskerm` and optional `request_id:your-id`. Add a Tasker Event → Intent Received profile with action `dev.chet.miccontrol.RESULT`. The reply contains `success` (boolean), `status` (text), `command`, `request_id`, and, when known, `audio_muted`, `privacy_blocked`, `hardware_blocked` (booleans). Tasker variable mapping depends on the version; use the `status` text to diagnose initially. Validate the returned TOKEN before trusting replies. STATUS success means the framework answered, not that the microphone is unmuted.

## Shell check (root)
```sh
su -c 'am broadcast --user 0 -a dev.chet.miccontrol.ENABLE -p android --es TOKEN 8257bc6cf4e3d8decaa9e2ed2b4d495e740b4b31ea36f14a --ez privacy true'
```
Broadcast dispatch completion alone is not proof of a state change. Check the app STATUS or RESULT broadcast, and make a real recording to verify audio capture. This initial build targets a single primary-user Pixel; Android restrictions can prevent automation while locked. No automatic repeated unmute, scheduled polling, or persistent override.

## Design and limits
Hooks ActivityManagerService.systemReady, then registers a command receiver on the system main looper. Clears Binder caller identity and uses the foreground user's context for AudioManager. Android 16 SensorPrivacyManager's setSensorPrivacy(source, sensor, boolean) and isSensorPrivacyEnabled(toggleType, sensor) are invoked reflectively. Privacy changes are asynchronous; verification is delayed 350 ms. The command key is checked before privileged work. No hardware switch or enterprise/user restriction is bypassed. Other apps can subsequently change the state. Requests close together can supersede each other; wait for a result before issuing the next command.

**Prototype: not built or tested on a device in this environment.** Workflow runs assembleDebug and lintDebug. A successful APK build does not establish that your ROM permits these service calls. Check LSPosed logs for `MicControl: framework hook installed` and `receiver ready`; attach relevant logs if either is missing or commands fail. Disable the module and reboot to remove the hook. Disabling/uninstalling does not restore a microphone state previously requested; restore it manually first.

The key is generated once for this source archive and embedded in the APK. Keep the repository private. It is an accidental-command guard, not strong authentication against another app that can inspect this APK or root. Replace Protocol.TOKEN with a new random key and rebuild/reboot to rotate it; update Tasker too. Do not put the key in public posts or logs.

## Framework references
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/AudioService.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/SensorPrivacyManager.java
