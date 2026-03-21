package moe.radar.mihon_gateway.service

import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.AbstractHttpService
import com.linecorp.armeria.server.ServiceRequestContext
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import moe.radar.mihon_gateway.source.SourceManager

class ImageProxyService : AbstractHttpService() {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun doGet(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        val sourceId = ctx.pathParam("sourceId")?.toLongOrNull()
            ?: return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "Invalid sourceId")

        val imageUrl = ctx.queryParam("url")
            ?: return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, "Missing 'url' query parameter")

        val future = scope.async {
            try {
                val source = SourceManager.getCatalogueSourceOrThrow(sourceId)

                if (source !is HttpSource) {
                    return@async HttpResponse.of(
                        HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Source $sourceId is not an HttpSource",
                    )
                }

                val page = Page(index = 0, url = "", imageUrl = imageUrl)
                val response = source.getImage(page)

                val body = response.body
                    ?: return@async HttpResponse.of(
                        HttpStatus.BAD_GATEWAY,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Empty response from upstream",
                    )

                val bytes = body.bytes()
                val contentType = response.header("Content-Type")
                    ?.let { MediaType.parse(it) }
                    ?: MediaType.OCTET_STREAM

                val headers = ResponseHeaders.builder(HttpStatus.OK)
                    .contentType(contentType)
                    .build()

                HttpResponse.of(headers, HttpData.wrap(bytes))
            } catch (e: IllegalArgumentException) {
                HttpResponse.of(
                    HttpStatus.NOT_FOUND,
                    MediaType.PLAIN_TEXT_UTF_8,
                    e.message ?: "Source not found",
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to proxy image: sourceId=$sourceId" }
                HttpResponse.of(
                    HttpStatus.BAD_GATEWAY,
                    MediaType.PLAIN_TEXT_UTF_8,
                    "Failed to fetch image: ${e.message}",
                )
            }
        }

        return HttpResponse.of(future.asCompletableFuture())
    }
}
