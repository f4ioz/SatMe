/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.telemesure

import org.json.JSONArray
import org.json.JSONObject

/**
 * HADES-SA's (SO-127) GENESIS telemetry made readable: the frames with a
 * right CRC ([Genesis.Deframeur]), their data descrambled, type byte left
 * out. The layouts and the arithmetic follow the structures AMSAT-EA
 * publishes with its decoder (« HADES-SA_SpinnyONE », github.com/AMSAT-EA,
 * CC BY 4.0, © AMSAT EA): facts of the format, written here anew — no code
 * copied. Integers are little-endian; 12-bit fields straddle two words as
 * the published structures pack them.
 *
 * - 1 energy: the panels' power (mW), the bus and battery voltages (mV),
 *   the battery, CPU and payload currents (mA);
 * - 2 temperatures, 5 their lowest and highest: raw / 2 − 40 °C (255: none);
 * - 3 state: uptime, resets, battery state, transponder mode, antennas,
 *   the share of time in each mode, the picture being sent;
 * - 4 energy ranges, 9 extended energy (ten INA channels);
 * - 8 antenna deployment (burn wire), raw values as sent;
 * - 14 time series: one variable, 30 samples 3 min apart (the last now);
 * - 15 BBS: five callsigns, messages and their voice frames.
 *
 * The satellite's clock ([Lecture.horloge]) counts seconds since it was
 * first switched on, not UTC: the frame's own time is when it was heard.
 */
object TelemesureGenesis {

    /** One value: [cle] stable, [fr] / [en] its label, [v] the number (null: none sent), [u] its unit, [x] a text instead. */
    class Champ(val cle: String, val fr: String, val en: String, val v: Double?, val u: String = "", val x: String? = null, val xEn: String? = null) {
        fun libelle(en: Boolean) = if (en) this.en else fr
        fun texte(en: Boolean) = if (en) xEn ?: x else x
    }

    /** A time series (type 14): [valeurs] oldest first, [pasS] apart, the last at the frame's time. */
    class Serie(val variable: Int, val fr: String, val en: String, val u: String, val valeurs: List<Double?>, val pasS: Int = 180)

    /** A BBS entry (type 15). */
    class Bbs(val indicatif: String, val message: String, val tramesVoix: Int)

    class Lecture(val type: Int, val horloge: Long?, val champs: List<Champ>, val serie: Serie? = null, val bbs: List<Bbs> = emptyList()) {
        val fr: String get() = NOMS_FR[type] ?: "type $type"
        val en: String get() = NOMS_EN[type] ?: "type $type"
        fun nom(en: Boolean) = if (en) this.en else fr
        fun champ(cle: String): Champ? = champs.firstOrNull { it.cle == cle }
    }

    val NOMS_FR = mapOf(1 to "Énergie", 2 to "Températures", 3 to "État", 4 to "Énergie (plages)", 5 to "Températures (plages)",
        8 to "Déploiement des antennes", 9 to "Énergie détaillée", 12 to "Éphémérides", 13 to "Essai PN9", 14 to "Série temporelle",
        15 to "Messagerie (BBS)")
    val NOMS_EN = mapOf(1 to "Power", 2 to "Temperatures", 3 to "Status", 4 to "Power ranges", 5 to "Temperature ranges",
        8 to "Antenna deployment", 9 to "Extended power", 12 to "Ephemeris", 13 to "PN9 test", 14 to "Time series", 15 to "Messaging (BBS)")

    /** A type's name, read or not ([en]: in English). */
    fun nomType(type: Int, en: Boolean): String = (if (en) NOMS_EN[type] else NOMS_FR[type]) ?: Genesis.NOMS_TYPES[type] ?: "type $type"

    /** The data size (type byte and CRC left out) of each type read. */
    val TAILLES = mapOf(1 to 28, 2 to 14, 3 to 38, 4 to 32, 5 to 24, 8 to 28, 9 to 120, 14 to 35, 15 to 70)

    // ---------------------------------------------------------------- bytes

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun s8(b: ByteArray, i: Int) = b[i].toInt()
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    /** A 12-bit two's complement value. */
    private fun s12(v: Int) = if (v and 0x800 != 0) v - 0x1000 else v

