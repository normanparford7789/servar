# TouchMirror

Mirror touch events from your primary phone (no root) to multiple target devices (with root) over the **internet** via a relay server.

## Features

- ✅ Works over the internet (no WiFi / USB required)
- ✅ Controller device needs NO root
- ✅ Multi-touch mirroring with normalized coordinates (works across different screen sizes)
- ✅ Floating button on controller: pause/resume mirroring instantly
- ✅ Automatic reconnection if connection drops
- ✅ Multiple target devices in the same session
- ✅ Auto-start on boot (optional)
- ✅ Real-time latency display
- ✅ Low battery: batched touch events at ~60 fps

---

## Architecture

```
[Controller Phone]  ──WebSocket──>  [Railway Server]  ──WebSocket──>  [Target Phone(s)]
  (no root)                         (Node.js + Socket.io)               (root required)
```

---

## Setup (3 Steps)

### Step 1 — Deploy the Server on Railway

1. Go to [railway.app](https://railway.app) and sign in
2. Click **New Project → Deploy from GitHub Repo**
3. Select this repository and choose the `server/` folder
4. In Railway **Variables**, add:

   | Variable | Value |
   |---|---|
   | `SESSION_SECRET` | A strong random password, e.g. `abc123XYZ!` |
   | `PORT` | *(Railway sets this automatically — don't add it)* |

5. After deploy, copy your Railway URL, e.g. `https://touchmirror-production.up.railway.app`

### Step 2 — Download the APK

Download the latest APK from **Actions → Build TouchMirror APK → Artifacts** (look for `TouchMirror-Release-APK`).

Install the APK on **all** your phones.

### Step 3 — Configure Each Phone

Open TouchMirror → **Settings** and enter:

| Field | Value |
|---|---|
| **Server URL** | Your Railway URL (e.g. `https://touchmirror-xxx.railway.app`) |
| **Session ID** | Same on all phones, e.g. `my-session-1` |
| **Session Secret** | The `SESSION_SECRET` you set on Railway |
| **Device Mode** | `Controller` on your main phone, `Target` on others |

Then press **Connect**.

---

## Controller Phone Setup

1. Set mode to **Controller**
2. Grant **Overlay Permission** (draw over other apps)
3. Enable **TouchMirror** in **Accessibility Settings** (so it can re-inject gestures locally)
4. Press **Connect**
5. A floating blue button appears — tap it to **pause/resume** mirroring

> The floating button shows green when connected, orange when connecting, red when disconnected.

## Target Phone Setup (requires root)

1. Set mode to **Target**
2. Press **Connect**
3. When prompted, grant **root access** to TouchMirror
4. Done — all touches from the controller will be mirrored

---

## How Touch Mirroring Works

```
1. User touches screen on Controller
2. Transparent overlay captures raw touch coordinates
3. Coordinates normalized to 0.0–1.0 (works on any screen size)
4. Events sent to Railway server via WebSocket
5. Server relays to all Target devices in the same session
6. Target devices execute: `su -c "input tap X Y"` (root shell)
```

---

## Security Notes

- All devices must use the same `SESSION_SECRET` to join a session
- The secret is validated server-side before joining
- Change the default secret before deploying
- Sessions are isolated — multiple teams can use the same server with different secrets

---

## Environment Variables (server)

Copy `server/.env.example` to `server/.env`:

```bash
cp server/.env.example server/.env
```

| Variable | Required | Description |
|---|---|---|
| `PORT` | No | Server port (Railway sets automatically) |
| `SESSION_SECRET` | **Yes** | Shared secret for all devices. Must match on server and all phones. |
| `CORS_ORIGIN` | No | CORS origin (default: `*`) |

---

## Building APK Locally

Requirements: Android Studio or JDK 17 + Android SDK

```bash
cd app
chmod +x gradlew
./gradlew assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

---

## Troubleshooting

| Problem | Solution |
|---|---|
| "Not configured" error | Open Settings, fill in Server URL + Session Secret |
| Controller touches not mirrored | Enable TouchMirror in Accessibility Settings |
| No floating button | Grant Overlay Permission in Settings |
| Target not executing touches | Grant root access when prompted; check root is available |
| Disconnects frequently | Check server is running; ensure SESSION_SECRET matches |
| "Wrong secret" error | Verify SESSION_SECRET is identical on server and all phones |

---

## License

MIT
