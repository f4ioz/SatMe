/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Le récit de la dernière mort de l'application.
 *
 * On a passé deux jours à chercher pourquoi SatMe se fermait au démarrage sur
 * un POCO M4 sans jamais obtenir une seule ligne de trace. Le Play Console ne
 * dit rien : les remontées de plantage demandent un volume d'installations
 * qu'un test fermé à trois personnes n'atteindra jamais, et ne comptent que
 * les appareils dont le propriétaire a accepté le partage des diagnostics.
 * Le rapport de pré-lancement, lui, teste des appareils qui ne sont pas celui
 * qui plante. Autrement dit : quand l'application meurt chez un testeur,
 * personne ne le sait, et surtout pas nous.
 *
 * D'où ceci. Au moment où le processus tombe, on écrit la trace sur le disque
 * de l'application, puis on laisse Android finir son travail — le système
 * affiche toujours « SatMe s'est arrêté », rien n'est masqué. Au lancement
 * suivant, on propose d'envoyer le fichier par courrier. Aucun serveur, aucune
 * bibliothèque tierce, aucune permission de plus : un fichier texte et une
 * intention `mailto:`.
 *
 * Cet objet-ci ne connaît pas Android : il ne sait que mettre en forme du
 * texte, ce qui le rend vérifiable sur le banc d'essai ordinaire.
 */
object Plantage {

    /** Le fichier vit dans `filesDir`, effacé avec l'application. */
    const val NOM = "dernier-plantage.txt"

    const val DESTINATAIRE = "mail@f4ioz.fr"
    const val SUJET = "SatMe — rapport de plantage"

    /**
     * Une intention transporte ses extras par un tuyau du noyau dont la
     * capacité se compte en centaines de kilo-octets, partagée avec tout le
     * reste. Une pile d'appels profonde, avec ses causes chaînées, dépasse
     * vite le raisonnable ; passé cette limite on coupe par la fin, parce que
     * les premières lignes sont celles qui nomment la panne.
     */
    const val MAX = 12_000

    /** La pile d'appels complète, causes comprises, telle que la JVM la rend. */
    fun trace(t: Throwable): String {
        val w = StringWriter()
        PrintWriter(w).use { t.printStackTrace(it) }
        return w.toString().trimEnd()
    }

    /**
     * Coupe par la fin en le disant. Une trace tronquée sans avertissement se
     * lit comme une trace complète, et l'on cherche alors une cause racine qui
     * a simplement été jetée.
     */
    fun tronque(s: String, max: Int = MAX): String =
        if (s.length <= max) s
        else s.take(max) + "\n\n[…] rapport coupé à $max caractères sur ${s.length}."

    /**
     * L'en-tête compte autant que la pile. « Ça plante » et « ça plante sur ce
     * modèle-là, avec ces modules-là » ne se réparent pas de la même façon :
     * c'est justement la ligne des modules qui départage un défaut de notre
     * code d'une installation à laquelle il manque un morceau.
     */
    fun redige(
        t: Throwable,
        version: String,
        code: Int,
        appareil: String,
        androidVersion: String,
        modules: String,
        horodatage: String,
        fil: String = "?"
    ): String = tronque(
        buildString {
            appendLine("SatMe $version ($code)")
            appendLine("Appareil : $appareil")
            appendLine("Android : $androidVersion")
            appendLine("Modules : $modules")
            appendLine("Fil : $fil")
            appendLine("Quand : $horodatage")
            appendLine()
            append(trace(t))
        })

    /**
     * Le corps du courrier. On demande une phrase au testeur : la trace dit ce
     * qui a cassé, elle ne dit jamais ce qu'il était en train de faire.
     */
    fun corpsDuMail(rapport: String): String = buildString {
        appendLine("Bonjour,")
        appendLine()
        appendLine("SatMe s'est arrêté. Voici ce que l'application a noté.")
        appendLine()
        appendLine("Ce que je faisais à ce moment-là :")
        appendLine()
        appendLine()
        appendLine("----------------------------------------")
        append(rapport)
    }

    /**
     * La ligne des modules, à partir de ce qu'Android déclare. Un paquet
     * d'installation servi en morceaux — langue, densité, jeu d'instructions —
     * peut en perdre un en chemin chez certains constructeurs, et l'application
     * meurt alors avant sa première ligne de code. Savoir qu'il n'y en avait
     * aucun élimine cette piste d'un coup.
     */
    fun modules(noms: Array<String?>?): String {
        val propres = noms?.filterNotNull()?.filter { it.isNotBlank() } ?: emptyList()
        return if (propres.isEmpty()) "aucun" else propres.joinToString(", ")
    }
}

/**
 * Le rapport sur le disque. Séparé de sa mise en forme parce qu'un objet qui
 * écrit des fichiers se vérifie avec un dossier temporaire, et que l'on ne
 * veut pas d'Android pour cela non plus.
 *
 * Toutes les opérations avalent leurs échecs. Elles sont appelées depuis un
 * processus en train de mourir : une exception ici remplacerait la panne à
 * expliquer par une panne dans l'explication.
 */
object PlantageDisque {

    private fun fichier(dossier: File) = File(dossier, Plantage.NOM)

    fun ecrit(dossier: File, texte: String): Boolean = runCatching {
        if (!dossier.exists()) dossier.mkdirs()
        fichier(dossier).writeText(texte)
        true
    }.getOrDefault(false)

    /** Le rapport, ou `null` s'il n'y en a pas — ou s'il est vide. */
    fun lit(dossier: File): String? = runCatching {
        val f = fichier(dossier)
        if (!f.isFile) return null
        f.readText().ifBlank { null }
    }.getOrNull()

    fun efface(dossier: File): Boolean = runCatching {
        val f = fichier(dossier)
        !f.exists() || f.delete()
    }.getOrDefault(false)
}