    /** A temperature byte: raw / 2 − 40 °C, 255 none. */
    fun temperature(raw: Int): Double? = if (raw == 0xFF) null else raw / 2.0 - 40.0

    private fun t(cle: String, fr: String, en: String, raw: Int) = Champ(cle, fr, en, temperature(raw), "°C")

    // ---------------------------------------------------------------- frames

    /** The frame of [type] made readable, or null when its size is not the one known. */
    fun lis(type: Int, d: ByteArray): Lecture? {
        if (TAILLES[type] != d.size) return null
        return runCatching {
            when (type) {
                1 -> energie(d)
                2 -> temperatures(d)
                3 -> etat(d)
                4 -> plagesEnergie(d)
                5 -> plagesTemperatures(d)
                8 -> antennes(d)
                9 -> energieEtendue(d)
                14 -> serie(d)
                15 -> bbs(d)
                else -> null
            }
        }.getOrNull()
    }

    /** A frame kept as hex ([RecepteurGenesis.json]'s « hex », the data) made readable. */
    fun lis(type: Int, hex: String): Lecture? = AssemblageSsdv.deHex(hex)?.let { lis(type, it) }

    private fun energie(d: ByteArray): Lecture {
        val w10 = u16(d, 10); val w12 = u16(d, 12); val w14 = u16(d, 14); val w16 = u16(d, 16)
        val w18 = u16(d, 18); val w20 = u16(d, 20); val w22 = u16(d, 22)
        val vbus1 = (w10 ushr 4) * 1400 / 1000
        val vbat1 = (((w10 shl 8) and 0x0F00) or ((w12 ushr 8) and 0xFF)) * 1400 / 1000
        val vcpuBrut = ((w12 shl 4) and 0x0FF0) or (w14 ushr 12)
        val vcpu = if (vcpuBrut > 0) 1210 * 4096 / vcpuBrut else 0
        val vbus2 = (w14 and 0x0FFF) * 4
        val vbus3 = (w16 ushr 4) * 4
        val vbat2 = (((w16 shl 8) and 0x0F00) or (w18 ushr 8)) * 4
        val ibat = s12(((w18 and 0x0F) shl 8) or (w20 ushr 8))
        // The CPU's current reads reversed (its chip is mounted the other way round).
        val icpuBrut = ((w20 shl 4) and 0x0FF0) or (w22 ushr 12)
        val icpu = if (icpuBrut and 0x800 != 0) 0x1000 - icpuBrut else icpuBrut
        val ipl = s12(w22 and 0x0FFF)
        val l = listOf(
            Champ("vbat", "Batterie (ADC)", "Battery (ADC)", vbat1.toDouble(), "mV"),
            Champ("vbat2", "Batterie (I2C)", "Battery (I2C)", vbat2.toDouble(), "mV"),
            Champ("ibat", "Courant batterie (+ décharge, − charge)", "Battery current (+ out, − in)", ibat.toDouble(), "mA"),
            Champ("vbus1", "Bus (ADC)", "Bus (ADC)", vbus1.toDouble(), "mV"),
            Champ("vbus2", "Bus 2 (I2C)", "Bus 2 (I2C)", vbus2.toDouble(), "mV"),
            Champ("vbus3", "Bus 3 (I2C)", "Bus 3 (I2C)", vbus3.toDouble(), "mV"),
            Champ("vcpu", "Alimentation CPU", "CPU supply", vcpu.toDouble(), "mV"),
            Champ("icpu", "Courant CPU (entrée DC/DC)", "CPU current (DC/DC input)", icpu.toDouble(), "mA"),
            Champ("ipl", "Courant charge utile", "Payload current", ipl.toDouble(), "mA"),
            Champ("pa", "Panneau A (crête 3 min)", "Panel A (3 min peak)", (u8(d, 4) shl 1).toDouble(), "mW"),
            Champ("pb", "Panneau B (crête 3 min)", "Panel B (3 min peak)", (u8(d, 5) shl 1).toDouble(), "mW"),
            Champ("pc", "Panneau C (crête 3 min)", "Panel C (3 min peak)", (u8(d, 6) shl 1).toDouble(), "mW"),
            Champ("pd", "Panneau D (crête 3 min)", "Panel D (3 min peak)", (u8(d, 7) shl 1).toDouble(), "mW"),
            Champ("psol", "Puissance solaire", "Solar power", (u16(d, 8) shl 1).toDouble(), "mW"),
            Champ("signal", "Signal crête reçu", "Peak signal received", u8(d, 24).toDouble(), "dB"),
            Champ("bruit", "Bruit (modem)", "Noise (modem)", u8(d, 25).toDouble(), "dB"),
        )
        return Lecture(1, u32(d, 0), l)
    }

