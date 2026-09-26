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
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Le pilote CI-V de l'IC-9700, derrière un [SerialLink].
 *
 * Trame CI-V : `FE FE <poste> <pupitre> <commande> [données…] FD`, le poste à
 * 0xA2 par défaut, le pupitre à 0xE0 par convention. La fréquence tient en cinq
 * octets BCD petit-boutiens ; le ton d'accès, lui, est **gros-boutien**, et
 * cette seule différence a coûté des passages entiers sur SO-50.
 *
 * Le pilote ne connaît plus de port USB : il parle à un [SerialLink], que
 * [open] remplit avec le vrai câble et que [attach] remplit, au banc, avec un
 * [Ic9700Sim]. C'est ce qui rend le reste de ce fichier vérifiable.
 */
class CivController(private val context: Context? = null) : RigDriver {

    override val name: String = "Icom IC-9700 (CI-V)"
    private var link: SerialLink? = null

    /**
     * Le fil ne porte qu'une conversation à la fois.
     *
     * Le CI-V délimite ses trames et porte des adresses, ce qui le rend moins
     * fragile que le Yaesu — mais pas invulnérable : le `drain` d'une question
     * jette ce qui traîne, et ce qui traîne peut être la réponse qu'une autre
     * coroutine attend. La boucle Doppler écrit pendant que le sondage
     * d'émission interroge deux fois par seconde ; sans verrou, l'une ramasse
     * la réponse de l'autre.
     */
    private val fil = kotlinx.coroutines.sync.Mutex()
    var radioAddr: Int = 0xA2
    var controllerAddr: Int = 0xE0

    /**
     * Silence imposé entre deux trames. Quarante millisecondes sur un vrai bus
     * CI-V, sans quoi le poste en laisse tomber ; zéro au banc, où il n'y a
     * personne à ménager et où l'attente ne ferait qu'allonger les essais.
     */
    var pacingMs: Long = 40L

    override val isOpen: Boolean get() = link != null

    companion object {
        private const val ACTION_USB_PERMISSION = UsbPermission.ACTION_CAT
    }

    /** Branche n'importe quel fil série — un vrai câble, ou un poste simulé. */
    fun attach(l: SerialLink) { link = l }

    /**
     * La raison du dernier echec, en clair, pour l'ecran de diagnostic.
     *
     * « J'ai vraiment du mal a connecter » : jusqu'ici l'application repondait
     * « ouverture impossible » et se taisait. Permission refusee, appareil deja
     * pris par une autre application, port inexistant, poste muet : quatre
     * causes, un seul message. On les distingue maintenant.
     */
    var lastError: String = ""
        private set

    /**
     * Tous les ports serie visibles, appareil par appareil, mis a plat.
     *
     * Un IC-9700 branche seul donne donc deux entrees, pas une.
     */
    fun availablePorts(): List<PortRef> {
        val ctx = context ?: return emptyList()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val out = ArrayList<PortRef>()
        UsbSerialProber.getDefaultProber().findAllDrivers(um).forEachIndexed { d, drv ->
            val n = drv.ports.size.coerceAtLeast(1)
            for (i in 0 until n) {
                out.add(PortRef(d, i, CatScan.etiquette(
                    drv.device.productName, drv.device.deviceName, i, n)))
            }
        }
        return out
    }

    /** Les etiquettes des ports, dans l'ordre ou on peut les choisir. */
    fun availableDeviceNames(): List<String> = availablePorts().map { it.label }

    /** Request permission for the first recognized device (async; user prompt). */
    fun requestPermission() {
        val ctx = context ?: return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull() ?: return
        UsbPermission.ensure(ctx, um, driver.device, ACTION_USB_PERMISSION)
    }

