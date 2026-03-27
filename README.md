This is a Kotlin Multiplatform project targeting Android, iOS.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…

### Run Telegram Bot On Installed Windows App

If you already installed the app (for example in `C:\Program Files\tj.khujand.solana.trading.bot`), you can run Telegram bot without Gradle.

1. Copy `scripts/windows/run-telegram-bot-installed.bat` to your installed app folder:
   - `C:\Program Files\tj.khujand.solana.trading.bot`
2. Open PowerShell and set env vars:
   ```powershell
   $env:TELEGRAM_BOT_TOKEN="YOUR_NEW_TOKEN"
   $env:TELEGRAM_ADMIN_CHAT_ID="7629981910"
   $env:TELEGRAM_ADMIN_USER_ID="7629981910"
   ```
3. Run:
   ```powershell
   & "C:\Program Files\tj.khujand.solana.trading.bot\run-telegram-bot-installed.bat"
   ```

To restart bot: stop it in the console (`Ctrl + C`) and run the same `.bat` again.