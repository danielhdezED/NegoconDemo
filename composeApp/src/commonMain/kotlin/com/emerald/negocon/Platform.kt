package com.emerald.negocon

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform