package kr.across.autotether

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/** 식별된 PC. key는 대문자 호스트명(도메인/.local 제거). */
data class PcIdentity(val key: String, val displayName: String, val ip: String, val mac: String?, val via: String)

/**
 * 권한 없이 USB 테더링 대역의 PC 호스트명을 알아낸다.
 *
 * 1) NetBIOS NBSTAT (UDP 137) — Windows 호스트명 + 어댑터 MAC
 * 2) mDNS 역방향 PTR (UDP 5353, legacy unicast) — Windows 10+/macOS/Linux(avahi)
 * 3) LLMNR 역방향 PTR (UDP 5355) — Windows
 *
 * 폰은 PC의 IP를 직접 알 수 없으므로(TetheringManager 클라이언트 목록은 시스템 API)
 * 테더 인터페이스의 /24 대역 전체에 질의를 보내고 응답한 호스트를 PC로 본다.
 * RNDIS/NCM의 PC쪽 MAC은 폰의 USB 가젯이 정하는 값이어서 PC 고유값이 아닐 수 있으므로 키로 쓰지 않는다.
 */
object PcIdentifier {
    private const val TAG = "PcIdentifier"

    fun identify(iface: TetherIface, timeoutMs: Int = 1500): PcIdentity? {
        val targets = subnetHosts(iface)
        if (targets.isEmpty()) return null
        return identifyTargets(targets, timeoutMs)
    }

    internal fun identifyTargets(targets: List<Inet4Address>, timeoutMs: Int): PcIdentity? {
        return nbstat(targets, timeoutMs)
            ?: reversePtr(targets, 5353, "mDNS", timeoutMs)
            ?: reversePtr(targets, 5355, "LLMNR", timeoutMs)
    }

