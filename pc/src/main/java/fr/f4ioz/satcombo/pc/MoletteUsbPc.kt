/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.MoletteUsb
import fr.f4ioz.satcombo.plateforme.Stockage
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream

/** Linux input devices as the kernel lists them, and the events they send. */
object EntreesLinux {

    /** One input device of /proc/bus/input/devices. */
    data class Appareil(
        val nom: String,
        /** Its event node, `event21`. */
        val evenement: String,
        val vendeur: String,
        val produit: String,
        /** Sends volume up and down. */
        val volume: Boolean,
        /** Has the letter keys: a whole keyboard as far as the kernel says. */
        val lettres: Boolean,
    ) {
        val id: String get() = "$vendeur:$produit"
        /** One of these USB volume knobs: its USB id, or a « Controller » sending the volume. */
        val molette: Boolean get() = volume && (id.equals(ID_MOLETTE, ignoreCase = true) || nom.contains("Controller"))
    }

    const val ID_MOLETTE = "2341:484d"
    const val EV_KEY = 1
    const val KEY_MUTE = 113
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115

    /** Bit [n] of a kernel bitmap line (`B: KEY=…`): 64-bit words in hex, the highest first. */
    fun bit(bitmap: String, n: Int): Boolean {
        val mots = bitmap.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val i = mots.size - 1 - n / 64
        if (i < 0) return false
        val m = mots[i].toULongOrNull(16) ?: return false
        return (m shr (n % 64)) and 1uL == 1uL
    }

    /** The devices listed, those with an event node. */
    fun lit(texte: String): List<Appareil> = texte.split(Regex("\\n\\s*\\n")).mapNotNull { bloc ->
        var nom = ""; var ev = ""; var ven = ""; var pro = ""; var touches = ""
        bloc.lines().forEach { l ->
            when {
                l.startsWith("I:") -> {
                    ven = Regex("Vendor=([0-9a-fA-F]+)").find(l)?.groupValues?.get(1).orEmpty().lowercase()
                    pro = Regex("Product=([0-9a-fA-F]+)").find(l)?.groupValues?.get(1).orEmpty().lowercase()
                }
                l.startsWith("N:") -> nom = l.substringAfter("Name=").trim().removeSurrounding("\"")
                l.startsWith("H:") -> ev = l.substringAfter("Handlers=").split(' ').firstOrNull { it.matches(Regex("event\\d+")) }.orEmpty()
                l.startsWith("B: KEY=") -> touches = l.substringAfter("KEY=")
            }
        }
        if (ev.isEmpty()) null
        else Appareil(nom, ev, ven, pro,
            volume = bit(touches, KEY_VOLUMEUP) && bit(touches, KEY_VOLUMEDOWN),
            lettres = (16..25).all { bit(touches, it) } && (30..38).all { bit(touches, it) })
    }

    /**
     * The device to read: the one named in Réglages (by name, event numbers
     * change at each plug), else the knob. Never a keyboard by itself.
     */
    fun choisit(liste: List<Appareil>, choix: String): Appareil? =
        if (choix.isNotBlank()) liste.firstOrNull { it.nom == choix && it.volume }
        else liste.firstOrNull { it.molette && it.volume }

    /** One `struct input_event`. */
    data class Evenement(val type: Int, val code: Int, val valeur: Int)

    /** A 64-bit `struct input_event`: timeval (16), type u16, code u16, value s32, little-endian. */
    const val TAILLE = 24

    fun decode(b: ByteArray, o: Int = 0): Evenement {
        fun u16(i: Int) = (b[o + i].toInt() and 0xff) or ((b[o + i + 1].toInt() and 0xff) shl 8)
        val v = (b[o + 20].toInt() and 0xff) or ((b[o + 21].toInt() and 0xff) shl 8) or
            ((b[o + 22].toInt() and 0xff) shl 16) or (b[o + 23].toInt() shl 24)
        return Evenement(u16(16), u16(18), v)
    }

    /** One event as the kernel writes it (for the tests, and nothing else). */
    fun encode(type: Int, code: Int, valeur: Int): ByteArray = ByteArray(TAILLE).also { b ->
        b[16] = type.toByte(); b[17] = (type shr 8).toByte()
        b[18] = code.toByte(); b[19] = (code shr 8).toByte()
        for (i in 0..3) b[20 + i] = (valeur shr (8 * i)).toByte()
    }

