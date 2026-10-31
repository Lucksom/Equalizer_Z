package com.example.shizuku

import android.content.Context
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's process as shell (uid 2000) or root (uid 0).
 * Shizuku v13+ tries the Context constructor first; older versions use the default one.
 */
@Keep
class ShizukuUserService() : IUserService.Stub() {

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code == 0) output else "ERROR($code): $output"
    } catch (t: Throwable) {
        "ERROR: ${t.message}"
    }
}
