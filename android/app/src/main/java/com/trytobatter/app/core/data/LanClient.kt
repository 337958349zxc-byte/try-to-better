package com.trytobatter.app.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

object LanAddress {
    fun normalize(input: String): String {
        val uri = URI(if("://" in input.trim()) input.trim() else "http://${input.trim()}")
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")) { "请填写局域网地址，例如 192.168.0.23:8081" }
        val pieces = uri.host?.split('.')?.map { it.toIntOrNull() ?: -1 } ?: emptyList()
        require(pieces.size == 4 && pieces.all { it in 0..255 }) { "请使用电脑的局域网 IPv4 地址" }
        val private = pieces[0] == 10 || pieces[0] == 127 || (pieces[0] == 192 && pieces[1] == 168) || (pieces[0] == 172 && pieces[1] in 16..31) || (pieces[0] == 169 && pieces[1] == 254)
        require(private && (uri.port == -1 || uri.port in 1..65535)) { "只支持局域网地址" }
        return "${uri.scheme}://${uri.host}:${if(uri.port < 0) 8081 else uri.port}"
    }
}

class LanApiException(val status: Int, message: String): IOException(message)

class LanClient(private val context: Context) {
    fun lanNetwork(): android.net.Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
        return networks.distinct().firstOrNull { network -> manager.getNetworkCapabilities(network)?.let { c ->
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true }
    }
    fun connection(url: String): HttpURLConnection {
        val parsed = URL(url)
        val lan = lanNetwork()
        val connection = if(parsed.host == "127.0.0.1") parsed.openConnection() else (lan ?: throw IOException("未找到 Wi-Fi 或以太网，请连接电脑所在局域网")).openConnection(parsed)
        return (connection as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 5000; instanceFollowRedirects = false }
    }
    fun request(address: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val c = connection(LanAddress.normalize(address) + path)
        try {
            c.requestMethod = if(body == null) "GET" else "POST"
            if(token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if(body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 4_000_000) { "本次同步内容过大，请减少批次" }
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            require(status !in 300..399) { "电脑服务发生重定向，请检查地址" }
            val raw = (if(status < 400) c.inputStream else c.errorStream)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size() + n <= 8_000_000) { "电脑响应过大" }; out.write(buffer, 0, n) }
                out.toString("UTF-8")
            } ?: "{}"
            val result = runCatching { JSONObject(raw) }.getOrElse { throw IOException("电脑响应格式无效，请检查服务地址") }
            if(status != 200) throw LanApiException(status, result.optString("error", "电脑请求失败（$status）"))
            return result
        } finally { c.disconnect() }
    }
    fun health(address: String): JSONObject = request(address, "/api/health").also {
        require(it.optBoolean("ok") && it.optString("service") == "try-to-batter" && it.optInt("protocol") == 1 && it.optString("serverId").isNotBlank()) { "这不是兼容的 try to better 电脑服务" }
    }
}
