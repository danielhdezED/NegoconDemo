package com.emerald.negocon

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.PrintLnLogger

expect fun provideApplicationContext(): ApplicationContext

fun createBlueFalcon(): BlueFalcon = BlueFalcon(
    log = PrintLnLogger,
    context = provideApplicationContext(),
    autoDiscoverAllServicesAndCharacteristics = false
)
