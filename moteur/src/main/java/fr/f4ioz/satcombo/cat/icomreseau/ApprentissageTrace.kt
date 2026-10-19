/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.cat.icomreseau

/**
 * The credentials' coding learnt from the owner's own capture of a login:
 * the host's login packets (to the control port) give the coded user and
 * password, the clear ones come from the settings, and the radio's reply
 * says whether that login was accepted.
 *
 * Secrets stay in here: [Bilan.message] and the counts never carry a coded
 * byte nor a clear character; the coded fields and the table are only for
 * the settings.
 */
object ApprentissageTrace {

    enum class Reponse { ACCEPTEE, REFUSEE, INCONNUE }

    /** What the page may be told: [message], counts, places. The rest is for the settings only. */
    class Bilan(
        val ok: Boolean,
        val message: String,
        val connexions: Int = 0,
        val reponse: Reponse = Reponse.INCONNUE,
        val appris: Int = 0,
        /** Places (0…15) at which no character is known yet. */
        val placesManquantes: List<Int> = emptyList(),
        internal val utilisateurCode: ByteArray? = null,
        internal val mdpCode: ByteArray? = null,
        internal val table: String? = null,
    ) {
        /** The coded user, the coded password (hex) and the table to keep; null when nothing is to be kept. */
        fun aGarder(): Triple<String, String, String>? =
            if (ok && utilisateurCode != null && mdpCode != null && table != null)
                Triple(CodageIdentifiants.versHex(utilisateurCode), CodageIdentifiants.versHex(mdpCode), table) else null

        override fun toString() = "Bilan(ok=$ok, connexions=$connexions, reponse=$reponse, appris=$appris)"
    }

    private class Connexion(val client: Int, val portClient: Int, val demande: Int,
                            val utilisateur: ByteArray, val mdp: ByteArray, var reponse: Reponse = Reponse.INCONNUE)

    private fun estConnexion(b: ByteArray): Boolean {
        if (b.size != Paquets.CONNEXION_LONGUEUR) return false
        val e = Paquets.entete(b) ?: return false
        return e.type == Paquets.T_DONNEES && e.longueur == Paquets.CONNEXION_LONGUEUR &&
            b[Paquets.J_SENS].toInt() == Paquets.SENS_DEMANDE && b[Paquets.J_GENRE].toInt() == Paquets.G_CONNEXION
    }

    /** The radio's verdict on a login, when [b] is its reply. */
    private fun verdict(b: ByteArray): Pair<Int, Reponse>? {
        val j = Paquets.jeton(b) ?: return null
        if (j.genre != Paquets.G_CONNEXION || j.sens != Paquets.SENS_REPONSE) return null
        return j.demande to (if (j.erreur == 0) Reponse.ACCEPTEE else Reponse.REFUSEE)
    }

    /** Non-zero bytes at the start of a zero-padded field. */
    private fun longueur(c: ByteArray): Int { val n = c.indexOfFirst { it.toInt() == 0 }; return if (n < 0) c.size else n }

    fun apprends(trace: ByteArray, portControle: Int, utilisateur: String, mdp: String, tableAvant: String): Bilan {
        if (utilisateur.isEmpty() || mdp.isEmpty())
            return Bilan(false, "tape d'abord l'identifiant et le mot de passe en clair puis « Enregistrer » : SatMe les compare à la trace")
        val paquets = TracePcap.udp(trace) ?: return Bilan(false, "fichier non reconnu (trace .pcap ou .pcapng attendue)")
        val connexions = ArrayList<Connexion>()
        for (p in paquets) {
            val b = p.donnees
            if (p.portDestination == portControle && estConnexion(b)) {
                connexions += Connexion(p.source, p.portSource, Paquets.u16le(b, Paquets.J_DEMANDE_JETON),
                    b.copyOfRange(Paquets.C_UTILISATEUR, Paquets.C_UTILISATEUR + Paquets.CHAMP),
                    b.copyOfRange(Paquets.C_MOT_DE_PASSE, Paquets.C_MOT_DE_PASSE + Paquets.CHAMP))
            } else if (p.portSource == portControle) {
                val (demande, rep) = verdict(b) ?: continue
                // The latest login of that host that still waits for its answer.
                connexions.lastOrNull { it.client == p.destination && it.portClient == p.portDestination &&
                    it.demande == demande && it.reponse == Reponse.INCONNUE }?.reponse = rep
            }
        }
        if (connexions.isEmpty()) return Bilan(false,
            "aucune connexion au poste dans la trace (paquets de 0x80 octets vers le port $portControle) : capture le début d'une connexion")
        val n = connexions.size
        // The last accepted login; else the last whose answer is not in the trace; a refused one teaches nothing.
        val c = connexions.lastOrNull { it.reponse == Reponse.ACCEPTEE } ?: connexions.lastOrNull { it.reponse == Reponse.INCONNUE }
            ?: return Bilan(false, "$n connexion(s) dans la trace, toutes refusées par le poste : rien appris", n, Reponse.REFUSEE)
        val u = utilisateur.toByteArray(Charsets.ISO_8859_1)
        val m = mdp.toByteArray(Charsets.ISO_8859_1)
        if (longueur(c.utilisateur) != u.size || longueur(c.mdp) != m.size)
            return Bilan(false, "l'identifiant ou le mot de passe tapé n'a pas la longueur de celui de la trace : vérifie-les", n, c.reponse)
        val table = CodageIdentifiants().apply { lis(tableAvant) }
        val avant = table.taille
        if (!table.apprends(utilisateur, c.utilisateur) || !table.apprends(mdp, c.mdp))
            return Bilan(false, "la trace ne s'accorde pas avec ce que SatMe avait déjà appris (identifiants tapés différents ?) : rien changé", n, c.reponse)
        val msg = when (c.reponse) {
            Reponse.ACCEPTEE -> "appris d'une connexion acceptée par le poste"
            Reponse.REFUSEE -> ""
            Reponse.INCONNUE -> "appris ; réponse du poste non reconnue"
        }
        return Bilan(true, msg, n, c.reponse, table.taille - avant, table.placesVides(),
            c.utilisateur, c.mdp, table.ecrit())
    }
}
