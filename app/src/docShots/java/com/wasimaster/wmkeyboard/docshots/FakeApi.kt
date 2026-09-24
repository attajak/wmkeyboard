package com.wasimaster.wmkeyboard.docshots

import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO

/**
 * A stand-in for the web services the keyboard's tools call, on 127.0.0.1.
 *
 * The tools talk plain HttpURLConnection, so a real local server is the whole
 * fake: a shot points the tool's address at [base] (a setting, or
 * ServiceEndpoints with [com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints.overridesEverywhere])
 * and every answer is fixed text, so a shot reads the same on every run.
 *
 * A route is a path prefix; the longest one that matches answers. Anything
 * else is a 404, which the tools show as their own error state.
 */
class FakeApi(routes: List<Route>) {

    class Route(
        val path: String,
        val type: String = "application/json",
        /** Streamed answers: each chunk is flushed, then this long passes. */
        val chunkDelayMs: Long = 0,
        val body: (Request) -> List<ByteArray>,
    )

    /** What a route is handed: the path, the query, and the posted body. */
    class Request(val path: String, val query: String, val body: String)

    private val byLength = routes.sortedByDescending { it.path.length }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val route = byLength.firstOrNull { ex.requestURI.path.startsWith(it.path) }
            val request = Request(ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(), ex.requestBody.readBytes().decodeToString())
            if (route == null) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
                return@createContext
            }
            val chunks = route.body(request)
            ex.responseHeaders.add("Content-Type", route.type)
            if (route.chunkDelayMs > 0) {
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    for (chunk in chunks) {
                        out.write(chunk)
                        out.flush()
                        Thread.sleep(route.chunkDelayMs)
                    }
                }
            } else {
                val bytes = chunks.fold(ByteArray(0)) { acc, b -> acc + b }
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        start()
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)

    init {
        running += this
    }

    companion object {
        private val running = java.util.concurrent.CopyOnWriteArrayList<FakeApi>()

        /** Stops every server a shot started; the next shot starts its own. */
        fun stopAll() {
            running.forEach { it.stop() }
            running.clear()
        }

        fun json(path: String, text: String) = Route(path) { listOf(text.toByteArray()) }

        fun image(path: String, format: String = "png") = Route(path, type = "image/$format") { req ->
            listOf(tile(req.path.hashCode(), format))
        }

        /**
         * A soft two-colour gradient tile with a circle on it: something that
         * reads as a picture in a thumbnail grid without being anybody's photo.
         * The colours come from [seed], so every URL gets its own.
         */
        fun tile(seed: Int, format: String = "png", width: Int = 240, height: Int = 240): ByteArray {
            val palette = listOf(
                Color(0x4C8DF6), Color(0x8B5CF6), Color(0xF59E0B), Color(0x10B981),
                Color(0xEF4444), Color(0xEC4899), Color(0x06B6D4), Color(0x84CC16),
            )
            val a = palette[Math.floorMod(seed, palette.size)]
            val b = palette[Math.floorMod(seed / 7 + 3, palette.size)]
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, a, width.toFloat(), height.toFloat(), b)
            g.fillRect(0, 0, width, height)
            g.color = Color(255, 255, 255, 150)
            val r = width / 4
            g.fillOval(width / 2 - r + Math.floorMod(seed, 40) - 20, height / 2 - r, 2 * r, 2 * r)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
        }
    }
}