    /** The rule that lets the user read the knob (the owner types it, sudo in his own terminal). */
    fun regleUdev(id: String = ID_MOLETTE): String {
        val (v, p) = id.split(':').let { (it.getOrNull(0) ?: "2341") to (it.getOrNull(1) ?: "484d") }
        return "echo 'SUBSYSTEM==\"input\", ATTRS{idVendor}==\"$v\", ATTRS{idProduct}==\"$p\", MODE=\"0660\", GROUP=\"plugdev\"' | " +
            "sudo tee /etc/udev/rules.d/70-satme-molette.rules && sudo udevadm control --reload && sudo udevadm trigger"
    }

    /**
     * The helper that takes the knob for SatMe alone (EVIOCGRAB: the desktop
     * no longer changes the volume): Java cannot make that ioctl. Standard
     * library only. It prints `ok 1` (taken) or `ok 0`, then `k code value`
     * per key, `fin` when the device goes. It ends with SatMe-PC (its stdin
     * closes, or its parent changes), and the kernel releases the grab with
     * the descriptor in any case.
     */
    val ASSISTANT = """
import os, sys, struct, fcntl, select, signal
signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
EVIOCGRAB = 0x40044590
dev, veut = sys.argv[1], sys.argv[2] == '1'
try:
    fd = os.open(dev, os.O_RDONLY | os.O_NONBLOCK)
except OSError as e:
    print('err %d %s' % (e.errno, e.strerror), flush=True)
    sys.exit(2)
pris = False
if veut:
    try:
        fcntl.ioctl(fd, EVIOCGRAB, 1)
        pris = True
    except OSError as e:
        print('nograb %d' % e.errno, flush=True)
print('ok %d' % (1 if pris else 0), flush=True)
fmt = 'llHHi'
n = struct.calcsize(fmt)
parent = os.getppid()
reste = b''
try:
    while True:
        r, _, _ = select.select([fd, 0], [], [], 1.0)
        if os.getppid() != parent:
            break
        if 0 in r and not os.read(0, 4096):
            break
        if fd in r:
            try:
                b = os.read(fd, n * 64)
            except BlockingIOError:
                continue
            except OSError:
                print('fin', flush=True)
                break
            if not b:
                print('fin', flush=True)
                break
            reste += b
            while len(reste) >= n:
                _, _, t, c, v = struct.unpack(fmt, reste[:n])
                reste = reste[n:]
                if t == 1:
                    sys.stdout.write('k %d %d\n' % (c, v))
            sys.stdout.flush()
finally:
    if pris:
        try:
            fcntl.ioctl(fd, EVIOCGRAB, 0)
        except OSError:
            pass
    os.close(fd)
""".trimIndent()

    /** A stream of events; `null` at its end. */
    interface Source : Closeable {
        val capture: Boolean
        fun lit(): Evenement?
    }

    class Refus(message: String) : Exception(message)

    /** Read directly (no capture): the raw events of the device. */
    class SourceDirecte(private val entree: InputStream) : Source {
        override val capture = false
        private val b = ByteArray(TAILLE)
        override fun lit(): Evenement? {
            var n = 0
            while (n < TAILLE) {
                val r = entree.read(b, n, TAILLE - n)
                if (r < 0) return null
                n += r
            }
            return decode(b)
        }
        override fun close() { runCatching { entree.close() } }
    }

    /** Through the helper: captured when it could, its lines read. */
    class SourceAssistant(python: String, chemin: String, veut: Boolean) : Source {
        private val p: Process = ProcessBuilder(python, "-c", ASSISTANT, chemin, if (veut) "1" else "0")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val sortie = p.inputStream.bufferedReader()
        override val capture: Boolean
        /** Why the capture failed, when asked and refused (an errno). */
        val refusCapture: String?

        init {
            var refus: String? = null
            var pris = false
            while (true) {
                val l = sortie.readLine() ?: run { p.destroy(); throw Refus("l'assistant s'est arrêté") }
                when {
                    l.startsWith("err ") -> { p.destroy(); throw Refus(l.removePrefix("err ").substringAfter(' ')) }
                    l.startsWith("nograb ") -> refus = l.removePrefix("nograb ")
                    l.startsWith("ok ") -> { pris = l == "ok 1"; break }
                }
            }
            capture = pris; refusCapture = refus
        }

        override fun lit(): Evenement? {
            while (true) {
                val l = sortie.readLine() ?: return null
                if (l == "fin") return null
                val m = l.split(' ')
                if (m.size == 3 && m[0] == "k") return Evenement(EV_KEY, m[1].toIntOrNull() ?: continue, m[2].toIntOrNull() ?: continue)
            }
        }

