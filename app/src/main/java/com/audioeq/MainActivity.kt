package com.audioeq

import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audioeq.shizuku.ShizukuManager
import com.audioeq.ui.AudioEqScreen
import com.audioeq.ui.EqViewModel
import com.audioeq.ui.theme.AudioEqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchedSessionId = intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1) ?: -1
        setContent {
            val eqViewModel: EqViewModel = viewModel()
            val state by eqViewModel.uiState.collectAsStateWithLifecycle()

            AudioEqTheme(
                themeMode = state.themeMode,
                seedHex = state.seedHex
            ) {
                LaunchedEffect(launchedSessionId) {
                    if (launchedSessionId > 0) {
                        eqViewModel.attachExternalAudioSession(launchedSessionId)
                    }
                }
                AudioEqScreen(
                    uiState = state,
                    viewModel = eqViewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh()
    }
}
