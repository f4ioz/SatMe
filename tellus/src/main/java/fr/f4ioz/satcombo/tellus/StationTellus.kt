/*
 * TellusMe — the SatMe ecosystem's terrestrial station
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.tellus

import fr.f4ioz.satcombo.cat.ModeleHf
import fr.f4ioz.satcombo.plateforme.Lignes
import fr.f4ioz.satcombo.plateforme.PortsSerie
import fr.f4ioz.satcombo.plateforme.Stockage
import fr.f4ioz.satcombo.sdr.hpsdr.HpsdrDecouverte
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * TellusMe's settings, in its own file (~/.tellusme/reglages.properties),
 * the way SatMe-PC keeps its own ([fr.f4ioz.satcombo.pc.StockageFichier]).
 */
class ReglagesTellus(private val s: Stockage) {
    var indicatif: String get() = s.getString("indicatif", "")!!; set(v) = put { putString("indicatif", v.trim().uppercase()) }
    var locator: String get() = s.getString("locator", "")!!; set(v) = put { putString("locator", v.trim().uppercase()) }
    /** "AUCUN", "IC7000" or "BRICK2". */
    var poste: String get() = s.getString("poste", "AUCUN")!!; set(v) = put { putString("poste", v) }
    /** The serial port's key (see [fr.f4ioz.satcombo.cat.IdentiteUsb]), "" until chosen. */
    var portCat: String get() = s.getString("cat.port", "")!!; set(v) = put { putString("cat.port", v) }
    var vitesseCat: Int get() = s.getInt("cat.vitesse", ModeleHf.IC7000.vitesses.first()); set(v) = put { putInt("cat.vitesse", v) }
    var adresseCiv: Int get() = s.getInt("cat.adresse", ModeleHf.IC7000.adresse); set(v) = put { putInt("cat.adresse", v) }
    /** The Brick2's address; "" to find it (on request, by a broadcast on the LAN). */
    var brickHote: String get() = s.getString("brick.hote", "")!!; set(v) = put { putString("brick.hote", v.trim()) }
    var brickPort: Int get() = s.getInt("brick.port", 1024); set(v) = put { putInt("brick.port", v) }
    var brickDebit: Int get() = s.getInt("brick.debit", 48_000); set(v) = put { putInt("brick.debit", v) }
    var clusterHote: String get() = s.getString("cluster.hote", "dxfun.com")!!; set(v) = put { putString("cluster.hote", v.trim()) }
    var clusterPort: Int get() = s.getInt("cluster.port", 8000); set(v) = put { putInt("cluster.port", v) }
    var clusterActif: Boolean get() = s.getBoolean("cluster.actif", false); set(v) = put { putBoolean("cluster.actif", v) }
    var vfoHz: Long get() = s.getLong("vfo.hz", 7_074_000L); set(v) = put { putLong("vfo.hz", v) }
    var vfoMode: String get() = s.getString("vfo.mode", "USB")!!; set(v) = put { putString("vfo.mode", v) }

    private inline fun put(f: Stockage.Edition.() -> Unit) { s.edit().apply { f(); apply() } }

    fun json(): JSONObject = JSONObject().put("indicatif", indicatif).put("locator", locator).put("poste", poste)
        .put("portCat", portCat).put("vitesseCat", vitesseCat).put("adresseCiv", "%02X".format(adresseCiv))
        .put("brickHote", brickHote).put("brickPort", brickPort).put("brickDebit", brickDebit)
        .put("clusterHote", clusterHote).put("clusterPort", clusterPort).put("clusterActif", clusterActif)
}

/**
 * The station as the page sees it: one rig at a time (IC-7000, Brick2 or
 * none — a VFO typed by hand, for the logbook), its frequency, mode and
 * S-meter. **Receive only**: nothing here keys a transmitter, and both
 * drivers carry their own guard ([LienReception], [fr.f4ioz.satcombo.sdr.hpsdr.GardeHpsdr]).
 */
