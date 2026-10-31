# Shizuku permission: setup and troubleshooting

Targets the current Shizuku API (13.x, package `rikka.shizuku`). Check the latest version number on Maven Central (`dev.rikka.shizuku:api`); 13.1.5 is the newest I know of.

## Why your app is not asking (most common causes)

1. **`ShizukuProvider` is missing from the manifest.** Without it the app never receives Shizuku's binder, so `requestPermission` has nothing to talk to.
2. **Requesting before the binder arrives.** `Shizuku.pingBinder()` is false until the provider delivers it. Wait for `OnBinderReceivedListener` (the helper does this).
3. **Shizuku is not running.** Open Shizuku and start it (Wireless debugging on Android 11+, or ADB). Non-root Shizuku must be restarted after every reboot.
4. **"Deny and don't ask again" was chosen.** Shizuku stops showing the dialog. Re-allow the app in Shizuku → Authorized apps.
5. **Old imports.** Use `rikka.shizuku.Shizuku`, not `moe.shizuku.api`.
6. **Android 11+ package visibility.** Without `<queries>` your app cannot detect that Shizuku is installed (the permission dialog itself is unaffected).
7. **Shizuku older than v11** is not supported by the current API.

## 1. Gradle

```kotlin
// app/build.gradle.kts
android {
    buildFeatures { aidl = true }
}
dependencies {
    val shizuku = "13.1.5"   // check Maven Central for the latest
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```
If your minSdk is 23, enable core library desugaring (required by the Shizuku API at that level).

## 2. AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <queries>
        <package android:name="moe.shizuku.privileged.api" />
    </queries>

    <application ...>

        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

    </application>
</manifest>
```

## 3. Files
Put them in your package (change `com.yourapp.equalizer`):
- `IUserService.aidl` → `app/src/main/aidl/com/yourapp/equalizer/shizuku/`
- `ShizukuUserService.kt`, `ShizukuManager.kt` → `app/src/main/java/com/yourapp/equalizer/shizuku/`

If you use R8, add: `-keep class com.yourapp.equalizer.shizuku.ShizukuUserService { *; }`

## 4. Application class
```kotlin
class EqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
```
Register it with `android:name=".EqApp"` on `<application>`.

## 5. Compose usage
```kotlin
@Composable
fun ShizukuCard() {
    val state by ShizukuManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    val (label, action) = when (state) {
        ShizukuState.NOT_INSTALLED -> "Install Shizuku" to { /* open shizuku.rikka.app/download */ }
        ShizukuState.NOT_RUNNING -> "Start Shizuku" to { ShizukuManager.openShizukuApp() }
        ShizukuState.UNSUPPORTED_VERSION -> "Update Shizuku" to { }
        ShizukuState.NEEDS_PERMISSION -> "Grant permission" to { ShizukuManager.requestPermission() }
        ShizukuState.DENIED_PERMANENTLY -> "Re-allow in Shizuku" to { ShizukuManager.openShizukuApp() }
        ShizukuState.READY -> "Connected (${ShizukuManager.privilege()})" to {
            scope.launch {
                val pkg = "com.yourapp.equalizer"
                ShizukuManager.exec("pm grant $pkg android.permission.DUMP")
            }
        }
    }
    Button(onClick = { action() }) { Text(label) }
}
```
Also call `ShizukuManager.refresh()` in `onResume` so the card updates after the user returns from the Shizuku app.

## 6. Using it for the equalizer
- Grant your app the DUMP permission without a PC: `pm grant <your.package> android.permission.DUMP`.
- List audio sessions: `dumpsys media.audio_flinger` (filter it, e.g. `| head -c 200000`, because large outputs can exceed the Binder limit).
- Check the privilege with `ShizukuManager.privilege()`. A "root" result means Shizuku was started as root.
- `Shizuku.newProcess` is being removed. Use the UserService (`exec`) for shell commands.