    /**
     * Demande la permission pour l'adaptateur n° [index] **et attend la
     * réponse** de l'utilisateur.
     *
     * C'est la moitié qui manquait à la connexion CAT : l'ancienne suite
     * affichait la boîte de dialogue puis ouvrait le port dans la foulée, alors
     * que la permission n'était pas encore accordée. L'ouverture échouait, et
     * seule une seconde tentative — après un débranchement, un détour par le
     * menu SDR, un rebranchement — finissait par marcher.
     */
    suspend fun ensurePermission(index: Int = 0): Boolean {
        val ctx = context ?: return false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        // L'index designe un port, pas un appareil : la permission, elle, se
        // demande pour l'appareil qui le porte. Les deux ports de l'IC-9700
        // partagent donc une seule autorisation, et une seule boite de dialogue.
        val ref = availablePorts().getOrNull(index) ?: return false
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um)
            .getOrNull(ref.deviceIndex) ?: return false
        if (um.hasPermission(driver.device)) return true
        return UsbPermission.await(ctx, um, driver.device, ACTION_USB_PERMISSION)
    }

    /** Open the first recognized device at the given baud rate. */
    override suspend fun open(baud: Int): Boolean = open(0, baud)

    /**
     * Ouvre l'adaptateur n° [index], et non plus systématiquement le premier.
     *
     * Le premier reconnu n'est pas forcément le poste : une clé SDR branchée
     * en même temps se présente elle aussi comme un adaptateur série, et selon
     * l'ordre de branchement c'est elle que l'on ouvrait. D'où une connexion
     * qui dépendait de la chorégraphie des câbles. L'index se choisit
     * maintenant dans les paramètres.
     */
    suspend fun open(index: Int, baud: Int): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um)
        val refs = availablePorts()
        val ref = refs.getOrNull(index) ?: refs.firstOrNull()
        if (ref == null) { lastError = t("cat_err_no_device"); return@withContext false }
        val driver = drivers.getOrNull(ref.deviceIndex)
        if (driver == null) { lastError = t("cat_err_no_device"); return@withContext false }
        if (!um.hasPermission(driver.device)) {
            lastError = t("cat_err_denied"); return@withContext false
        }
        val connection = um.openDevice(driver.device)
        if (connection == null) { lastError = t("cat_err_open_device"); return@withContext false }
        val p = driver.ports.getOrNull(ref.portIndex)
        if (p == null) { lastError = t("cat_err_no_port"); return@withContext false }
        runCatching {
            p.open(connection)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Les deux lignes de controle du terminal. Beaucoup de ponts CDC —
            // et celui de l'Icom en fait partie — restent muets tant que l'hote
            // ne s'est pas annonce : le port s'ouvre, l'ecriture passe, et rien
            // ne revient jamais. Deux lignes, et le poste repond.
            runCatching { p.setDTR(true); p.setRTS(true) }
            link = UsbSerialLink(p)
            lastError = ""
            true
        }.getOrElse {
            lastError = tf("cat_err_open_port", it.message ?: "?")
            runCatching { p.close() }
            false
        }
    }

    override fun close() {
        runCatching { link?.close() }
        link = null
        forgetBands()
    }

    /** Encode a frequency (Hz) into 5 little-endian BCD bytes for CI-V cmd 0x05. */
    private fun freqToBcd(hz: Long): ByteArray = CatDecode.freqToBcdLe(hz)

    private fun frame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFE.toByte(), radioAddr.toByte(), controllerAddr.toByte(),
            cmd.toByte()) + data + byteArrayOf(CatDecode.END.toByte())

    /**
     * Rassemble ce qui revient du fil jusqu'à ce que [stop] soit satisfait, ou
     * jusqu'à [timeoutMs].
     *
     * C'est la boucle qui manquait. L'ancienne version faisait une lecture
     * unique de soixante-quatre octets : quand le poste faisait précéder sa
     * réponse d'un accusé de réception — ce qui arrive — la lecture rendait
     * l'accusé, et la réponse tombait dans le vide de la lecture suivante,
     * c'est-à-dire nulle part.
     */
    private fun collect(
        l: SerialLink, timeoutMs: Long, stop: (List<ByteArray>) -> Boolean
    ): List<ByteArray> {
        val acc = ArrayList<Byte>()
        val scratch = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        var frames: List<ByteArray> = emptyList()
        while (true) {
            val n = runCatching { l.read(scratch, 100) }.getOrDefault(0)
            if (n > 0) {
                for (i in 0 until n) acc += scratch[i]
                frames = CatDecode.splitCiv(acc.toByteArray())
                if (stop(frames)) break
            } else if (pacingMs == 0L) {
                // Au banc, rien ne met du temps à arriver : une lecture vide
                // veut dire qu'il n'y a plus rien, et non qu'il faut patienter.
                break
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        frames.forEach { f ->
            CatJournal.log(false, f, if (CatDecode.isEcho(f, radioAddr, controllerAddr))
                "écho du bus : " + CatDecode.describeCiv(f) else CatDecode.describeCiv(f))
        }
        return frames
    }

    /** Jette ce qui traîne encore sur le fil avant de poser une question. */
    private fun drain(l: SerialLink) {
        val scratch = ByteArray(256)
        var guard = 0
        while (guard++ < 8) {
            val n = runCatching { l.read(scratch, 1) }.getOrDefault(0)
            if (n <= 0) break
        }
    }

    /**
     * Émet une trame et attend l'accusé de réception.
     *
     * Rend faux quand le poste refuse : jusqu'ici, un refus était indiscernable
     * d'un succès, et c'est précisément par là que le ton d'accès mal encodé
     * est passé.
     */
    private suspend fun send(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock false
        if (!l.write(bytes, 500)) return@withLock false
        CatJournal.log(true, bytes, CatDecode.describeCiv(bytes))
        // Le poste a besoin d'un souffle entre deux trames, ou il en perd.
        if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
        val frames = collect(l, if (pacingMs > 0) 200L else 0L) { f ->
            CatDecode.isAck(f, radioAddr, controllerAddr) ||
                CatDecode.isNak(f, radioAddr, controllerAddr)
        }
        !CatDecode.isNak(frames, radioAddr, controllerAddr)
    } }

    /**
     * Émet une trame et rassemble ce qui revient, jusqu'à reconnaître la
     * réponse attendue.
     *
     * Deux défauts corrigés ici, et ils allaient ensemble. Le premier : une
     * seule lecture de soixante-quatre octets. Quand le poste faisait précéder
     * sa réponse d'un accusé de réception — ce qui arrive — la lecture unique
     * rendait l'accusé, et la réponse tombait dans le vide de la lecture
     * suivante, c'est-à-dire nulle part. Le second : la réponse était cherchée
     * à l'octet près dans un tampon brut, sans se demander d'où elle venait.
     * Le bus CI-V étant à un seul fil, ce qui revient contient toujours notre
     * propre question ; on relisait donc ce que l'on venait d'écrire.
     *
     * @return toutes les trames reçues, dans l'ordre, écho compris.
     */
    suspend fun exchange(
        cmd: Int, data: ByteArray = ByteArray(0),
        expect: Int = -1, expectSub: Int = -1, timeoutMs: Long = 600
    ): List<ByteArray> = withContext(Dispatchers.IO) { fil.withLock {
        val l = link ?: return@withLock emptyList()
        // Ce qui traîne encore sur le fil appartient à la question précédente.
        drain(l)
        val out = frame(cmd, data)
        if (!l.write(out, 500)) return@withLock emptyList()
        CatJournal.log(true, out, CatDecode.describeCiv(out))

        val frames = collect(l, timeoutMs) { f ->
            if (expect >= 0)
                CatDecode.payload(f, radioAddr, controllerAddr, expect, expectSub) != null ||
                    CatDecode.isNak(f, radioAddr, controllerAddr)
            else
                CatDecode.isAck(f, radioAddr, controllerAddr) ||
                    CatDecode.isNak(f, radioAddr, controllerAddr)
        }
        if (pacingMs > 0) kotlinx.coroutines.delay(pacingMs)
        frames
    } }

    /** Send a frame and read whatever the radio answers (for diagnostics). */
    suspend fun sendAndRead(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray? {
        if (link == null) return null
        val frames = exchange(cmd, data, expect = cmd)
        return frames.fold(ByteArray(0)) { a, b -> a + b }
    }

    /**
     * Test the link by reading the operating frequency (CI-V cmd 0x03).
     * Returns a human-readable result.
     */
    suspend fun testLink(): String {
        if (link == null) return t("civ_no_port")
        val frames = exchange(0x03, expect = 0x03)
        if (frames.isEmpty()) return t("civ_no_reply")
        val hex = frames.flatMap { f -> f.map { it } }.joinToString(" ") { "%02X".format(it) }
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x03)
        val hz = p?.let { CatDecode.bcdLeToFreq(it) }
        return if (hz != null) tf("civ_link_ok", "%.5f MHz".format(hz / 1e6))
        else tf("civ_unexpected", hex)
    }

    /**
     * Le poste émet-il ? `null` si l'on ne sait pas.
     *
     * CI-V 0x1C 0x00 : la réponse vaut 00 en réception, 01 en émission. C'est
     * la seule façon de le savoir quand l'opérateur passe en émission par le
     * VOX — l'application ne commande alors rien, elle observe.
     */
    suspend fun isTransmitting(): Boolean? {
        if (link == null) return null
        val frames = exchange(0x1C, byteArrayOf(0x00), expect = 0x1C)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x1C) ?: return null
        // La charge utile est « 00 <état> » : on lit le dernier octet.
        return p.lastOrNull()?.let { (it.toInt() and 0xFF) != 0 }
    }

    /** Set the operating frequency (Hz) on the currently selected VFO. */
    suspend fun setFrequency(hz: Long): Boolean = send(frame(0x05, freqToBcd(hz)))

    /** Select VFO A or B (cmd 0x07 00=A / 01=B). For same-band split layout. */
    suspend fun selectVfo(sub: Boolean): Boolean =
        send(frame(0x07, byteArrayOf(if (sub) 0x01 else 0x00)))

    /** Enable/disable split (cmd 0x0F 01=on / 00=off). For same-band V/V (ISS). */
    suspend fun setSplitOn(on: Boolean): Boolean =
        send(frame(0x0F, byteArrayOf(if (on) 0x01 else 0x00)))

    /** Enable/disable satellite mode (IC-9700: cmd 0x16 0x5A, 01=on/00=off). */
    suspend fun setSatelliteMode(on: Boolean): Boolean =
        send(frame(0x16, byteArrayOf(0x5A, if (on) 0x01 else 0x00)))

    /** Select MAIN or SUB band in satellite mode (cmd 0x07 0xD0=main / 0xD1=sub). */
    suspend fun selectMainSub(sub: Boolean): Boolean {
        onSub = sub
        return send(frame(0x07, byteArrayOf(if (sub) 0xD1.toByte() else 0xD0.toByte())))
    }

    /** Set operating mode on current VFO. cmd 0x06 <mode> <filter>.
     *  modes: 0x00 LSB, 0x01 USB, 0x02 AM, 0x03 CW, 0x05 FM, 0x07 CW-R, 0x08 USB-D… */
    suspend fun setMode(mode: Int, filter: Int = 0x01): Boolean =
        send(frame(0x06, byteArrayOf(mode.toByte(), filter.toByte())))

    /**
     * Relit la fréquence du VFO courant (Hz), ou null.
     *
     * Trois conditions, et il en manquait trois : la trame doit venir du poste,
     * porter la commande 0x03, et donner une fréquence vraisemblable.
     */
    suspend fun readFrequency(): Long? {
        if (link == null) return null
        val frames = exchange(0x03, expect = 0x03)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x03) ?: return null
        return CatDecode.bcdLeToFreq(p)
    }

    /**
     * Relit le mode du VFO courant (0x00 LSB, 0x01 USB, 0x05 FM…), ou null.
     *
     * Le poste répond `04 <mode> <filtre>` ; seul le premier octet nous
     * intéresse. On relit parce qu'on ne peut pas faire autrement : le mode se
     * change aussi à la main, et rien n'oblige le poste à rester où on l'a mis.
     */
    suspend fun readMode(): Int? {
        if (link == null) return null
        val frames = exchange(0x04, expect = 0x04)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x04) ?: return null
        return if (p.isEmpty()) null else p[0].toInt() and 0xFF
    }

    /**
     * Read the SELECTED (00) or UNSELECTED (01) VFO frequency via cmd 0x25.
     * This does NOT change which VFO is active (unlike 0x03 + band select).
     */
    suspend fun readVfoFreq(unselected: Boolean): Long? {
        if (link == null) return null
        val sub = if (unselected) 0x01 else 0x00
        val frames = exchange(0x25, byteArrayOf(sub.toByte()), expect = 0x25, expectSub = sub)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x25, sub) ?: return null
        return CatDecode.bcdLeToFreq(p)
    }

    /**
     * Set the SELECTED (00) or UNSELECTED (01) VFO frequency via cmd 0x25,
     * WITHOUT changing the active band/VFO. In IC-9700 satellite mode the
     * unselected VFO is the uplink (SUB) — so we tune it without swapping.
     */
    suspend fun setVfoFreq(hz: Long, unselected: Boolean): Boolean =
        send(frame(0x25, byteArrayOf(if (unselected) 0x01 else 0x00) + freqToBcd(hz)))

    /** Set the SELECTED/UNSELECTED VFO mode via cmd 0x26 (no band swap). */
    suspend fun setVfoMode(mode: Int, unselected: Boolean, filter: Int = 0x01, dataMode: Int = 0x00): Boolean =
        send(frame(0x26, byteArrayOf(if (unselected) 0x01 else 0x00, mode.toByte(), dataMode.toByte(), filter.toByte())))

    /** Enable/disable repeater tone (CTCSS) on TX. cmd 0x16 0x42. */
    suspend fun setToneOn(on: Boolean): Boolean =
        send(frame(0x16, byteArrayOf(0x42, if (on) 0x01 else 0x00)))

    /**
     * Règle le ton d'accès (670 = 67,0 Hz). Commande 0x1B 0x00, trois octets BCD
     * **gros-boutiens** : 88,5 Hz donne `00 08 85`.
     *
     * L'ancienne version encodait le ton comme une fréquence, c'est-à-dire à
     * l'envers : `00 88 50`, soit 885,0 Hz pour le poste. Hors plage, donc
     * ignoré — mais accusé réception, si bien que rien ne le signalait. Sur
     * SO-50 le relais ne s'ouvrait jamais, et l'on cherchait la panne du côté
     * de la puissance.
     */
    suspend fun setToneFreq(tenthHz: Int): Boolean {
        if (!CatDecode.toneInRange(tenthHz)) return false
        return send(frame(0x1B, byteArrayOf(0x00) + CatDecode.toneToBcdBe(tenthHz)))
    }

    /** Relit le ton d'accès réglé dans le poste, en dixièmes de hertz. */
    suspend fun readToneFreq(): Int? {
        if (link == null) return null
        val frames = exchange(0x1B, byteArrayOf(0x00), expect = 0x1B, expectSub = 0x00)
        val p = CatDecode.payload(frames, radioAddr, controllerAddr, 0x1B, 0x00) ?: return null
        return CatDecode.bcdBeToTone(p)
    }

    private var onSub = false  // tracks which band is currently selected

    /**
     * Ce que le poste affiche, pour autant qu'on le sache.
     *
     * Deux valeurs, mises à jour à chaque écriture réussie, et remises à null
     * dès qu'on n'en répond plus — changement de satellite, débranchement. Elles
     * ne servent qu'à une chose : choisir l'ordre des deux écritures du couple
     * satellite, ce qui suppose de savoir d'où l'on part. Tant qu'on ne le sait
     * pas, on relit le poste une fois ; ensuite on suit ses propres consignes,
     * car relire deux fréquences dix fois par seconde encombrerait le bus pour
     * rien.
     */
    private var knownMain: Long? = null
    private var knownSub: Long? = null
    private var bandesLues = false

    /**
     * Oublier l'état supposé du poste.
     *
     * À appeler au changement de satellite et à la fermeture : c'est le seul
     * moment où quelqu'un d'autre que nous a pu toucher aux VFO.
     */
    fun forgetBands() { knownMain = null; knownSub = null; bandesLues = false }

    /** Read frequency of MAIN (downlink) = the selected band in sat mode. */
    suspend fun readMainFrequency(): Long? {
        if (onSub) { selectMainSub(false) }
        return readFrequency()
    }

    /** Read frequency of SUB (uplink) band, then return to MAIN. */
    suspend fun readSubFrequency(): Long? {
        selectMainSub(true)
        val f = readFrequency()
        selectMainSub(false)
        return f
    }

    /**
     * Poser la descente sur MAIN et la montée sur SUB, en mode satellite.
     *
     * Les commandes 0x25/0x26 ne savent pas atteindre la bande secondaire d'un
     * IC-9700 en satellite transbande : il faut sélectionner la bande (0x07
     * D0/D1) puis écrire avec 0x05. Cela, c'était déjà vrai en 18.18.
     *
     * Ce qui manquait, c'est **l'ordre**. Le poste refuse d'avoir ses deux
     * bandes sur la même à la fois, et passer d'un satellite en V/U à un
     * satellite en U/V est précisément un échange des deux bandes : la première
     * écriture, quelle qu'elle soit, amenait une bande là où l'autre se trouvait
     * encore, le poste répondait NAK sans que rien ne l'affiche, et l'on voyait
     * deux fréquences en 435 dans le panneau POSTE. « Ça ne switch pas » : non,
     * et aucun ordre fixe ne pouvait le faire.
     *
     * [BandPlan] décide donc de l'ordre à partir de l'état du poste, quitte à
     * garer la montée sur une troisième bande le temps de libérer la place. On
     * finit toujours sur MAIN, pour que la molette de réception reste sous la
     * main de l'opérateur.
     */
    suspend fun setSatellitePair(downlinkHz: Long, uplinkHz: Long) {
        // Au premier couple d'un passage, on ne sait rien du poste : on le lit
        // une fois. Ensuite nos propres consignes suffisent à le savoir.
        if (!bandesLues) {
            // Une seule fois : un poste muet ne doit pas nous faire relire deux
            // fréquences dix fois par seconde. Sans réponse, on retombe sur
            // l'ordre d'avant, qui a le mérite d'être celui qu'on connaît.
            bandesLues = true
            knownSub = readSubFrequency()
            knownMain = readMainFrequency()
        }
        val etapes = BandPlan.steps(knownMain, knownSub, downlinkHz, uplinkHz)
        for (e in etapes) {
            selectMainSub(e.sub)
            if (setFrequency(e.hz)) {
                if (e.sub) knownSub = e.hz else knownMain = e.hz
            } else {
                // Un refus veut dire que le poste n'est pas où on le croyait :
                // on le rappellera au tour suivant plutôt que de s'enfoncer.
                forgetBands()
            }
        }
        if (onSub) selectMainSub(false)
    }

    /** Set only the uplink (SUB), then return to MAIN for RX. */
    override suspend fun setUplink(uplinkHz: Long) {
        selectMainSub(true)
        setFrequency(uplinkHz)
        selectMainSub(false)
    }

    // ---- Same-band (V/V, e.g. ISS FM) layout: satellite mode OFF, split ON,
    //      RX on VFO A, TX on VFO B. ----

    /** Arm same-band: sat mode off, split on. Call once at pass init. */
    suspend fun armSameBandSplit() {
        setSatelliteMode(false)
        selectVfo(false)        // VFO A = RX
        setSplitOn(true)
    }

    /** Read VFO A (RX) frequency in same-band split. */
    suspend fun readVfoAFrequency(): Long? {
        selectVfo(false)
        return readFrequency()
    }

    /** Same-band pair: RX on VFO A, TX on VFO B (split). Finish on A. */
    suspend fun setSplitPair(downlinkHz: Long, uplinkHz: Long) {
        selectVfo(true)         // VFO B = TX
        setFrequency(uplinkHz)
        selectVfo(false)        // back to VFO A = RX
        setFrequency(downlinkHz)
    }

    /** Same-band: set only the TX (VFO B), return to RX (VFO A). */
    suspend fun setSplitUplink(uplinkHz: Long) {
        selectVfo(true)
        setFrequency(uplinkHz)
        selectVfo(false)
    }

    // ---- RigDriver interface mappings ----
    override suspend fun enterSatelliteMode() { setSatelliteMode(true) }
    override suspend fun setModes(downlink: String, uplink: String) {
        // 0x06 sets the mode on the currently selected band; select each in turn.
        selectMainSub(true);  setMode(modeByte(uplink))
        selectMainSub(false); setMode(modeByte(downlink))
    }
    override suspend fun readDownlink(): Long? = readMainFrequency()
    override suspend fun setPair(downlinkHz: Long, uplinkHz: Long) = setSatellitePair(downlinkHz, uplinkHz)
    override suspend fun setCtcss(tenthHz: Int) {
        if (tenthHz > 0) { setToneOn(true); setToneFreq(tenthHz) } else setToneOn(false)
    }

    private fun modeByte(m: String): Int = when (m.uppercase()) {
        "LSB" -> 0x00; "USB" -> 0x01; "AM" -> 0x02; "CW" -> 0x03; "FM" -> 0x05
        else -> 0x01
    }
}