class StationTellus(
    val reglages: ReglagesTellus,
    private val ports: PortsSerie,
    /** Discovery (a broadcast reaches the LAN: only on the operator's request). */
    private val decouvre: (String, Int, Boolean) -> HpsdrDecouverte.Radio = { h, p, d -> HpsdrDecouverte.cherche(h, p, d) },
    private val pacingMs: Long = 40L,
) {
    @Volatile var ic: PosteIc7000? = null; private set
    val brick = SessionBrick(reglages.brickHote, reglages.brickPort)
    @Volatile var derniereRadio: HpsdrDecouverte.Radio? = null; private set
    @Volatile private var actif = true

    val modele: ModeleHf? get() = ModeleHf.de(reglages.poste)

    init {
        brick.regle(reglages.vfoHz.takeIf { it in 100_000L..60_000_000L }, reglages.brickDebit)
        brick.regleAudio(mode = reglages.vfoMode.takeIf { it in DemodBrick.MODES })
        choisis(reglages.poste)
        Thread({ while (actif) { runCatching { sonde() }; Thread.sleep(300) } }, "tellus-cat").apply { isDaemon = true; start() }
    }

    private fun sonde() {
        val p = ic ?: return
        runBlocking { p.sonde() }
        p.frequence?.let { if (it != reglages.vfoHz) reglages.vfoHz = it }
    }

    /** The rig in use: "AUCUN", "IC7000" or "BRICK2". The previous one is let go (the Brick2 told to stop). */
    @Synchronized fun choisis(id: String) {
        val m = ModeleHf.de(id)
        reglages.poste = m?.id ?: "AUCUN"
        ic?.ferme(); ic = null
        if (m != ModeleHf.BRICK2) brick.arrete()
        if (m == ModeleHf.IC7000) {
            val p = PosteIc7000({ ouvrePort() }, reglages.adresseCiv, pacingMs)
            ic = p
            p.ouvre()
        }
        if (m == ModeleHf.BRICK2) { brick.hote = reglages.brickHote; brick.port = reglages.brickPort }
    }

    /** The cable the operator chose, RTS and DTR **low**: on many interfaces they key the transmitter. */
    private fun ouvrePort(): fr.f4ioz.satcombo.cat.SerialLink? {
        val cle = reglages.portCat.ifEmpty { return null }
        val p = ports.liste().firstOrNull { it.cle == cle } ?: return null
        return runBlocking { ports.ouvre(p, reglages.vitesseCat, Lignes.BASSES) }.lien
    }

    fun ports(): JSONArray = JSONArray(ports.liste().map { JSONObject().put("cle", it.cle).put("libelle", it.libelle) })

    val frequence: Long get() = when (modele) {
        ModeleHf.IC7000 -> ic?.frequence ?: reglages.vfoHz
        ModeleHf.BRICK2 -> brick.freq
        else -> reglages.vfoHz
    }
    val mode: String get() = when (modele) {
        ModeleHf.IC7000 -> ic?.mode ?: reglages.vfoMode
        ModeleHf.BRICK2 -> brick.demod.mode
        else -> reglages.vfoMode
    }

    fun accorde(hz: Long): Boolean {
        val ok = when (modele) {
            ModeleHf.IC7000 -> runBlocking { ic?.accorde(hz) ?: false }
            ModeleHf.BRICK2 -> if (ModeleHf.BRICK2.recoit(hz)) { brick.regle(hz = hz); true } else false
            else -> hz in 100_000L..500_000_000L
        }
        if (ok) reglages.vfoHz = hz
        return ok
    }

    fun regleMode(m: String): Boolean {
        val n = m.uppercase()
        val ok = when (modele) {
            ModeleHf.IC7000 -> runBlocking { ic?.regleMode(n) ?: false }
            ModeleHf.BRICK2 -> if (n in DemodBrick.MODES) { brick.regleAudio(mode = n); true } else false
            else -> n in PosteIc7000.CODES_MODES
        }
        if (ok) reglages.vfoMode = n
        return ok
    }

    /** Finds the Brick2: at its address, or by a broadcast when none is set (the operator asked). */
    fun chercheBrick(): HpsdrDecouverte.Radio {
        val r = decouvre(reglages.brickHote, reglages.brickPort, reglages.brickHote.isEmpty())
        derniereRadio = r
        if (r.enLigne && reglages.brickHote.isEmpty()) { brick.hote = r.ip; brick.port = r.port }
        return r
    }

    fun modes(): List<String> = when (modele) {
        ModeleHf.BRICK2 -> listOf("LSB", "USB", "CW", "AM", "FM")
        else -> listOf("LSB", "USB", "CW", "CW-R", "AM", "FM", "RTTY")
    }

    fun json(): JSONObject {
        val m = modele
        val hz = frequence
        val o = JSONObject().put("poste", m?.id ?: "AUCUN").put("libelle", m?.libelle ?: "Aucun poste (VFO manuel)")
            .put("hz", hz).put("mode", mode).put("modes", JSONArray(modes()))
            .put("bandes", JSONArray(m?.bandes ?: PlanHf.BANDES.map { it.nom }))
            .put("bande", PlanHf.bande(hz)?.nom ?: "")
            .put("reception", true)
        PlanHf.segment(hz)?.let { o.put("segment", it.nom).put("famille", PlanHf.famille(it.usage)) }
        when (m) {
            ModeleHf.IC7000 -> {
                val p = ic
                o.put("connecte", p?.connecte == true).put("erreur", p?.erreur ?: "")
                    .put("smetre", PosteIc7000.texteS(p?.smetre)).put("smetreBrut", p?.smetre ?: JSONObject.NULL)
                    .put("smetreFraction", (p?.smetre ?: 0) / 241.0).put("refus", p?.refuses ?: 0)
                    .put("reconnexions", p?.reconnexions ?: 0)
            }
            ModeleHf.BRICK2 -> {
                val db = brick.smetre
                o.put("connecte", brick.enMarche).put("erreur", brick.erreur ?: "").put("smetre", "%.0f dBFS".format(java.util.Locale.ROOT, db))
                    .put("smetreFraction", ((db + 130.0) / 100.0).coerceIn(0.0, 1.0)).put("refus", brick.refuses)
                    .put("debit", brick.debit).put("audio", brick.audio).put("trames", brick.trames)
                derniereRadio?.let { r -> o.put("radio", JSONObject().put("enLigne", r.enLigne).put("ip", r.ip).put("carte", r.carte)
                    .put("firmware", r.firmware).put("occupee", r.occupee).put("mac", r.mac).put("erreur", r.erreur)) }
            }
            else -> o.put("connecte", false).put("erreur", "").put("smetre", "—").put("smetreFraction", 0)
        }
        return o
    }

    fun arrete() { actif = false; ic?.ferme(); brick.arrete() }
}
