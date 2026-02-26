package com.emerald.negocon

import dev.bluefalcon.ApplicationContext
import platform.UIKit.UIApplication

actual fun provideApplicationContext(): ApplicationContext = UIApplication.sharedApplication
