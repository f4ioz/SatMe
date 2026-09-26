/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.UiState
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.SpaceBg
import fr.f4ioz.satcombo.ui.theme.SpaceCard
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saisir un contact, pendant le passage.
 *
 * L'écran est plein et le clavier occupe la moitié basse : on tape l'indicatif,
 * on valide, on recommence. Le contact porte le satellite affiché et l'heure de
 * sa validation.
 *
 * **Il ne reste rien de la file.** Cet écran l'a servie pendant sept versions —
 * un tampon posé pendant le contact, nommé après — et cet usage n'a jamais été
 * celui d'Olivier. La file laissait derrière elle un compteur qui ne menait
 * nulle part, une croix qui n'effaçait rien, et un bouton « ⏱ tampon » qui
 * enregistrait un second contact sans vider les champs : c'est lui qui a
 * inscrit F1FPL deux fois à une seconde d'intervalle le 25 août.
 *
 * Ce qui reste en tête — le satellite, l'heure, l'azimut, l'élévation — n'est
 * pas décoratif : ce sont les seules choses qui ne se retrouvent pas après
 * coup.
 */

@Composable
fun NommageScreen(ui: UiState, vm: MainViewModel) {
    // **Le contact est daté de sa validation, sans exception**, et il porte le
    // satellite affiché. Il n'y a rien d'autre à retrouver avant de saisir.
    val satCourant = ui.selected

    val enCw = ui.opMode == "CW"
    val rstDefaut = if (enCw) "599" else "59"
    // RS en phonie (deux chiffres), RST en CW (trois) : le T est le tonus
    // d'une note télégraphique, il n'existe pas en FM ni en BLU.
    val rstMax = if (enCw) 3 else 2
    // Mêmes raisons que la saisie : la file peut avancer pendant qu'on règle
    // un report, il n'a pas à revenir au défaut pour autant.
    var rstEnvoye by remember { mutableStateOf(rstDefaut) }
    var rstRecu by remember { mutableStateOf(rstDefaut) }
    // Le champ qui reçoit le clavier : 0 = RS envoyé, 1 = RS reçu,
    // 2 = locator, null = indicatif.
    //
    // **Sans clé de rappel.** Ces deux-là étaient rappelés sur `entree.timeMs`,
    // c'est-à-dire sur `System.currentTimeMillis()` relu à chaque composition :
    // la clé changeait à chaque rafraîchissement de position, donc une fois par
    // seconde. On touchait « Locator », le champ se vidait, et une seconde plus
    // tard le focus retombait sur l'indicatif sans rien dire — les lettres
    // suivantes partaient dans l'indicatif et le carré restait vide. C'est le
    // décalage relevé le 25 août. Le focus n'appartient qu'aux doigts : rien
    // d'autre ne le déplace, et la validation le remet à zéro elle-même.
    var rstActif by remember { mutableStateOf<Int?>(null) }
    var carreAvantFocus by remember { mutableStateOf("") }
    // Le suffixe a-t-il été posé par la touche « /P /M » ?
    //
    // La question n'a pas de réponse dans le texte : la touche de suffixe et
    // la touche « / » produisent la même barre. Seul le geste sait laquelle
    // des deux a servi, donc seul le geste peut le dire.
    var suffixePose by remember { mutableStateOf(false) }

    // **Le champ ne se vide qu'à la validation.** Il était remis à zéro dès que
    // l'entrée présentée changeait, c'est-à-dire à chaque rafraîchissement :
    // l'indicatif à moitié frappé partait au carnet, et c'est de là que
    // venaient les indicatifs tronqués.
    var saisie by remember { mutableStateOf("") }
    var carre by remember { mutableStateOf("") }
    var carreTouche by remember { mutableStateOf(false) }

    val memoire = ui.express.memoire

    // Le liseré d'émission, **ici aussi**.
    //
    // Il existe déjà tout autour de l'écran, dans le Box racine. Mais sur cet
    // écran-là les yeux sont sur les touches, en bas, et un filet de cinq
    // points au bord d'un téléphone tenu à bout de bras ne se voit pas quand
    // on cherche la lettre suivante. Le rappel se pose donc autour du clavier,
    // à l'endroit où le regard travaille.
    //
    // Même pulsation, même rouge : c'est un seul signal montré deux fois, et
    // non deux signaux à interpréter.
    val eclatTx = if (ui.catUi.enEmission) {
        val pulse = rememberInfiniteTransition(label = "txSaisie")
        pulse.animateFloat(
            initialValue = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700), repeatMode = RepeatMode.Reverse),
            label = "txSaisieAlpha").value
    } else 0f

    Column(Modifier.fillMaxSize().background(SpaceBg).padding(10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.fermeNommage() }) {
                Icon(Icons.Default.ArrowBack, null, tint = Cyan)
            }
            Text(t("entry_title"), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            // Le compteur de la file et la croix d'abandon sont partis avec
            // elle. La croix appelait `supprimeEntree` avec l'heure courante,
            // c'est-à-dire la clé d'une entrée qui n'existait pas : elle
            // n'effaçait rien, elle fermait l'écran. Une flèche de retour dit
            // déjà cela, et ne prétend rien de plus.
            IconButton(onClick = {
                vm.setSettingsSection("express")
                vm.openSettings()
            }) {
                Icon(Icons.Default.Keyboard, t("menu_express"),
                    tint = TextLo, modifier = Modifier.size(20.dp))
            }
        }

        // On saisit toujours ; seul un satellite manquant empêche d'enregistrer.
        if (satCourant == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(t("nommage_no_sat"), color = TextLo, fontSize = 14.sp)
            }
            return@Column
        }

        // ------------------------------------------------ les trois chiffres
        val tf = remember(ui.useUtc) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                if (ui.useUtc) timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        }
        Surface(color = SpaceCard, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Le satellite est cliquable : il se corrige ici, tant
                    // que le contact n'est pas parti dans l'ADIF.
                    var choixSat by remember { mutableStateOf(false) }
                    // Le nom et la mire sur la même rangée. **La mire passe en
                    // premier dans le partage de la largeur** : c'est le nom
                    // qui cède, jamais elle.
                    //
                    // Sans cela la rangée déborde — « JAS-2 (FO-29) », le
                    // compteur du passage et le cadran ne tiennent pas —, et
                    // Compose replie alors les textes de la mire caractère par
                    // caractère : « −58° ÉL » se lisait à la verticale, une
                    // lettre par ligne, et la carte doublait de hauteur en
                    // poussant tout l'écran vers le bas. Un alignement ne vaut
                    // que par la largeur de son conteneur.
                    //
                    // Le nom **remplit** la largeur qui reste (`fill` par
                    // défaut), et c'est ce qui repousse la mire au bord droit :
                    // les chiffres se lisent alors au même endroit quel que
                    // soit le satellite. En `fill = false` le nom ne prenait
                    // que sa propre largeur et la mire venait se coller contre
                    // lui, à mi-écran — la place gagnée l'avait été au prix de
                    // l'alignement.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(satCourant.name + " ▾", color = TextHi,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (satCourant.name.length > 9) 13.sp else 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                                .clickable { choixSat = true }
                                .padding(end = 6.dp))
                        // Qui a déjà été appelé pendant ce passage : on
                        // évite de rappeler deux fois la même station.
                        var listeFaits by remember { mutableStateOf(false) }
                        val faits = remember(ui.log.size, satCourant.catalogNumber,
                            ui.passes.size) { vm.indicatifsDuPassage() }
                        if (faits.isNotEmpty()) {
                            TextButton(
                                onClick = { listeFaits = true },
                                contentPadding = androidx.compose.foundation.layout
                                    .PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text("✓ ${faits.size}", color = Cyan, fontSize = 12.sp)
                            }
                        }
                        if (listeFaits) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { listeFaits = false },
                                confirmButton = {
                                    TextButton(onClick = { listeFaits = false }) {
                                        Text(t("close"), color = Cyan)
                                    }
                                },
                                title = { Text(t("worked_this_pass")) },
                                text = {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        faits.forEach { ind ->
                                            Text(ind, color = TextHi, fontSize = 16.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(vertical = 3.dp))
                                        }
                                    }
                                })
                        }
                        // Il y avait ici un bouton « ⏱ tampon », dernier reste
                        // de l'heure du tampon. Il appelait le même
                        // `ajouteContactDirect` que le gros bouton — même
                        // contact, même heure — mais sans vider les champs :
                        // deux appuis, deux contacts identiques. C'est lui qui
                        // a inscrit F1FPL à 12:12:42 puis à 12:12:43. Il n'y a
                        // qu'une façon d'enregistrer, et c'est ENREGISTRER.
                        MireClavier(ui.livePosition, Modifier)
                    }

                    if (choixSat) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { choixSat = false },
                            confirmButton = {},
                            title = { Text(t("nommage_sat_change")) },
                            text = {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    ui.satellites.forEach { s ->
                                        Text(s.name,
                                            color = if (s.name == satCourant.name) Cyan else TextHi,
                                            fontSize = 15.sp,
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable {
                                                    // On choisit le satellite
                                                    // sur lequel on trafique.
                                                    vm.select(s)
                                                    choixSat = false
                                                }
                                                .padding(vertical = 8.dp))
                                    }
                                }
                            })
                    }
                    // **L'heure et le temps qui reste, sur la même ligne.**
                    //
                    // Deux nombres courts, chacun sur sa rangée, mangeaient
                    // deux fois la hauteur pour rien — et cette hauteur est
                    // prise au clavier, qui est ce que l'écran a de plus
                    // précieux. L'heure à gauche, le rebours à droite : ils
                    // se lisent ensemble et coûtent une ligne.
                    //
                    // Rien à droite quand le satellite ne se couche pas. Sur
                    // QO-100 un compte à rebours n'aurait aucun sens, et un
                    // tiret laisserait croire à une donnée manquante.
                    val passage = ui.passes.firstOrNull {
                        ui.nowMs in it.aosEpochMs..it.losEpochMs
                    } ?: ui.passes.firstOrNull { it.aosEpochMs > ui.nowMs }
                    val maintenantTexte = tf.format(Date(ui.nowMs))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // L'heure qui court : le contact sera daté de sa
                        // validation. L'azimut et l'élévation sont au cadran ;
                        // les répéter en chiffres volerait la place du clavier.
                        Text(maintenantTexte + (if (ui.useUtc) " UTC" else " LOC"),
                            color = TextLo, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        if (passage != null) {
                            val leve = ui.nowMs in passage.aosEpochMs..passage.losEpochMs
                            val reste = if (leve) passage.losEpochMs - ui.nowMs
                                        else passage.aosEpochMs - ui.nowMs
                            Text(
                                (if (leve) t("los") else t("aos")) + " " + fmtCountdown(reste),
                                color = if (leve) Cyan else Amber,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
        // Le conteneur prend toute la largeur : sans cela, l'alignement à
        // droite du drapeau se fait sur la largeur du champ, et il retombe
        // au milieu — c'est ce qu'on voyait, une rangée perdue en hauteur.
        Box(Modifier.fillMaxWidth().clickable {
            if (rstActif == 2 && carre.isEmpty()) {
                carre = carreAvantFocus; carreTouche = false
            }
            rstActif = null
        }) {
            ChampIndicatif(saisie, Indicatifs.etat(saisie, memoire), nom = connu?.nom.orEmpty())

            // Le drapeau et le pays, **posés sur** le champ de l'indicatif
            // plutôt qu'en dessous : ils n'ont plus de rangée à eux, et cette
            // hauteur revient au clavier.
            val pays = remember(saisie) { fr.f4ioz.satcombo.domain.Dxcc.entite(saisie) }
            if (pays != null) {
                val f = fr.f4ioz.satcombo.data.Flags.ALL
                    .firstOrNull { it.code == pays.drapeau }
                Row(Modifier.align(Alignment.CenterEnd).padding(end = 30.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (f != null) {
                        val bmp = remember(pays.drapeau) {
                            fr.f4ioz.satcombo.data.FlagDraw.bitmap(f, 22)
                        }
                        Image(bmp.asImageBitmap(), null)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(pays.nom, color = TextLo, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        // Où pointer, pendant qu'on écrit : la page du clavier est celle où
        // l'on reste tout le passage.
        // Où l'on est dans le transpondeur : purement visuel, sans prise sur
        // la fréquence — un doigt qui vise une lettre ne doit pas déplacer le
        // VFO.
        BandePassante(
            // Les transpondeurs ne sont chargés que pour le satellite ouvert
            // dans l'écran de détail : hors de là, il n'y a pas de bande à
            // situer, et la barre ne s'affiche pas plutôt que de mentir.
            basHz = ui.transmitters.getOrNull(ui.selectedTxIndex)?.downlinkLowHz,
            hautHz = ui.transmitters.getOrNull(ui.selectedTxIndex)?.downlinkHighHz,
            courantHz = ui.rxRestHz ?: ui.catRadioDownlinkHz,
            modifier = Modifier.padding(top = 4.dp))



        Spacer(Modifier.height(6.dp))
        if (rstActif == 2) {
            // Les carrés déjà vus pour cette station, du plus récent au plus
            // ancien, filtrés par le début tapé. Un appui remplit et rend le
            // focus à l'indicatif : le geste est fini.
            val candidats = (connu?.locators ?: emptyList())
                .map { it.locator }
                .filter { carre.isEmpty() || it.startsWith(carre) }
                .distinct().take(5)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                candidats.forEach { g ->
                    Surface(color = SpaceCard, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 6.dp)
                            .clickable { carre = g; carreTouche = true; rstActif = null }) {
                        Text(g, color = Cyan, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        } else
        LigneSuggestions(
            suggestions = Indicatifs.suggestions(
                saisie, memoire, System.currentTimeMillis(), satCourant.name),
            onChoisir = { c ->
                // La suggestion se prend telle quelle. L'ancien code recollait
                // le suffixe déjà tapé — juste quand les entrées étaient
                // groupées par base, faux depuis qu'elles portent leur
                // suffixe : F5RRO proposé + « /P » en cours redonnait
                // F5RRO/P (l'appui semblait mort), et F5RRO/P proposé
                // fabriquait F5RRO/P/P.
                saisie = c.indicatif
                // Choisir une suggestion est un choix explicite : le carré de
                // cette entrée s'installe, même si le champ avait été touché —
                // c'est le second défaut relevé, le locator vidé au focus qui
                // ne se remplissait plus.
                carre = Indicatifs.locatorPropose(c, c.indicatif)
                carreTouche = false
                rstActif = null
            })

        Spacer(Modifier.weight(1f))

        // Le report, pré-rempli 59 (phonie) ou 599 (CW) : le cas courant ne
        // coûte aucun geste. Un appui sur un champ lui donne le focus — cyan —
        // et les chiffres du clavier s'y écrivent ; un second appui le rend à
        // l'indicatif. La rangée est volontairement basse : sa première
        // version avait poussé « Enregistrer à l'heure actuelle » hors de
        // l'écran.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Le locator, éditable comme les reports : un appui donne le
            // focus, le clavier écrit dedans — lettres et chiffres, six
            // caractères. La couleur cyan continue de dire « proposé, pas
            // vérifié » tant qu'on n'y a pas touché.
            Text(t("contact_locator_label"), color = TextLo, fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp))
            Surface(
                color = if (rstActif == 2) Cyan.copy(alpha = 0.18f) else SpaceCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(end = 10.dp)
                    .clickable {
                        if (rstActif == 2) {
                            // On quitte le focus : si rien n'a été tapé, le
                            // carré d'avant revient — l'appui par erreur ne
                            // coûte rien.
                            if (carre.isEmpty()) { carre = carreAvantFocus; carreTouche = false }
                            rstActif = null
                        } else {
                            carreAvantFocus = carre
                            carre = ""
                            carreTouche = true
                            rstActif = 2
                        }
                    },
            ) {
                Text(carre.ifBlank { "—" },
                    color = when {
                        rstActif == 2 -> Cyan
                        carreTouche -> TextHi
                        else -> Cyan
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Text(if (enCw) "RST" else "RS", color = TextLo, fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp))
            listOf(0 to rstEnvoye, 1 to rstRecu).forEach { (idx, valeur) ->
                Surface(
                    color = if (rstActif == idx) Cyan.copy(alpha = 0.18f) else SpaceCard,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp)
                        .clickable {
                            if (rstActif == 2 && carre.isEmpty()) {
                                carre = carreAvantFocus; carreTouche = false
                            }
                            rstActif = if (rstActif == idx) null else idx
                        },
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (idx == 0) "↑" else "↓",
                            color = if (rstActif == idx) Cyan else TextLo, fontSize = 11.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(valeur.ifBlank { "—" },
                            color = if (rstActif == idx) Cyan else TextHi,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }

        ClavierIndicatif(
            liseretTx = if (ui.catUi.enEmission)
                Color(0xFFFF2D2D).copy(alpha = eclatTx) else null,
            saisie = saisie,
            memoire = memoire,
            mainGauche = ui.express.mainGauche,
            disposition = ui.express.disposition,
            onCaractere = { c ->
                val actif = rstActif
                if (actif == 2) {
                    carre = (carre + c).take(6).uppercase()
                    carreTouche = true
                } else if (actif != null && c.isDigit()) {
                    // Le report se tape sur le même clavier que l'indicatif :
                    // pas de second clavier, pas de clavier système. Trois
                    // chiffres au plus — 599 est le plus long des reports
                    // usuels — et le champ repart à vide au premier chiffre
                    // s'il portait encore la valeur proposée.
                    if (actif == 0) {
                        rstEnvoye = ((if (rstEnvoye == rstDefaut) "" else rstEnvoye) + c).take(rstMax)
                    } else {
                        rstRecu = ((if (rstRecu == rstDefaut) "" else rstRecu) + c).take(rstMax)
                    }
                } else {
                    saisie = Indicatifs.ajoute(saisie, c, suffixePose)
                    if (!carreTouche || carre.isEmpty()) {
                        val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
                        carre = Indicatifs.locatorPropose(connu, saisie)
                    }
                }
            },
            onBarre = {
                // La barre s'ajoute à la fin, jamais en tête et jamais en
                // double : un indicatif ne commence pas par une barre, et deux
                // barres consécutives ne veulent rien dire.
                if (saisie.isNotEmpty() && !saisie.endsWith("/")) saisie += "/"
                // Ce qui suit appartient à l'indicatif, pas à un suffixe : les
                // lettres s'écriront dans l'ordre où on les entend.
                suffixePose = false
                // Un préfixe de pays change l'entité : le carré du correspondant
                // en métropole n'a plus rien à voir avec celui d'où il émet.
                if (!carreTouche || carre.isEmpty()) carre = ""
            },
            onSuffixe = {
                val (b, suf) = Indicatifs.separe(saisie)
                saisie = b + Indicatifs.suffixeSuivant(suf)
                // Le cycle repasse par « aucun suffixe » : la marque tombe avec
                // lui, sans quoi les lettres continueraient de s'insérer devant
                // une barre qui n'est plus là.
                suffixePose = Indicatifs.suffixe(saisie).isNotEmpty()
                // Le suffixe vient d'apparaître : le carré hérité n'a plus lieu
                // d'être, puisque le correspondant s'est déplacé.
                if (!carreTouche || carre.isEmpty()) {
                    val connu = memoire.firstOrNull { it.indicatif == Indicatifs.cle(saisie) }
                    carre = Indicatifs.locatorPropose(connu, saisie)
                }
            },
            onEfface = {
                val actif = rstActif
                if (actif == 2 && carre.isNotEmpty()) { carre = carre.dropLast(1); carreTouche = true }
                else if (actif == 0 && rstEnvoye.isNotEmpty()) rstEnvoye = rstEnvoye.dropLast(1)
                else if (actif == 1 && rstRecu.isNotEmpty()) rstRecu = rstRecu.dropLast(1)
                else if (saisie.isNotEmpty()) {
                    saisie = saisie.dropLast(1)
                    // Effacer le suffixe efface la marque : « F4IOZ/P » revenu
                    // à « F4IOZ/ » n'a plus de suffixe à protéger.
                    if (Indicatifs.suffixe(saisie).isEmpty()) suffixePose = false
                }
            },
            onValide = {
                val origine = when {
                    carre.isBlank() -> Indicatifs.OrigineLocator.INCONNU
                    carreTouche -> Indicatifs.OrigineLocator.SAISI
                    else -> Indicatifs.OrigineLocator.PROPOSE
                }
                // Le gros bouton enregistre **à l'heure actuelle**.
                //
                // C'est le geste courant : on valide en fin de contact, et
                // l'heure du contact est celle-là. L'heure du tampon — celle
                // du double appui sur la boussole — sert au rattrapage d'une
                // file en retard, cas plus rare : elle passe sur le petit
                // bouton du dessous.
                // Un contact neuf, à l'heure actuelle, sur le satellite
                // affiché. Pas de file à faire avancer.
                vm.ajouteContactDirect(satCourant, saisie, carre,
                    rstEnvoye, rstRecu, origine.name)
                // Tout se vide ici, et seulement ici — le focus compris, sans
                // quoi le report du contact suivant se taperait dans le champ
                // resté ouvert.
                saisie = ""; carre = ""; carreTouche = false
                rstEnvoye = rstDefaut; rstRecu = rstDefaut
                rstActif = null; carreAvantFocus = ""; suffixePose = false
            },
        )
    }
}
