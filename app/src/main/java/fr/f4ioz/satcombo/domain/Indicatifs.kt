/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Les indicatifs : les reconnaître, les deviner, ne jamais les refuser.
 *
 * Le calcul qui commande tout le reste : sur un gros passage de RS-44, dix
 * contacts en douze minutes font un correspondant toutes les soixante-dix
 * secondes. Nommer avec le clavier système coûte quinze à vingt secondes,
 * fautes de frappe comprises, et ces secondes-là tombent pendant qu'on appelle
 * le suivant — d'où les validations à vide, c'est-à-dire des indicatifs connus
 * à l'instant et perdus pour toujours. Avec trois caractères et une proposition,
 * on tombe à quatre appuis et trois secondes. Le temps existait ; c'est la
 * saisie qui le mangeait.
 *
 * Un principe traverse tout le fichier et ne souffre aucune exception : **rien
 * ici ne bloque une saisie**. La plausibilité est une couleur, pas une barrière.
 * L'indicatif que le format rejette est justement le DX rare pour lequel on a
 * sorti l'antenne, et une application qui l'empêche de rentrer dans le carnet
 * n'a pas compris à quoi sert un carnet.
 */
object Indicatifs {

    /** Les suffixes que fait défiler la touche unique du clavier. */
    val SUFFIXES: List<String> = listOf("", "/P", "/M", "/MM")

    /** Suffixe suivant dans le cycle, pour la touche « / ». */
    fun suffixeSuivant(actuel: String): String {
        val i = SUFFIXES.indexOf(actuel.uppercase())
        return if (i < 0) SUFFIXES[1] else SUFFIXES[(i + 1) % SUFFIXES.size]
    }

    /**
     * Sépare l'indicatif de son suffixe d'exploitation.
     *
     * Seuls les suffixes d'exploitation sont détachés. `FG/F4IOZ` a un préfixe,
     * pas un suffixe : c'est un autre pays, donc un autre indicatif, et le
     * fondre avec `F4IOZ` mélangerait deux entités dans les statistiques.
     */
    fun separe(brut: String): Pair<String, String> {
        val net = brut.trim().uppercase()
        if (net.isEmpty()) return "" to ""
        // On regarde la DERNIÈRE barre, et non le nombre de morceaux.
        //
        // L'ancienne lecture exigeait exactement deux morceaux, et laissait donc
        // passer `LA/DF2ET/P` en bloc : préfixe de pays **et** suffixe
        // d'exploitation à la fois. Le carnet d'Olivier en compte dix-huit —
        // `TF/M0NKC/P`, `EA6/DF2ET/P`, `F/DF2ET/P` — et pour chacun le `/P`
        // passait inaperçu. Conséquence directe : le carré de la station fixe
        // était hérité par la station portable, c'est-à-dire précisément le seul
        // carré dont on sait qu'il est faux.
        val i = net.lastIndexOf('/')
        if (i <= 0) return net to ""
        val queue = net.substring(i)
        return if (queue in SUFFIXES) net.substring(0, i) to queue else net to ""
    }

    /**
     * Où se pose une lettre frappée au clavier.
     *
     * Deux gestes produisent une barre, et ils veulent dire le contraire l'un
     * de l'autre :
     *
     * - La touche `/P /M` **pose un suffixe d'exploitation**. Ce suffixe reste
     *   collé au bout, et les lettres tapées ensuite complètent l'indicatif
     *   devant lui : sur `F4IOZ/P`, un `X` donne `F4IOZX/P`. C'est ce qui
     *   permet de poser le portable dès qu'on l'entend, sans attendre la fin
     *   de l'indicatif.
     * - La touche `/` **ouvre un préfixe de pays**. Ce qui suit appartient à
     *   l'indicatif lui-même et s'écrit dans l'ordre où on l'entend.
     *
     * Sans [suffixePose] pour les distinguer, la seule lecture disponible est
     * celle du texte — et `DL/P` s'y lit comme `DL` en portable. Toutes les
     * lettres suivantes passaient alors devant : `DL/PA3GAN`, tapé dans le bon
     * ordre, s'écrivait `DLA3GAN/P`. Le texte ne peut pas trancher, parce que
     * les deux gestes produisent exactement le même texte ; seul le geste le
     * sait.
     */
    fun ajoute(saisie: String, lettre: Char, suffixePose: Boolean): String {
        if (!suffixePose) return saisie + lettre
        // Le suffixe posé sur un champ vide se retrouve seul, et `separe` le
        // rend alors comme base : un indicatif ne commence pas par une barre,
        // et cette lecture est la bonne pour un texte qu'on découvre. Mais ici
        // on ne découvre rien — la touche vient de poser ce suffixe. Sans ce
        // cas, poser le portable dès qu'on l'entend puis taper l'indicatif
        // donnait `/PF4IOZ`.
        if (saisie.uppercase() in SUFFIXES) return lettre + saisie
        val (b, suf) = separe(saisie)
        return b + lettre + suf
    }

