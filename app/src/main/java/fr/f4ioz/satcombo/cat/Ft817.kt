/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.cat

import android.content.Context
import android.hardware.usb.UsbManager
import fr.f4ioz.satcombo.usb.UsbPermission
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * La clé d'un périphérique : son numéro de série s'il en a un, sinon son couple
 * constructeur/produit et sa position dans l'arbre USB.
 *
 * Au niveau du fichier parce que les deux classes s'en servent — le poste seul
 * pour ouvrir, le couple pour lister et pour sonder — et qu'une clé calculée de
 * deux façons différentes serait une clé qui ne désigne rien.
 */
internal fun cleDe(dev: android.hardware.usb.UsbDevice): String =
    IdentiteUsb.cle(
        runCatching { dev.serialNumber }.getOrNull(),
        dev.vendorId, dev.productId, dev.deviceName)

/** A USB-serial adapter as shown in the RX/TX assignment UI. */
data class UsbSerialInfo(
    val serial: String?,        // numéro de série USB, absent sur beaucoup de puces
    val label: String,          // product name or device path
    val deviceName: String,     // /dev/bus/usb/…
    val hasPermission: Boolean,
    /**
     * La clé qui désigne cet adaptateur, numéro de série ou identité de repli.
     *
     * C'est elle qu'on mémorise désormais, et non le numéro de série : un
     * PL2303TA n'en a pas, et l'ouverture échouait avant même d'essayer.
     */
    val cle: String = serial.orEmpty(),
    /** Fréquence lue au bout de ce câble, si un poste a répondu. */
    val freqLueHz: Long? = null,
    /** L'adaptateur a-t-il déjà été interrogé ? Distingue « pas encore » de « muet ». */
    val sonde: Boolean = false,
)

/**
 * Yaesu FT-817/818 CAT over one USB-serial adapter.
 *
 * Protocol: 5-byte frames — 4 parameter bytes then 1 opcode. CAT is always on.
 * Serial settings are 8 data bits, NO parity, TWO stop bits (8N2!), at the rate
 * set in the rig's menu #14 (4800 default, 9600 or 38400).
 *
 *  - set frequency : 4 BCD bytes (8 digits, unit 10 Hz) + 0x01
 *  - read freq+mode: 00 00 00 00 0x03 → 5 bytes back (4 BCD + mode)
 *  - set mode      : <mode> 00 00 00 + 0x07
 *  - CTCSS/DCS mode: <0x8A off | 0x4A encoder | 0x2A enc+dec> 00 00 00 + 0x0A
 *  - CTCSS tone    : 2 BCD bytes (tenths of Hz, e.g. 06 70 = 67.0) + 0x0B
 *  - read TX status: 00 00 00 00 0xF7 → 1 byte, bit7 SET while receiving
 *
 * Comme le pilote CI-V, celui-ci ne connaît plus de port USB : il parle à un
 * [SerialLink], que [attach] peut remplir avec un [Ft817Sim].
 */
class Ft817Cat(private val context: Context? = null) {

    private var link: SerialLink? = null
    var boundSerial: String? = null
        private set
    val isOpen: Boolean get() = link != null

    /**
     * Le fil ne porte qu'une conversation à la fois.
     *
     * Le protocole Yaesu n'a **ni délimiteur ni adresse** : une réponse est
     * une suite d'octets qu'on reconnaît uniquement en les comptant. Deux
     * questions posées en même temps sur le même fil rendent donc deux
     * réponses indiscernables, et chacune ramasse celle de l'autre.
     *
     * Or c'est exactement ce qui se passait : la boucle Doppler écrit les
     * fréquences pendant que le sondage d'émission demande l'état deux fois
     * par seconde. Le `drain` du sondage jetait les octets attendus par le
     * Doppler ; l'acquittement du Doppler était lu par le sondage à la place
     * de son octet d'état. D'où un liseré qui s'allume au hasard — et une
     * fréquence relue au dixième de sa valeur, plausible et fausse.
     *
     * Le symptôme semblait dépendre du port : il ne dépendait que du hasard
     * des instants, et changer de câble changeait les temps de réponse assez
     * pour déplacer le hasard.
     *
     * Une transaction — question, réponse, acquittement — est donc prise en
     * entier sous ce verrou.
     */
    private val fil = kotlinx.coroutines.sync.Mutex()

