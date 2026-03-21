plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Shared bundle from Suwayomi
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.9.0")
    implementation("io.insert-koin:koin-core:4.1.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.20")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")
    implementation("ca.gosyer:kotlin-multiplatform-appdirs:2.0.0")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.typesafe:config:1.4.5")
    implementation("io.github.config4k:config4k:0.7.0")
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.32")
    implementation("de.femtopedia.dex2jar:dex-tools:2.4.32")
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.3")
    // Removed kcef - not needed for gRPC service (webview library)
}
