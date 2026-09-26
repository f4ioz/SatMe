/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.diag

/**
 * Failure mode: what the app can still say when it no longer opens.
 *
 * The crash handler writes the trace to disk, but the dialog that offers to
 * send it lives in the main screen — the very screen that does not appear when
 * the app dies at startup. The report would be written and unreachable.
 *
 * Hence a second entry point with its own icon, sharing nothing with the first
 * (no Compose, ViewModel, app theme or string resources), so it cannot die of
 * the same causes. If it opens while the app does not, that is already a clue;
 * if it does not open either, a stronger one.
 *
 * This object knows nothing of Android: text only, so it runs in plain unit
 * tests.
 */
object ModeEchec {

    /**
     * One diagnostic step. [action] returns a short sentence on what it found;
     * if it throws, the exception speaks.
     */
    class Etape(val nom: String, val action: () -> String)

    class Resultat(
        val nom: String,
        val ok: Boolean,
        val detail: String,
        val ms: Long
    )

    /**
     * Catches `Throwable`, not `Exception`, on purpose: the failures sought
     * here are precisely not ordinary exceptions — `OutOfMemoryError` on a
     * low-RAM device, `NoClassDefFoundError` without Google services,
     * `UnsatisfiedLinkError` on a misaligned native library.
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
     * The class name matters as much as the message, often more: a null
     * message says nothing, `NoClassDefFoundError` names the fault by itself.
     * The root cause is appended when different, since it names the real
     * failure behind an `ExceptionInInitializerError`.
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

    /** The step results, in the order they ran. */
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
     * Cuts from the START, unlike [Plantage.tronque].
     *
     * In a stack trace the first lines name the fault; in a system log, the
     * last ones do. Cutting the wrong end throws away what we came for.
     */
    fun tronqueParLeDebut(s: String, max: Int = 40_000): String =
        if (s.length <= max) s
        else "[…] journal coupé au début, ${s.length - max} caractères plus anciens écartés.\n\n" +
            s.takeLast(max)

    /**
     * Process exit reasons as numbered by Android (`ApplicationExitInfo`).
     * Copied rather than imported so unit tests can read them without the
     * Android class; the values are frozen for backward compatibility.
     *
     * Watch `INITIALISATION` (7): a process that never managed to build
     * itself — missing split, rejected native library, missing resources.
     * None of our code ran, so no handler of ours can have recorded it.
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

    /** One process death, reduced to what is worth reporting. */
    class Sortie(
        val quand: String,
        val raison: Int,
        val description: String?,
        val importance: Int,
        val octets: Long
    )

    /**
     * Past process deaths. Android keeps this itself, with no permission and
     * without us surviving to write it: the only witness of crashes before our
     * first instruction.
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

    /** The final report, in reading order. */
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
