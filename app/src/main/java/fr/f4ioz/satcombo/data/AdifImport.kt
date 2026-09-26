/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import fr.f4ioz.satcombo.domain.Indicatifs
import java.util.Calendar
import java.util.TimeZone

/**
 * Lire un ADIF, et n'en garder que ce qui sert à deviner.
 *
 * C'est la moitié « depuis Wavelog » du va-et-vient, et elle est délibérément
 * maigre : **on ne rapatrie pas les QSO**. On rapatrie un index de prédiction —
 * indicatif, carré, nombre de contacts, date du dernier. Dix mille contacts y
 * tiennent en quelques centaines de kilo-octets, se chargent en mémoire au
 * démarrage et se cherchent en microsecondes.
 *
 * Rapatrier les contacts entiers donnerait une seconde source de vérité à
 * réconcilier avec la première, pour aucun bénéfice : le carnet de référence
 * reste local, et ceci n'est qu'un cache de ce que le serveur sait de plus que
 * lui.
 *
 * L'analyseur est volontairement tolérant. Un ADIF sorti d'un carnet tiers
 * comporte toujours quelque chose d'inattendu — un champ de longueur fausse,
 * un en-tête bavard, une casse improbable — et un import qui échoue en entier
 * sur un enregistrement bancal ne sert personne. Ce qui ne se lit pas est
 * compté et sauté.
 */
object AdifImport {

    /** Ce qu'un import a donné, et ce qu'il a laissé de côté. */
    class Bilan(
        val contacts: List<Indicatifs.Contact>,
        val enregistrementsLus: Int,
        val sansIndicatif: Int,
        val sansDate: Int,
    ) {
        val retenus: Int get() = contacts.size

        /**
         * Le nombre d'**indicatifs distincts**, et non de contacts.
         *
         * C'est le seul chiffre qui décrive ce que le clavier a gagné : trente-
         * huit QSO avec le même correspondant n'ajoutent qu'une entrée à sa
         * mémoire. Annoncer les contacts laisserait croire à un enrichissement
         * qui n'a pas eu lieu.
         */
        val indicatifs: Int get() = contacts.distinctBy { it.indicatif }.size

        /** Écartés faute d'indicatif ou de date : ce qui est perdu, et pourquoi. */
        val ecartes: Int get() = sansIndicatif + sansDate
    }

    /**
     * Un champ ADIF : `<TAG:longueur>valeur`, avec un type facultatif que l'on
     * ignore. La longueur fait foi, y compris quand la valeur contient des
     * espaces ou des chevrons.
     */
    private val CHAMP = Regex("<([A-Za-z0-9_]+):(\\d+)(?::[A-Za-z])?>", RegexOption.IGNORE_CASE)

    /** Découpe un enregistrement en couples étiquette → valeur. */
    fun champs(enregistrement: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (true) {
            val m = CHAMP.find(enregistrement, i) ?: break
            val nom = m.groupValues[1].uppercase()
            val longueur = m.groupValues[2].toIntOrNull() ?: 0
            val debut = m.range.last + 1
            // Une longueur qui déborde du texte : on prend ce qui reste plutôt
            // que de lever. Le champ est probablement tronqué, mais un
            // indicatif tronqué vaut mieux qu'un import perdu.
            val fin = (debut + longueur).coerceAtMost(enregistrement.length)
            if (longueur > 0) out[nom] = enregistrement.substring(debut, fin)
            i = fin
        }
        return out
    }

    /**
     * Date et heure ADIF vers un instant.
     *
     * `QSO_DATE` fait huit chiffres, `TIME_ON` six ou quatre. Tout est en temps
     * universel par définition du format, et c'est la seule lecture correcte :
     * interpréter dans le fuseau du téléphone décalerait tout l'historique d'une
     * ou deux heures selon la saison.
     */
    fun instant(date: String, heure: String): Long? {
        val d = date.trim()
        if (d.length != 8 || !d.all { it.isDigit() }) return null
        val brut = heure.trim()
        if (brut.length != 4 && brut.length != 6) return null
        if (!brut.all { it.isDigit() }) return null
        val h = brut.padEnd(6, '0')
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(
            d.substring(0, 4).toInt(),
            d.substring(4, 6).toInt() - 1,
            d.substring(6, 8).toInt(),
            h.substring(0, 2).toInt(),
            h.substring(2, 4).toInt(),
            h.substring(4, 6).toInt(),
        )
        return c.timeInMillis
    }

    /**
     * Lit un fichier entier.
     *
     * @param satellitesSeulement ne garder que les contacts marqués
     *   `PROP_MODE=SAT`. Vrai par défaut : la mémoire prédictive sert pendant un
     *   passage, et les correspondants d'un contest VHF n'y ont rien à faire —
     *   ils ne feraient que pousser vers le bas ceux qu'on va vraiment entendre.
     */
    fun lit(
        texte: String,
        filtre: String = fr.f4ioz.satcombo.domain.FiltreMoisson.SAT,
    ): Bilan {
        // L'en-tête se termine par <EOH> quand il existe ; sans en-tête, tout
        // le fichier est du corps.
        val corps = texte.split(Regex("<EOH>", RegexOption.IGNORE_CASE)).let {
            if (it.size > 1) it.drop(1).joinToString("<EOH>") else it[0]
        }

        val out = ArrayList<Indicatifs.Contact>()
        var lus = 0
        var sansIndicatif = 0
        var sansDate = 0

        corps.split(Regex("<EOR>", RegexOption.IGNORE_CASE)).forEach { bloc ->
            if (!bloc.contains('<')) return@forEach
            val f = champs(bloc)
            if (f.isEmpty()) return@forEach
            lus++

            // Le tri retenu par l'opérateur. Wavelog ne sait filtrer que la
            // bande satellite chez lui ; tout le reste se trie ici.
            if (!fr.f4ioz.satcombo.domain.FiltreMoisson.retient(
                    filtre, f["PROP_MODE"].orEmpty(),
                    f["MODE"].orEmpty(), f["BAND"].orEmpty())
            ) return@forEach

            val call = f["CALL"].orEmpty().trim().uppercase()
            if (call.isEmpty()) { sansIndicatif++; return@forEach }

            val quand = instant(f["QSO_DATE"].orEmpty(), f["TIME_ON"].orEmpty())
            if (quand == null) { sansDate++; return@forEach }

            out.add(
                Indicatifs.Contact(
                    indicatif = call,
                    locator = f["GRIDSQUARE"].orEmpty().trim().uppercase(),
                    quandMs = quand,
                    satellite = f["SAT_NAME"].orEmpty().trim(),
                    // 3668 des 3763 contacts du carnet d'Olivier portent un
                    // nom. C'est le champ le plus rentable de l'import après le
                    // carré : il ne coûte rien à garder et se lit d'un coup
                    // d'œil pendant un passage.
                    nom = f["NAME"].orEmpty().trim(),
                )
            )
        }
        return Bilan(out, lus, sansIndicatif, sansDate)
    }
}
