package kr.across.autotether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

class PcIdentifierParseTest {

    private val pcIp = InetAddress.getByName("192.168.42.129") as Inet4Address

    @Test
    fun nbstatQueryEncodesWildcard() {
        val q = PcIdentifier.buildNbstatQuery(0x1234)
        assertEquals(50, q.size)
        assertEquals(0x12, q[0].toInt() and 0xff)
        assertEquals(0x20, q[12].toInt())
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII))
        assertEquals(0x21, q[47].toInt()) // type NBSTAT
    }

    @Test
    fun parsesNbstatResponse() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        val query = PcIdentifier.buildNbstatQuery(0x1234)
        out.write(query, 12, 34) // echoed name
        out.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0))
        val names = listOf(
            Triple("WORKGROUP", 0x00, 0x8400), // group → skip
            Triple("DESKTOP-AB12", 0x00, 0x0400),
            Triple("DESKTOP-AB12", 0x20, 0x0400),
        )
        val rd = ByteArrayOutputStream()
        rd.write(names.size)
        names.forEach { (n, suffix, flags) ->
            rd.write(n.padEnd(15, ' ').toByteArray(Charsets.US_ASCII))
            rd.write(suffix); rd.write(flags shr 8); rd.write(flags and 0xff)
        }
        rd.write(byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f))
        val rdBytes = rd.toByteArray()
        out.write(rdBytes.size shr 8); out.write(rdBytes.size and 0xff)
        out.write(rdBytes)

        val id = PcIdentifier.parseNbstat(out.toByteArray(), 0x1234, pcIp)!!
        assertEquals("DESKTOP-AB12", id.key)
        assertEquals("0A:1B:2C:3D:4E:5F", id.mac)
        assertEquals("192.168.42.129", id.ip)
        assertNull(PcIdentifier.parseNbstat(out.toByteArray(), 0x9999, pcIp))
    }

    @Test
    fun parsesPtrAnswerWithCompression() {
        val q = PcIdentifier.buildPtrQuery(0x0102, pcIp)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x01, 0x02, 0x84.toByte(), 0, 0, 1, 0, 1, 0, 0, 0, 0))
        out.write(q, 12, q.size - 12) // question
        // answer: name = pointer to question (offset 12), PTR IN ttl rdata
        out.write(byteArrayOf(0xc0.toByte(), 12, 0, 12, 0, 1, 0, 0, 0, 120))
        val rdata = ByteArrayOutputStream()
        rdata.write(9); rdata.write("Jun-PC-01".toByteArray())
        rdata.write(5); rdata.write("local".toByteArray()); rdata.write(0)
        val rb = rdata.toByteArray()
        out.write(0); out.write(rb.size); out.write(rb)

        assertEquals("Jun-PC-01.local", PcIdentifier.parsePtrAnswer(out.toByteArray()))
    }

    @Test
    fun ptrQueryUsesReverseName() {
        val q = PcIdentifier.buildPtrQuery(1, pcIp)
        val name = String(q, 12, q.size - 16, Charsets.US_ASCII)
        assertEquals("\u0003129\u000242\u0003168\u0003192\u0007in-addr\u0004arpa\u0000", name)
    }
}
