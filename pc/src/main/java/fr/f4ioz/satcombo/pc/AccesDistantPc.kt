/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A password, kept only as a salted PBKDF2-HMAC-SHA256 hash:
 * `pbkdf2-sha256$<iterations>$<salt>$<hash>` (base64, no padding).
 */
object MotDePasse {
    const val ITERATIONS = 210_000
    private val alea = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()

    fun hache(mdp: String, iterations: Int = ITERATIONS): String {
        val sel = ByteArray(16).also { alea.nextBytes(it) }
        return "pbkdf2-sha256$" + iterations + "$" + b64.encodeToString(sel) + "$" + b64.encodeToString(derive(mdp, sel, iterations))
    }

    /** True when [mdp] is the password [stocke] was made from (compared in constant time). */
    fun verifie(mdp: String, stocke: String): Boolean {
        val p = stocke.split('$')
        if (p.size != 4 || p[0] != "pbkdf2-sha256") return false
        val n = p[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val sel = runCatching { Base64.getDecoder().decode(p[2]) }.getOrNull() ?: return false
        val attendu = runCatching { Base64.getDecoder().decode(p[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(mdp, sel, n), attendu)
    }

    private fun derive(mdp: String, sel: ByteArray, n: Int): ByteArray {
        val spec = PBEKeySpec(mdp.toCharArray(), sel, n, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    /** Four French words drawn at random (about 32 bits, more than enough behind [LimiteEssais]). */
    fun phrase(mots: Int = 4): String = (1..mots).joinToString("-") { MOTS[alea.nextInt(MOTS.size)] }

    /** Plain words, no accents: typed on any keyboard. */
    internal val MOTS = ("abeille abri acier agneau aigle algue allure amande ambre ananas ancre angle anis antenne arbre arche argent " +
        "armoire astre atlas aube auberge aurore averse avion azur badge bague baie balcon balise bambou banane barque bassin bateau " +
        "berger biscuit blason bocal boheme bougie boussole branche brique brise bruine bulle buisson cabane cactus cadran caillou " +
        "calme campagne canal canard canoe capitaine caramel carnet carton cascade castor cerise chalet chameau champ chanson charbon " +
        "chardon chemin chene cheval chocolat cigale citron clairon clocher colline comete compas comptoir corail corde coton coucou " +
        "crayon crepe cristal cumulus cygne dauphin delta desert diamant domino dragon dune ecluse ecorce ecureuil emeraude encre " +
        "epice escalier etang etoile faucon fenetre fougere fanal falaise farine figue flamme fleuve flocon foret fontaine fusee " +
        "galet garenne gazelle geyser girafe givre glacier glycine gondole gorille goudron grenier grillon guitare hamac hameau " +
        "harpe herbe hibou horizon houblon igloo ile indigo jardin jasmin jonquille kayak koala lagune lampe lavande legume lezard " +
        "licorne lilas lion loutre lumiere lune lutin magnolia manege marais marelle marmotte melodie menthe meteore miel mimosa " +
        "moulin mouette muguet myrtille navire neige noisette nuage oasis ocean olive orage orange orchidee ours outil pagaie " +
        "palmier panda papillon parapluie pastel pelican pepite phare piano pigeon pinson planete plume poivre pollen pomme " +
        "prairie prisme puits quartz radar radeau raisin rameau rivage riviere roseau rubis ruisseau sabot sable safran sapin " +
        "satellite saule savane sentier signal silex sirop soleil source sphinx tambour tandem tempete terrasse tigre tilleul " +
        "tonnerre torrent toucan tournesol trefle tribord tulipe tunnel valise vallee vanille velours verger violon volcan voilier " +
        "wagon yaourt zebre zephyr").split(' ')
}

/**
 * Failed passwords counted per address: after 5, one minute to wait, then
 * twice as long after each 5 more (at most an hour). A success forgets them.
 */
class LimiteEssais(private val horloge: () -> Long = System::currentTimeMillis) {
    private class Compte(var echecs: Int = 0, var jusqua: Long = 0L, var dernier: Long = 0L)
    private val comptes = LinkedHashMap<String, Compte>()

    /** Milliseconds [ip] still has to wait before trying (0: may try now). */
    @Synchronized fun attente(ip: String): Long = (comptes[ip]?.jusqua ?: 0L).minus(horloge()).coerceAtLeast(0L)

    @Synchronized fun echec(ip: String) {
        val t = horloge()
        // Quiet for a day: forgotten. Never more than a thousand addresses remembered.
        comptes.entries.removeIf { t - it.value.dernier > JOUR_MS }
        if (comptes.size >= 1000 && ip !in comptes) comptes.remove(comptes.keys.first())
        val c = comptes.getOrPut(ip) { Compte() }
        c.echecs++; c.dernier = t
        if (c.echecs % SEUIL == 0) {
            val n = (c.echecs / SEUIL - 1).coerceAtMost(6)
            c.jusqua = t + minOf(BASE_MS shl n, MAX_MS)
        }
    }

    @Synchronized fun reussite(ip: String) { comptes.remove(ip) }

    companion object {
        const val SEUIL = 5
        const val BASE_MS = 60_000L
        const val MAX_MS = 3_600_000L
        private const val JOUR_MS = 86_400_000L
    }
}

/**
 * The sessions opened by the password: a random 256-bit token in an
 * HttpOnly cookie; only its SHA-256 kept, in memory and in [fichier], so a
 * restart keeps them (for [dureeMs] after the login).
 */
class SessionsPc(private val fichier: File?, private val horloge: () -> Long = System::currentTimeMillis,
                 private val dureeMs: Long = DUREE_MS) {
    private val sessions = LinkedHashMap<String, Long>()
    private val alea = SecureRandom()

    init {
        fichier?.takeIf { it.isFile }?.let { f ->
            runCatching {
                val t = horloge()
                f.readLines().forEach { l ->
                    val p = l.trim().split(' ')
                    val fin = p.getOrNull(1)?.toLongOrNull()
                    if (p.size == 2 && p[0].length == 64 && fin != null && fin > t) sessions[p[0]] = fin
                }
            }
        }
    }

    val nombre: Int @Synchronized get() { purge(); return sessions.size }

    /** A new session: its token (for the cookie), never stored as such. */
    @Synchronized fun cree(): String {
        val b = ByteArray(32).also { alea.nextBytes(it) }
        val jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        purge()
        while (sessions.size >= MAX) sessions.remove(sessions.keys.first())
        sessions[empreinte(jeton)] = horloge() + dureeMs
        ecrit()
        return jeton
    }

    @Synchronized fun valide(jeton: String?): Boolean {
        if (jeton.isNullOrEmpty() || jeton.length > 100) return false
        val fin = sessions[empreinte(jeton)] ?: return false
        return fin > horloge()
    }

    @Synchronized fun retire(jeton: String?) { if (!jeton.isNullOrEmpty() && sessions.remove(empreinte(jeton)) != null) ecrit() }

    /** Every session closed, but [sauf]'s. */
    @Synchronized fun retireTout(sauf: String? = null) {
        val garde = sauf?.takeIf { valide(it) }?.let { empreinte(it) }
        val fin = garde?.let { sessions[it] }
        sessions.clear()
        if (garde != null && fin != null) sessions[garde] = fin
        ecrit()
    }

    private fun purge() { val t = horloge(); sessions.entries.removeIf { it.value <= t } }

    private fun ecrit() {
        val f = fichier ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(sessions.entries.joinToString("") { it.key + " " + it.value + "\n" })
            prive(tmp)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val DUREE_MS = 30L * 86_400_000L
        private const val MAX = 64
        fun empreinte(jeton: String): String =
            MessageDigest.getInstance("SHA-256").digest(jeton.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Readable by its owner only (where the system allows). */
        internal fun prive(f: File) {
            runCatching { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
        }
    }
}

/**
 * The page opened to the network (`--reseau`) asks a password of every
 * other machine; this PC (127.0.0.1, ::1) stays free. The password's hash in
 * `acces-distant.properties`, the sessions in `acces-sessions.txt`.
 *
 * The first start with `--reseau` and no password draws one ([initial]),
 * written in SatMe-PC's log and shown in Réglages on this PC until another
 * is chosen.
 */
class AccesDistantPc(private val dossier: File, private val reseau: Boolean,
                     private val horloge: () -> Long = System::currentTimeMillis,
                     private val iterations: Int = MotDePasse.ITERATIONS) {
    private val fichier = File(dossier, "acces-distant.properties")
    private val props = Properties()
    val sessions = SessionsPc(File(dossier, "acces-sessions.txt"), horloge)
    val essais = LimiteEssais(horloge)

    init {
        if (fichier.isFile) runCatching { fichier.reader(Charsets.UTF_8).use { props.load(it) } }
    }

    /** A password is set: other machines may log in. */
    val defini: Boolean @Synchronized get() = !props.getProperty("hash").isNullOrEmpty()

    /** The password drawn at the first start, while it is still the one (else null). */
    val initial: String? @Synchronized get() = props.getProperty("initial")?.takeIf { it.isNotEmpty() && defini }

    /** When the password was set (ms), 0 when none. */
    val depuis: Long @Synchronized get() = props.getProperty("depuis")?.toLongOrNull() ?: 0L

    /**
     * At start: with `--reseau`, a password drawn the first time there is
     * none (once only: one removed later stays removed). Its line for the log, or null.
     */
    @Synchronized fun initialise(): String? {
        if (!reseau) return null
        if (!defini && props.getProperty("initialise") != "1") {
            val p = MotDePasse.phrase()
            props.setProperty("hash", MotDePasse.hache(p, iterations))
            props.setProperty("initial", p)
            props.setProperty("depuis", horloge().toString())
            props.setProperty("initialise", "1")
            ecrit()
        }
        return initial?.let { "  accès à distance : mot de passe « $it » (le changer dans Réglages › Accès à distance)" }
    }

    /** The password changed (8 characters at least); every other session closed. Null when done, else why not. */
    fun regle(mdp: String, sessionGardee: String? = null): String? {
        if (mdp.length < 8) return "8 caractères au moins"
        if (mdp.length > 200) return "200 caractères au plus"
        val h = MotDePasse.hache(mdp, iterations)
        synchronized(this) {
            props.setProperty("hash", h); props.remove("initial")
            props.setProperty("depuis", horloge().toString()); props.setProperty("initialise", "1")
            ecrit()
        }
        sessions.retireTout(sessionGardee)
        return null
    }

    /** No password any more: other machines get nothing, every session closed. */
    fun retire() {
        synchronized(this) { props.remove("hash"); props.remove("initial"); props.remove("depuis"); props.setProperty("initialise", "1"); ecrit() }
        sessions.retireTout()
    }

    sealed class Connexion {
        class Ouverte(val jeton: String) : Connexion()
        class Refusee(val raison: String) : Connexion()
    }

    /** A login from [ip]: a session, or why not (wrong password, too many tries, none set). */
    fun connecte(ip: String, mdp: String): Connexion {
        val h = synchronized(this) { props.getProperty("hash") }
        if (h.isNullOrEmpty()) return Connexion.Refusee(PAS_DEFINI)
        val attente = essais.attente(ip)
        if (attente > 0) return Connexion.Refusee(attendre(attente))
        if (mdp.isEmpty() || mdp.length > 200 || !MotDePasse.verifie(mdp, h)) {
            essais.echec(ip)
            val a = essais.attente(ip)
            return Connexion.Refusee(if (a > 0) "Mot de passe incorrect. " + attendre(a) else "Mot de passe incorrect.")
        }
        essais.reussite(ip)
        return Connexion.Ouverte(sessions.cree())
    }

    fun session(jeton: String?): Boolean = defini && sessions.valide(jeton)

    private fun ecrit() {
        runCatching {
            dossier.mkdirs()
            val tmp = File(dossier, fichier.name + ".tmp")
            tmp.writer(Charsets.UTF_8).use { props.store(it, "SatMe-PC : accès à distance (mot de passe haché)") }
            SessionsPc.prive(tmp)
            if (!tmp.renameTo(fichier)) { fichier.delete(); tmp.renameTo(fichier) }
        }
    }

    companion object {
        const val COOKIE = "satme_session"
        const val PAS_DEFINI = "Mot de passe à définir sur le PC (Réglages › Accès à distance)."
        fun attendre(ms: Long): String {
            val s = (ms + 999) / 1000
            return "Trop d'essais : réessayer dans " + (if (s >= 60) ((s + 59) / 60).toString() + " min" else "$s s") + "."
        }

        /** The cookie a session is carried in: never read by the page's script, never sent by another site. */
        fun cookie(jeton: String): String =
            "Set-Cookie: $COOKIE=$jeton; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + SessionsPc.DUREE_MS / 1000 + "\r\n"
        const val COOKIE_EFFACE = "Set-Cookie: $COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0\r\n"

        /** Where to go once logged in: a path of this site only. */
        fun suite(s: String?): String =
            s?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") && it.none { c -> c < ' ' } && it.length < 500 }
                ?.takeIf { !it.startsWith("/connexion") } ?: "/"
    }
}
