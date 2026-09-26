/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.diag

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * Le mode échec.
 *
 * Cet écran est écrit contre lui-même : pas de Compose, pas de modèle de vue,
 * pas de thème de l'application, pas une seule chaîne tirée des ressources, pas
 * de fichier de mise en page. Les vues sont construites à la main et les
 * couleurs sont des entiers. Tout ce qu'on lui retire est une cause de panne en
 * moins qu'il partage avec l'écran qui ne s'ouvre pas — et c'est exactement ce
 * qu'on lui demande : s'ouvrir quand l'autre n'y arrive plus.
 *
 * Il ne répare rien de lui-même. Il montre le rapport que le garde-fou a écrit
 * lors de la chute précédente, ce qu'Android a retenu de la mort du processus,
 * et le résultat d'un démarrage démonté en épreuves. Puis il propose d'envoyer
 * le tout. C'est la boucle qui manquait : jusqu'ici la trace était écrite, et
 * personne ne pouvait aller la chercher.
 */
class EchecActivity : Activity() {

    private lateinit var texte: TextView
    private lateinit var colonne: LinearLayout
    private val fil = Handler(Looper.getMainLooper())

    private var entete = ""
    private var plantage: String? = null
    private var sorties = ""
    private var journal: String? = null
    private val resultats = mutableListOf<ModeEchec.Resultat>()
    private var confirmeEffacement = false

    private val francais: Boolean by lazy {
        Locale.getDefault().language.lowercase().startsWith("fr")
    }

