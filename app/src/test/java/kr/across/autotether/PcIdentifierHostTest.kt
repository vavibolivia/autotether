package kr.across.autotether

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 개발 PC 자신에게 질의해 NBSTAT/mDNS/LLMNR 파서를 실제 응답으로 확인하는 수동 테스트.
 * 실행: ./gradlew testDebugUnitTest -Dautotether.hostIp=<PC IP>
 */
class PcIdentifierHostTest {
    @Test
    fun identifyHost() {
        val ip = System.getProperty("autotether.hostIp")
        assumeTrue("autotether.hostIp not set", !ip.isNullOrBlank())
        val target = InetAddress.getByName(ip) as Inet4Address
        val id = PcIdentifier.identifyTargets(listOf(target), 2000)
        println("RESULT: $id")
        assert(id != null) { "no answer from $ip" }
    }
}
