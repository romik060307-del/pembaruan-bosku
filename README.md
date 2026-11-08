# OHLI Social — Android Client v1.0.9 (Dynamic Server Config)

This is the Android WebView client for the existing OHLI Social website/backend. It does not bundle, recreate, or modify the OHLI backend or database.

## What changed in v1.0.9

- Improved Android WebView camera/microphone permission handling: concurrent permission requests are queued instead of overwriting a single pending request.
- Grants only the requested camera and/or microphone resource for the trusted OHLI origin, including partial grants when only one Android permission is approved.
- Declares optional front-camera and autofocus hardware features without excluding devices that do not report them.
- Preserves the remote server-config URL, Cloudflare fallback URL, existing WebView navigation, media file picker, and long-press guard.

## What changed in v1.0.8

- Added one startup request to a remote `server.json` configuration endpoint.
- The endpoint returns `{ "server_url": "https://your-current-quick-tunnel.trycloudflare.com/" }`.
- The APK stores the last valid server URL locally. If the config endpoint is unreachable, it uses the last saved URL, then the build-time fallback.
- Configuration is requested only at app startup or when the user taps Retry. It is not polled during chat, camera, microphone, voice/video calls, Live, or media uploads.
- The config request has short connection/read timeouts and a 16 KB response limit.
- Existing WebView permissions, file picker, long-press crash guard, same-WebView HTTPS navigation, and Android Back behavior are retained.

## Important: first-time setup is required once

The APK needs the permanent URL of your Netlify config file. Deploy the companion folder `ohli-server-config-netlify` to Netlify first, then copy the resulting URL ending in `/server.json`.

Open `app/build.gradle` and replace only this line:

```gradle
buildConfigField 'String', 'SERVER_CONFIG_URL', '"https://GANTI-SUBDOMAIN-NETLIFY-KAMU.netlify.app/server.json"'
```

For example, if Netlify gives you `https://ohli-config-example.netlify.app`, the line becomes:

```gradle
buildConfigField 'String', 'SERVER_CONFIG_URL', '"https://ohli-config-example.netlify.app/server.json"'
```

Build the APK once after setting that stable config URL. Do not change this line when the Cloudflare Quick Tunnel changes.

## Updating the Cloudflare Quick Tunnel later

1. Open the `ohli-server-config-netlify` folder.
2. Edit only `server.json` and replace the `server_url` value with the newest HTTPS Quick Tunnel URL.
3. Publish the updated folder to the same Netlify site (production deploy).
4. Reopen OHLI. The APK fetches the config at startup and uses the new server URL.

No APK rebuild is needed for later `server_url` changes, provided the Netlify config-site URL stays the same.

## Fallback behavior

1. Valid URL returned by the remote config file.
2. Last valid URL saved on the device.
3. Build-time fallback `OHLI_URL` in `app/build.gradle`.

The remote config URL is a public endpoint and the JSON file must not contain passwords, API keys, session tokens, or personal data. Only put the public HTTPS server base URL in it.

## Cloudflare Quick Tunnel limitations

The configuration file only changes where the APK connects. It does not start or keep the Cloudflare tunnel alive. The Quick Tunnel must be running and publicly reachable. Since every Quick Tunnel hostname is a different web origin, Android WebView cookies and local storage may not automatically carry over to the new hostname; users may need to sign in again depending on the OHLI website's authentication design.

## Build APK with GitHub Actions

1. Upload the project contents to the root of a GitHub repository.
2. Set `SERVER_CONFIG_URL` in `app/build.gradle` once as described above.
3. Open **Actions**.
4. Select **Build OHLI Social Client APK** and run the workflow.
5. Download artifact **OHLI-Social-Client-APK** and extract `app-debug.apk`.

## Build requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle 8.9
- Java compatibility 1.8

This source package has not been built on a physical Android device in this environment. Confirm the generated APK with GitHub Actions and test it on your Android phone before distributing it.


## Camera-switch troubleshooting

This APK change hardens the Android WebView permission bridge used by getUserMedia. It does not alter the website's JavaScript WebRTC call controller. If camera switching still reports `RTCRtpSender.replaceTrack(): The peer connection is closed`, the hosted website's camera-switch function must be inspected because that error means the JavaScript is attempting to replace a track on a closed RTCPeerConnection. The web source (or the exact deployed JavaScript bundle) is required to fix that lifecycle path safely.

This source has not been built with Android SDK or tested on a physical phone in this environment. Build with GitHub Actions, then test an active call and switch front-to-back and back-to-front.
