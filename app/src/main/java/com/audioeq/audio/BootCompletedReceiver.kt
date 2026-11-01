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

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = EqRepository(context.applicationContext)
                val startOnBoot = repo.startOnBoot.value
                val masterEnabled = repo.masterEnabled.value
                if (!startOnBoot || !masterEnabled) return@launch

                val controller = AudioEngineController(
                    context = context.applicationContext,
                    shizukuManager = com.audioeq.shizuku.ShizukuManager(context.applicationContext),
                    scope = this,
                    onRouteChanged = { route, devKey, _ ->
                        repo.switchOutputRoute(route, devKey)
                    }
                )
                val mode = repo.engineMode.value
                val profile = repo.currentRouteProfile.value
                controller.applyDspState(
                    mode = mode,
                    masterEnabled = masterEnabled,
                    bandGainsDb = profile.bandGainsDb,
                    rightBandGainsDb = profile.rightBandGainsDb,
                    splitLREnabled = profile.splitLREnabled,
                    balanceDb = profile.balanceDb,
                    preampDb = profile.preampDb,
                    autoGainEnabled = profile.autoGainEnabled,
                    limiterEnabled = profile.limiterEnabled,
                    limiterCeilingDb = profile.limiterCeilingDb,
                    subBassHighPassHz = profile.subBassHighPassHz,
                    iso226ContourEnabled = profile.iso226ContourEnabled,
                    midSideWidth = profile.midSideWidth,
                    vocalPresenceBoost = profile.vocalPresenceBoost,
                    headphoneProfile = null,
                    headphoneCorrectionStrength = profile.headphoneCorrectionStrength,
                    headphoneFullRange = profile.headphoneFullRange,
                    tiltBassDb = profile.tiltBassDb,
                    tiltTrebleDb = profile.tiltTrebleDb,
                    speakerCalibrationEnabled = profile.speakerCalibrationEnabled,
                    speakerCalStrength = profile.speakerCalStrength,
                    speakerCorrection31Db = emptyList(),
                    hearingCurve = repo.hearingCurve.value,
                    enhancerConfig = repo.enhancerConfig.value,
                    compatibility10Band = repo.compatibility10Band.value,
                    transientMode = repo.transientMode.value,
                    usbBitPerfectEnabled = repo.usbBitPerfectEnabled.value
                )
            } catch (_: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
