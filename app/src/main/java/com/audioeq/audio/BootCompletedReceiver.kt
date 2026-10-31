package com.audioeq.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.audioeq.data.EqRepository
import com.audioeq.model.EngineMode
import com.audioeq.model.EqConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * APK-only Start-on-Boot receiver (v5 Section 17.2 F12 & Section 18.2):
 * Restores the equalizer engine when `BOOT_COMPLETED` fires if `startOnBoot` is enabled,
 * and in Root mode invokes the non-flashing `su` session helper.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = EqRepository(context.applicationContext)
                val prefs = repo.preferencesFlow.firstOrNull() ?: return@launch
                if (!prefs.startOnBoot || !prefs.isEnabled) return@launch

                val controller = AudioEngineController(context.applicationContext)
                val mode = EngineMode.entries.firstOrNull { it.id == prefs.selectedModeId } ?: EngineMode.LEGACY
                val preset = EqConstants.BUILT_IN_PRESETS.firstOrNull { it.id == prefs.selectedPresetId }
                val route = controller.getCurrentRoute()

                val layers = Layers(
                    userDb = DoubleArray(10) { prefs.gainsDb.getOrElse(it) { 0f }.toDouble() },
                    intensity = prefs.intensity.toDouble(),
                    routeDb = route.offsetsDb,
                    contourDb = DoubleArray(10)
                )
                controller.loadEngineState(
                    mode = mode,
                    enabled = prefs.isEnabled,
                    abCompare = false,
                    layers = layers,
                    mbc = EqConstants.mbcForPreset(preset, prefs.nightModeEnabled),
                    routeBaseDb = route.baseInputGainDb,
                    limiterDb = route.limiterThresholdDb,
                    flatBypass = preset?.bypassChain == true,
                    bassBoostStrength = preset?.bassBoostStrength ?: 150,
                    virtualizerStrength = preset?.virtualizerStrength ?: 150
                )
            } catch (_: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
