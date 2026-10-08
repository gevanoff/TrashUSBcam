# Experimental live sharing

One Android publisher → one browser viewer, video only. The Android Share menu sends a link via an installed messaging app. WebRTC carries the video; this Node service only exchanges connection descriptions and issues temporary TURN credentials. It does not receive or record camera frames. The TURN relay forwards encrypted media when a direct path is unavailable.

## Run the service

Requires Node 22+ (CI uses 24). There are no runtime npm dependencies. Playwright is a pinned development dependency for the browser media smoke test. Generate two different random secrets, one for publisher authorization and one shared with coturn:

```bash
openssl rand -hex 32
openssl rand -hex 32
```

Set these environment variables in your service manager or private environment file. Do not commit the secrets:

```bash
export PUBLISH_KEY='replace-with-first-generated-secret'
export TURN_SHARED_SECRET='replace-with-second-generated-secret'
export TURN_URLS='turn:relay.example.com:3478?transport=udp,turn:relay.example.com:3478?transport=tcp'
export STUN_URL='stun:relay.example.com:3478'
export HOST='127.0.0.1'
export PORT='8080'
node streaming/server.mjs
```

Put an HTTPS reverse proxy in front of port 8080, on a dedicated hostname. Example Caddy site (replace the hostname, point its DNS to this server, and allow HTTPS):

```caddyfile
live.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

The app requires an HTTPS origin with no path prefix. For local service tests only, HTTP on loopback is supported. In-memory sessions disappear when the server restarts. Use one service instance; horizontal scaling needs shared session state. Default capacity is 20 sessions, each lasting at most one hour. A publisher missing heartbeats for 45 seconds loses the session.

## TURN relay

Use a reachable coturn instance with REST-style shared-secret authentication. Its `static-auth-secret` must match `TURN_SHARED_SECRET`. Set a realm, external/public IP mapping if necessary, and a bounded UDP relay port range. Open its listener and relay ports in the firewall. Example configuration fragment:

```ini
listening-port=3478
fingerprint
use-auth-secret
static-auth-secret=replace-with-second-generated-secret
realm=relay.example.com
min-port=49160
max-port=49200
no-multicast-peers
no-cli
```

Apply the deployment's peer-address restrictions and allocation/bandwidth limits. For restrictive networks, also configure a TLS listener and add a `turns:` URL with a valid certificate. See [coturn configuration](https://github.com/coturn/coturn/blob/master/examples/etc/turnserver.conf) and [REST authentication](https://github.com/coturn/coturn/blob/master/README.turnserver). Omitting TURN may work on a LAN but is not a dependable internet deployment. Credential expiry matches session expiry; stopping a session closes the publisher peer immediately, while issued TURN credentials expire at their original deadline.

## Android and viewer flow

1. Install the PR's debug APK. Connect the USB or Wi-Fi camera.
2. Tap the share symbol at the upper right of the preview. Enter the HTTPS server and `PUBLISH_KEY`. Only the server address is remembered; the key is not saved.
3. Tap **Start sharing**, then **Send viewing link**, and choose your messaging app/contact.
4. Return to TrashUSBcam. The camera pauses while another app is in front; the link remains available. This version does not capture other apps or stream in the background.
5. The recipient opens the link in a current WebRTC-capable browser and taps **Watch live**. If a messaging app's embedded browser does not support playback, open the link in the system browser first.
6. Tap the sharing button → **Stop sharing** to revoke the session.

Anyone possessing the link can claim its one viewing slot. The capability lives in the URL fragment, so ordinary HTTP requests and link previews do not transmit it to the service. Joining requires an explicit button press. There are no analytics or external viewer assets. A claimed link cannot be reused, including after refreshing or leaving the viewer page; create a new share to reconnect. This is deliberate for the first version. No account login or recipient identity verification is provided.

Android sends frames with a longest edge of 640 pixels at approximately 6 fps, using the selected rotation and screen-relative reflection. The capture surface is sampled, not the app UI. Photos and recordings remain unchanged. Wi-Fi cameras with an isolated access point need a separate internet route (usually cellular); camera sockets retain their existing explicit Wi-Fi binding. Network handoffs may require starting a new share.

## Validation

```bash
node --test streaming/server.test.mjs
node --check streaming/public/viewer.js
npm ci --prefix streaming
npx --prefix streaming playwright install chromium
node streaming/browser-smoke.cjs
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace
```

Tests cover publisher authentication, a single viewer claim, role separation, offer/answer exchange, pause, revocation, expiry, abandoned sessions, request size limits, TURN credential issuance, and video color conversion. The Chromium smoke test connects a synthetic video publisher to the actual viewer, checks decoded colors, rejects a second viewer, and verifies pause/resume and revocation. CI publishes the APK and Android reports.

Physical acceptance testing remains required:

- USB and Wi-Fi camera → desktop/mobile browser on the LAN, then across cellular/internet.
- Force relay-only ICE in a test build of both peers to verify TURN, not just direct connections.
- All four rotations with mirror on/off; check an asymmetric labeled target.
- Send via SMS/WhatsApp/Signal/Telegram, return to the camera, verify pause/resume.
- Stop sharing, unplug camera, lock phone, kill app, expire a link, refresh viewer, deny internet.
- Verify no stale visible frame while paused, no microphone audio, and no gallery/UI capture.
- Record local MP4 while streaming; assess heat, battery, frame rate, memory, and cheap-camera stability.

This feature is not deployment-ready until HTTPS/TURN and real-camera/browser tests pass. No public server is bundled or provisioned by this PR.
