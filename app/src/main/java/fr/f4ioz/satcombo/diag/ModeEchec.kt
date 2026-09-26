/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.diag

/**
 * Le mode échec : ce que l'application peut encore dire quand elle ne s'ouvre
 * plus.
 *
 * Le garde-fou de la 18.28 écrit bien la trace sur le disque au moment de la
 * chute. Ce qu'il ne peut pas faire, c'est la montrer : la fenêtre qui propose
 * de l'envoyer vit dans l'écran principal, et l'écran principal est justement
 * celui qui n'apparaît pas. Chez un testeur dont l'application meurt au
 * démarrage, le rapport est donc écrit, complet, et inaccessible.
 *
 * D'où un second point d'entrée, avec sa propre icône, qui ne partage rien
 * avec le premier : ni Compose, ni modèle de vue, ni thème de l'application,
 * ni la moindre chaîne de caractères tirée des ressources. Il ne peut donc pas
 * mourir des mêmes causes. S'il s'ouvre alors que l'application ne s'ouvre pas,
 * c'est déjà un renseignement ; s'il ne s'ouvre pas non plus, c'en est un autre,
 * bien plus fort.
 *
 * Cet objet-ci ne connaît pas Android — il ne fait que du texte, et se vérifie
 * donc au banc ordinaire.
 */
object ModeEchec {

    /**
     * Une épreuve du diagnostic. [action] rend une phrase courte décrivant ce
     * qu'elle a constaté ; si elle lève, c'est l'exception qui parle.
     */
    class Etape(val nom: String, val action: () -> String)

    class Resultat(
        val nom: String,
        val ok: Boolean,
        val detail: String,
        val ms: Long
    )

    /**
     * On attrape `Throwable` et non `Exception`, délibérément. Les pannes que
     * l'on cherche ici sont précisément celles qui ne sont pas des exceptions
     * ordinaires : `OutOfMemoryError` sur un appareil à petite mémoire,
     * `NoClassDefFoundError` sur un téléphone sans services Google,
     * `UnsatisfiedLinkError` sur une bibliothèque native mal alignée. Les
     * laisser passer reviendrait à ne pas tester ce qu'on est venu tester.
     */
    fun execute(etape: Etape, horloge: () -> Long = System::currentTimeMillis): Resultat {
        val debut = horloge()
        return try {
            val detail = etape.action()
            Resultat(etape.nom, true, detail, horloge() - debut)
        } catch (t: Throwable) {
            Resultat(etape.nom, false, decrit(t), horloge() - debut)
        }
    }

    /**
     * Le nom de la classe compte autant que le message, et souvent davantage :
     * un message nul se lit « null » et ne dit rien, alors que
     * `NoClassDefFoundError` désigne la panne à lui tout seul. On ajoute la
     * cause racine quand elle diffère, car c'est elle qui nomme la vraie
     * défaillance derrière un `ExceptionInInitializerError`.
     */
    fun decrit(t: Throwable): String {
        val tete = "${t.javaClass.simpleName}: ${t.message ?: "(sans message)"}"
        var racine: Throwable = t
        var garde = 0
        while (racine.cause != null && racine.cause !== racine && garde++ < 16) {
            racine = racine.cause!!
        }
        return if (racine === t) tete
        else "$tete\n      cause : ${racine.javaClass.simpleName}: ${racine.message ?: "(sans message)"}"
    }

    fun ligne(r: Resultat): String =
        "${if (r.ok) "OK  " else "ÉCHEC"} ${r.nom} (${r.ms} ms)\n      ${r.detail}"

    /** Le bloc des épreuves, dans l'ordre où elles ont été passées. */
    fun bloc(resultats: List<Resultat>): String {
        if (resultats.isEmpty()) return "Diagnostic non lancé."
        val echecs = resultats.count { !it.ok }
        return buildString {
            appendLine("Diagnostic : ${resultats.size} épreuves, $echecs en échec.")
            appendLine()
            resultats.forEach { appendLine(ligne(it)) }
        }.trimEnd()
    }

