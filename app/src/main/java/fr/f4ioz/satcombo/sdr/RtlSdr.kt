/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sdr

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * Pilote RTL-SDR intégré : la clé se branche sur l'USB-C du téléphone et SatMe
 * la commande directement, sans passer par une application tierce.
 *
 * Une clé RTL-SDR est un tuner Rafael Micro R820T/R2 suivi d'un démodulateur
 * DVB-T Realtek RTL2832U. On la détourne de sa fonction : le RTL2832U est
 * placé en « mode SDR », son décodeur DVB-T court-circuité, et il déverse par
 * son entrée bulk le flux IQ 8 bits non signé venant du convertisseur.
 *
 * Le dialogue se fait entièrement en transferts de contrôle sur l'endpoint 0 :
 *  - écriture/lecture d'un bloc de registres (démodulateur, USB, système) ;
 *  - passerelle I2C vers le tuner, qui n'est pas visible autrement.
 * Le tuner ne répond que si le « répéteur I2C » du RTL2832U est ouvert : toute
 * transaction vers le R820T est donc encadrée par une ouverture et une
 * fermeture de ce répéteur. C'est le piège classique du pilote.
 *
 * Toute l'arithmétique (PLL, FI, débit, gain) est déportée dans [RtlTuning],
 * qui ne dépend pas d'Android et se teste au banc. Ici il ne reste que du
 * transport d'octets.
 *
 * ATTENTION : cette fonction est en bêta. Elle n'a pas pu être validée sur
 * matériel dans l'environnement de compilation.
 */
