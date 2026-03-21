import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.google.protobuf") version "0.9.4"
    application
}

group = "moe.radar"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jitpack.io") }
    maven {
        url =
            uri("https://github.com/Suwayomi/Suwayomi-Server/raw/android-jar/")
    }
}

dependencies {
    // gRPC + Armeria (gRPC-Web + CORS support for browser clients)
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-protobuf:1.76.0")
    implementation("io.grpc:grpc-services:1.76.0")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")
    implementation("com.linecorp.armeria:armeria-grpc:1.33.4")

    // Coroutines (match Suwayomi)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    // OkHttp (match Suwayomi versions exactly)
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.2.1")
    implementation("com.squareup.okhttp3:logging-interceptor:5.2.1")
    implementation("com.squareup.okio:okio:3.16.2")

    // RxJava (required by Tachiyomi source API)
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("io.reactivex:rxandroid:1.2.1")
    implementation("io.reactivex:rxkotlin:1.0.0")

    // Kotlinx Serialization (match Suwayomi)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.9.0")

    // Koin dependency injection
    implementation("io.insert-koin:koin-core:4.1.1")

    // Tachiyomi extension dependencies
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.github.null2264:injekt-koin:ee267b2e27")

    // Dex2Jar for APK → JAR conversion
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.32")
    implementation("de.femtopedia.dex2jar:dex-tools:2.4.32")
    implementation("org.ow2.asm:asm:9.8")

    // APK parsing
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("com.android.tools.build:apksig:8.13.0")

    // Android annotations
    implementation("androidx.annotation:annotation:1.9.1")

    // Settings library (required by AndroidCompat)
    implementation("com.russhwolf:multiplatform-settings-jvm:1.3.0")
    implementation("com.russhwolf:multiplatform-settings-serialization-jvm:1.3.0")

    // XML Pull Parser
    implementation("xmlpull:xmlpull:1.1.3.4a")

    // ICU4J for text normalization
    implementation("com.ibm.icu:icu4j:77.1")

    // Natural sort comparator
    implementation("com.github.gpanther:java-nat-sort:natural-comparator-1.1")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.1")
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // AndroidCompat (as module, like Suwayomi)
    implementation(project(":AndroidCompat"))
    implementation(project(":AndroidCompat:Config"))

    // AndroidCompat dependencies
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Cache
    implementation("io.github.reactivecircus.cache4k:cache4k:0.14.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.grpc:grpc-testing:1.60.1")
    testImplementation("io.grpc:grpc-netty-shaded:1.60.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.insert-koin:koin-test:4.1.1")
    testImplementation("io.insert-koin:koin-test-junit5:4.1.1")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.1"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
                id("grpckt")
            }
            it.builtins {
                id("kotlin")
            }
        }
    }
}

application {
    mainClass.set("moe.radar.mihon_gateway.Main")
    applicationDefaultJvmArgs = listOf(
        "-Dsuwayomi.tachidesk.config.server.debugLogsEnabled=false"
    )
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

// Configure source sets
sourceSets {
    main {
        kotlin {
            // Only include src/main/kotlin (has eu/kanade/tachiyomi, moe/radar/mihon_gateway, and minimal suwayomi utils)
            srcDirs("src/main/kotlin")
            // Exclude database-dependent Suwayomi impl files (not utils!)
            exclude("**/suwayomi/tachidesk/manga/impl/Category*.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Library.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Manga.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/MangaList.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Chapter*.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Page.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Search.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/Source.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/backup/**")
            exclude("**/suwayomi/tachidesk/manga/impl/download/**")
            exclude("**/suwayomi/tachidesk/manga/impl/extension/Extension*.kt")
            exclude("**/suwayomi/tachidesk/manga/impl/sync/**")
            exclude("**/suwayomi/tachidesk/manga/impl/track/**")
            exclude("**/suwayomi/tachidesk/manga/impl/update/**")
            exclude("**/suwayomi/tachidesk/manga/impl/chapter/**")
        }
    }
}
