package moe.radar.mihon_gateway

import xyz.nulldev.ts.config.ApplicationRootDir
import java.io.File

/**
 * Simplified ApplicationDirs for stateless gRPC service
 */
class ApplicationDirs(
    val dataRoot: String = ApplicationRootDir,
    val tempRoot: String = "${System.getProperty("java.io.tmpdir")}/mihon_grpc",
) {
    val extensionsRoot = "$dataRoot/extensions"
    val tempThumbnailCacheRoot = "$tempRoot/thumbnails"
    val tempMangaCacheRoot = "$tempRoot/manga-cache"

    init {
        // Ensure directories exist
        File(extensionsRoot).mkdirs()
        File(tempThumbnailCacheRoot).mkdirs()
        File(tempMangaCacheRoot).mkdirs()
    }

    companion object {
        val instance by lazy { ApplicationDirs() }
    }
}
