package com.example

import android.app.Application
import com.example.shizuku.ShizukuManager

class EqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