    /** 자기 주소를 제외한 /24 호스트(prefix가 더 좁으면 그 범위). */
    private fun subnetHosts(iface: TetherIface): List<Inet4Address> {
        val b = iface.address.address
        val self = b[3].toInt() and 0xff
        val prefix = iface.prefixLength.coerceIn(24, 30)
        val size = 1 shl (32 - prefix)
        val base = self and (size - 1).inv() and 0xff
        return (1 until size - 1).map { base + it }.filter { it != self }.map {
            InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], it.toByte())) as Inet4Address
        }
    }

    // ---------------- NetBIOS NBSTAT ----------------

    private fun nbstat(targets: List<Inet4Address>, timeoutMs: Int): PcIdentity? = try {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val txId = Random.nextInt(0x10000)
            val q = buildNbstatQuery(txId)
            targets.forEach { sock.send(DatagramPacket(q, q.size, it, 137)) }
            receiveFirst(sock, timeoutMs) { data, from -> parseNbstat(data, txId, from) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "nbstat failed", e); null
    }

    internal fun buildNbstatQuery(txId: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(txId shr 8); out.write(txId and 0xff)
        out.write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // flags, QD=1, AN/NS/AR=0
        // "*" + 15 x 0x00, first-level encoding
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        out.write(0x20)
        raw.forEach { v ->
            val x = v.toInt() and 0xff
            out.write('A'.code + (x shr 4)); out.write('A'.code + (x and 0x0f))
        }
        out.write(0)
        out.write(byteArrayOf(0, 0x21, 0, 1)) // NBSTAT, IN
        return out.toByteArray()
    }

    internal fun parseNbstat(d: ByteArray, txId: Int, from: InetAddress): PcIdentity? {
        if (d.size < 12 || u16(d, 0) != txId) return null
        if (u16(d, 6) < 1) return null // ANCOUNT
        var p = skipName(d, 12) ?: return null
        if (p + 10 > d.size || u16(d, p) != 0x21) return null
        p += 10 // type, class, ttl, rdlength
        val count = d[p].toInt() and 0xff; p += 1
        var host: String? = null
        for (i in 0 until count) {
            if (p + 18 > d.size) return null
            val name = String(d, p, 15, Charsets.US_ASCII).trim()
            val suffix = d[p + 15].toInt() and 0xff
            val group = (u16(d, p + 16) and 0x8000) != 0
            if (host == null && suffix == 0x00 && !group && name.isNotEmpty()) host = name
            p += 18
        }
        host ?: return null
        val mac = if (p + 6 <= d.size) (0 until 6).joinToString(":") { "%02X".format(d[p + it]) } else null
        return PcIdentity(normalize(host), host, from.hostAddress ?: "", mac?.takeIf { it != "00:00:00:00:00:00" }, "NetBIOS")
    }

    // ---------------- mDNS / LLMNR reverse PTR ----------------

    private fun reversePtr(targets: List<Inet4Address>, port: Int, via: String, timeoutMs: Int): PcIdentity? = try {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val ids = HashMap<Int, Inet4Address>()
            targets.forEach { ip ->
                val id = Random.nextInt(0x10000)
                ids[id] = ip
                val q = buildPtrQuery(id, ip)
                sock.send(DatagramPacket(q, q.size, ip, port))
            }
            receiveFirst(sock, timeoutMs) { data, from ->
                // mDNS legacy unicast 응답은 ID를 돌려주고, 일부 구현은 0을 쓰므로 송신원 주소로도 허용
                if (data.size < 12) return@receiveFirst null
                val id = u16(data, 0)
                if (id != 0 && ids[id] == null) return@receiveFirst null
                parsePtrAnswer(data)?.let { name ->
                    val clean = name.removeSuffix(".").removeSuffix(".local")
                    PcIdentity(normalize(clean), clean, from.hostAddress ?: "", null, via)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "$via failed", e); null
    }

    internal fun buildPtrQuery(id: Int, ip: Inet4Address): ByteArray {
        val b = ip.address.map { it.toInt() and 0xff }
        val labels = listOf(b[3], b[2], b[1], b[0]).map { it.toString() } + listOf("in-addr", "arpa")
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        labels.forEach { l -> out.write(l.length); out.write(l.toByteArray(Charsets.US_ASCII)) }
        out.write(0)
        out.write(byteArrayOf(0, 12, 0, 1)) // PTR, IN
        return out.toByteArray()
    }

    internal fun parsePtrAnswer(d: ByteArray): String? {
        val qd = u16(d, 4)
        val an = u16(d, 6)
        if (an < 1) return null
        var p = 12
        repeat(qd) { p = (skipName(d, p) ?: return null) + 4 }
        repeat(an) {
            p = skipName(d, p) ?: return null
            if (p + 10 > d.size) return null
            val type = u16(d, p)
            val rdlen = u16(d, p + 8)
            val rd = p + 10
            if (type == 12) return readName(d, rd)
            p = rd + rdlen
        }
        return null
    }

    // ---------------- DNS helpers ----------------

    private inline fun <T> receiveFirst(sock: DatagramSocket, timeoutMs: Int, parse: (ByteArray, InetAddress) -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1500)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            sock.soTimeout = left.toInt().coerceAtLeast(1)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val r = parse(buf.copyOf(pkt.length), pkt.address)
            if (r != null) return r
        }
    }

    private fun u16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)

    /** 이름 필드를 건너뛴 다음 오프셋. */
    private fun skipName(d: ByteArray, start: Int): Int? {
        var p = start
        while (p < d.size) {
            val len = d[p].toInt() and 0xff
            when {
                len == 0 -> return p + 1
                (len and 0xc0) == 0xc0 -> return p + 2
                else -> p += 1 + len
            }
        }
        return null
    }

    private fun readName(d: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var p = start
        var jumps = 0
        while (p < d.size) {
            val len = d[p].toInt() and 0xff
            when {
                len == 0 -> return sb.toString()
                (len and 0xc0) == 0xc0 -> {
                    if (p + 1 >= d.size || ++jumps > 10) return null
                    p = ((len and 0x3f) shl 8) or (d[p + 1].toInt() and 0xff)
                }
                else -> {
                    if (p + 1 + len > d.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(d, p + 1, len, Charsets.UTF_8))
                    p += 1 + len
                }
            }
        }
        return null
    }

    private fun normalize(host: String) = host.substringBefore('.').trim().uppercase()
}
