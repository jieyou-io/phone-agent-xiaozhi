package com.xiaozhi.phoneagent

import android.app.Application
import com.xiaozhi.phoneagent.utils.HttpClient

class PhoneAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HttpClient.initialize(this)
    }
}