    /** Silence entre deux trames : vingt-cinq millisecondes sur un vrai poste. */
    var pacingMs: Long = 25L

    /** Branche n'importe quel fil série — un vrai câble, ou un poste simulé. */
    fun attach(l: SerialLink) { link = l }

    /** Open the adapter whose FTDI serial matches [deviceSerial] at [baud], 8N2. */
    suspend fun open(deviceSerial: String?, baud: Int): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um)
            .filter { um.hasPermission(it.device) }

        // On désigne l'adaptateur par sa clé et non par son numéro de série.
        // L'ancienne écriture exigeait `deviceSerial != null` : un câble sans
        // numéro — un PL2303TA, par exemple — ne pouvait jamais satisfaire
        // cette condition, et n'était donc jamais ouvert, alors que le pilote
        // le reconnaissait parfaitement.
        val cles = drivers.map { cleDe(it.device) }
        val choisie = IdentiteUsb.resout(deviceSerial, cles) ?: return@withContext false
        val driver = drivers.getOrNull(cles.indexOf(choisie)) ?: return@withContext false
        val conn = um.openDevice(driver.device) ?: return@withContext false
        val p = driver.ports.firstOrNull() ?: return@withContext false
        runCatching {
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_2, UsbSerialPort.PARITY_NONE)
            link = UsbSerialLink(p)
            boundSerial = choisie
        }.isSuccess
    }

    fun close() {
        runCatching { link?.close() }
        link = null; boundSerial = null
    }

    private fun frameOf(p1: Int, p2: Int, p3: Int, p4: Int, op: Int) =
        byteArrayOf(p1.toByte(), p2.toByte(), p3.toByte(), p4.toByte(), op.toByte())

    /**
     * Un acquittement n'a pas été ramassé : le fil porte encore un octet.
     *
     * Tant que ce drapeau est levé, la question suivante prend le temps de
     * vider le fil pour de bon au lieu d'y jeter un coup d'œil.
     */
    @Volatile private var residu = false

    /**
     * Jette ce qui traîne encore sur le fil avant de poser une question.
     *
     * Le coup d'œil ordinaire ne coûte rien : une milliseconde suffit à voir
     * ce qui est **déjà arrivé**. Mais un octet encore en vol ne s'y montre
     * pas — un adaptateur USB-série garde ses octets jusqu'à seize
     * millisecondes avant de les remonter. D'où [patient], employé quand on
     * sait qu'un acquittement manque à l'appel.
     */
    private fun drain(l: SerialLink, patient: Boolean = false) {
        val scratch = ByteArray(16)
        var guard = 0
        val attente = if (patient) 60 else 1
        while (guard++ < 8) {
            if (runCatching { l.read(scratch, attente) }.getOrDefault(0) <= 0) break
        }
        residu = false
    }

    /**
     * Ramasse l'octet d'acquittement qui suit toute commande d'écriture.
     *
     * Le FT-817 répond `00` à chaque ordre. Ne pas le lire laissait cet octet
     * sur le fil, et la lecture suivante commençait donc un octet trop tôt :
     * la fréquence revenait au dixième, au centième de sa valeur — un nombre
     * parfaitement plausible, jamais signalé, et faux.
     */
    private fun eatAck(l: SerialLink) {
        val one = ByteArray(1)
        // Le délai est un plafond, pas un coût : la lecture rend la main dès
        // que l'octet arrive. L'ancien plafond de soixante millisecondes — et
        // d'**une** seule quand le rythme était nul — était parfois trop court,
        // et l'acquittement restait alors sur le fil.
        //
        // C'est de là que venait le liseré qui clignote quand on tourne la
        // molette : l'application écrit beaucoup pendant qu'on cherche, un
        // acquittement `00` est laissé derrière, et la lecture d'état
        // d'émission qui suit le ramasse à la place de son octet. Or `00` a le
        // bit de poids fort à zéro, c'est-à-dire, pour le FT-817, « en
        // émission ». Le poste ne mentait pas : on ne lisait pas sa réponse.
        val n = runCatching { l.read(one, if (pacingMs > 0) 150 else 5) }.getOrDefault(0)
        if (n > 0) {
            CatJournal.log(false, one, CatDecode.describeYaesu(one, fromRig = true))
            residu = false
        } else residu = true
    }

    private suspend fun cmd(p1: Int, p2: Int, p3: Int, p4: Int, op: Int): Boolean =
        withContext(Dispatchers.IO) { fil.withLock {
            val l = link ?: return@withLock false
            val f = frameOf(p1, p2, p3, p4, op)
            val ok = l.write(f, 500)
            CatJournal.log(true, f, CatDecode.describeYaesu(f, fromRig = false))
            if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)   // demi-duplex
            eatAck(l)
            ok
        } }

    /**
     * Émet une question et rassemble exactement [want] octets de réponse.
     *
     * Le protocole Yaesu n'ayant pas de délimiteur, il n'y a rien à
     * reconnaître : il faut compter. À 4800 bauds, cinq octets prennent une
     * dizaine de millisecondes et arrivent rarement d'un seul coup.
     */
    private fun ask(l: SerialLink, f: ByteArray, want: Int, timeoutMs: Long): ByteArray? {
        // Ce qui traîne encore appartient à la commande précédente.
        drain(l, patient = residu)
        if (!l.write(f, 500)) return null
        CatJournal.log(true, f, CatDecode.describeYaesu(f, fromRig = false))
        val acc = ByteArray(want)
        val scratch = ByteArray(16)
        var got = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (got < want) {
            val n = runCatching { l.read(scratch, 200) }.getOrDefault(0)
            if (n > 0) {
                val take = minOf(n, want - got)
                System.arraycopy(scratch, 0, acc, got, take)
                got += take
            } else if (pacingMs == 0L) break
            if (System.currentTimeMillis() >= deadline) break
        }
        if (got < want) return null
        CatJournal.log(false, acc,
            CatDecode.describeYaesu(acc, fromRig = true, lastOp = f[4].toInt() and 0xFF))
        return acc
    }

    /** Set frequency (Hz). FT-817 resolution is 10 Hz, 8 BCD digits big-endian. */
    suspend fun setFrequency(hz: Long): Boolean {
        val b = CatDecode.yaesuFreq(hz)
        return cmd(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
            b[2].toInt() and 0xFF, b[3].toInt() and 0xFF, 0x01)
    }

    /** Read frequency (Hz) + mode byte, or null. Accumulates the 5-byte reply
     *  across partial USB reads (slow 4800 baud link). */
    suspend fun readFrequencyAndMode(): Pair<Long, Int>? = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock null
        val acc = ask(l, frameOf(0, 0, 0, 0, 0x03), 5, 600) ?: return@withLock null
        val hz = CatDecode.yaesuFreqOf(acc) ?: return@withLock null
        hz to (acc[4].toInt() and 0xFF)
    } }

    suspend fun readFrequency(): Long? = readFrequencyAndMode()?.first

    /** Set operating mode: LSB/USB/CW/CWR/AM/FM/DIG/PKT. */
    suspend fun setMode(mode: String): Boolean = cmd(modeByte(mode), 0, 0, 0, 0x07)

    /**
     * Ton d'accès en émission, en dixièmes de hertz (670 = 67,0 Hz), zéro pour
     * le couper.
     *
     * Ici l'encodage était déjà juste — deux octets BCD gros-boutiens, 88,5 Hz
     * donnant `08 85`. C'est le pilote Icom qui appliquait à tort la règle
     * petit-boutienne de la fréquence ; la comparaison des deux dialectes rend
     * l'erreur évidente, ce qui est un argument de plus pour les avoir mis côte
     * à côte.
     */
    suspend fun setCtcss(tenthHz: Int): Boolean {
        // Les deux trames du ton forment un ordre unique : les séparer
        // laisserait une écriture de fréquence s'intercaler entre le mode de
        // ton et sa valeur.
        return if (tenthHz > 0) {
            if (!CatDecode.toneInRange(tenthHz)) return false
            val b = CatDecode.toneToBcdBe(tenthHz)   // 00 <hh> <ll>
            cmd(0x4A, 0, 0, 0, 0x0A) &&
                cmd(b[1].toInt() and 0xFF, b[2].toInt() and 0xFF, 0, 0, 0x0B)
        } else cmd(0x8A, 0, 0, 0, 0x0A)
    }

    /** True while the rig is TRANSMITTING (PTT down), false while receiving,
     *  null if unknown. Bit 7 of the 0xF7 status byte is SET during RX. */
    /**
     * Le dernier octet d'état rendu par le poste, ou `null` s'il n'a rien dit.
     *
     * Uniquement pour la ligne de diagnostic. Le liseré a coûté plusieurs
     * versions parce qu'on raisonnait sur une conclusion — « émission » ou
     * « réception » — sans jamais voir ce que le poste avait réellement
     * répondu. Trois causes possibles demandaient trois correctifs opposés, et
     * un seul octet les départage.
     */
    @Volatile var dernierEtatTx: Int? = null
        private set

    suspend fun isTransmitting(): Boolean? = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock null
        val acc = ask(l, frameOf(0, 0, 0, 0, 0xF7), 1, 300)
        if (acc == null) { dernierEtatTx = null; return@withLock null }
        val octet = acc[0].toInt() and 0xFF
        dernierEtatTx = octet
        // Bit de poids fort à zéro : le FT-817 déclare l'émission. Au repos il
        // rend 0xFF.
        (octet and 0x80) == 0
    } }

    private fun modeByte(m: String): Int = when (m.uppercase()) {
        "LSB" -> 0x00; "USB" -> 0x01; "CW" -> 0x02; "CWR" -> 0x03
        "AM" -> 0x04; "FM" -> 0x08; "DIG" -> 0x0A; "PKT" -> 0x0C
        else -> 0x01
    }
}

