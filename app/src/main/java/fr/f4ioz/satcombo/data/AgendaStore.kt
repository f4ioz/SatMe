/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context

/**
 * L'agenda de l'opérateur : les rendez-vous qu'aucun calcul d'orbite ne peut
 * deviner.
 *
 * SatMe sait déjà quand un satellite passe. Ce qu'il ne sait pas, c'est qu'un
 * correspondant a donné rendez-vous sur tel passage, ou qu'une station annonce
 * de la SSTV tel jour à telle heure. Ces informations-là arrivent par un forum,
 * un réseau, une liste de diffusion, et elles se perdent entre le moment où on
 * les lit et le moment où elles servent.
 *
 * Un rendez-vous porte donc : quand — un instant, ou un créneau du début à la
 * fin —, quoi, combien de temps avant il faut le rappeler, et de quoi il
 * s'agit techniquement : le satellite, le genre d'émission, la fréquence.
 *
 * Le créneau n'est pas un détail de confort. Une annonce comme « SSTV depuis
 * QMR-KWT-2 du 1er août 06:30 UTC au 2 août 19:30 UTC » ne désigne pas un
 * passage mais tous les passages de ces deux jours-là : c'est justement ce
 * qu'un agenda à instant unique ne savait pas dire.
 *
 * Le stockage tient en une chaîne, une ligne par rendez-vous, champs séparés
 * par des tabulations. Pas de JSON : une base de données pour une poignée de
 * lignes coûterait plus cher en migrations qu'elle ne rapporte, et un format
 * lisible se répare à la main. Les champs s'ajoutent en fin de ligne et
 * [decode] accepte les lignes courtes, donc un agenda écrit par une version
 * précédente se relit sans rien perdre. [encode] et [decode] ne touchent pas à
 * Android, donc ils se vérifient sur machine.
 */
object AgendaStore {

    data class AgendaEvent(
        /** Identifiant, l'instant de création — sert aussi de clé d'alarme. */
        val id: Long,
        /** Ce dont il s'agit : « SSTV ISS », « sked F6KMX »… */
        val title: String,
        /** Début du rendez-vous, en millisecondes UTC. */
        val timeMs: Long,
        /** Satellite concerné, facultatif. */
        val satName: String = "",
        /** Rappel combien de minutes avant. 0 = au moment même. */
        val leadMin: Int = 60,
        /** Note libre. */
        val note: String = "",
        /** Faux quand le rappel est désactivé sans supprimer le rendez-vous. */
        val enabled: Boolean = true,
        /**
         * Fin du créneau. 0 — ou toute valeur qui ne dépasse pas le début —
         * signifie « pas de créneau » : le rendez-vous est un instant, comme
         * il l'était avant que les créneaux existent.
         */
        val endMs: Long = 0L,
        /**
         * Le genre d'émission attendue : SSTV, NOAA, SKED, BEACON… Vide quand
         * ça n'a pas de sens. Ces mots sont les mêmes dans toutes les langues
         * du trafic amateur, donc ils ne se traduisent pas.
         */
        val kind: String = "",
        /** Fréquence annoncée, en hertz. 0 = aucune. */
        val freqHz: Long = 0L
    ) {
        /** Instant où le rappel doit sonner. */
        val alertMs: Long get() = timeMs - leadMin * 60_000L

        /** Vrai quand le rendez-vous couvre une durée et non un instant. */
        val isWindow: Boolean get() = endMs > timeMs

        /** La fin réelle : celle qui a été saisie, ou le début à défaut. */
        val endOrStartMs: Long get() = if (endMs > timeMs) endMs else timeMs

        /**
         * Ce rendez-vous concerne-t-il le passage qui va de [aosMs] à [losMs] ?
         *
         * C'est un recouvrement, pas une inclusion : un créneau de trente-sept
         * heures contient des dizaines de passages entiers, et un instant noté
         * à 14 h 30 vaut pour le passage qui commence à 14 h 32. Le battement
         * de cinq minutes de part et d'autre existe parce que personne ne note
         * un rendez-vous à la seconde près.
         */
        fun covers(aosMs: Long, losMs: Long, slackMs: Long = 5 * 60_000L): Boolean =
            aosMs - slackMs <= endOrStartMs && losMs + slackMs >= timeMs

        /** Le rendez-vous est-il en cours à l'instant [ms] ? */
        fun activeAt(ms: Long, slackMs: Long = 5 * 60_000L): Boolean =
            ms >= timeMs - slackMs && ms <= endOrStartMs + slackMs

        /** Le créneau recouvre-t-il la période [fromMs] – [toMs] ? */
        fun overlaps(fromMs: Long, toMs: Long): Boolean =
            fromMs <= endOrStartMs && toMs >= timeMs

        /** La fréquence en MHz, ou null quand il n'y en a pas. */
        val freqMhz: Double? get() = if (freqHz > 0L) freqHz / 1e6 else null
    }