    fun base(brut: String): String = separe(brut).first

    fun suffixe(brut: String): String = separe(brut).second

    /**
     * La clé sous laquelle un indicatif est mémorisé.
     *
     * On range sous la base : c'est ainsi qu'on retrouve `F4HRJ` en tapant
     * `F4H` alors que le contact d'hier était `F4HRJ/P`.
     */
    fun cle(brut: String): String = separe(brut).let { it.first + it.second }

    /**
     * Le préfixe, au sens où on l'entend en radio : tout ce qui précède le
     * dernier chiffre, celui-ci compris.
     *
     * La lecture naïve — « les lettres, puis les chiffres » — se trompe sur
     * tous les préfixes qui commencent par un chiffre : 9A pour la Croatie, 2E
     * pour l'Angleterre, 3DA pour l'Eswatini. Elle rendait « 9 » pour 9A3XYZ.
     * Le dernier chiffre est le seul repère qui tienne dans les deux cas.
     *
     * Sert à deviner l'entité et à juger la plausibilité, jamais à interdire.
     */
    fun prefixe(brut: String): String {
        val b = base(brut)
        val dernierChiffre = b.indexOfLast { it.isDigit() }
        return if (dernierChiffre < 0) b else b.take(dernierChiffre + 1)
    }

    /**
     * Structure plausible d'un indicatif amateur : au moins une lettre, au
     * moins un chiffre, un suffixe d'au moins une lettre après le dernier
     * chiffre, et rien qui ne soit lettre, chiffre ou barre.
     *
     * Volontairement large. Un jugement plus serré rejetterait des indicatifs
     * parfaitement réguliers — les indicatifs spéciaux d'événement, les
     * préfixes à deux chiffres — et l'on aurait gagné une rigueur inutile
     * contre une porte fermée au mauvais moment.
     */
    fun plausible(brut: String): Boolean {
        val b = base(brut)
        if (b.length < 3) return false
        if (!b.all { it.isLetterOrDigit() }) return false
        if (b.none { it.isDigit() } || b.none { it.isLetter() }) return false
        val dernierChiffre = b.indexOfLast { it.isDigit() }
        if (dernierChiffre == b.length - 1) return false
        return b.drop(dernierChiffre + 1).all { it.isLetter() }
    }

    /** Un carré vu chez un correspondant, avec ce qu'on en sait. */
    class LocatorVu(
        val locator: String,
        val contacts: Int,
        val dernierMs: Long,
    )

    /** Ce que la mémoire retient d'un correspondant. */
    class Connu(
        val indicatif: String,
        val contacts: Int,
        val dernierMs: Long,
        val locators: List<LocatorVu> = emptyList(),
        val dernierSat: String = "",
        /**
         * Le nom du correspondant, quand le carnet importé le connaît.
         *
         * Il ne sert à rien au calcul et à tout à l'usage : reconnaître
         * « Olivier » d'un coup d'œil vaut mieux que relire cinq caractères, et
         * c'est ce qui permet de valider sans hésiter pendant un passage.
         */
        val nom: String = "",
    ) {
        /**
         * Le carré à proposer : **le plus fréquent**, la date ne départageant
         * qu'à égalité.
         *
         * C'était l'inverse — le plus récent gagnait — et une poignée de
         * contacts fautifs suffisait à évincer une vérité établie. Cas réel :
         * F5RRO, dix-sept contacts en JN18FR au journal, quelques-uns écrits
         * par erreur en JN33AF pendant que la suggestion fuyait entre l'entrée
         * de base et l'exploitation portable — l'application proposait JN33AF
         * à chaque nouveau contact, et l'erreur se recopiait d'elle-même.
         *
         * La fréquence résiste à l'accident ; la date ne résiste à rien. Une
         * station qui déménage vraiment finira par l'emporter, contact après
         * contact, ce qui est le bon rythme pour un changement rare.
         */
        val locatorPrincipal: String
            get() = locators.maxWithOrNull(
                compareBy<LocatorVu> { it.contacts }.thenBy { it.dernierMs }
            )?.locator.orEmpty()
    }