/**
 * The classic portable full-duplex satellite station: TWO FT-817s, one fixed on
 * RX (downlink) and one on TX (uplink), each on its own FTDI cable. Assignment
 * is remembered by the FTDI chips' unique serial numbers, so it survives
 * replugging and hub-port changes.
 */
class Ft817Pair(private val context: Context? = null) {

    val rx = Ft817Cat(context)
    val tx = Ft817Cat(context)
    val isOpen: Boolean get() = rx.isOpen || tx.isOpen
    val bothOpen: Boolean get() = rx.isOpen && tx.isOpen

    /** Branche deux postes simulés — le couple entier tient alors au banc. */
    fun attach(rxLink: SerialLink, txLink: SerialLink) {
        rx.attach(rxLink); tx.attach(txLink)
    }

    /** Silence entre trames, appliqué aux deux postes à la fois. */
    var pacingMs: Long
        get() = rx.pacingMs
        set(v) { rx.pacingMs = v; tx.pacingMs = v }

    companion object {
        private const val ACTION_USB_PERMISSION = UsbPermission.ACTION_CAT
    }

    /** All recognized USB-serial adapters, with FTDI serial when readable. */
    fun listDevices(): List<UsbSerialInfo> {
        val ctx = context ?: return emptyList()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(um).map { d ->
            val has = um.hasPermission(d.device)
            val serie = if (has) runCatching { d.device.serialNumber }.getOrNull() else null
            UsbSerialInfo(
                serial = serie,
                label = d.device.productName ?: "USB serial",
                deviceName = d.device.deviceName,
                hasPermission = has,
                cle = cleDe(d.device)
            )
        }
    }