    private fun m(fr: String, en: String) = if (francais) fr else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { construis() }.onFailure {
            // Si même cela échoue, il reste le texte brut : mieux vaut un écran
            // laid qu'un second écran qui ne s'ouvre pas.
            val t = TextView(this)
            t.setPadding(32, 64, 32, 32)
            t.text = ModeEchec.decrit(it)
            setContentView(t)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun construis() {
        val defile = ScrollView(this)
        defile.setBackgroundColor(FOND)
        colonne = LinearLayout(this)
        colonne.orientation = LinearLayout.VERTICAL
        colonne.setPadding(dp(16), dp(24), dp(16), dp(24))
        defile.addView(colonne, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(defile)

        colonne.addView(titre(m("SatMe — mode échec", "SatMe — failure mode")))
        colonne.addView(paragraphe(m(
            "Cet écran n'utilise rien de ce qui sert à l'application normale. " +
                "S'il s'ouvre alors que SatMe ne s'ouvre pas, la panne est dans " +
                "l'application et le rapport ci-dessous la nomme. Appuyez sur " +
                "« Lancer le diagnostic », puis sur « Envoyer le rapport ».",
            "This screen shares nothing with the normal app. If it opens while " +
                "SatMe does not, the fault is in the app and the report below " +
                "names it. Tap “Run diagnostics”, then “Send report”.")))

        colonne.addView(bouton(m("Lancer le diagnostic", "Run diagnostics")) { lanceDiagnostic() })
        colonne.addView(bouton(m("Envoyer le rapport", "Send report")) { envoie() })
        colonne.addView(bouton(m("Copier le rapport", "Copy report")) { copie() })
        colonne.addView(bouton(m("Lancer SatMe normalement", "Start SatMe normally")) {
            runCatching {
                startActivity(Intent(this, fr.f4ioz.satcombo.MainActivity::class.java))
            }.onFailure { avertit(ModeEchec.decrit(it)) }
        })
        colonne.addView(bouton(m("Vider le cache orbital", "Clear orbital cache")) {
            val f = File(filesDir, "tle_cache.txt")
            val fait = runCatching { !f.exists() || f.delete() }.getOrDefault(false)
            avertit(if (fait) m("Cache vidé.", "Cache cleared.") else m("Échec.", "Failed."))
        })
        colonne.addView(bouton(m("Effacer tous les réglages", "Erase all settings")) { efface() })

        texte = TextView(this)
        texte.setTextColor(TEXTE)
        texte.typeface = Typeface.MONOSPACE
        texte.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        texte.setTextIsSelectable(true)
        texte.setPadding(0, dp(16), 0, 0)
        colonne.addView(texte)

        collecte()
    }

    /** Ce qui se lit sans rien lancer : l'appareil, la trace, le registre. */
    private fun collecte() {
        entete = runCatching { EtatAppareil.entete(this) }.getOrElse { ModeEchec.decrit(it) }
        plantage = runCatching { PlantageDisque.lit(filesDir) }.getOrNull()
        val trace = runCatching { EtatAppareil.traceSysteme(this) }.getOrNull()
        sorties = runCatching {
            val bloc = ModeEchec.blocSorties(EtatAppareil.sorties(this))
            if (trace != null) "$bloc\n\n  Trace conservée par le système :\n$trace" else bloc
        }.getOrElse { ModeEchec.decrit(it) }
        journal = runCatching { EtatAppareil.journal() }.getOrNull()
        affiche()
    }

    private fun rapport(): String = ModeEchec.rapport(
        entete = entete,
        plantage = plantage,
        sorties = sorties,
        diagnostic = ModeEchec.bloc(resultats),
        journal = journal)

    private fun affiche() {
        texte.text = rapport()
    }

    /**
     * Une épreuve par tour de boucle de messages, et non toutes d'affilée : la
     * liste se remplit sous les yeux du testeur, et surtout la dernière ligne
     * affichée désigne l'épreuve en cours si le processus meurt pendant.
     */
    private fun lanceDiagnostic() {
        resultats.clear()
        val etapes = runCatching { Epreuves.liste(this) }.getOrElse {
            avertit(ModeEchec.decrit(it)); return
        }
        fun suivante(i: Int) {
            if (i >= etapes.size) {
                avertit(m("Diagnostic terminé.", "Diagnostics finished."))
                return
            }
            resultats += ModeEchec.execute(etapes[i])
            affiche()
            fil.post { suivante(i + 1) }
        }
        fil.post { suivante(0) }
    }

    /**
     * Le rapport complet part en pièce jointe, et sa tête dans le corps du
     * message : une pièce jointe se perd dans un partage par messagerie
     * instantanée, un corps trop long se fait couper par le client de courrier.
     * Les deux ensemble survivent à l'un comme à l'autre.
     */
    private fun envoie() {
        val complet = rapport()
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Plantage.DESTINATAIRE))
            putExtra(Intent.EXTRA_SUBJECT, "SatMe — rapport de mode échec")
            putExtra(Intent.EXTRA_TEXT, Plantage.corpsDuMail(Plantage.tronque(complet, 60_000)))
        }
        runCatching {
            val f = File(filesDir, FICHIER)
            f.writeText(complet)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f)
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(i, m("Envoyer à", "Send to"))) }
            .onFailure { avertit(ModeEchec.decrit(it)) }
    }

    private fun copie() {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SatMe", rapport()))
            avertit(m("Rapport copié.", "Report copied."))
        }.onFailure { avertit(ModeEchec.decrit(it)) }
    }

    /**
     * Deux appuis, parce que ceci efface le QTH, les favoris et les réglages du
     * poste. C'est la dernière chose à essayer, pas la première — mais quand un
     * réglage enregistré est ce qui tue le démarrage, c'est la seule qui marche.
     */
    private fun efface() {
        if (!confirmeEffacement) {
            confirmeEffacement = true
            avertit(m("Appuyez encore pour confirmer : tous les réglages seront perdus.",
                "Tap again to confirm: all settings will be lost."))
            return
        }
        confirmeEffacement = false
        val noms = listOf("satcombo_settings", "satcombo_favorites", "satcombo_satconfig",
            "satcombo_sources", "satcombo_tle_cache", "satcombo_pota", "satcombo_agenda")
        var faits = 0
        noms.forEach { n -> runCatching { if (deleteSharedPreferences(n)) faits++ } }
        avertit(m("$faits jeux de réglages effacés.", "$faits settings stores erased."))
        collecte()
    }

    private fun avertit(s: String) {
        runCatching { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    }

    private fun titre(s: String) = TextView(this).apply {
        text = s
        setTextColor(CYAN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun paragraphe(s: String) = TextView(this).apply {
        text = s
        setTextColor(TEXTE_BAS)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, 0, 0, dp(12))
    }

    private fun bouton(s: String, action: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { runCatching { action() } }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(6) }
    }

    private companion object {
        const val FICHIER = "rapport-mode-echec.txt"
        val FOND = Color.rgb(11, 16, 32)
        val TEXTE = Color.rgb(230, 232, 239)
        val TEXTE_BAS = Color.rgb(163, 172, 194)
        val CYAN = Color.rgb(77, 208, 225)
    }
}