    private val CAPTEURS = listOf(
        Triple("pa", "Panneau A", "Panel A"), Triple("pb", "Panneau B", "Panel B"), Triple("pc", "Panneau C", "Panel C"),
        Triple("pd", "Panneau D", "Panel D"), null, Triple("eps", "Alimentation (EPS)", "Power board (EPS)"),
        Triple("tx", "Émetteur", "Transmitter"), Triple("tx2", "Émetteur (NTC)", "Transmitter (NTC)"),
        Triple("rx", "Récepteur", "Receiver"), Triple("cpu", "Processeur", "Processor"))

    private fun temperatures(d: ByteArray): Lecture =
        Lecture(2, u32(d, 0), CAPTEURS.mapIndexedNotNull { i, c -> c?.let { t(it.first, it.second, it.third, u8(d, 4 + i)) } })

    private fun plagesTemperatures(d: ByteArray): Lecture {
        val l = ArrayList<Champ>()
        CAPTEURS.forEachIndexed { i, c ->
            if (c == null) return@forEachIndexed
            l += t(c.first + "_min", c.second + " (min.)", c.third + " (min)", u8(d, 4 + i))
            l += t(c.first + "_max", c.second + " (max.)", c.third + " (max)", u8(d, 14 + i))
        }
        return Lecture(5, u32(d, 0), l)
    }

    private val BATTERIE_FR = listOf("pleine (≥ 3925 mV)", "très chargée (3800–3925 mV)", "chargée (3550–3800 mV, faible puissance)",
        "faible (3300–3550 mV)", "très faible (3200–3300 mV)", "extrêmement faible (2500–3200 mV)", "abîmée (< 2500 mV)")
    private val BATTERIE_EN = listOf("full (≥ 3925 mV)", "high (3800–3925 mV)", "charged (3550–3800 mV, low power)",
        "low (3300–3550 mV)", "very low (3200–3300 mV)", "extremely low (2500–3200 mV)", "damaged (< 2500 mV)")
    private val TRANSPONDEUR_FR = listOf("coupé", "FM", "FSK régénératif")
    private val TRANSPONDEUR_EN = listOf("off", "FM", "regenerative FSK")
    private val RESET_FR = listOf("inconnue", "basse énergie", "chien de garde (fenêtre)", "chien de garde (indépendant)", "logiciel",
        "mise sous tension", "broche de reset", "chute de tension")
    private val RESET_EN = listOf("unknown", "low power", "window watchdog", "independent watchdog", "software", "power-on", "reset pin", "brownout")

    private fun texte(cle: String, fr: String, en: String, xFr: String, xEn: String) = Champ(cle, fr, en, null, "", xFr, xEn)

