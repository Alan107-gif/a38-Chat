# a38-Chat

Native Android client for the <a href="https://www.corecosmetic.de/chat/">Corecosmetic A38 chat</a>.

The app talks to the web chat through `https://www.corecosmetic.de/chat/api.php`.
Registration remains in the browser; the app login stores an encrypted app token
in Android Keystore-backed storage and supports multiple saved accounts.

# Installation
- <a href="https://github.com/Alan107-gif/a38-Chat/raw/main/a38-Chat.apk">Download</a> the .zip or .apk file.
- Unpack the .zip file and tap the .apk file to start the installation.
- Google Play Protect may warn you.
- Find the Install anyway option and tap it.

You can trust our app because the entire source code is publicly available here.

## Features

- Native Android interface for the Corecosmetic A38 web chat.
- The chat opens as the main screen, with quick access to recent conversations.
- Secure app login with encrypted token storage; registration stays on the web.
- Multi-account support with saved accounts and fast account switching.
- Contact list built automatically from people you have already messaged.
- Text messages and compressed WebP image uploads.
- Image preview in chat plus full-size image view with recipient handoff.
- Blog and security pages available inside the app without leaving the workflow.
- Light, Dark and Neon Moni themes; Light is the default.
- Built-in language options: German, English, French, Russian, Ukrainian and Italian.

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
