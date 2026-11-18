package com.example.iplayertv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Programme(
    val pid: String, val title: String, val episode: String,
    val channel: String, val thumb: String, val desc: String,
)

class Api(base: String) {
    private val root = base.trim().trimEnd('/')

    private fun call(path: String, method: String = "GET"): String {
        val c = URL(root + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 130000
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("Server returned $code: ${body.take(200)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(q: String, channel: String): List<Programme> = withContext(Dispatchers.IO) {
        val arr = JSONObject(call("/api/search?q=${enc(q)}&channel=${enc(channel)}")).getJSONArray("results")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Programme(o.getString("pid"), o.getString("title"), o.optString("episode"),
                o.optString("channel"), o.optString("thumb"), o.optString("desc"))
        }
    }

    suspend fun prepare(pid: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        parseStatus(call("/api/prepare/$pid", "POST"))
    }

    suspend fun status(pid: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        parseStatus(call("/api/status/$pid"))
    }

    private fun parseStatus(body: String): Pair<String, String?> {
        val o = JSONObject(body)
        return o.getString("state") to (if (o.isNull("error")) null else o.getString("error"))
    }

    fun mediaUrl(pid: String) = "$root/media/$pid"
}