    private fun etat(d: ByteArray): Lecture {
        val bat = u8(d, 14) ushr 4; val mode = u8(d, 14) and 0x0F
        val reset = u8(d, 13) and 0x0F
        val ss = u8(d, 15)
        val pa = when { ss and 1 == 0 -> "coupé" to "disabled"; ss and 2 != 0 -> "OK" to "OK"; else -> "en défaut" to "failed" }
        val ant = u8(d, 17)
        val antTexte = when (ant) { 0 -> "déployées" to "deployed"; 1 -> "non déployées" to "not deployed"; else -> "inconnu" to "unknown" }
        val l = listOf(
            texte("batterie", "Batterie", "Battery", BATTERIE_FR.getOrElse(bat) { "état $bat" }, BATTERIE_EN.getOrElse(bat) { "state $bat" }),
            texte("transpondeur", "Transpondeur", "Transponder", TRANSPONDEUR_FR.getOrElse(mode) { "mode $mode" }, TRANSPONDEUR_EN.getOrElse(mode) { "mode $mode" }),
            texte("antennes", "Antennes", "Antennas", antTexte.first, antTexte.second),
            texte("ampli", "Amplificateur", "Power amplifier", pa.first, pa.second),
            Champ("allume", "Depuis la dernière remise à zéro", "Since the last reset", u32(d, 4).toDouble(), "s"),
            Champ("demarrages", "Démarrages du processeur", "Processor starts", u16(d, 8).toDouble()),
            texte("reset", "Cause du dernier redémarrage", "Last reset cause", RESET_FR.getOrElse(reset) { "$reset" }, RESET_EN.getOrElse(reset) { "$reset" }),
            Champ("essaisAntennes", "Essais de déploiement", "Deployment attempts", u8(d, 11).toDouble()),
            Champ("chargeUtile", "Mises en route de la charge utile", "Payload starts", u8(d, 10).toDouble()),
            Champ("tachesManquees", "Tâches en retard", "Tasks missed", u8(d, 16).toDouble()),
            Champ("eeprom", "Erreurs EEPROM", "EEPROM errors", u8(d, 18).toDouble()),
            Champ("pRx", "Temps en réception", "Time receiving", u8(d, 27).toDouble(), "%"),
            Champ("pTlm", "Temps en télémesure", "Time in telemetry", u8(d, 28).toDouble(), "%"),
            Champ("pTransp", "Temps en transpondeur", "Time as transponder", u8(d, 29).toDouble(), "%"),
            Champ("pHp", "Émission forte puissance", "High power transmitting", u8(d, 30).toDouble(), "%"),
            Champ("pLp", "Émission faible puissance", "Low power transmitting", u8(d, 31).toDouble(), "%"),
            Champ("pVbat", "Batterie au-dessus du bus", "Battery above the bus", u8(d, 34).toDouble(), "%"),
            Champ("image", "Image SSDV en cours", "SSDV picture being sent", u8(d, 37).toDouble()),
        )
        return Lecture(3, u32(d, 0), l)
    }

    private fun plagesEnergie(d: ByteArray): Lecture {
        fun vAdc(w: Int) = (1400 * (w ushr 4) / 1000).toDouble()
        fun vBat(wa: Int, wb: Int) = (1400 * (((wa shl 8) and 0x0F00) or ((wb ushr 8) and 0xFF)) / 1000).toDouble()
        val l = listOf(
            Champ("vbus_min", "Bus (min.)", "Bus (min)", vAdc(u16(d, 4)), "mV"),
            Champ("vbus_max", "Bus (max.)", "Bus (max)", vAdc(u16(d, 15)), "mV"),
            Champ("vbat_min", "Batterie (min.)", "Battery (min)", vBat(u16(d, 4), u16(d, 6)), "mV"),
            Champ("vbat_max", "Batterie (max.)", "Battery (max)", vBat(u16(d, 15), u16(d, 17)), "mV"),
            Champ("vbat2_min", "Batterie I2C (min.)", "Battery I2C (min)", (u8(d, 11) * 64).toDouble(), "mV"),
            Champ("vbat2_max", "Batterie I2C (max.)", "Battery I2C (max)", (u8(d, 22) * 64).toDouble(), "mV"),
            Champ("ibat_charge", "Charge batterie (max.)", "Battery charge (max)", u8(d, 12).toDouble(), "mA"),
            Champ("ibat_decharge", "Décharge batterie (max.)", "Battery discharge (max)", u8(d, 23).toDouble(), "mA"),
            Champ("icpu_min", "Courant CPU (min.)", "CPU current (min)", s8(d, 13).toDouble(), "mA"),
            Champ("icpu_max", "Courant CPU (max.)", "CPU current (max)", s8(d, 24).toDouble(), "mA"),
            Champ("ipl_max", "Courant charge utile (max.)", "Payload current (max)", (u8(d, 25) shl 2).toDouble(), "mA"),
            Champ("rx_charge", "Batterie en réception : charge", "Battery receiving: charge", u8(d, 26).toDouble(), "mA"),
            Champ("rx_decharge", "Batterie en réception : décharge", "Battery receiving: discharge", u8(d, 27).toDouble(), "mA"),
            Champ("lp_decharge", "Batterie en émission faible : décharge", "Battery low power TX: discharge", u8(d, 29).toDouble(), "mA"),
            Champ("hp_decharge", "Batterie en émission forte : décharge", "Battery high power TX: discharge", u8(d, 31).toDouble(), "mA"),
        )
        return Lecture(4, u32(d, 0), l)
    }