    /**
     * Interroge un adaptateur : y a-t-il un poste au bout, et sur quelle
     * fréquence ?
     *
     * C'est la réponse au cas du duplex, où deux câbles identiques sans numéro
     * de série sont indiscernables par leur étiquette. Les deux postes, eux, ne
     * sont pas sur la même bande — l'un sur la descente, l'autre sur la montée —
     * et leur propre réponse dit lequel est lequel.
     */
    /**
     * Interroge un adaptateur : y a-t-il un poste au bout, et sur quelle
     * fréquence ?
     *
     * C'est la réponse au cas du duplex, où deux câbles identiques sans numéro
     * de série sont indiscernables par leur étiquette. Les deux postes, eux, ne
     * sont pas sur la même bande — l'un sur la descente, l'autre sur la montée —
     * et leur propre réponse dit lequel est lequel.
     *
     * On passe par un poste seul plutôt que de réécrire la trame et le
     * décodage : `open` et `readFrequency` sont déjà éprouvés, et deux
     * écritures du même protocole finiraient par diverger.
     */
    suspend fun sonde(cle: String, baud: Int): Long? {
        val poste = Ft817Cat(context)
        if (!poste.open(cle, baud)) return null
        val hz = runCatching { poste.readFrequency() }.getOrNull()
        poste.close()
        return hz?.takeIf { IdentiteUsb.freqPlausible(it) }
    }

