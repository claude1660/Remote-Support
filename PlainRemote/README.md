# PlainRemote

# PlainRemote

A small Android app for accessing **your own phone's files** and
**mirroring/controlling its screen** from a PC or browser. It has two modes:

- **Local Wi-Fi mode** (the original) — no cloud, no internet, works only
  when the phone and the PC are on the same Wi-Fi network.
- **Internet mode** (new) — works over **any network** (mobile data,
  different Wi-Fi networks entirely), for supporting distant customers.
  Screen video and remote-control commands travel **directly,
  peer-to-peer, over WebRTC**; Firebase is used only to exchange the
  small initial connection handshake, so it comfortably fits the free
  Spark plan even for long sessions.

## ⚠️ Important — read first

- This only works on **a phone the customer has set up themselves**.
  Android will not let a remote party silently enable screen capture or
  the accessibility service — both require a tap on the physical device
  each time, and in internet mode a fresh session code every time too.
- Only use this with someone's knowledge and consent. Installing
  "remote control" software on someone's phone without their knowledge
  is illegal in most places (stalkerware laws) even if the underlying
  tech is the same as legitimate remote-support apps (which this is
  modeled on).
- Local Wi-Fi mode's HTTP server has no authentication — don't expose
  port 8080 to the internet via port-forwarding.
- Internet mode's session codes are short (6 digits) and, as shipped,
  Firestore's security rules would need to allow writes to `sessions/*`
  without login (see "Firebase setup" below) — treat a code as valid
  for one session only and make sure the app deletes the session
  document when the session ends (it does, in `RemoteSessionService`).

## What it does

| Feature | Local Wi-Fi mode | Internet mode |
|---|---|---|
| Screen mirroring | JPEG snapshots polled over HTTP | Live WebRTC video track (`ScreenCapturerAndroid`) |
| Remote taps/swipes/back/home | HTTP request → `AccessibilityService` | WebRTC data channel → `AccessibilityService` |
| File browsing/download | Embedded HTTP server (NanoHTTPD) | Not included (add if you need it) |
| Works across networks? | No — same Wi-Fi only | Yes — any network, via free STUN/TURN |
| Server dependency | None | Firebase Firestore (free Spark plan), used only for signaling |

## Project structure

```
PlainRemote/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/plainremote/
│       │   ├── MainActivity.kt              # UI: both modes, shows IP or session code
│       │   ├── WebServerService.kt          # Local Wi-Fi mode: HTTP server + routes
│       │   ├── ScreenCapture.kt             # Local Wi-Fi mode: MediaProjection -> JPEG frames
│       │   ├── RemoteSessionService.kt      # Internet mode: WebRTC peer connection + screen track
│       │   ├── SignalingClient.kt           # Internet mode: Firestore signaling (handshake only)
│       │   └── RemoteAccessibilityService.kt  # Executes taps/swipes/back/home (both modes)
│       └── res/                              # layout, strings, theme, icon
├── web/
│   └── technician.html                       # Internet mode: technician-side browser page
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Building it

### Option A — Android Studio (easiest)

1. Install [Android Studio](https://developer.android.com/studio) (handles the Gradle
   wrapper automatically — you don't need to install Gradle yourself).
2. `File > Open` → select the `PlainRemote` folder.
3. Let Gradle sync (downloads dependencies from Google/Maven the first time).
4. Connect your phone (USB debugging on) or use an emulator, then press ▶ Run.

### Option B — GitHub Codespaces / any Linux CLI

This repo doesn't ship the Gradle wrapper binary, so generate it once inside
the Codespace (which has full internet access, unlike some sandboxed CI
environments):

```bash
# Java 17 + Android SDK command-line tools
sudo apt-get update -y && sudo apt-get install -y openjdk-17-jdk unzip gradle
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q tools.zip && rm tools.zip && mv cmdline-tools latest

cat >> ~/.bashrc << 'EOF'
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
EOF
source ~/.bashrc

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd PlainRemote                              # this folder (has settings.gradle)
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle wrapper --gradle-version 8.7 && chmod +x gradlew

