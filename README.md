# 🎵 YT Background Player

An Android application that loads the YouTube mobile website and keeps audio playing even when the screen is turned off or the app is minimized — something the official YouTube app locks behind YouTube Premium.

![Android](https://img.shields.io/badge/Android-5.0%2B-brightgreen?logo=android)
![Java](https://img.shields.io/badge/Language-Java-orange?logo=java)
![License](https://img.shields.io/badge/License-MIT-blue)
![Build](https://img.shields.io/badge/Build-Gradle%208.2-blue?logo=gradle)

---

## 📱 Screenshots

> Open the app → browse YouTube → press Home or turn off screen → audio keeps playing.

---

## ✨ Features

- 🎵 **Background audio** — audio continues when you minimize the app
- 📵 **Screen-off playback** — audio continues when the screen turns off
- 🔔 **Notification controls** — Play / Pause / Stop from the notification shade
- ▶️ **Full YouTube interface** — loads `m.youtube.com`, supports search, history, playlists
- 📺 **Fullscreen video** — tap the fullscreen button on any video
- 🔙 **Smart back button** — navigates back in history; on the home page moves to background (keeps audio playing)
- 🔐 **Google sign-in** — log into your YouTube account normally

---

## 🛠️ How It Works

The official YouTube app pauses audio on screen-off unless you pay for Premium. This app bypasses that using three techniques:

### 1. Foreground Service + Wake Lock
```java
// Keeps the CPU alive when the screen turns off
wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTPlayer::AudioWakeLock");

// mediaPlayback type = Android won't kill this service
<service android:foregroundServiceType="mediaPlayback" />
```

### 2. Page Visibility API Override
YouTube's web player listens for `visibilitychange` events to detect when the screen turns off and pauses automatically. We intercept this:
```javascript
// Make YouTube always think the page is visible
Object.defineProperty(document, 'hidden', { get: () => false });
Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });

// Block the event before YouTube's listener sees it
document.addEventListener('visibilitychange',
  e => e.stopImmediatePropagation(), true);
```

### 3. Never calling WebView.onPause()
Most WebView apps call `webView.onPause()` in `Activity.onPause()` — this kills media. We deliberately skip it and let the foreground service keep the WebView alive.

---

## 🏗️ Project Structure

```
YTPlayer/
├── app/
│   └── src/main/
│       ├── java/com/ytplayer/
│       │   ├── MainActivity.java       # WebView setup, lifecycle management
│       │   └── AudioService.java       # Foreground service, wake lock, MediaSession
│       ├── res/
│       │   ├── layout/activity_main.xml
│       │   ├── drawable/               # Vector icons (play, pause, stop, yt logo)
│       │   ├── mipmap-*/               # App launcher icons
│       │   └── values/                 # Colors, strings, themes
│       └── AndroidManifest.xml
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradlew
```

---

## ⚙️ Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17+ |
| Android SDK | API 34 (compile), API 23 min |
| Gradle | 8.2 |

---

## 🚀 Build & Install

### Clone the repo
```bash
git clone https://github.com/sivaprasath2004/yt-background-player-android.git
cd yt-background-player-android
```

### Build debug APK
```bash
chmod +x gradlew
./gradlew assembleDebug
```

### Install on connected device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Or open in Android Studio
```
File → Open → select the project folder → Run ▶️
```

---

## 📦 Dependencies

```groovy
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.android.material:material:1.11.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
implementation 'androidx.media:media:1.7.0'   // MediaSession support
```

No ExoPlayer, no third-party video libraries — just WebView + Android framework APIs.

---

## 🔒 Permissions

| Permission | Reason |
|-----------|--------|
| `INTERNET` | Load YouTube |
| `FOREGROUND_SERVICE` | Keep service running in background |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Screen-off audio (Android 10+) |
| `WAKE_LOCK` | Keep CPU awake when screen is off |
| `POST_NOTIFICATIONS` | Show playback notification (Android 13+) |

---

## ⚠️ Known Limitations

- YouTube may occasionally update their web player to detect and block this approach
- This uses YouTube's mobile website — not the native app experience
- Video playback stops when screen is off (by design — audio only in background)
- Some YouTube features (like Chapters, live chat) may behave differently in WebView

---

## 🤝 Contributing

Pull requests are welcome! If YouTube changes their visibility detection and breaks background play, open an issue with details.

1. Fork the repo
2. Create a feature branch: `git checkout -b fix/visibility-override`
3. Commit your changes: `git commit -m 'Fix: update visibility API override'`
4. Push and open a Pull Request

---

## 📄 License

```
MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

## 🙏 Acknowledgements

- Inspired by the frustration of YouTube Premium paywalling background play
- Page Visibility API override technique from WebView background media research
- Built with ❤️ using Android WebView + MediaSession APIs