    /**
     * Coupe par le DÉBUT, à l'inverse de [Plantage.tronque].
     *
     * Ce n'est pas une symétrie gratuite : dans une pile d'appels, ce sont les
     * premières lignes qui nomment la panne, alors que dans un journal système
     * ce sont les dernières. Couper du mauvais côté jette exactement ce qu'on
     * était venu chercher.
     */
    fun tronqueParLeDebut(s: String, max: Int = 40_000): String =
        if (s.length <= max) s
        else "[…] journal coupé au début, ${s.length - max} caractères plus anciens écartés.\n\n" +
            s.takeLast(max)

    /**
     * Les motifs de mort de processus tels qu'Android les numérote
     * (`ApplicationExitInfo`). Recopiés en clair plutôt qu'importés : cette
     * table doit se lire au banc d'essai, où la classe Android n'existe pas, et
     * les valeurs sont figées par compatibilité ascendante.
     *
     * `INITIALISATION` (7) mérite l'attention : c'est le motif d'un processus
     * qui n'a jamais réussi à se construire — paquet servi en morceaux dont il
     * manque une pièce, bibliothèque native refusée, ressources introuvables.
     * Aucune de nos lignes n'a tourné, donc aucun garde-fou à nous ne peut
     * l'avoir noté.
     */
    fun nomDeRaison(code: Int): String = when (code) {
        0 -> "inconnu"
        1 -> "arrêt volontaire de l'application"
        2 -> "tué par signal"
        3 -> "mémoire insuffisante"
        4 -> "PLANTAGE (exception non rattrapée)"
        5 -> "PLANTAGE NATIF"
        6 -> "ANR (application qui ne répond plus)"
        7 -> "ÉCHEC D'INITIALISATION du processus"
        8 -> "changement de permission"
        9 -> "consommation excessive de ressources"
        10 -> "arrêt demandé par l'utilisateur"
        11 -> "arrêt par le système (utilisateur)"
        12 -> "dépendance disparue"
        13 -> "autre"
        14 -> "gelé par le système"
        15 -> "changement d'état du paquet"
        16 -> "paquet mis à jour"
        else -> "code $code"
    }

    /** Une mort de processus, réduite à ce qui se raconte. */
    class Sortie(
        val quand: String,
        val raison: Int,
        val description: String?,
        val importance: Int,
        val octets: Long
    )

    /**
     * Le registre des morts précédentes. Android le tient lui-même, sans
     * permission et sans que nous ayons eu à survivre pour l'écrire : c'est le
     * seul témoin des chutes survenues avant notre première instruction.
     */
    fun blocSorties(sorties: List<Sortie>): String {
        if (sorties.isEmpty())
            return "Registre des arrêts : vide, ou non tenu par cette version d'Android " +
                "(le registre demande Android 11)."
        return buildString {
            appendLine("Registre des arrêts (le plus récent d'abord) :")
            sorties.forEach { s ->
                appendLine("  ${s.quand} — ${nomDeRaison(s.raison)}")
                val d = s.description?.takeIf { it.isNotBlank() }
                if (d != null) appendLine("      $d")
                if (s.octets > 0) appendLine("      mémoire : ${s.octets / 1024} Mo")
            }
        }.trimEnd()
    }

    /** L'assemblage final, dans l'ordre où on le lira. */
    fun rapport(
        entete: String,
        plantage: String?,
        sorties: String,
        diagnostic: String,
        journal: String?
    ): String = buildString {
        appendLine("=== SatMe — rapport de mode échec ===")
        appendLine()
        appendLine(entete.trimEnd())
        appendLine()
        appendLine("--- Dernier plantage enregistré ---")
        appendLine(plantage?.trimEnd() ?: "Aucun rapport de plantage sur le disque.")
        appendLine()
        appendLine("--- Arrêts du processus ---")
        appendLine(sorties.trimEnd())
        appendLine()
        appendLine("--- Diagnostic ---")
        appendLine(diagnostic.trimEnd())
        if (journal != null) {
            appendLine()
            appendLine("--- Journal système (notre application seulement) ---")
            appendLine(journal.trimEnd())
        }
    }.trimEnd()
}
