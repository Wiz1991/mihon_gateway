package moe.radar.mihon_gateway.config

import com.typesafe.config.Config
import io.github.config4k.getValue
import xyz.nulldev.ts.config.ConfigModule

class NetworkConfigModule(
    getConfig: () -> Config,
) : ConfigModule(getConfig) {
    val proxyEnabled: Boolean by getConfig()
    val proxyType: String by getConfig()
    val proxyHost: String by getConfig()
    val proxyPort: Int by getConfig()
    val proxyUsername: String by getConfig()
    val proxyPassword: String by getConfig()
    val rateLimitMultiplier: Int by getConfig()

    companion object {
        fun register(config: Config) = NetworkConfigModule { config.getConfig("server.network") }
    }
}