    /**
     * D'où vient le carré inscrit dans un contact.
     *
     * Enregistré au moment de la validation, et impossible à reconstituer après
     * coup. Le jour où un correspondant dit « je n'étais pas en JN18 », c'est ce
     * champ qui dit si c'est la base qui a menti ou la frappe qui a fauté.
     */
    enum class OrigineLocator { SAISI, PROPOSE, INCONNU }

    /**
     * Le carré à pré-remplir, ou vide s'il ne faut rien proposer.
     *
     * Le cas du suffixe est le seul qui compte vraiment : `F4HRJ/P` ne doit
     * **jamais** hériter du carré de `F4HRJ`. Le `/P` dit précisément que le
     * correspondant s'est déplacé ; hériter reviendrait à inscrire avec
     * assurance le seul carré dont on sait qu'il est faux.
     */
    fun locatorPropose(connu: Connu?, brutSaisi: String): String {
        if (connu == null) return ""
        // L'entrée est désormais celle du suffixe exact : F5RRO/P connu
        // propose SON carré — celui de ses sorties — et un /P jamais vu ne
        // propose rien. Le carré de la base ne fuit plus vers le portable,
        // ni l'inverse.
        return connu.locatorPrincipal
    }

    // ------------------------------------------------------------ prédiction

    /** Demi-vie de la récence, en jours. */
    private const val DEMI_VIE_JOURS = 120.0

    private const val JOUR_MS = 86_400_000.0

    /**
     * Note d'une proposition. Plus c'est haut, plus ça remonte.
     *
     * Trois termes, dans cet ordre d'importance : la fréquence des contacts
     * (en logarithme — le vingtième QSO avec le même correspondant apprend
     * moins que le deuxième), la récence (décroissance exponentielle : un
     * correspondant d'il y a trois ans compte, mais pas autant que celui de
     * la semaine dernière), et une prime au correspondant déjà entendu sur le
     * même satellite, qui est le meilleur indice court dont on dispose pendant
     * un passage.
     */
    fun note(connu: Connu, maintenantMs: Long, satActif: String = ""): Double {
        val frequence = ln(1.0 + connu.contacts)
        val jours = ((maintenantMs - connu.dernierMs).coerceAtLeast(0L)) / JOUR_MS
        val recence = exp(-jours / DEMI_VIE_JOURS)
        val memeSat = if (satActif.isNotBlank() &&
            connu.dernierSat.equals(satActif, ignoreCase = true)) 0.6 else 0.0
        return frequence + 2.0 * recence + memeSat
    }

    /**
     * Les propositions pour une saisie en cours.
     *
     * Trois au maximum : au-delà, la ligne de suggestions demande une lecture
     * au lieu d'un coup d'œil, et l'on a reperdu les secondes qu'on venait
     * gagner. Rien n'est proposé sous deux caractères — sur une seule lettre,
     * tout ressemble à tout.
     */
    fun suggestions(
        saisie: String,
        memoire: List<Connu>,
        maintenantMs: Long,
        satActif: String = "",
        max: Int = 3,
    ): List<Connu> {
        val debut = base(saisie)
        if (debut.length < 2) return emptyList()
        return memoire.asSequence()
            .filter { it.indicatif.startsWith(debut) }
            .sortedWith(
                compareByDescending<Connu> { note(it, maintenantMs, satActif) }
                    .thenBy { it.indicatif }
            )
            .take(max)
            .toList()
    }

