/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo

import android.app.Application
import fr.f4ioz.satcombo.audio.RecorderService
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.VeilleAuto
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The automatic mode on the phone, as on SatMe-PC (see [VeilleAuto]): the
 * chosen satellites' passes, each followed by itself 5 min before AOS (the
 * satellite chosen, so the rig under CAT and the mast follow it) and recorded
 * by the recorder armed for their windows. The recorder can be armed only
 * while SatMe is on screen (Android's rule): armed again each time it is.
 * Nothing transmits; the satellite changed by hand during a pass stays.
 */
class VeilleTelephone(
    private val app: Application,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val predictor: PassPredictor,
    private val ui: () -> UiState,
    private val observateur: () -> Observer,
    private val choisis: (Int) -> Unit,
    private val activeEnregistreur: () -> Unit,
    private val locator: () -> String,
) {
    /** The plan shown (kept, set aside, lost passes). */
    val plan = MutableStateFlow<List<VeilleAuto.Ligne>>(emptyList())
    /** Where it stands, in words. */
    val etat = MutableStateFlow("")
    @Volatile var calcul = false
        private set

    @Volatile private var passages: List<SatPass> = emptyList()
    @Volatile private var calculeLe = 0L
    @Volatile private var calculePour: Pair<Set<Int>, Int> = emptySet<Int>() to -1
    /** The windows last armed, so they are armed again only when they changed. */
    @Volatile private var armees: List<Triple<Long, Long, String>> = emptyList()
    private val suivis = HashSet<String>()

    fun demarre() {
        scope.launch {
            while (true) {
                runCatching { tic() }
                delay(15_000)
            }
        }
    }

    /** Its choices, read by its card. */
    val reglages: SettingsStore get() = settings

    /** A choice changed by its card: kept, then planned and armed again. */
    fun regle(f: SettingsStore.() -> Unit) { settings.f(); change() }

    /** Its choices changed (its card, the PC page): plan and arm again now. */
    fun change() { scope.launch { runCatching { tic(force = true) } } }

    companion object {
        /**
         * May the recorder take the input? Without the permission (never asked
         * yet), arming it would stop the app (Android refuses the microphone
         * service): it is asked by a first recording on the phone (⏺).
         */
        fun microAutorise(ctx: android.content.Context, source: String): Boolean {
            val perms = listOf(android.Manifest.permission.RECORD_AUDIO) +
                if (source == "BT" && android.os.Build.VERSION.SDK_INT >= 31) listOf(android.Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
            return perms.all { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        }
    }

    private fun enAvant(): Boolean {
        val moi = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(moi)
        return moi.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private suspend fun tic(force: Boolean = false) {
        val maintenant = System.currentTimeMillis()
        val sats = settings.veilleSats
        val cle = sats to ui().satellites.size
        // The passes of the chosen satellites, 24 h: again every 10 min, or when the choice changed.
        if (force || cle != calculePour || maintenant - calculeLe > 10 * 60_000L) {
            calcul = true
            val obs = observateur()
            val l = ui().satellites.filter { it.catalogNumber in sats }
            val minEl = ui().minElevDeg.toDouble()
            passages = withContext(Dispatchers.Default) {
                l.flatMap { s ->
                    if (!PassPredictor.elementsUtilisables(s)) emptyList()
                    else runCatching { predictor.upcomingPasses(s, obs, maintenant - 15 * 60_000L, 24, minEl) }.getOrDefault(emptyList())
                }
            }
            calculeLe = maintenant; calculePour = cle; calcul = false
        }
        val p = VeilleAuto.plan(passages, sats, settings.veilleElMin.toDouble(), settings.veilleEcartes, maintenant)
        plan.value = p
        val R = RecorderService
        if (!settings.veilleActive) {
            if (R.armePar == R.PAR_VEILLE && R.fenetres.value.isNotEmpty()) R.desarme(app)
            armees = emptyList(); etat.value = ""
            return
        }
        // Followed: the pass whose time has come, once (a hand that picks another one is not overruled).
        val aSuivre = VeilleAuto.aSuivre(p, maintenant)
        if (aSuivre != null && !VeilleAuto.ecarte(aSuivre, suivis.toSet())) {
            suivis += VeilleAuto.cle(aSuivre)
            if (ui().selected?.catalogNumber != aSuivre.catalogNumber) choisis(aSuivre.catalogNumber)
        }
        val mots = ArrayList<String>()
        val h = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).apply {
            if (ui().useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC") }
        when {
            sats.isEmpty() -> mots += t("veille_aucun_sat")
            aSuivre != null -> mots += tf("veille_en_cours", aSuivre.satName, h.format(java.util.Date(aSuivre.losEpochMs)))
            else -> p.firstOrNull { it.garde }?.let { mots += tf("veille_prochain", it.passage.satName, h.format(java.util.Date(it.passage.aosEpochMs))) }
                ?: run { mots += t("veille_aucun") }
        }
        // The sound: the recorder armed for the kept passes' windows.
        val f = VeilleAuto.fenetres(p, maintenant)
        when {
            !settings.veilleSon -> {
                if (R.armePar == R.PAR_VEILLE && R.fenetres.value.isNotEmpty()) R.desarme(app)
                armees = emptyList()
            }
            R.sstvArmee -> mots += t("veille_sstv")
            f.isEmpty() -> {}
            armees.containsAll(f) && R.armePar == R.PAR_VEILLE && R.fenetres.value.isNotEmpty() -> mots += tf("veille_arme", R.fenetres.value.size)
            // A recording under way (by hand, or a pass): armed after it.
            ui().recording && !(R.armePar == R.PAR_VEILLE && R.fenetres.value.isNotEmpty()) -> mots += t("veille_apres_enreg")
            !microAutorise(app, ui().recorderSource) -> mots += t("veille_micro")
            !enAvant() -> mots += t("veille_ouvrir")
            else -> {
                if (!ui().recorderEnabled) activeEnregistreur()
                runCatching {
                    R.arme(app, f.first().third, f.map { it.first to it.second }, ui().recorderSource, ui().recorderUnprocessed, locator(),
                        avanceMs = VeilleAuto.AVANT_SON_MS, par = R.PAR_VEILLE, noms = f.map { it.third })
                    armees = f
                    mots += tf("veille_arme", f.size)
                }.onFailure { mots += t("veille_ouvrir") }
            }
        }
        etat.value = mots.joinToString(" ")
    }
}