    /** Ask permission for every recognized adapter that lacks it. */
    fun requestPermissions() {
        val ctx = context ?: return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbSerialProber.getDefaultProber().findAllDrivers(um).forEach { d ->
            // Un code de requête par adaptateur : le couple en demande deux à la
            // suite, et deux PendingIntent de même code n'en feraient qu'un.
            UsbPermission.ensure(ctx, um, d.device, ACTION_USB_PERMISSION, d.device.deviceId)
        }
    }

    /** Open both rigs by their remembered serials. Returns rxOk to txOk. */
    suspend fun open(rxSerial: String?, txSerial: String?, baud: Int): Pair<Boolean, Boolean> {
        val a = if (!rxSerial.isNullOrBlank()) rx.open(rxSerial, baud) else false
        val b = if (!txSerial.isNullOrBlank()) tx.open(txSerial, baud) else false
        return a to b
    }

    fun close() { rx.close(); tx.close() }

    /** Doppler pair: downlink to the RX rig, uplink to the TX rig. The TX write
     *  is skipped while that rig is actually transmitting (half-duplex safety —
     *  same behaviour as SatPC32). */
    suspend fun setPair(downlinkHz: Long, uplinkHz: Long) {
        if (rx.isOpen) rx.setFrequency(downlinkHz)
        if (tx.isOpen && tx.isTransmitting() != true) tx.setFrequency(uplinkHz)
    }

    suspend fun setUplink(uplinkHz: Long) {
        if (tx.isOpen && tx.isTransmitting() != true) tx.setFrequency(uplinkHz)
    }

    suspend fun readDownlink(): Long? = if (rx.isOpen) rx.readFrequency() else null

    /**
     * Relit le VFO du poste d'émission.
     *
     * Symétrique de [readDownlink], et absente jusqu'ici : l'émission était
     * écrite sans jamais être relue. C'est ce qui interdisait de se servir de sa
     * molette comme d'une commande — on ne peut pas tenir compte d'un geste
     * qu'on ne voit pas.
     *
     * On ne lit pas en émission : le poste répondrait mal, et l'on n'a de toute
     * façon rien à corriger pendant qu'on parle.
     */
    suspend fun readUplink(): Long? =
        if (tx.isOpen && tx.isTransmitting() != true) tx.readFrequency() else null

    suspend fun setModes(downlink: String, uplink: String) {
        if (rx.isOpen) rx.setMode(downlink)
        if (tx.isOpen) tx.setMode(uplink)
    }

    /** CTCSS on the TX rig only (the uplink carries the tone). */
    suspend fun setCtcss(tenthHz: Int) { if (tx.isOpen) tx.setCtcss(tenthHz) }

    /** Human-readable link test of both rigs. */
    suspend fun testLink(): String {
        val r = if (!rx.isOpen) "—" else rx.readFrequency()?.let { "%.5f MHz".format(it / 1e6) } ?: "?"
        val t = if (!tx.isOpen) "—" else tx.readFrequency()?.let { "%.5f MHz".format(it / 1e6) } ?: "?"
        return "RX: $r · TX: $t"
    }
}
