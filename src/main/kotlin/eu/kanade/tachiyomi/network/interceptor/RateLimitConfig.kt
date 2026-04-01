package eu.kanade.tachiyomi.network.interceptor

import moe.radar.mihon_gateway.config.NetworkConfigModule
import xyz.nulldev.ts.config.GlobalConfigManager

internal fun getRateLimitMultiplier(): Int {
    return try {
        val config = GlobalConfigManager.module<NetworkConfigModule>()
        if (config.proxyEnabled) config.rateLimitMultiplier.coerceAtLeast(1) else 1
    } catch (_: Exception) {
        1
    }
}