    /**
     * Les lettres et chiffres qui prolongent réellement quelque chose de connu.
     *
     * Le clavier s'en sert pour **mettre en valeur**, jamais pour retirer. La
     * distinction n'est pas cosmétique : un clavier qui supprime les touches
     * improbables interdit de noter le DX rare jamais contacté, c'est-à-dire
     * exactement celui qu'on avait sorti l'antenne pour attraper.
     */
    fun suitesConnues(saisie: String, memoire: List<Connu>): Set<Char> {
        val debut = base(saisie)
        val out = HashSet<Char>()
        memoire.forEach { c ->
            if (c.indicatif.length > debut.length && c.indicatif.startsWith(debut)) {
                out.add(c.indicatif[debut.length])
            }
        }
        return out
    }

    /** État d'un indicatif saisi, pour la pastille affichée à côté du champ. */
    enum class Etat { DEJA_CONTACTE, PLAUSIBLE, INHABITUEL, VIDE }

    fun etat(saisie: String, memoire: List<Connu>): Etat {
        if (saisie.isBlank()) return Etat.VIDE
        // La pastille verte parle de l'opérateur, pas de son carré : F4HRJ est
        // déjà contacté même si on ne l'a travaillé qu'en F4HRJ/P. La mémoire
        // sépare désormais les exploitations, la comparaison se fait donc sur
        // la base.
        val b = base(saisie)
        if (memoire.any { base(it.indicatif) == b }) return Etat.DEJA_CONTACTE
        return if (plausible(saisie)) Etat.PLAUSIBLE else Etat.INHABITUEL
    }

    // ------------------------------------------------------------- la mémoire

    /**
     * Construit la mémoire à partir d'une liste de contacts.
     *
     * Les couples indicatif + carré sont conservés, et non un carré unique par
     * indicatif : un correspondant en portable change de carré, et proposer
     * celui de l'an dernier est pire que ne rien proposer.
     */
    fun memoire(contacts: List<Contact>): List<Connu> {
        class Acc {
            var n = 0
            var dernier = 0L
            var sat = ""
            var nom = ""
            val carres = HashMap<String, IntArray>()
            val carresDate = HashMap<String, Long>()
        }

        val map = HashMap<String, Acc>()
        contacts.forEach { c ->
            val k = cle(c.indicatif)
            if (k.isEmpty()) return@forEach
            val a = map.getOrPut(k) { Acc() }
            a.n++
            if (c.quandMs >= a.dernier) {
                a.dernier = c.quandMs
                if (c.satellite.isNotBlank()) a.sat = c.satellite
            }
            // Le nom se garde dès qu'on en voit un, même sur un contact plus
            // ancien : un carnet n'en porte pas à chaque ligne, et le plus
            // récent est parfois justement celui qui n'en a pas.
            if (a.nom.isBlank() && c.nom.isNotBlank()) a.nom = c.nom
            // Chaque entrée possède ses carrés en propre : la clé porte le
            // suffixe, donc F5RRO/P accumule les siens sans jamais toucher à
            // ceux de F5RRO. L'ancienne règle — jeter le carré des suffixés —
            // les rendait invisibles ; celle-ci les montre à leur place.
            val g = c.locator.trim().uppercase()
            if (g.isNotEmpty()) {
                a.carres.getOrPut(g) { IntArray(1) }[0]++
                val d = a.carresDate[g] ?: 0L
                if (c.quandMs > d) a.carresDate[g] = c.quandMs
            }
        }
        return map.map { (k, a) ->
            Connu(
                indicatif = k,
                contacts = a.n,
                dernierMs = a.dernier,
                dernierSat = a.sat,
                nom = a.nom,
                locators = a.carres.map { (g, n) ->
                    LocatorVu(g, n[0], a.carresDate[g] ?: 0L)
                }.sortedByDescending { it.dernierMs },
            )
        }
    }

    /** Le minimum qu'un contact doit porter pour alimenter la mémoire. */
    class Contact(
        val indicatif: String,
        val locator: String,
        val quandMs: Long,
        val satellite: String = "",
        val nom: String = "",
    )
}
