package fr.f4ioz.satcombo.sdr.hpsdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** A fake Brick2 that, like the real one, answers a discovery only from source port 1024 (to the broadcast address). */
class HpsdrDecouverteDiffusionTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private fun avecRadio(c: (Int) -> Unit) {
        val s = DatagramSocket(0, lo).apply { soTimeout = 100 }
        val fil = Thread {
            val b = ByteArray(100)
            while (!s.isClosed) {
                val dp = DatagramPacket(b, b.size)
                try { s.receive(dp) } catch (e: java.net.SocketTimeoutException) { continue } catch (e: Exception) { break }
                if (dp.port == 1024 && dp.length == 63 && b[2].toInt() == 2) {
                    val r = HpsdrDecouverte.reponseRadio(byteArrayOf(0, 0x1C, 0xC0.toByte(), 0xA2.toByte(), 0x22, 0x5C), 32, 1)
                    s.send(DatagramPacket(r, r.size, dp.address, dp.port))
                }
            }
        }.apply { isDaemon = true; start() }
        try { c(s.localPort) } finally { s.close(); fil.join(1000) }
    }

    @Test fun trouve_la_radio_depuis_le_port_1024_via_la_diffusion() = avecRadio { port ->
        val r = HpsdrDecouverte.cherche("", port, diffusion = true, attenteMs = 1500, diffusions = { listOf(lo) })
        assertTrue(r.erreur, r.enLigne)
        assertEquals("00:1C:C0:A2:22:5C", r.mac)
        assertEquals("3.2", r.firmware)
    }

    @Test fun depuis_un_port_ephemere_la_radio_se_tait() = avecRadio { port ->
        val r = HpsdrDecouverte.cherche("", port, diffusion = true, attenteMs = 400, diffusions = { listOf(lo) }, portSource = 0)
        assertFalse(r.enLigne)
    }

    @Test fun les_adresses_de_diffusion_se_calculent() {
        HpsdrDecouverte.adressesDiffusion().forEach { assertTrue(it.hostAddress.endsWith(".255") || it.hostAddress.contains(".")) }
    }
}
