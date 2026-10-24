/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.sstv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fr.f4ioz.satcombo.data.ActivitesTelephone
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.stockage
import fr.f4ioz.satcombo.domain.JournalPassage
import fr.f4ioz.satcombo.domain.PassPredictor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The SSTV options on the phone: their settings, the missing slide's notice
 * (laid down as one-off work, said on the activities' channel), the
 * retention of the SSTV recordings, the QSL card (Canvas) and the ARISS
 * award pack, both made in the cache's export folder to be shared.
 */
object OptionsSstvTelephone {

    fun options(ctx: Context) = OptionsSstv(stockage(ctx, "satcombo_settings"))

    private fun dossierRec(ctx: Context) = File(ctx.getExternalFilesDir(null), "recordings")
    private fun fichierGardes(ctx: Context) = File(ctx.filesDir, "sstv-gardes.txt")
    private fun fichierDites(ctx: Context) = File(ctx.filesDir, "sstv-alertes-diapos.json")
    private fun export(ctx: Context) = File(ctx.cacheDir, "export").apply { mkdirs() }

    fun gardes(ctx: Context): Set<String> = RetentionSon.litGardes(fichierGardes(ctx))
    fun marqueGarde(ctx: Context, nom: String, on: Boolean): Set<String> = RetentionSon.marqueGarde(fichierGardes(ctx), nom, on)

    // ------------------------------------------------------------------ the notice

    const val TAG = "sstv_diapo_attendue"

    /**
     * The notices for the passes to come laid down again (all cleared
     * first: the timetable may have moved). Called with the activities' check.
     */
    suspend fun planifieAlertes(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelAllWorkByTag(TAG)
        val s = SettingsStore(ctx)
        val r = options(ctx).reglagesAlerte
        if (!r.actif || !s.activitesOn) return
        val t = System.currentTimeMillis()
        val e = ClassementTelephone.etat(ctx, attendre = true)
        val p = ClassementTelephone.prevision(ctx, e, t) ?: return
        val dites = AlerteDiapo.litDites(runCatching { fichierDites(ctx).readText() }.getOrNull())
        AlerteDiapo.alertes(p, r, t).filter { it.cle !in dites }.take(20).forEach { a ->
            wm.enqueue(OneTimeWorkRequestBuilder<DiapoAttendueWorker>()
                .setInitialDelay((a.quandMs - t).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .setInputData(workDataOf("cle" to a.cle, "titre" to AlerteDiapo.titre(a), "texte" to AlerteDiapo.texte(a), "aos" to a.passage.aosEpochMs))
                .build())
        }
    }

    /** Said once (the key kept); a pass already begun is not said. */
    fun dis(ctx: Context, cle: String, titre: String, texte: String, aos: Long) {
        val s = SettingsStore(ctx)
        if (!s.activitesOn || !options(ctx).alerteDiapo) return
        if (System.currentTimeMillis() > aos + 60_000L) return
        val f = fichierDites(ctx)
        val dites = AlerteDiapo.litDites(runCatching { f.readText() }.getOrNull())
        if (cle in dites) return
        runCatching { f.writeText(AlerteDiapo.ecritDites(dites + cle)) }
        fr.f4ioz.satcombo.notify.ActivitesWorker.notifie(ctx, titre, texte, ("diapo:$cle").hashCode())
    }

    // ------------------------------------------------------------------ retention

    fun planRetention(ctx: Context, t: Long = System.currentTimeMillis()): RetentionSon.Plan {
        val o = options(ctx)
        val images = SstvHub.shots(ctx).map { it.second }
        val liees = RetentionSon.liees(images)
        val planches = RetentionSon.planches(runCatching { Planche.Rangement(File(ctx.filesDir, "planches")).modeles() }.getOrDefault(emptyList()))
        val enCours = fr.f4ioz.satcombo.audio.RecorderService.state.value.let { if (it.recording) listOfNotNull(it.fileName) else emptyList() }
        val gardes = gardes(ctx) + enCours
        val journal = if (o.retentionJournal) runCatching {
            JournalPassage.Rangement(File(ctx.filesDir, "journal")).passages().flatMap { it.enregistrements }.toSet()
        }.getOrDefault(emptySet()) else emptySet()
        val fichiers = (dossierRec(ctx).listFiles { f -> f.isFile && f.name.startsWith("SatMe_") && (f.name.endsWith(".mp3") || f.name.endsWith(".wav")) } ?: emptyArray())
            .map { RetentionSon.Fichier(it.name, it.length(), it.lastModified()) }
        return RetentionSon.plan(o.reglagesRetention, fichiers, liees.keys, liees, planches, gardes, journal, t)
    }

    /** The retention applied (nothing when off); the number deleted. */
    fun appliqueRetention(ctx: Context, pourquoi: String): Int {
        val r = options(ctx).reglagesRetention
        if (!r.actif) return 0
        val raison = listOfNotNull(if (r.jours > 0) "plus de ${r.jours} jours" else null, if (r.maxMo > 0) "au-delà de ${r.maxMo} Mo" else null).joinToString(", ") + " ($pourquoi)"
        return RetentionSon.applique(planRetention(ctx), dossierRec(ctx), File(dossierRec(ctx), "retention-sons.log"), raison, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ QSL and ARISS

    private suspend fun passagesAutour(ctx: Context, s: SstvMeta.SstvShot): List<SatPass> = runCatching {
        val norm = { x: String -> x.uppercase().replace(Regex("\\(.*?\\)"), "").replace(Regex("[^A-Z0-9]"), "") }
        val n = norm(s.satName)
        val sats = ActivitesTelephone.satellites(ctx)
        val sat = sats.firstOrNull { norm(it.name) == n } ?: sats.firstOrNull { n.isNotEmpty() && norm(it.name).startsWith(n) } ?: return emptyList()
        if (!PassPredictor.elementsUtilisables(sat) || s.timeMs <= 0) return emptyList()
        PassPredictor().upcomingPasses(sat, ActivitesTelephone.observateur(ctx), s.timeMs - 2 * 3_600_000L, 3, 0.0)
    }.getOrDefault(emptyList())

    private suspend fun infos(ctx: Context, s: SstvMeta.SstvShot): Triple<QslSstv.Infos, SatPass?, Int> {
        val st = SettingsStore(ctx)
        val acts = runCatching { ActivitesTelephone.enLigne(ctx).liste(st.reglagesActivites) }.getOrDefault(emptyList())
        val serie = acts.firstOrNull { it.type == fr.f4ioz.satcombo.domain.ActivitesAVenir.Type.SSTV && s.timeMs in (it.debutMs - 3_600_000L)..(it.finMs + 3_600_000L) }?.titre.orEmpty()
        // The series' groups first: their numbers include those the timetable estimates.
        val e = runCatching { ClassementTelephone.etat(ctx) }.getOrNull()
        val g = (e?.let { runCatching { ClassementTelephone.prevision(ctx, it) }.getOrNull()?.groupes }.orEmpty() + e?.groupes.orEmpty())
            .firstOrNull { gr -> gr.images.any { it.fileName == s.fileName } }
        val pas = passagesAutour(ctx, s)
        val i = QslSstv.infos(s, st.callsign, SstvHub.qthLocator.ifBlank { st.manualLocator }, pas, acts, emptyList(), serie.take(60),
            if (s.diapo > 0) s.diapo else g?.diapo ?: 0, if (s.diapos > 0) s.diapos else g?.total ?: 12, options(ctx).qslSatme)
        return Triple(i, QslSstv.passageDe(s, pas), g?.notes?.get(s.fileName) ?: -1)
    }

    /** The card drawn (Canvas) from [QslSstv]'s layout; the picture only read. */
    fun rend(src: Bitmap, i: QslSstv.Infos, d: QslSstv.Disposition, jpeg: Boolean): ByteArray {
        val m = QslSstv.mise(src.width, src.height, i, d)
        val out = Bitmap.createBitmap(m.largeur, m.hauteur, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF0E1A2B.toInt())
        c.drawBitmap(src, null, RectF(m.image.x, m.image.y, m.image.x + m.image.l, m.image.y + m.image.h), Paint(Paint.FILTER_BITMAP_FLAG))
        for (r in m.fonds) c.drawRect(r.x, r.y, r.x + r.l, r.y + r.h, Paint().apply { color = r.couleur })
        for (t in m.textes) {
            if (t.texte.isBlank()) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = t.couleur; textSize = t.taille; typeface = if (t.gras) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textAlign = if (t.align == QslSstv.Align.DROITE) Paint.Align.RIGHT else Paint.Align.LEFT
            }
            while (p.measureText(t.texte) > t.maxL && p.textSize > 6f) p.textSize = p.textSize * 0.94f
            c.drawText(t.texte, t.x, t.y - p.ascent(), p)
        }
        val o = ByteArrayOutputStream()
        out.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 92 else 100, o)
        out.recycle()
        return o.toByteArray()
    }

    /** The QSL of [png] written in the export folder (to share); null when it cannot be. */
    suspend fun qsl(ctx: Context, png: File, shot: SstvMeta.SstvShot): File? = runCatching {
        val o = options(ctx)
        val b = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        val octets = rend(b, infos(ctx, shot).first, o.qslDisposition, o.qslJpeg)
        b.recycle()
        File(export(ctx), QslSstv.nomFichier(png.name, o.qslJpeg)).apply { writeBytes(octets) }
    }.getOrNull()

    /** The ARISS award pack of [l] written in the export folder; null when none could go in. */
    suspend fun pack(ctx: Context, l: List<Pair<File, SstvMeta.SstvShot>>): File? = runCatching {
        val o = options(ctx)
        val t = System.currentTimeMillis()
        val entrees = l.take(12).mapNotNull { (f, s) ->
            // ARISS asks for the pictures as received: never a corrected or fused one.
            if (CorrectionsGalerie.estTraitee(f)) return@mapNotNull null
            val octets = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
            val b = BitmapFactory.decodeFile(f.absolutePath) ?: return@mapNotNull null
            val (i, p, note) = infos(ctx, s)
            val q = runCatching { rend(b, i, o.qslDisposition, o.qslJpeg) }.getOrNull()
            val e = PackAriss.Entree(s, octets, b.width, b.height, q, QslSstv.nomFichier(f.name, o.qslJpeg), i, p, note)
            b.recycle()
            e
        }
        if (entrees.isEmpty()) return null
        val st = SettingsStore(ctx)
        val poste = fr.f4ioz.satcombo.cat.ModeleIcom.entries.firstOrNull { it.id == st.rigModel }?.libelle ?: st.rigModel
        File(export(ctx), PackAriss.nomFichier(entrees.first().infos.indicatif, t)).apply { writeBytes(PackAriss.zip(entrees, PackAriss.materiel(null, poste), t)) }
    }.getOrNull()
}

/** One « diapo manquante attendue » notice ([OptionsSstvTelephone.planifieAlertes]): said once. */
class DiapoAttendueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val d = inputData
        val cle = d.getString("cle") ?: return Result.failure()
        OptionsSstvTelephone.dis(applicationContext, cle, d.getString("titre").orEmpty(), d.getString("texte").orEmpty(), d.getLong("aos", 0L))
        return Result.success()
    }
}