    private fun antennes(d: ByteArray): Lecture {
        val l = listOf(
            Champ("voc", "Batterie à vide (côté charge utile)", "Battery open circuit (payload side)", u16(d, 0).toDouble(), "mV"),
            Champ("dv", "Chute de tension", "Voltage drop", s16(d, 2).toDouble(), "mV"),
            Champ("ibr", "Courant de chauffe du fil", "Burn wire current", u16(d, 4).toDouble(), "mA"),
            Champ("ibrpk", "Courant de chauffe (crête)", "Burn wire current (peak)", u16(d, 6).toDouble(), "mA"),
            Champ("voc2", "Batterie à vide (côté bus)", "Battery open circuit (bus side)", u16(d, 10).toDouble(), "mV"),
            Champ("duree", "Durée de chauffe", "Burn time", u16(d, 20).toDouble(), "s"),
            Champ("etat", "Contact (début / fin / maintenant)", "Switch (start / end / now)", null, "",
                "${u8(d, 22)} / ${u8(d, 23)} / ${u8(d, 24)}"),
            Champ("compteur", "Compteur", "Counter", u8(d, 26).toDouble()),
        )
        return Lecture(8, null, l)
    }

    private val INA = listOf("SPA" to "Panneau A", "SPB" to "Panneau B", "SPC" to "Panneau C", "SPD" to "Panneau D", "SUN" to "Solaire (total)",
        "BAT" to "Batterie", "BATP" to "Batterie (+)", "BATN" to "Batterie (−)", "CPU" to "Processeur", "PL" to "Charge utile")
    private val INA_EN = listOf("Panel A", "Panel B", "Panel C", "Panel D", "Solar (total)", "Battery", "Battery (+)", "Battery (−)", "Processor", "Payload")

    private fun energieEtendue(d: ByteArray): Lecture {
        val l = ArrayList<Champ>()
        for (n in 0 until 10) {
            val o = n * 12
            val (k, fr) = INA[n]; val en = INA_EN[n]
            l += Champ(k.lowercase() + "_v", "$fr : tension", "$en: voltage", s16(d, o).toDouble(), "mV")
            l += Champ(k.lowercase() + "_i", "$fr : courant", "$en: current", s16(d, o + 2).toDouble(), "mA")
            l += Champ(k.lowercase() + "_p", "$fr : puissance moyenne", "$en: mean power", s16(d, o + 4).toDouble(), "mW")
            l += Champ(k.lowercase() + "_pp", "$fr : puissance crête", "$en: peak power", s16(d, o + 10).toDouble(), "mW")
        }
        return Lecture(9, null, l)
    }

    private val VARIABLES = mapOf(0 to Triple("Signal crête", "Peak signal", "dB"), 1 to Triple("Bruit (modem)", "Noise (modem)", "dB"),
        2 to Triple("Batterie", "Battery", "mV"), 3 to Triple("Température du processeur", "Processor temperature", "°C"),
        4 to Triple("Température du panneau B", "Panel B temperature", "°C"), 5 to Triple("Température moyenne des panneaux", "Panels' mean temperature", "°C"))

    private fun serie(d: ByteArray): Lecture {
        val v = u8(d, 4)
        val (fr, en, u) = VARIABLES[v] ?: Triple("Variable $v", "Variable $v", "")
        val valeurs = (0 until 30).map { i ->
            val r = u8(d, 5 + i)
            when (v) {
                2 -> ((r shl 4) * 1400 / 1000).toDouble()
                3, 4, 5 -> temperature(r)
                else -> r.toDouble()
            }
        }
        val s = Serie(v, fr, en, u, valeurs)
        val dernier = valeurs.lastOrNull { it != null }
        return Lecture(14, u32(d, 0), listOf(Champ("serie", "$fr (dernière)", "$en (latest)", dernier, u),
            Champ("min", "$fr (min. sur 87 min)", "$en (min over 87 min)", valeurs.filterNotNull().minOrNull(), u),
            Champ("max", "$fr (max. sur 87 min)", "$en (max over 87 min)", valeurs.filterNotNull().maxOrNull(), u)), serie = s)
    }

