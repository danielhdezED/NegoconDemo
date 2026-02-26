package com.emerald.negocon

import android.app.Application
import dev.bluefalcon.ApplicationContext

object AndroidAppContext {
    lateinit var application: Application
        private set

    fun init(application: Application) {
        this.application = application
    }
}

actual fun provideApplicationContext(): ApplicationContext = AndroidAppContext.application
