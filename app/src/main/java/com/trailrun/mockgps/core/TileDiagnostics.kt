package com.trailrun.mockgps.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 瓦片连接诊断。
 *
 * 为什么要做这个：地图空白的原因在「电脑上」和「手机上」完全可能不同
 * （运营商出口、校园网、DNS 污染、系统代理各不相同），
 * 只有在设备本身发起请求才能得到可信结论。
 *
 * 只保留 OSM 之后，这里逐个测试 [OSM_ENDPOINTS] 里的每个端点 ——
 * 因为 OSM 官方站在国内实测完全不可达，而镜像是可用的，
 * 用户需要看到「到底哪个能通」才能判断是网络问题还是应用问题。
 */
object TileDiagnostics {

    /** 一个待测试的瓦片端点（直接复用取瓦片时使用的同一份列表）。 */
    data class Result(
        val endpoint: TileEndpoint,
        val ok: Boolean,
        /** 可读结论，例如 "HTTP 200 image/png 512×512 3620B 28ms" 或 "连接被重置"。 */
        val detail: String,
        val elapsedMs: Long,
        /** 服务端**真实**返回的像素尺寸，例如 "512×512"；非 PNG 时为 null。 */
        val pixels: String? = null,
    )

    /** 把经纬度换算成 z/x/y，拼出一个真实瓦片地址。 */
    private fun tileUrl(base: String, lat: Double, lon: Double, zoom: Int = 16): String {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        // 必须去掉基址结尾的斜杠再拼，否则会拼出 "//16/x/y.png" 这种双斜杠地址。
        // 多数服务器容忍它，但 osm.rrze.fau.de 会直接 404 —— 诊断就会误报「不可用」。
        return "${base.trimEnd('/')}/$zoom/$x/$y.png"
    }

    /**
     * 从 PNG 的 IHDR 读出真实像素尺寸。
     *
     * 为什么要读：端点「声明」的 tileSize 是猜的，服务端「实际」给多少必须验证。
     * 一个声明 512 却返回 256 的端点会让 osmdroid 按 512 排布、把图放大 2 倍，
     * 比原来更糊 —— 这条断言就是为了防止这种自欺。
     */
    private fun pngSize(b: ByteArray, len: Int): String? {
        if (len < 24) return null
        if (b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() ||
            b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()
        ) return null
        fun be(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        val w = be(16)
        val h = be(20)
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
        return "$w×$h"
    }

    /** 测试单个端点。 */
    suspend fun test(
        endpoint: TileEndpoint,
        lat: Double,
        lon: Double,
        timeoutMs: Int = 9000,
    ): Result = withContext(Dispatchers.IO) {
        val url = tileUrl(endpoint.baseUrl, lat, lon)
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                // 不伪造 User-Agent：要测的就是 App 真实的请求能否成功
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val type = conn.contentType ?: "?"

            val stream = BufferedInputStream(conn.inputStream)
            val buffer = ByteArray(4096)
            var total = 0
            // 累积一个足够长的文件头：PNG 的 IHDR 在第 16~23 字节，
            // 只留「第一次 read 的内容」在网络分片时可能不足 24 字节。
            val head = ByteArray(64)
            var headLen = 0
            while (total < 8192) {
                val n = stream.read(buffer)
                if (n <= 0) break
                if (headLen < head.size) {
                    val copy = minOf(n, head.size - headLen)
                    System.arraycopy(buffer, 0, head, headLen, copy)
                    headLen += copy
                }
                total += n
            }
            stream.close()

            val ms = System.currentTimeMillis() - started
            val isImage = type.startsWith("image/") || looksLikeImage(head)
            val px = pngSize(head, headLen)
            val ok = code == 200 && total > 0 && isImage

            val detail = buildString {
                append("HTTP ").append(code)
                append("  ").append(type)
                if (px != null) append("  ").append(px)
                append("  ").append(total).append("B")
                append("  ").append(ms).append("ms")
                if (!isImage && total > 0) append("  [返回的不是图片]")
                if (px != null) {
                    val actual = px.substringBefore('×').toIntOrNull()
                    if (actual != null && actual != endpoint.tileSize) {
                        append("  [端点标称 ").append(endpoint.tileSize)
                            .append("px，实际 ").append(actual).append("px]")
                    }
                }
            }
            Result(endpoint, ok, detail, ms, px)
        } catch (e: java.net.SocketTimeoutException) {
            Result(endpoint, false, "连接超时（${timeoutMs}ms 内无响应）", System.currentTimeMillis() - started)
        } catch (e: java.net.UnknownHostException) {
            Result(endpoint, false, "DNS 解析失败：${e.message}", System.currentTimeMillis() - started)
        } catch (e: javax.net.ssl.SSLException) {
            Result(endpoint, false, "TLS 握手失败：${e.message}", System.currentTimeMillis() - started)
        } catch (e: java.net.SocketException) {
            Result(endpoint, false, "连接被重置/中断：${e.message}", System.currentTimeMillis() - started)
        } catch (e: Exception) {
            Result(
                endpoint, false,
                "${e.javaClass.simpleName}: ${e.message}",
                System.currentTimeMillis() - started,
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) return true   // PNG
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return true       // JPEG
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) return true // GIF
        if (b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte()) return true // WEBP
        return false
    }

    /** 依次测试全部端点，逐个回调结果，方便界面边测边更新。 */
    suspend fun testAll(
        lat: Double,
        lon: Double,
        onEach: (Result) -> Unit = {},
    ): List<Result> {
        val out = ArrayList<Result>()
        for (e in OSM_ENDPOINTS) {
            val r = test(e, lat, lon)
            out.add(r)
            withContext(Dispatchers.Main) { onEach(r) }
        }
        return out
    }

    /**
     * 实测一遍所有端点，返回最值得用的那一个。
     *
     * 选择规则见 [chooseBestEndpoint]：清晰度优先于速度。
     * 全部不可达时返回 null —— 调用方此时应**保留**当前设置，
     * 而不是把用户切到一个同样不可用的端点上。
     */
    suspend fun pickFastest(lat: Double, lon: Double): TileEndpoint? =
        chooseBestEndpoint(
            testAll(lat, lon).map { EndpointProbe(it.endpoint, it.ok, it.elapsedMs) }
        )

    /**
     * 边测边回调（界面可以逐个显示结果），测完返回最值得用的端点。
     * 界面上的「测速并选最快」用的就是这个。
     */
    suspend fun testAllAndPick(
        lat: Double,
        lon: Double,
        onEach: (Result) -> Unit = {},
    ): TileEndpoint? = chooseBestEndpoint(
        testAll(lat, lon, onEach).map { EndpointProbe(it.endpoint, it.ok, it.elapsedMs) }
    )
}