    private const val PREFS = "satcombo_agenda"
    private const val KEY = "events"

    /** Les genres proposés. Le premier, vide, veut dire « sans précision ». */
    val KINDS: List<String> = listOf("", "SSTV", "NOAA", "SKED", "BEACON", "CONTEST")

    // ------------------------------------------------------------ sérialisation

    /** Un champ ne peut contenir ni tabulation ni retour à la ligne. */
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    fun encode(list: List<AgendaEvent>): String = list.joinToString("\n") { e ->
        listOf(
            e.id.toString(), e.timeMs.toString(), e.leadMin.toString(),
            if (e.enabled) "1" else "0",
            clean(e.title), clean(e.satName), clean(e.note),
            // À partir d'ici, les champs ajoutés après coup. Ils vont en fin de
            // ligne et jamais ailleurs : c'est ce qui permet à une version
            // ancienne de relire un agenda récent sans le casser.
            e.endMs.toString(), clean(e.kind).uppercase(), e.freqHz.toString()
        ).joinToString("\t")
    }

    /**
     * Relit la liste. Une ligne abîmée est ignorée et ne fait pas perdre les
     * autres : c'est tout l'intérêt d'un format ligne par ligne. Une ligne
     * courte — écrite avant les créneaux — se relit avec les valeurs par
     * défaut, c'est-à-dire exactement comme un rendez-vous instantané.
     */
    fun decode(text: String?): List<AgendaEvent> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<AgendaEvent>()
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            val f = line.split("\t")
            if (f.size < 5) return@forEach
            val id = f[0].toLongOrNull() ?: return@forEach
            val time = f[1].toLongOrNull() ?: return@forEach
            out += AgendaEvent(
                id = id,
                timeMs = time,
                leadMin = f[2].toIntOrNull() ?: 60,
                enabled = f[3] != "0",
                title = f[4],
                satName = f.getOrNull(5) ?: "",
                note = f.getOrNull(6) ?: "",
                endMs = f.getOrNull(7)?.toLongOrNull() ?: 0L,
                kind = (f.getOrNull(8) ?: "").uppercase(),
                freqHz = f.getOrNull(9)?.toLongOrNull() ?: 0L)
        }
        return out.sortedBy { it.timeMs }
    }

    // ------------------------------------------------------------------ disque

    fun load(ctx: Context): List<AgendaEvent> =
        decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))

    fun save(ctx: Context, list: List<AgendaEvent>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, encode(list.sortedBy { it.timeMs })).apply()
    }

    /** Ajoute ou remplace un rendez-vous, selon que son identifiant existe. */
    fun put(ctx: Context, e: AgendaEvent): List<AgendaEvent> {
        val list = load(ctx).filter { it.id != e.id } + e
        val sorted = list.sortedBy { it.timeMs }
        save(ctx, sorted)
        return sorted
    }

    fun remove(ctx: Context, id: Long): List<AgendaEvent> {
        val list = load(ctx).filter { it.id != id }
        save(ctx, list)
        return list
    }

    /**
     * Efface les rendez-vous terminés depuis plus de [days] jours.
     *
     * Un agenda qui ne se vide jamais devient une liste d'archives que
     * personne ne lit ; mais effacer dès l'heure passée priverait l'opérateur
     * de la trace de ce qu'il vient de faire. Le compte part de la fin du
     * créneau : un événement de deux jours ne doit pas s'effacer pendant qu'il
     * a encore lieu.
     */
    fun purge(ctx: Context, days: Int = 30, nowMs: Long = System.currentTimeMillis()):
        List<AgendaEvent> {
        val cut = nowMs - days * 86_400_000L
        val list = load(ctx)
        val kept = list.filter { it.endOrStartMs >= cut }
        if (kept.size != list.size) save(ctx, kept)
        return kept
    }
}