./gradlew assembleDebug
```

Builds succeed even without `app/google-services.json` present (internet
mode just won't work until you add it — see "Firebase setup" below).

**Output APKs** (ABI-split to keep size down — see below):
```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk     ← install this on any phone from ~2017 onward
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk   ← only for very old 32-bit phones
app/build/outputs/apk/debug/app-universal-debug.apk     ← works on any device, but larger
```
`adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` if the
phone is plugged into the same machine, otherwise copy the file to the
phone and open it there (allow "install from unknown sources" once).

## Keeping the app size down

WebRTC's native libraries are the biggest contributor to APK size (several
MB per CPU architecture). Two things are already wired up in `app/build.gradle`
to keep this in check:

- **ABI splits** — produces separate `arm64-v8a` / `armeabi-v7a` APKs
  instead of one file carrying all 4 architectures; picking the right one
  per device roughly halves what's downloaded/installed compared to the
  universal APK.
- **R8 shrinking + resource shrinking on release builds** (`minifyEnabled true`,
  `shrinkResources true` in the `release` block, with matching keep-rules in
  `app/proguard-rules.pro` so WebRTC's JNI calls and Firestore's models
  survive shrinking). Debug builds skip this (so they build faster and stay
  easy to debug) — for the smallest real install, build a signed release
  APK (`./gradlew assembleRelease`, needs a signing key you provide) rather
  than the debug one.

## Firebase setup (needed for internet mode only)

Local Wi-Fi mode needs no setup at all. Internet mode needs a **free**
Firebase project:

1. Go to the [Firebase console](https://console.firebase.google.com/),
   create a project (Spark/free plan is enough — no billing needed).
2. **Build → Firestore Database → Create database** → start in
   **test mode** for now (test mode rules expire after 30 days; before
   that, tighten them — see below).
3. **Add app → Android** with package name `com.example.plainremote`.
   Download the generated `google-services.json` and place it at
   `app/google-services.json` (same folder as `app/build.gradle`).
4. **Add app → Web** (for the technician page). Copy the config object
   it shows you (`{apiKey: ..., projectId: ..., ...}`) — you'll paste
   this into `web/technician.html` when connecting.
5. Recommended Firestore rules once you've tested it works (replaces
   test-mode's wide-open rules; still no login required, but at least
   scopes access to the `sessions` collection and stops arbitrary
   database-wide reads/writes):
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /sessions/{code} {
         allow read, write: if true;
         match /{subcollection}/{doc} {
           allow read, write: if true;
         }
       }
     }
   }
   ```

## Using it — Local Wi-Fi mode

1. Open the app, flip **Start service** on.
2. Approve the screen-capture prompt.
3. Tap **Enable remote control (Accessibility)** → find "PlainRemote" →
   turn it on (must be done manually on the device — an Android
   security requirement).
4. Open the shown `http://192.168.x.x:8080` on a computer on the same
   Wi-Fi network.

## Using it — Internet mode (distant customers)

1. On the customer's phone: open the app, make sure accessibility is
   enabled (step 3 above, one-time), then flip **Start internet
   session** on and approve the screen-capture prompt.
2. The app shows a 6-digit **session code**. Have the customer read it
   to you (phone call, chat, however you're already talking to them).
3. On your side, open `web/technician.html` in a browser (double-click
   it, or host it anywhere — GitHub Pages works fine and is free).
   Paste in your Firebase **web** config and the session code, then
   click **Connect**.
4. You'll see their screen live; click to tap, click-drag to swipe,
   plus Back/Home buttons. Click **End session** when done — this
   closes the connection on both sides and deletes the Firestore
   session document.

## Known limitations / things to improve

- The free Open Relay Project TURN servers (used as a fallback when a
  direct peer-to-peer path can't be found, e.g. carrier-grade mobile
  NAT) have bandwidth limits and no uptime guarantee. Fine to start
  with; if sessions start failing under load, run your own free
  `coturn` on a small VPS and swap the ICE server list in
  `RemoteSessionService.kt` and `technician.html`.
- Internet-mode session codes have no expiry beyond the session itself
  and no rate-limiting — a determined guesser could try codes against
  your Firestore project. Low risk given the short window a code is
  live and 1-in-a-million odds per guess, but worth knowing.
- Text input simulation isn't implemented — only taps, swipes, and
  Back/Home. Could be added via `ACTION_SET_TEXT` on the focused node
  in `RemoteAccessibilityService`, forwarded over the same data channel.
- File browsing only exists in local Wi-Fi mode; internet mode doesn't
  expose the file server (add it as a second data channel if needed).
- No app icon PNGs are bundled (a simple vector drawable is used
  instead) — swap in your own via Android Studio's Image Asset tool.

