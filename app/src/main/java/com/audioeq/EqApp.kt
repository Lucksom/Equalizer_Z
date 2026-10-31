package com.audioeq

import android.app.Application
import com.audioeq.shizuku.ShizukuManager

class EqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