    private fun ascii(d: ByteArray, de: Int, n: Int) =
        String(CharArray(n) { val c = u8(d, de + it); if (c in 32..126) c.toChar() else ' ' }).trim()

    private fun bbs(d: ByteArray): Lecture {
        val l = (0 until 5).map { i -> Bbs(ascii(d, i * 6, 6).trim('-', ' '), ascii(d, 30 + i * 7, 7), u8(d, 65 + i)) }
        val pleins = l.filter { it.indicatif.isNotBlank() }
        return Lecture(15, null, listOf(Champ("messages", "Messages", "Messages", pleins.size.toDouble())), bbs = l)
    }

    // ---------------------------------------------------------------- for the pages

    /** A short line: the values that matter most of the frame ([en]: in English). */
    fun resume(l: Lecture, en: Boolean): String {
        fun f(cle: String, nomFr: String, nomEn: String): String? {
            val c = l.champ(cle) ?: return null
            val t = c.texte(en) ?: c.v?.let { nombre(it, c.u) } ?: return null
            return (if (en) nomEn else nomFr) + " " + t
        }
        val parts = when (l.type) {
            1 -> listOf(f("vbat", "batterie", "battery")?.let { b -> l.champ("ibat")?.v?.let { i -> b + " (" + (if (i > 0) "+" else "") + nombre(i, "mA") + ")" } ?: b },
                f("psol", "solaire", "solar"))
            2 -> listOf(f("cpu", "CPU", "CPU"), f("eps", "EPS", "EPS"), f("tx", "TX", "TX"), f("pb", "panneau B", "panel B"))
            3 -> listOf(f("batterie", "batterie", "battery"), f("transpondeur", "transpondeur", "transponder"), f("antennes", "antennes", "antennas"))
            4 -> listOf(f("vbat_min", "batterie min.", "battery min"), f("vbat_max", "max.", "max"))
            5 -> listOf(f("cpu_min", "CPU min.", "CPU min"), f("cpu_max", "max.", "max"))
            8 -> listOf(f("ibr", "fil chauffé", "burn wire"), f("duree", "pendant", "for"), f("compteur", "compteur", "counter"))
            9 -> listOf(f("sun_p", "solaire", "solar"), f("bat_v", "batterie", "battery"))
            14 -> listOf(l.serie?.let { s -> (if (en) s.en else s.fr) + " " + (s.valeurs.lastOrNull { it != null }?.let { nombre(it, s.u) } ?: "—") })
            15 -> listOf(l.bbs.count { it.indicatif.isNotBlank() }.let { n -> if (en) "$n message" + (if (n > 1) "s" else "") else "$n message" + (if (n > 1) "s" else "") })
            else -> emptyList()
        }
        return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** [v] with its unit (French style decimals are the page's business: a dot here). */
    fun nombre(v: Double, u: String): String {
        val s = if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
        return if (u.isEmpty()) s else "$s $u"
    }

    fun json(l: Lecture, en: Boolean): JSONObject {
        val o = JSONObject().put("type", l.type).put("nom", l.nom(en)).put("resume", resume(l, en))
        l.horloge?.let { o.put("horloge", it) }
        o.put("champs", JSONArray(l.champs.map { c ->
            JSONObject().put("k", c.cle).put("l", c.libelle(en)).put("u", c.u).apply {
                c.texte(en)?.let { put("x", it) } ?: put("v", c.v ?: JSONObject.NULL)
            }
        }))
        l.serie?.let { s ->
            o.put("serie", JSONObject().put("nom", if (en) s.en else s.fr).put("u", s.u).put("pas", s.pasS)
                .put("v", JSONArray(s.valeurs.map { it ?: JSONObject.NULL })))
        }
        if (l.bbs.isNotEmpty()) o.put("bbs", JSONArray(l.bbs.map { b -> JSONObject().put("de", b.indicatif).put("m", b.message).put("voix", b.tramesVoix) }))
        return o
    }
}
