package com.example.a2desktop

import com.example.a2desktop.link.LINK_DEFAULT_PORT
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkCrypto
import com.example.a2desktop.link.LocalNetworks
import com.example.a2desktop.link.LocalSubnet
import com.example.a2desktop.link.PhoneFinder
import com.example.a2desktop.link.PhonePing
import com.example.a2desktop.link.PingReply
import com.example.a2desktop.link.QrPairing
import com.example.a2desktop.ui.qrModules
import com.example.a2desktop.ui.qrPixels
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pairing code (what is drawn, and that a camera could read it back) and finding the phone on the home network. */
class QrAndFinderTest {
    private val key = LinkCrypto.normalizePairingKey(LinkCrypto.generatePairingKey())!!

    // ---- the code ----

    private fun read(text: String): String {
        val (pixels, width, height) = qrPixels(qrModules(text), scale = 6)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun theDrawnCodeReadsBackAsExactlyWhatThePhoneNeeds() {
        val payload = QrPairing.encode(key, "DESKTOP-K7EQIDF")
        assertEquals(payload, read(payload))
        val parsed = QrPairing.parse(read(payload))!!
        assertEquals(key, parsed.key)
        assertEquals("DESKTOP-K7EQIDF", parsed.pcName)
    }

    @Test fun aNameWithOddCharactersStillReadsBack() {
        val payload = QrPairing.encode(key, "Ana's PC & café")
        assertEquals("Ana's PC & café", QrPairing.parse(read(payload))!!.pcName)
    }

    @Test fun theCodeIsAModestSizeSoItScansEasilyOffAScreen() {
        val size = qrModules(QrPairing.encode(key, "DESKTOP-K7EQIDF")).size
        assertTrue(size <= 41, "a version 6 code or smaller: $size modules a side")
    }

    @Test fun theFormatMatchesWhatThePhoneReads() {
        assertEquals("mimi-pair://pair?v=1&k=$key&n=My+PC", QrPairing.encode(key, "My PC"))
        assertNull(QrPairing.parse("https://example.com/?k=$key"))
        assertNull(QrPairing.parse("mimi-pair://pair?v=1&k=SHORT"))
        assertNull(QrPairing.parse("mimi-pair://pair?v=2&k=$key"))
        assertNotNull(QrPairing.parse("mimi-pair://pair?v=1&k=$key"))
    }

    // ---- which hosts are asked ----

    @Test fun aHomeNetworkIsEveryHostExceptThisPcTheNetworkAndTheBroadcast() {
        val hosts = LocalNetworks.hostsOf(LocalSubnet("192.168.0.15", 24))
        assertEquals(253, hosts.size)
        assertFalse("192.168.0.15" in hosts, "this PC")
        assertFalse("192.168.0.0" in hosts)
        assertFalse("192.168.0.255" in hosts)
        assertTrue("192.168.0.16" in hosts && "192.168.0.1" in hosts && "192.168.0.254" in hosts)
    }

    @Test fun aSlightlyBiggerNetworkIsAskedWholeAndAHugeOneOnlyAroundThisPc() {
        assertEquals(1021, LocalNetworks.hostsOf(LocalSubnet("192.168.1.5", 22)).size)
        val wide = LocalNetworks.hostsOf(LocalSubnet("10.4.7.9", 8))
        assertEquals(253, wide.size, "a /8 is cut to the /24 around this PC")
        assertTrue(wide.all { it.startsWith("10.4.7.") })
    }

    @Test fun aNetworkWithNoOtherHostsHasNothingToAsk() {
        assertTrue(LocalNetworks.hostsOf(LocalSubnet("10.2.0.2", 32)).isEmpty(), "a VPN tunnel's one address")
        assertTrue(LocalNetworks.hostsOf(LocalSubnet("not an address", 24)).isEmpty())
    }

    @Test fun vpnAndVirtualAdaptersAreNotNetworksThePhoneCanBeOn() {
        assertTrue(LocalNetworks.isVirtual("ProTUN", "Proton VPN Windows Tunnel"))
        assertTrue(LocalNetworks.isVirtual("wireguard0", "WireGuard Tunnel"))
        assertTrue(LocalNetworks.isVirtual("eth2", "VMware Virtual Ethernet Adapter"))
        assertTrue(LocalNetworks.isVirtual("vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter"))
        assertFalse(LocalNetworks.isVirtual("Ethernet", "Realtek Gaming 2.5GbE Family Controller"))
        assertFalse(LocalNetworks.isVirtual("WiFi", "MediaTek Wi-Fi 6 MT7902LEN Wireless LAN Card"))
    }

    // ---- searching ----

    private fun finder(vararg answering: Pair<String, String?>, threads: Int = 8, ports: IntRange = LINK_DEFAULT_PORT..LINK_DEFAULT_PORT + 9, asked: MutableList<LinkAddress>? = null): PhoneFinder {
        val replies = answering.toMap()
        return PhoneFinder(
            subnets = { listOf(LocalSubnet("192.168.0.15", 24)) },
            ping = { address ->
                asked?.add(address)
                if (address.host in replies && address.port == LINK_DEFAULT_PORT) PingReply(replies[address.host]) else null
            },
            threads = threads,
            ports = ports,
        )
    }

    @Test fun theFinderReturnsTheHostsThatAnswerAndTheirKeyFingerprints() {
        val found = finder("192.168.0.16" to "aaaaaaaaaaaaaaaa", "192.168.0.77" to null).search()
        assertEquals(setOf("192.168.0.16", "192.168.0.77"), found.map { it.address.host }.toSet())
        assertEquals("aaaaaaaaaaaaaaaa", found.first { it.address.host == "192.168.0.16" }.keyId)
        assertNull(found.first { it.address.host == "192.168.0.77" }.keyId, "an older phone app shows none")
    }

    @Test fun theFinderFindsNothingWhenNobodyAnswers() {
        assertTrue(finder().search().isEmpty())
    }

    @Test fun everyPortThePhoneMayHaveMovedToIsTriedOnTheLastKnownHostOnly() {
        val asked = java.util.Collections.synchronizedList(mutableListOf<LinkAddress>())
        finder(asked = asked).search(firstHosts = listOf("192.168.0.16"))
        assertEquals((LINK_DEFAULT_PORT..LINK_DEFAULT_PORT + 9).toList(), asked.filter { it.host == "192.168.0.16" }.map { it.port }.sorted())
        assertEquals(listOf(LINK_DEFAULT_PORT), asked.filter { it.host == "192.168.0.40" }.map { it.port }, "everyone else is asked on the one port")
        assertEquals(253 + 9, asked.size, "the 253 hosts of the network, and nine more ports on the known one")
    }

    @Test fun aSlowOrStuckHostCannotHoldTheSearchUpPastItsLimit() {
        val slow = PhoneFinder(
            subnets = { listOf(LocalSubnet("192.168.0.15", 28)) }, // 13 hosts
            ping = { Thread.sleep(5_000); null },
            threads = 4,
            totalMillis = 300,
        )
        val started = System.nanoTime()
        assertTrue(slow.search().isEmpty())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "gave up at its limit")
    }

    // ---- asking one address ----

    @Test fun aRealSocketThatAnswersLikeThePhoneIsRecognisedWithItsFingerprint() {
        ServerSocket(0).use { server ->
            val served = AtomicInteger()
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        client.getInputStream().read(ByteArray(512))
                        served.incrementAndGet()
                        val body = """{"app":"a2","protocol":1,"kid":"0123456789abcdef"}"""
                        client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                    }
                }
            }
            val reply = PhonePing.ping(LinkAddress("127.0.0.1", server.localPort))
            assertNotNull(reply)
            assertEquals("0123456789abcdef", reply.keyId)
            assertEquals(1, served.get())
        }
    }

    @Test fun somethingElseOnThePortOrNothingAtAllIsNotAPhone() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        client.getInputStream().read(ByteArray(512))
                        client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nhello web".toByteArray())
                    }
                }
            }
            assertNull(PhonePing.ping(LinkAddress("127.0.0.1", server.localPort)), "a web server is not the phone")
        }
        assertNull(PhonePing.ping(LinkAddress("127.0.0.1", 1)), "nothing listening")
    }
}
