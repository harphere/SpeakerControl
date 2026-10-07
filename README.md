# SpeakerControl v1.0.0 — Phone calls first

Separate LSPosed app for Android 16 / Pixel 8 Pro. Controls speakerphone on active ordinary phone calls. No microphone changes, media control or WhatsApp support in this build.

## Build and install
Upload the contents of SpeakerControl to your repository root, including .github. Run Actions → Build SpeakerControl APK. Download and extract SpeakerControl-v1.0.0-APK, then install app-debug.apk. Includes explicit platform-tools workflow fix. Uses Java 17, Gradle 8.11.1, API 35 and assembleDebug + lintDebug. Gradle is installed by the action; no wrapper required. Debug signing keys can change between runners; later updates may require uninstall/reinstall. Disable MicControl if no longer needed.

## Test
Enable SpeakerControl in LSPosed for your default Phone app (the app displays its package). Suggested scopes: com.google.android.dialer and com.android.dialer. Select a different dialer manually if needed. Do not select System Framework. Reboot and open Phone; broadcasts do not start a stopped dialer. Make/answer a normal non-emergency call, wait for it to connect, and test STATUS → ENABLE → DISABLE with Bluetooth/wired headsets disconnected initially. ENABLE requests speakerphone. DISABLE requests earpiece, not the previously used Bluetooth device. An unavailable route returns an error. No commands are queued for the next call.

## Tasker Send Intent
Action: dev.chet.speakercontrol.ENABLE
Package: your default Phone app package shown in SpeakerControl
Target: Broadcast Receiver
Extra: TOKEN:60e14a4bf9daf288f5956adc247de64e1adcc87b0caa9fe7
Leave Class/Data/MIME blank. Other actions: dev.chet.speakercontrol.DISABLE, TOGGLE, STATUS. TOGGLE uses observed current route. Wait for the result before issuing another command.

For Tasker replies add reply_package:net.dinglisch.android.taskerm and optionally request_id:your-id. Event → Intent Received action dev.chet.speakercontrol.RESULT. Extras: TOKEN, command, request_id, success, call_active, speaker_on, route (-1 means unknown), status, backend_package. Validate TOKEN. STATUS success means the query was handled, not that speakerphone is on. Check call_active and route. The token is also displayed in the app.

## Implementation and limits
Hooks Application.attach plus InCallService.onBind/onUnbind inside the dialer. Uses InCallService.setAudioRoute and observes getCallAudioState up to four times over 1.2 seconds. These public APIs remain available but were deprecated at API 34. Does not bypass audio permissions, force audio mode, or repeatedly override the user's route. Dialers with custom lifecycle/process behaviour may need adaptation from logs. Source and ZIP checks do not establish runtime compatibility. APK build and device behaviour are untested here; workflow runs build and lint. Share SpeakerControl LSPosed log lines and app status if commands fail. Disable the module and reboot to unload hooks; restore route manually if needed.

Keep source private. The embedded token is a guard against accidental commands, not strong authentication against APK inspection/root. Rotate Protocol.TOKEN and rebuild/reboot if exposed; update Tasker too.

References:
https://developer.android.com/reference/android/telecom/InCallService
https://developer.android.com/reference/android/telecom/CallAudioState
