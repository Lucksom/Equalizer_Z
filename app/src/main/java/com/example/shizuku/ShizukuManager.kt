package com.example.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,        // Shizuku app not found (and no Sui)
    NOT_RUNNING,          // installed, but the service is not started
    UNSUPPORTED_VERSION,  // Shizuku older than v11
    NEEDS_PERMISSION,     // running; call requestPermission()
    DENIED_PERMANENTLY,   // user chose "deny and don't ask again"
    READY                 // permission granted
}

object ShizukuManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    private var appContext: Context? = null

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IUserService? = null
    @Volatile private var pending: CompletableDeferred<IUserService>? = null

    // ---- Shizuku listeners -------------------------------------------------

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }

    private val onPermissionResult =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == REQUEST_CODE) refresh()
        }

    /** Call once, from Application.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            // "Sticky" fires immediately if the binder is already available.
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermissionResult)
        }
        refresh()
    }

    fun release() {
        runCatching {
            Shizuku.removeBinderReceivedListener(onBinderReceived)
            Shizuku.removeBinderDeadListener(onBinderDead)
            Shizuku.removeRequestPermissionResultListener(onPermissionResult)
        }
    }

    // ---- State ---------------------------------------------------------------

    fun isShizukuAppInstalled(context: Context? = appContext): Boolean {
        val ctx = context ?: return false
        return try {
            // Needs the <queries> entry in the manifest on Android 11+.
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun refresh() {
        val newState = try {
            when {
                !Shizuku.pingBinder() ->
                    if (isShizukuAppInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
                Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> ShizukuState.UNSUPPORTED_VERSION
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED_PERMANENTLY
                else -> ShizukuState.NEEDS_PERMISSION
            }
        } catch (_: Throwable) {
            if (isShizukuAppInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        _state.value = newState
        if (newState == ShizukuState.READY && service == null) bind()
    }

    /** "root" when Shizuku was started with root, otherwise "adb". */
    fun privilege(): String = try {
        when {
            !Shizuku.pingBinder() -> "none"
            Shizuku.getUid() == 0 -> "root"
            else -> "adb"
        }
    } catch (_: Throwable) {
        "none"
    }

    // ---- Permission ----------------------------------------------------------

    /** Shows Shizuku's permission dialog. Safe to call from a button click. */
    fun requestPermission() {
        refresh()
        runCatching {
            when (_state.value) {
                ShizukuState.NEEDS_PERMISSION -> Shizuku.requestPermission(REQUEST_CODE)
                ShizukuState.DENIED_PERMANENTLY,
                ShizukuState.NOT_RUNNING -> openShizukuApp()
                ShizukuState.NOT_INSTALLED -> openShizukuDownloadPage()
                else -> Unit
            }
        }
    }

    /** Opens the Shizuku app so the user can start the service or re-allow this app. */
    fun openShizukuApp() {
        val ctx = appContext ?: return
        val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { ctx.startActivity(intent) }
    }

    fun openShizukuDownloadPage() {
        val ctx = appContext ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    // ---- Shell access through a UserService ---------------------------------

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!binder.pingBinder()) return
            val s = IUserService.Stub.asInterface(binder)
            service = s
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs? {
        val ctx = appContext ?: return null
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val version = PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(pkg, 0)).toInt()
        return Shizuku.UserServiceArgs(ComponentName(pkg, ShizukuUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(version) // bump the app version when the service code changes
    }

    private fun bind() {
        if (_state.value != ShizukuState.READY) return
        val args = userServiceArgs() ?: return
        runCatching { Shizuku.bindUserService(args, connection) }
    }

    fun unbind() {
        val args = userServiceArgs() ?: return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }

    /** Runs a shell command as shell/root. Keep output small (Binder limit is about 1 MB). */
    suspend fun exec(command: String): Result<String> = runCatching {
        check(_state.value == ShizukuState.READY) { "Shizuku is not ready: ${_state.value}" }
        val s = service ?: CompletableDeferred<IUserService>().let { d ->
            pending = d
            bind()
            withTimeout(5_000) { d.await() }
        }
        val out = withContext(Dispatchers.IO) { s.exec(command) }
        if (out.startsWith("ERROR")) {
            throw IllegalStateException(out)
        }
        out
    }

    /** If UserService is already bound, executes command synchronously on current background thread. */
    fun execIfBound(command: String): String? {
        if (_state.value != ShizukuState.READY) return null
        val s = service ?: run {
            bind()
            return null
        }
        return try {
            val out = s.exec(command)
            if (out.startsWith("ERROR")) null else out
        } catch (_: Throwable) {
            null
        }
    }
}
