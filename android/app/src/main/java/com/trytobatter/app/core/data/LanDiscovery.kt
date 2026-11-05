package com.trytobatter.app.core.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

data class DiscoveredComputer(val address: String, val serverId: String, val name: String)

class LanDiscovery(private val context: Context) {
    private val client = LanClient(context)
    suspend fun find(remembered: String, expectedId: String? = null): DiscoveredComputer? {
        val candidates = withContext(Dispatchers.IO) {
            val addresses = linkedSetOf(remembered)
            val network = client.lanNetwork() ?: return@withContext addresses
            val properties = context.getSystemService(ConnectivityManager::class.java).getLinkProperties(network)
            val broadcasts = linkedSetOf("255.255.255.255")
            properties?.linkAddresses?.forEach { link -> if(link.address is Inet4Address && link.prefixLength in 1..30) {
                val bytes = link.address.address
                var value = 0L; bytes.forEach { value = (value shl 8) or (it.toInt() and 255).toLong() }
                val mask = (0xffffffffL shl (32 - link.prefixLength)) and 0xffffffffL
                val broadcast = (value and mask) or (mask xor 0xffffffffL)
                broadcasts += (3 downTo 0).joinToString(".") { ((broadcast shr (8 * it)) and 255).toString() }
            } }
            properties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress?.let { addresses += "http://$it:8081" }
            runCatching { DatagramSocket(null).use { socket ->
                socket.broadcast = true; socket.soTimeout = 300; socket.bind(InetSocketAddress(0)); network.bindSocket(socket)
                val query = "{\"probe\":\"try-to-batter\",\"protocol\":1}".toByteArray()
                broadcasts.forEach { host -> runCatching { socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), 28761)) } }
                val end = android.os.SystemClock.elapsedRealtime() + 900
                while(android.os.SystemClock.elapsedRealtime() < end) runCatching {
                    val packet = DatagramPacket(ByteArray(1024), 1024); socket.receive(packet)
                    val j = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if(j.optString("service") == "try-to-batter" && j.optInt("protocol") == 1) addresses += LanAddress.normalize("http://${packet.address.hostAddress}:${j.getInt("port")}")
                }
            } }
            addresses
        }
        return coroutineScope { candidates.take(8).map { address -> async(Dispatchers.IO) {
            runCatching {
                val normalized = LanAddress.normalize(address); val health = client.health(normalized)
                if(expectedId != null && health.getString("serverId") != expectedId) null else DiscoveredComputer(normalized, health.getString("serverId"), health.getString("serverName"))
            }.getOrNull()
        } }.awaitAll().filterNotNull().firstOrNull() }
    }
}