        override fun close() { runCatching { p.destroy() }; runCatching { p.outputStream.close() } }
    }

    /** python3 on the PATH, or null. */
    fun cherchePython(): String? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .map { File(it, "python3") }.firstOrNull { it.canExecute() }?.absolutePath
}

/**
 * The USB volume knob as a VFO, as on the phone: the same rule
 * ([MoletteUsb]), the same settings (on/off, step, learned keys, target).
 * Linux only: the knob's keyboard read from /dev/input, captured for SatMe
 * when chosen so that the desktop stops changing the volume.
 *
 * Tuning only: the satellite VFO, the RX and TX shifts (the frequencies
 * the CAT loop writes), the rig's own dial when nothing drives it, the SDR
 * dongle, QO-100's narrow band — the same while the rig transmits. Never
 * PTT, never a transmission started.
 */
class MoletteUsbPc(
    private val stockage: Stockage,
    private val settings: SettingsStore,
    private val monde: Monde,
    private val fichierAppareils: File = File("/proc/bus/input/devices"),
    private val dossierDev: File = File("/dev/input"),
    private val python: String? = EntreesLinux.cherchePython(),
    private val horloge: () -> Long = System::currentTimeMillis,
    /** Opens a device; by default the helper (or the device itself without python). */
    private val ouvre: ((File, Boolean) -> EntreesLinux.Source)? = null,
) {
    /** What the knob acts on: the station's tunings (receive side). */
    interface Monde {
        fun contexte(page: String?): MoletteUsb.Contexte
        /** The satellite VFO: the rest in the transponder (the uplink follows), else the RX shift. */
        fun vfo(dHz: Long)
        fun shiftRx(dHz: Long)
        fun shiftTx(dHz: Long)
        fun zeroRx()
        fun zeroTx()
        fun sdr(dHz: Long)
        fun qo100(dHz: Long)
    }

    /** Linux with its input list: elsewhere (Windows) the card says « non disponible ». */
    val dispo: Boolean = System.getProperty("os.name").orEmpty().startsWith("Linux") && fichierAppareils.canRead() || ouvre != null

    var appareilChoisi: String
        get() = stockage.getString("pc_molette_appareil", "") ?: ""
        set(v) { stockage.edit().putString("pc_molette_appareil", v).apply(); relance() }
    /** Taken for SatMe alone (the PC's volume no longer moves): on by default once the knob is on. */
    var captureVoulue: Boolean
        get() = stockage.getBoolean("pc_molette_capture", true)
        set(v) { stockage.edit().putBoolean("pc_molette_capture", v).apply(); relance() }
    /** AUTO, POSTE, SDR or QO100. */
    var accord: String
        get() = stockage.getString("pc_molette_accord", "AUTO") ?: "AUTO"
        set(v) { stockage.edit().putString("pc_molette_accord", if (v in ACCORDS) v else "AUTO").apply() }

    @Volatile var etat = "coupe"; private set
    @Volatile var message = ""; private set
    @Volatile private var lu: EntreesLinux.Appareil? = null
    @Volatile private var capture = false
    @Volatile private var source: EntreesLinux.Source? = null
    @Volatile private var generation = 0
    private val verrou = Object()

    @Volatile private var dernier: JSONObject? = null
    @Volatile private var apprend: Int? = null
    @Volatile private var apprendJusqua = 0L
    @Volatile private var contexte: String? = null
    @Volatile private var contexteMs = 0L

    fun demarre() {
        if (!dispo) return
        Thread({ while (true) runCatching { tour() }.onFailure { etat = "erreur"; message = it.message ?: "erreur"; attends(3_000) } },
            "satme-molette").apply { isDaemon = true; start() }
    }

    fun arrete() { relance() }

    /** The source closed: the loop opens it again with the new settings. */
    private fun relance() {
        generation++
        source?.close()
        synchronized(verrou) { verrou.notifyAll() }
    }

    private fun attends(ms: Long) = synchronized(verrou) { verrou.wait(ms) }

    fun appareils(): List<EntreesLinux.Appareil> = runCatching { EntreesLinux.lit(fichierAppareils.readText()) }.getOrDefault(emptyList())

    /** Capture only the knob, or a device that is no whole keyboard: never the PC's keyboard. */
    fun captureAutorisee(a: EntreesLinux.Appareil) = a.molette || !a.lettres

    private fun tour() {
        val gen = generation
        if (!settings.moletteVfo) { etat = "coupe"; message = ""; lu = null; attends(2_000); return }
        val a = EntreesLinux.choisit(appareils(), appareilChoisi)
        lu = a
        if (a == null) {
            etat = "absent"; message = if (appareilChoisi.isBlank()) "aucune molette branchée" else "« $appareilChoisi » n'est pas branché"
            attends(3_000); return
        }
        val dev = File(dossierDev, a.evenement)
        if (ouvre == null && !dev.canRead()) { etat = "permission"; message = "/dev/input/${a.evenement} illisible"; attends(3_000); return }
        val veut = captureVoulue && captureAutorisee(a)
        val s = try {
            (ouvre ?: ::ouvreParDefaut)(dev, veut)
        } catch (e: Exception) {
            etat = if (e.message.orEmpty().contains("ermission")) "permission" else "erreur"; message = e.message ?: "erreur"
            attends(3_000); return
        }
        source = s; capture = s.capture
        etat = "ouvert"
        message = when {
            captureVoulue && !captureAutorisee(a) -> "lecture seule : un clavier complet n'est jamais capturé"
            veut && !s.capture -> "lecture seule : capture refusée" + ((s as? EntreesLinux.SourceAssistant)?.refusCapture?.let { " ($it)" } ?: "")
            else -> ""
        }
        if (gen != generation) { s.close(); source = null; return }
        try {
            while (gen == generation && settings.moletteVfo) {
                val e = s.lit() ?: break
                if (e.type == EntreesLinux.EV_KEY) recoit(e.code, e.valeur)
            }
        } finally { s.close(); source = null; capture = false }
        if (gen == generation && settings.moletteVfo) { etat = "absent"; message = "molette débranchée"; attends(2_000) }
    }

    private fun ouvreParDefaut(dev: File, veut: Boolean): EntreesLinux.Source =
        python?.let { EntreesLinux.SourceAssistant(it, dev.path, veut) }
            ?: EntreesLinux.SourceDirecte(dev.inputStream())

    private fun touches() = MoletteUsb.Touches(
        settings.macroCodeA, cible(settings.macroCibleA, MoletteUsb.Cible.SHIFT_RX),
        settings.macroCodeB, cible(settings.macroCibleB, MoletteUsb.Cible.SHIFT_TX),
        settings.macroCodeC, cible(settings.macroCibleC, MoletteUsb.Cible.VFO),
        settings.macroCodeD, runCatching { MoletteUsb.Action.valueOf(settings.macroActionD) }.getOrDefault(MoletteUsb.Action.PAS))

    private fun cible(s: String, d: MoletteUsb.Cible) = runCatching { MoletteUsb.Cible.valueOf(s) }.getOrDefault(d)

    /** The page's tab (`sdr`, `qo100`…), told at each of its readings: forgotten after 20 s without one. */
    fun contextePage(ctx: String?) {
        if (ctx.isNullOrBlank()) return
        contexte = ctx.take(24); contexteMs = horloge()
    }

    private fun contexteActuel() = contexte.takeIf { horloge() - contexteMs < 20_000L }

    fun accordEffectif(): MoletteUsb.Accord = MoletteUsb.accordDe(accord, monde.contexte(contexteActuel()))

    /** One key of the knob, Linux code; value 1 pressed, 2 repeated, 0 released. */
    fun recoit(codeLinux: Int, valeur: Int) {
        if (valeur == 0) return
        val code = MoletteUsb.codeDepuisLinux(codeLinux)
        val rang = apprend
        if (rang != null) {
            if (horloge() > apprendJusqua) apprend = null
            else {
                if (valeur == 1 && MoletteUsb.apprenable(code)) { apprendTouche(rang, code); apprend = null }
                note(codeLinux, valeur, "appris"); return
            }
        }
        val g = MoletteUsb.geste(code, settings.moletteVfo, externe = true, pasHz = settings.molettePasHz, touches = touches())
        // A held key repeats: only a turn may (a held press would cycle the step).
        if (valeur == 2 && g !is MoletteUsb.Geste.Bouge) return
        note(codeLinux, valeur, applique(g))
    }

    /**
     * The page's wheel or drag on a frequency: [crans] steps of the knob's
     * step, on what the knob tunes now ([poste]: the rig, whatever the « Auto »
     * says — the wheel is on the rig's frequency). Works without the knob.
     */
    fun tourne(crans: Int, poste: Boolean = false): String {
        if (crans == 0) return "ignorée"
        val d = crans.coerceIn(-50, 50) * settings.molettePasHz
        if (poste) return when (settings.moletteCible) {
            "SHIFT_TX" -> { monde.shiftTx(d); "décalage TX " + signe(d) }
            "SHIFT_RX" -> { monde.shiftRx(d); "décalage RX " + signe(d) }
            else -> { monde.vfo(d); "VFO " + signe(d) }
        }
        return applique(MoletteUsb.Geste.Bouge(d))
    }

    private fun note(code: Int, valeur: Int, quoi: String) {
        dernier = JSONObject().put("code", code).put("nom", nomLinux(code)).put("valeur", valeur).put("geste", quoi).put("ms", horloge())
    }

    /** What the gesture did, in words for the test line. */
    private fun applique(g: MoletteUsb.Geste): String = when (g) {
        is MoletteUsb.Geste.ChoisitCible -> { settings.moletteCible = g.cible.name; "cible " + nomCible(g.cible.name) }
        is MoletteUsb.Geste.Bouge -> {
            val d = g.deltaHz
            when (accordEffectif()) {
                MoletteUsb.Accord.SDR -> { monde.sdr(d); "SDR " + signe(d) }
                MoletteUsb.Accord.QO100 -> { monde.qo100(d); "QO-100 " + signe(d) }
                MoletteUsb.Accord.POSTE -> when (settings.moletteCible) {
                    "SHIFT_TX" -> { monde.shiftTx(d); "décalage TX " + signe(d) }
                    "SHIFT_RX" -> { monde.shiftRx(d); "décalage RX " + signe(d) }
                    else -> { monde.vfo(d); "VFO " + signe(d) }
                }
            }
        }
        MoletteUsb.Geste.CibleSuivante -> { settings.moletteCible = MoletteUsb.cibleSuivante(settings.moletteCible); "cible " + nomCible(settings.moletteCible) }
        // Only the current target: the other shift may be one the operator keeps.
        MoletteUsb.Geste.RemetZero -> when (settings.moletteCible) {
            "SHIFT_TX" -> { monde.zeroTx(); "décalage TX à zéro" }
            "SHIFT_RX" -> { monde.zeroRx(); "décalage RX à zéro" }
            else -> "VFO : pas de zéro"
        }
        MoletteUsb.Geste.ChangePas -> { settings.molettePasHz = MoletteUsb.pasSuivant(settings.molettePasHz); "pas " + settings.molettePasHz + " Hz" }
        MoletteUsb.Geste.Ignore -> "ignorée"
    }

    /** A key learned: one key serves once (as on the phone). */
    private fun apprendTouche(rang: Int, code: Int) {
        if (settings.macroCodeA == code) settings.macroCodeA = 0
        if (settings.macroCodeB == code) settings.macroCodeB = 0
        if (settings.macroCodeC == code) settings.macroCodeC = 0
        if (settings.macroCodeD == code) settings.macroCodeD = 0
        when (rang) {
            0 -> settings.macroCodeA = code
            1 -> settings.macroCodeB = code
            2 -> settings.macroCodeC = code
            else -> settings.macroCodeD = code
        }
    }

    /** The card's settings (POST); the answer is the state. */
    fun regle(q: Map<String, String>): JSONObject {
        q["on"]?.let { settings.moletteVfo = it == "1"; relance() }
        q["appareil"]?.let { appareilChoisi = it.take(200) }
        q["capture"]?.let { captureVoulue = it == "1" }
        q["accord"]?.let { accord = it }
        q["pas"]?.toLongOrNull()?.takeIf { it in MoletteUsb.PAS }?.let { settings.molettePasHz = it }
        q["action"]?.takeIf { a -> MoletteUsb.Action.values().any { it.name == a } }?.let { settings.macroActionD = it }
        q["cible"]?.takeIf { c -> MoletteUsb.Cible.values().any { it.name == c } }?.let { settings.moletteCible = it }
        fun cibleOk(c: String?) = c?.takeIf { x -> MoletteUsb.Cible.values().any { it.name == x } }
        cibleOk(q["cibleA"])?.let { settings.macroCibleA = it }
        cibleOk(q["cibleB"])?.let { settings.macroCibleB = it }
        cibleOk(q["cibleC"])?.let { settings.macroCibleC = it }
        q["apprendre"]?.toIntOrNull()?.let { r ->
            if (r in 0..3) { apprend = r; apprendJusqua = horloge() + 20_000L } else apprend = null
        }
        q["oublie"]?.toIntOrNull()?.let { r ->
            when (r) { 0 -> settings.macroCodeA = 0; 1 -> settings.macroCodeB = 0; 2 -> settings.macroCodeC = 0
                3 -> settings.macroCodeD = MoletteUsb.VOLUME_MUTE }
        }
        return json()
    }

    fun json(): JSONObject {
        val o = JSONObject().put("dispo", dispo)
        if (!dispo) return o.put("message", "non disponible : Linux seulement")
        val liste = appareils()
        val a = lu ?: EntreesLinux.choisit(liste, appareilChoisi)
        if (apprend != null && horloge() > apprendJusqua) apprend = null
        val ae = accordEffectif()
        return o.put("on", settings.moletteVfo).put("etat", etat).put("message", message)
            .put("appareil", a?.let { JSONObject().put("nom", it.nom).put("evenement", it.evenement).put("id", it.id).put("molette", it.molette) })
            .put("appareils", JSONArray(liste.filter { it.volume }.map { JSONObject().put("nom", it.nom).put("evenement", it.evenement).put("id", it.id).put("molette", it.molette) }))
            .put("choix", appareilChoisi).put("capture", captureVoulue).put("capture_effective", capture)
            .put("accord", accord).put("accord_effectif", ae.name)
            .put("cible", settings.moletteCible).put("pas", settings.molettePasHz).put("action", settings.macroActionD)
            .put("touches", JSONArray(listOf(
                JSONObject().put("code", settings.macroCodeA).put("nom", nomCode(settings.macroCodeA)).put("cible", settings.macroCibleA),
                JSONObject().put("code", settings.macroCodeB).put("nom", nomCode(settings.macroCodeB)).put("cible", settings.macroCibleB),
                JSONObject().put("code", settings.macroCodeC).put("nom", nomCode(settings.macroCodeC)).put("cible", settings.macroCibleC),
                JSONObject().put("code", settings.macroCodeD).put("nom", nomCode(settings.macroCodeD)))))
            .put("apprend", apprend ?: -1)
            .put("dernier", dernier)
            .put("puce", if (settings.moletteVfo && etat == "ouvert") puce(ae) else "")
            .put("udev", EntreesLinux.regleUdev(a?.id?.takeIf { it.length == 9 } ?: EntreesLinux.ID_MOLETTE))
    }

    /** The header's chip: « 100 Hz · SDR ». */
    private fun puce(a: MoletteUsb.Accord) = settings.molettePasHz.let { if (it >= 1000) "${it / 1000} kHz" else "$it Hz" } + " · " + when (a) {
        MoletteUsb.Accord.SDR -> "SDR"
        MoletteUsb.Accord.QO100 -> "QO-100"
        MoletteUsb.Accord.POSTE -> when (settings.moletteCible) { "SHIFT_TX" -> "TX"; "SHIFT_RX" -> "RX"; else -> "VFO" }
    }

    companion object {
        val ACCORDS = setOf("AUTO", "POSTE", "SDR", "QO100")
        private fun signe(d: Long) = (if (d > 0) "+" else "−") + kotlin.math.abs(d) + " Hz"
        fun nomCible(c: String) = when (c) { "SHIFT_RX" -> "décalage RX"; "SHIFT_TX" -> "décalage TX"; else -> "VFO" }
        fun nomLinux(code: Int) = when (code) {
            EntreesLinux.KEY_VOLUMEUP -> "volume +"; EntreesLinux.KEY_VOLUMEDOWN -> "volume −"; EntreesLinux.KEY_MUTE -> "silence"
            164 -> "lecture/pause"; 163 -> "suivante"; 165 -> "précédente"; 166 -> "stop"
            else -> "touche $code"
        }
        /** A stored code (shared with the phone) in words. */
        fun nomCode(code: Int) = when {
            code == 0 -> "—"
            code == MoletteUsb.VOLUME_UP -> "volume +"; code == MoletteUsb.VOLUME_DOWN -> "volume −"; code == MoletteUsb.VOLUME_MUTE -> "silence"
            code == 85 -> "lecture/pause"; code == 86 -> "stop"; code == 87 -> "suivante"; code == 88 -> "précédente"
            code >= MoletteUsb.DECALAGE_LINUX -> "touche " + (code - MoletteUsb.DECALAGE_LINUX)
            else -> "code $code"
        }
    }
}
