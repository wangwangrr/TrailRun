package com.trailrun.mockgps.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地点搜索（地理编码）。
 *
 * 为什么用 Photon：实测国内网络下
 *   - Nominatim（OSM 官方）超时、维基百科超时；
 *   - 高德 / 腾讯的搜索接口都要求 API Key；
 *   - **Photon（Komoot 开源，基于 OSM 数据）可直连，且中文检索效果好**
 *     （搜「北京大学」直接命中，还支持拼音）。
 * 因此以 Photon 为默认，无需用户配置任何 Key。
 *
 * 搜索失败时不要吞掉错误，把原因返回给界面，方便用户判断是网络问题还是关键词问题。
 */
object PlaceSearch {

    data class Place(
        val name: String,
        val detail: String,
        val latitude: Double,
        val longitude: Double,
        /** 结果类型，例如 house / street / city。 */
        val kind: String = "",
    ) {
        /** 界面显示的一行副标题。 */
        val subtitle: String
            get() = detail.ifBlank { kind }
    }

    sealed class Outcome {
        data class Success(val places: List<Place>) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /**
     * 按关键词搜索地点。
     * @param nearLat/nearLon 传入当前地图中心，可让结果偏向附近（Photon 的 bias）。
     */
    suspend fun search(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        limit: Int = 12,
        timeoutMs: Int = 8000,
    ): Outcome = withContext(Dispatchers.IO) {
        searchBlocking(query, nearLat, nearLon, limit, timeoutMs)
    }

    /** 组装请求地址。抽出来是为了能单独验证编码与参数拼接。 */
    internal fun buildUrl(
        query: String,
        nearLat: Double?,
        nearLon: Double?,
        limit: Int,
    ): String {
        val builder = StringBuilder("https://photon.komoot.io/api/?q=")
            .append(URLEncoder.encode(query.trim(), "UTF-8"))
            .append("&limit=").append(limit)
        // 注意：不要加 lang 参数，Photon 对它返回 HTTP 400（实测）
        if (nearLat != null && nearLon != null) {
            builder.append("&lat=").append(nearLat).append("&lon=").append(nearLon)
        }
        return builder.toString()
    }

    /**
     * 同步版本的搜索：不依赖协程，便于在电脑上直接验证网络与解析全链路。
     * 界面走 [search]（内部只是把它切到 IO 线程）。
     */
    internal fun searchBlocking(
        query: String,
        nearLat: Double? = null,
        nearLon: Double? = null,
        limit: Int = 12,
        timeoutMs: Int = 8000,
    ): Outcome {
        val q = query.trim()
        if (q.isEmpty()) return Outcome.Success(emptyList())

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(buildUrl(q, nearLat, nearLon, limit)).openConnection() as HttpURLConnection)
                .apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    // 有些服务会按 UA 限流，带上可识别的标识
                    setRequestProperty("User-Agent", "TrailRun/1.0 (Android)")
                    instanceFollowRedirects = true
                }

            val code = conn.responseCode
            if (code != 200) {
                Outcome.Failure("搜索服务返回 HTTP $code")
            } else {
                val text = BufferedInputStream(conn.inputStream)
                    .use { it.readBytes().decodeToString() }
                Outcome.Success(parse(text))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Failure("搜索超时，请检查网络后重试")
        } catch (e: java.net.UnknownHostException) {
            Outcome.Failure("搜索服务域名解析失败（可能被网络限制）")
        } catch (e: javax.net.ssl.SSLException) {
            Outcome.Failure("搜索服务 TLS 握手失败")
        } catch (e: Exception) {
            Outcome.Failure("搜索失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 解析 Photon 的 GeoJSON FeatureCollection。
     *
     * internal 而非 private：这样可以在电脑上用真实响应做断言测试
     * （见 tools/verify/PlaceSearchTest.kt），不必等装到手机上才发现字段名读错。
     */
    internal fun parse(json: String): List<Place> {
        val out = ArrayList<Place>()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return out
        val features = root.optJSONArray("features") ?: return out

        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: JSONObject()
            val geom = f.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val name = props.optString("name").ifBlank {
                props.optString("street").ifBlank { props.optString("city") }
            }
            if (name.isBlank()) continue

            // 拼一个可读的地址：城市 + 区 + 街道 + 省份 + 国家
            val parts = listOf(
                props.optString("city"),
                props.optString("district"),
                props.optString("street"),
                props.optString("state"),
                props.optString("country"),
            ).filter { it.isNotBlank() }.distinct()

            out.add(
                Place(
                    name = name,
                    detail = parts.joinToString(" · "),
                    latitude = lat,
                    longitude = lon,
                    kind = props.optString("osm_value").ifBlank { props.optString("type") },
                )
            )
        }
        return out
    }
}
