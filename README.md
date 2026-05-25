# a38-Chat

Native Android client for the <a href="https://www.corecosmetic.de/chat/">Corecosmetic A38 chat</a>.

The app talks to the web chat.
Registration remains in the browser; the app login stores an encrypted app token
in Android Keystore-backed storage and supports multiple saved accounts.

# Installation
- <a href="https://github.com/Alan107-gif/a38-Chat/raw/main/a38-Chat.apk">Download</a> the .zip or .apk file.
- Unpack the .zip file and tap the .apk file to start the installation.
- Google Play Protect may warn you.
- Find the Install anyway option and tap it.

You can trust our app because the entire source code is publicly available here.

## Features

- No AD!
- Open the app and land directly in your chat, without a crowded web layout.
- Stay signed in, so you can write again without logging in every time.
- Use more than one account and switch between them from the side menu.
- Find past chat partners automatically in your contact list.
- Send quick text messages or share compressed images without leaving the app.
- Tap an image to view it large, then take over the recipient with one button.
- Choose the look that fits you: Light, Dark or Neon Moni.
- Read the chat blog and security info directly inside the app.
- Use the app in German, English, French, Russian, Ukrainian or Italian.
- Keep full transparency: the source code is public, and the APK is available here.

## Build

The repository contains the current installable APK and ZIP:

```text
a38-Chat.apk
a38-Chat.zip
```

Local release build environment:

```bash
export JAVA_HOME=/home/oem/.local/jdk/temurin-21
export ANDROID_HOME=/home/oem/Android/Sdk
export ANDROID_SDK_ROOT=/home/oem/Android/Sdk
export PATH=$JAVA_HOME/bin:/home/oem/.local/gradle/gradle-8.10.2/bin:$ANDROID_HOME/platform-tools:$PATH
gradle assembleRelease
```

The release APK is copied from `app/build/outputs/apk/release/app-release.apk`
to `a38-Chat.apk`.