class RtlSdr(private val ctx: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "fr.f4ioz.satcombo.SDR_USB_PERMISSION"

        // Blocs de registres du RTL2832U.
        private const val BLOCK_DEMOD = 0
        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2
        private const val BLOCK_I2C = 6

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000
        private const val DEMOD_CTL_1 = 0x300b

        private const val CTRL_IN = 0xC0
        private const val CTRL_OUT = 0x40
        private const val CTRL_TIMEOUT = 300

        private const val R820T_I2C = 0x34

        /**
         * Taille d'un transfert bulk, et pas une de plus.
         *
         * C'est le point qui empêchait la clé de débiter quoi que ce soit :
         * sous Android, usbfs refuse les transferts bulk de plus de 16 ko et
         * renvoie -1 sans autre explication. On lisait par blocs de 64 ko —
         * aucune lecture n'aboutissait, la vingtième erreur d'affilée coupait
         * la réception, et l'écran affichait « plus de données de la clé :
         * débranche et rebranche ». Le matériel n'y était pour rien.
         *
         * 16 ko à 1,06 Ms/s font 7,7 ms d'IQ, soit 130 transferts par seconde :
         * c'est précisément pour cela que la lecture est asynchrone (voir
         * [startStream]), sans quoi le temps de démodulation d'un bloc serait
         * du temps pendant lequel la clé n'est pas lue.
         */
        const val XFER = 16 * 1024

        /**
         * Couples identifiant/produit connus. La grande majorité des clés du
         * commerce sont des Realtek 0x0bda 0x2832 ou 0x2838 ; les autres sont
         * d'anciens tuners TNT réutilisés par la communauté.
         */
        private val KNOWN = setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838,
            0x0413 to 0x6680, 0x0413 to 0x6f0f,
            0x0458 to 0x707f,
            0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5,
            0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9, 0x0ccd to 0x00c0,
            0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0,
            0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133,
            0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680,
            0x1b80 to 0xd393, 0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397,
            0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4, 0x1b80 to 0xd3a8,
            0x1b80 to 0xd3af, 0x1b80 to 0xd3b0,
            0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103, 0x1d19 to 0x1104,
            0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286,
            0x1f4d to 0xd803
        )

        fun isRtl(d: UsbDevice): Boolean = (d.vendorId to d.productId) in KNOWN

        /** Première clé RTL-SDR branchée, ou null. */
        fun find(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull { isRtl(it) }

        /** Demande la permission USB pour [dev] (boîte de dialogue système). */
        fun requestPermission(ctx: Context, dev: UsbDevice) {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            fr.f4ioz.satcombo.usb.UsbPermission.ensure(ctx, um, dev, ACTION_USB_PERMISSION)
        }
    }

    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    var device: UsbDevice? = null
        private set

    val isOpen: Boolean get() = conn != null

    /** Débit réellement délivré par la clé (voir [RtlTuning.actualRate]). */
    var actualSampleRate: Double = 0.0
        private set

    /** Dernière fréquence centrale réellement synthétisée. */
    var tunedHz: Long = 0L
        private set

    /** Dernier message d'erreur technique, utile en bêta. */
    var lastError: String? = null
        private set

    // ------------------------------------------------------ transferts bruts

    private fun writeArray(block: Int, addr: Int, data: ByteArray): Boolean {
        val c = conn ?: return false
        val index = (block shl 8) or 0x10
        val n = c.controlTransfer(CTRL_OUT, 0, addr, index, data, data.size, CTRL_TIMEOUT)
        return n == data.size
    }

    private fun readArray(block: Int, addr: Int, len: Int): ByteArray? {
        val c = conn ?: return null
        val index = block shl 8
        val buf = ByteArray(len)
        val n = c.controlTransfer(CTRL_IN, 0, addr, index, buf, len, CTRL_TIMEOUT)
        return if (n == len) buf else null
    }

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int): Boolean {
        val data = if (len == 1) byteArrayOf(value.toByte())
                   else byteArrayOf((value shr 8).toByte(), value.toByte())
        return writeArray(block, addr, data)
    }

    private fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int): Boolean {
        val c = conn ?: return false
        val index = 0x10 or page
        val wValue = (addr shl 8) or 0x20
        val data = if (len == 1) byteArrayOf(value.toByte())
                   else byteArrayOf((value shr 8).toByte(), value.toByte())
        val n = c.controlTransfer(CTRL_OUT, 0, wValue, index, data, data.size, CTRL_TIMEOUT)
        // librtlsdr relit systématiquement un registre après écriture : le
        // démodulateur ne valide la page qu'à la transaction suivante.
        demodReadReg(0x0a, 0x01, 1)
        return n == data.size
    }

    private fun demodReadReg(page: Int, addr: Int, len: Int): Int {
        val c = conn ?: return -1
        val wValue = (addr shl 8) or 0x20
        val buf = ByteArray(len)
        val n = c.controlTransfer(CTRL_IN, 0, wValue, page, buf, len, CTRL_TIMEOUT)
        if (n != len) return -1
        return if (len == 1) (buf[0].toInt() and 0xff)
               else ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
    }

    /** Ouvre ou ferme le passage I2C vers le tuner. */
    private fun i2cRepeater(on: Boolean) {
        demodWriteReg(1, 0x01, if (on) 0x18 else 0x10, 1)
    }

    // ------------------------------------------------------------- tuner

    /** Table d'inversion de bits : le R820T renvoie ses octets à l'envers. */
    private val bitrevLut = intArrayOf(
        0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)

    private fun bitrev(b: Int): Int =
        (bitrevLut[b and 0x0f] shl 4) or bitrevLut[(b shr 4) and 0x0f]

    /** Image locale des registres du tuner : il ne se relit pas registre par registre. */
    private val shadow = IntArray(32)

    private fun tunerWrite(reg: Int, values: IntArray): Boolean {
        for (i in values.indices) {
            val r = reg + i
            if (r in 5..31) shadow[r] = values[i] and 0xff
        }
        // Le RTL2832U n'accepte pas plus de 8 octets par transaction I2C,
        // index de registre compris.
        var pos = 0
        while (pos < values.size) {
            val size = minOf(7, values.size - pos)
            val data = ByteArray(size + 1)
            data[0] = (reg + pos).toByte()
            for (i in 0 until size) data[i + 1] = values[pos + i].toByte()
            if (!writeArray(BLOCK_I2C, R820T_I2C, data)) return false
            pos += size
        }
        return true
    }

    private fun tunerWriteReg(reg: Int, value: Int): Boolean = tunerWrite(reg, intArrayOf(value))

    /** Écriture partielle : seuls les bits de [mask] sont remplacés. */
    private fun tunerWriteMask(reg: Int, value: Int, mask: Int): Boolean {
        val cur = if (reg in 5..31) shadow[reg] else 0
        val v = (cur and mask.inv()) or (value and mask)
        return tunerWriteReg(reg, v)
    }

    /**
     * Lecture du tuner. Le R820T ne sait pas lire à une adresse arbitraire :
     * il recrache toujours depuis le registre 0, et à l'envers bit à bit.
     */
    private fun tunerRead(len: Int): IntArray? {
        val raw = readArray(BLOCK_I2C, R820T_I2C, len) ?: return null
        return IntArray(len) { bitrev(raw[it].toInt() and 0xff) }
    }

    /** Séquence d'initialisation du R820T (registres 0x05 à 0x1f). */
    private val r820tInit = intArrayOf(
        0x83, 0x32, 0x75,
        0xc0, 0x40, 0xd6, 0x6c,
        0xf5, 0x63, 0x75, 0x68,
        0x6c, 0x83, 0x80, 0x00,
        0x0f, 0x00, 0xc0, 0x30,
        0x48, 0xcc, 0x60, 0x00,
        0x54, 0xae, 0x4a, 0xc0
    )

    private fun tunerInit(): Boolean {
        i2cRepeater(true)
        try {
            if (!tunerWrite(0x05, r820tInit)) return false

            // Étalonnage du filtre FI. On force le VGA à zéro, on lance le
            // calibrage à 56 MHz, puis on relit le code obtenu. L'échec n'est
            // pas fatal : le filtre garde alors sa valeur par défaut, large,
            // ce qui convient parfaitement à une réception FM étroite.
            tunerWriteMask(0x0c, 0x00, 0x0f)   // VGA = 0
            tunerWriteMask(0x13, 49, 0x3f)     // version
            tunerWriteMask(0x1d, 0x00, 0x38)   // LT gain test
            tunerWriteMask(0x0f, 0x04, 0x04)   // horloge de calibrage
            setTunerPll(56_000_000L)
            tunerWriteMask(0x0b, 0x10, 0x10)   // déclenchement
            Thread.sleep(2)
            tunerWriteMask(0x0b, 0x00, 0x10)
            tunerWriteMask(0x0f, 0x00, 0x04)   // horloge de calibrage coupée
            val cal = tunerRead(5)
            val code = if (cal != null) cal[4] and 0x0f else 0
            // 0x0f signale un calibrage raté : on retombe sur la valeur neutre.
            val filtCode = if (code == 0x0f) 0 else code
            tunerWriteMask(0x0a, 0x10 or filtCode, 0x1f)  // filtre passe-bande
            tunerWriteMask(0x0b, 0x6b, 0xef)              // coin haut, 1,0 MHz

            tunerWriteMask(0x07, 0x00, 0x80)   // pas de filtre image
            tunerWriteMask(0x06, 0x10, 0x30)   // +3 dB, 6 MHz
            tunerWriteMask(0x1e, 0x60, 0x60)   // extension à gain LNA max-1
            tunerWriteMask(0x05, 0x00, 0x80)   // loop-through actif
            tunerWriteMask(0x1f, 0x00, 0x80)
            tunerWriteMask(0x0f, 0x00, 0x80)   // filtre non élargi
            tunerWriteMask(0x19, 0x60, 0x60)   // courant polyphase minimal
            return true
        } finally {
            i2cRepeater(false)
        }
    }

    /** Programme la PLL du tuner sur [loHz] et renvoie la fréquence obtenue. */
    private fun setTunerPll(loHz: Long): Long {
        tunerWriteMask(0x10, 0x00, 0x10)   // refdiv = 1
        tunerWriteMask(0x1a, 0x00, 0x0c)   // autotune 128 kHz
        tunerWriteMask(0x12, 0x80, 0xe0)   // courant du VCO

        val probe = tunerRead(5)
        val fine = if (probe != null) (probe[4] and 0x30) shr 4 else 2
        val plan = RtlTuning.pllPlan(loHz, RtlTuning.XTAL, fine)
        if (!plan.lockable) { lastError = "PLL hors plage ($loHz Hz)"; return 0L }

        tunerWriteMask(0x10, (plan.divNum shl 5) and 0xe0, 0xe0)
        tunerWriteReg(0x14, plan.ni + (plan.si shl 6))
        tunerWriteMask(0x12, if (plan.sdmOff) 0x08 else 0x00, 0x08)
        tunerWriteReg(0x16, (plan.sdm shr 8) and 0xff)
        tunerWriteReg(0x15, plan.sdm and 0xff)

        // Deux tentatives d'accrochage : si la première échoue, on remonte le
        // courant du VCO, ce qui débloque la plupart des clés bon marché.
        var locked = false
        for (i in 0 until 2) {
            val st = tunerRead(3)
            if (st != null && (st[2] and 0x40) != 0) { locked = true; break }
            if (i == 0) tunerWriteMask(0x12, 0x60, 0xe0)
        }
        if (!locked) lastError = "PLL non verrouillée"
        tunerWriteMask(0x1a, 0x08, 0x08)   // autotune 8 kHz
        return if (locked) plan.achievedHz else 0L
    }

    /** Filtre d'accord d'entrée pour l'oscillateur local demandé. */
    private fun setTunerMux(loHz: Long) {
        val r = RtlTuning.muxRange(loHz)
        tunerWriteMask(0x17, r.openD, 0x08)
        tunerWriteMask(0x1a, r.rfMuxPoly, 0xc3)
        tunerWriteReg(0x1b, r.tfC)
        tunerWriteMask(0x10, r.xtalCap0p or 0x08, 0x0b)
        tunerWriteMask(0x08, 0x00, 0x3f)
        tunerWriteMask(0x09, 0x00, 0x3f)
    }

    // -------------------------------------------------------------- ouverture

    /** Ouvre la clé et l'amène en mode SDR. */
    fun open(dev: UsbDevice): Boolean {
        lastError = null
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(dev)) { lastError = "permission USB refusée"; return false }
        val c = um.openDevice(dev) ?: run { lastError = "ouverture USB impossible"; return false }
        val itf = dev.getInterface(0)
        if (!c.claimInterface(itf, true)) {
            c.close(); lastError = "interface USB déjà prise"; return false
        }
        var ep: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val e = itf.getEndpoint(i)
            if (e.direction == UsbConstants.USB_DIR_IN &&
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { ep = e; break }
        }
        if (ep == null) {
            c.releaseInterface(itf); c.close(); lastError = "endpoint bulk absent"; return false
        }
        conn = c; iface = itf; epIn = ep; device = dev

        if (!initBaseband()) { close(); lastError = lastError ?: "init du démodulateur"; return false }
        if (!tunerInit()) { close(); lastError = "tuner R820T muet"; return false }
        configureForR82xx()
        return true
    }

    fun close() {
        val c = conn
        if (c != null) {
            stopStream()
            runCatching {
                // Coupe l'endpoint puis endort le démodulateur : sans cela la
                // clé reste chaude et refuse parfois la réouverture.
                writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
                i2cRepeater(true)
                tunerWriteReg(0x06, 0xb1)   // tuner en veille
                i2cRepeater(false)
                demodWriteReg(0, 0x0c, 0x00, 1)
                writeReg(BLOCK_SYS, DEMOD_CTL, 0x20, 1)
            }
            runCatching { iface?.let { c.releaseInterface(it) } }
            runCatching { c.close() }
        }
        conn = null; iface = null; epIn = null; device = null
    }

    /** Réveil du RTL2832U et mise en mode SDR (décodeur DVB-T contourné). */
    private fun initBaseband(): Boolean {
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)

        writeReg(BLOCK_SYS, DEMOD_CTL_1, 0x22, 1)
        writeReg(BLOCK_SYS, DEMOD_CTL, 0xe8, 1)

        demodWriteReg(1, 0x01, 0x14, 1)   // reset logiciel
        demodWriteReg(1, 0x01, 0x10, 1)

        demodWriteReg(1, 0x15, 0x00, 1)   // pas d'inversion de spectre
        demodWriteReg(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1)

        val fir = RtlTuning.packFir()
        for (i in fir.indices) demodWriteReg(1, 0x1c + i, fir[i].toInt() and 0xff, 1)

        demodWriteReg(0, 0x19, 0x05, 1)   // mode SDR, AGC numérique coupée
        demodWriteReg(1, 0x93, 0xf0, 1)
        demodWriteReg(1, 0x94, 0x0f, 1)
        demodWriteReg(1, 0x11, 0x00, 1)
        demodWriteReg(1, 0x04, 0x00, 1)   // pas de boucle AGC RF/FI
        demodWriteReg(0, 0x61, 0x60, 1)   // filtre PID désactivé
        demodWriteReg(0, 0x06, 0x80, 1)   // chemin ADC I/Q par défaut
        demodWriteReg(1, 0xb1, 0x1b, 1)   // zéro-FI, correction DC et IQ
        demodWriteReg(0, 0x0d, 0x83, 1)   // pas d'horloge 4,096 MHz sur TP_CK0
        return true
    }

    /**
     * Configuration propre au R820T : la clé ne travaille pas en zéro-FI mais
     * à 3,57 MHz, avec une seule voie du convertisseur, et c'est le DDC du
     * RTL2832U qui redescend le tout en bande de base — d'où l'inversion de
     * spectre à réactiver.
     */
    private fun configureForR82xx() {
        demodWriteReg(1, 0xb1, 0x1a, 1)   // zéro-FI désactivé
        demodWriteReg(0, 0x08, 0x4d, 1)   // seule l'entrée I du convertisseur
        val ifr = RtlTuning.ifFreqRegs(RtlTuning.IF_FREQ)
        demodWriteReg(1, 0x19, ifr[0], 1)
        demodWriteReg(1, 0x1a, ifr[1], 1)
        demodWriteReg(1, 0x1b, ifr[2], 1)
        demodWriteReg(1, 0x15, 0x01, 1)   // inversion de spectre
    }

    // -------------------------------------------------------------- réglages

    /** Débit d'échantillonnage ; renvoie le débit réel, ou 0 si refusé. */
    fun setSampleRate(rate: Int): Double {
        if (!RtlTuning.rateSupported(rate)) { lastError = "débit non supporté"; return 0.0 }
        val ratio = RtlTuning.resampRatio(rate)
        demodWriteReg(1, 0x9f, (ratio shr 16) and 0xffff, 2)
        demodWriteReg(1, 0xa1, ratio and 0xffff, 2)
        demodWriteReg(1, 0x01, 0x14, 1)
        demodWriteReg(1, 0x01, 0x10, 1)
        actualSampleRate = RtlTuning.actualRate(rate)
        return actualSampleRate
    }

    /** Correction d'horloge en parties par million. */
    fun setFreqCorrection(ppm: Int) {
        val r = RtlTuning.freqCorrectionRegs(ppm)
        demodWriteReg(1, 0x3f, r[0], 1)
        demodWriteReg(1, 0x3e, r[1], 1)
    }

    /** Fréquence centrale ; renvoie la fréquence réellement obtenue, ou 0. */
    fun setCenterFreq(hz: Long): Long {
        if (conn == null) return 0L
        val lo = hz + RtlTuning.IF_FREQ
        i2cRepeater(true)
        try {
            setTunerMux(lo)
            val achieved = setTunerPll(lo)
            if (achieved == 0L) return 0L
            tunedHz = achieved - RtlTuning.IF_FREQ
            return tunedHz
        } finally {
            i2cRepeater(false)
        }
    }

    /** Gain du tuner en dixièmes de dB, ou null pour le mode automatique. */
    fun setGain(tenthDb: Int?) {
        i2cRepeater(true)
        try {
            if (tenthDb == null) {
                tunerWriteMask(0x05, 0x00, 0x10)   // LNA automatique
                tunerWriteMask(0x07, 0x10, 0x10)   // mélangeur automatique
                tunerWriteMask(0x0c, 0x0b, 0x9f)   // VGA fixe 26,5 dB
            } else {
                tunerWriteMask(0x05, 0x10, 0x10)   // LNA manuel
                tunerWriteMask(0x07, 0x00, 0x10)   // mélangeur manuel
                tunerWriteMask(0x0c, 0x08, 0x9f)   // VGA fixe 16,3 dB
                val (lna, mix) = RtlTuning.gainSplit(tenthDb)
                tunerWriteMask(0x05, lna, 0x0f)
                tunerWriteMask(0x07, mix, 0x0f)
            }
        } finally {
            i2cRepeater(false)
        }
    }

    /** AGC numérique du RTL2832U (indépendante du gain du tuner). */
    fun setAgc(on: Boolean) {
        demodWriteReg(0, 0x19, if (on) 0x25 else 0x05, 1)
    }

    // ------------------------------------------------------------- flux IQ

    /** Vide les tampons USB avant de commencer à lire. */
    fun resetBuffer() {
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
    }

    /**
     * Lit un paquet d'IQ en synchrone. Renvoie le nombre d'octets, 0 sur
     * expiration, -1 si le lien est mort. Les échantillons sont des entiers
     * 8 bits non signés, I puis Q, centrés sur 127,5.
     *
     * Chemin de secours : entre deux appels la clé n'est lue par personne et
     * ses tampons débordent. Utilisable pour vérifier qu'une clé répond, pas
     * pour décoder du SSTV. Le chemin normal est [startStream]/[readStream].
     */
    fun read(buf: ByteArray, timeoutMs: Int = 1000): Int {
        val c = conn ?: return -1
        val e = epIn ?: return -1
        return c.bulkTransfer(e, buf, minOf(buf.size, XFER), timeoutMs)
    }

    // --------------------------------------------------- flux IQ asynchrone

    /** Requêtes en vol. Toujours manipulées depuis le fil de lecture. */
    private val inflight = ArrayList<UsbRequest>()

    /**
     * Met [depth] transferts en file d'attente auprès du noyau.
     *
     * Le principe : pendant qu'on démodule le bloc qui vient d'arriver, les
     * autres requêtes continuent de se remplir. Le flux ne s'interrompt donc
     * jamais, ce qui est indispensable pour le SSTV — une image se décode sur
     * deux minutes de son continu, un trou de quelques millisecondes décale
     * toutes les lignes suivantes.
     *
     * Huit tampons de 16 ko font 128 ko, soit 60 ms de marge : largement de
     * quoi encaisser un coup de charge du téléphone.
     */
    fun startStream(depth: Int = 8): Boolean {
        stopStream()
        val c = conn ?: return false
        val e = epIn ?: return false
        for (i in 0 until depth) {
            val r = UsbRequest()
            if (!r.initialize(c, e)) { runCatching { r.close() }; break }
            val b = ByteBuffer.allocateDirect(XFER)
            r.clientData = b
            b.clear()
            if (!r.queue(b)) { runCatching { r.close() }; break }
            inflight += r
        }
        if (inflight.isEmpty()) lastError = "file USB refusée"
        return inflight.isNotEmpty()
    }

    /**
     * Attend le prochain transfert terminé, recopie les octets et remet
     * aussitôt la requête en file. Renvoie le nombre d'octets lus, 0 si rien
     * n'est arrivé dans le délai, -1 si le lien est mort.
     */
    fun readStream(out: ByteArray, timeoutMs: Long): Int {
        val c = conn ?: return -1
        if (inflight.isEmpty()) return -1
        val r = try {
            c.requestWait(timeoutMs)
        } catch (_: java.util.concurrent.TimeoutException) {
            return 0
        } catch (_: Exception) {
            return -1
        } ?: return -1
        val b = r.clientData as? ByteBuffer ?: return -1
        // Après requestWait, la position du tampon est le nombre d'octets reçus.
        val n = minOf(b.position(), out.size)
        if (n > 0) {
            b.flip()
            b.get(out, 0, n)
        }
        b.clear()
        if (!r.queue(b)) return -1
        return n
    }

    /** Annule les transferts en vol. À appeler avant de fermer la connexion. */
    fun stopStream() {
        for (r in inflight) {
            runCatching { r.cancel() }
            runCatching { r.close() }
        }
        inflight.clear()
    }
}
