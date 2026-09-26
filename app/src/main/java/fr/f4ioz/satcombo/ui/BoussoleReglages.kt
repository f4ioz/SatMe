/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.MainViewModel
import fr.f4ioz.satcombo.ble.BoussoleBle
import fr.f4ioz.satcombo.domain.BoussoleWit
import fr.f4ioz.satcombo.domain.PointageAntenne
import fr.f4ioz.satcombo.domain.SequenceCalibrage
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Le réglage de la boussole déportée.
 *
 * Trois choses à faire ici, dans cet ordre : choisir la source, désigner le
 * module, puis le caler. Le calage vient en dernier parce qu'il ne veut rien
 * dire tant qu'aucune trame n'arrive.
 */
@Composable
fun BoussoleCarte(vm: MainViewModel, ui: fr.f4ioz.satcombo.UiState) {
    val ctx = LocalContext.current
    val r = ui.rotor
    val etat by BoussoleBle.etat
    val raison by BoussoleBle.raison
    val cap by BoussoleBle.cap
    val brut by BoussoleBle.lacetBrut
    val attitude by BoussoleBle.attitudeBrute
    // Un seul lanceur pour l'export du relevé : le contrat ACTION_CREATE_DOCUMENT
    // est déjà écrit, inutile d'en refaire un.
    val enregistreReleve = rememberEnregistrer()
    // La flèche rangée, lue une fois : plusieurs blocs en ont besoin.
    val posee = PointageAntenne.depuisTexte(r.boussoleFleche)
    val trames by BoussoleBle.trames

    var azimutVise by remember { mutableStateOf("") }

    // Le relevé du nord attend celui de l'ouest. Rien n'est écrit tant que la
    // seconde visée n'est pas faite.
    var releveNord by remember { mutableStateOf<Float?>(null) }
    var messageCap by remember { mutableStateOf("") }

    // L'azimut **avant** calage ni inversion : c'est lui qu'il faut comparer
    // entre les deux visées, pas celui qu'on vient déjà de corriger.
    val brutVecteur: Float? = attitude?.let { att ->
        val f = BoussoleBle.fleche
        // Une flèche nulle n'a pas de direction : sans elle, pas de calage.
        if (f == null || f.norme < 0.1f) null
        // **Le même chemin de calcul que le calibrage**, sans exception.
        //
        // Le cadran passait par l'ancienne énumération, qui ne reconnaît pas
        // les conventions mesurées et retombait sur « directe » **en silence**.
        // SatMe mesurait donc une convention et en appliquait une autre : le
        // calibrage se déclarait bon et l'aiguille affichait un miroir exact,
        // azimut lu = 336° − azimut réel sur les quatre points cardinaux.
        else PointageAntenne.pointageLibre(
            att, f,
            PointageAntenne.ConventionLibre.decode(r.boussoleConvention)).azimutDeg
    }

    // L'apprentissage de l'axe se fait en deux temps : le relevé à plat attend
    // ici que le second geste vienne le confirmer. Rien n'est écrit tant que le
    // deuxième bouton n'a pas été touché.
    var relevePol1 by remember {
        mutableStateOf<fr.f4ioz.satcombo.domain.AttitudeWit?>(null) }
    var flecheApprise by remember {
        mutableStateOf<fr.f4ioz.satcombo.domain.Vec3?>(null) }
    var messageAppr by remember { mutableStateOf("") }

    // La première visée attend ici que la seconde vienne la confirmer. Rien
    // n'est écrit tant que les deux ne sont pas prises : une calibration à
    // moitié faite est une calibration fausse qu'on croit bonne.

    val demande = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { accords ->
        // Une permission refusée n'est pas un silence : sans cette branche,
        // l'opérateur appuie sur « Chercher », rien ne se passe, et rien ne dit
        // pourquoi.
        if (accords.values.all { it }) BoussoleBle.cherche(ctx)
        else {
            // Un refus silencieux, c'est un bouton qui ne fait rien.
            BoussoleBle.raison.value = "permissions"
            BoussoleBle.etat.value = BoussoleBle.Etat.ECHEC
        }
    }

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // **La miniature ne s'affiche qu'avec le module.**
            //
            // Elle annonce le boîtier Bluetooth ; quand la boussole du téléphone
            // est choisie, elle promet un appareil qui ne sert pas. Tout le
            // reste de cette carte était déjà conditionné au même choix : la
            // seule chose qui ne l'était pas était justement celle qui nomme
            // un matériel.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.boussoleSource == "BLE") {
                    MiniatureModule(Modifier.size(width = 34.dp, height = 46.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(t("bouss_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                    if (r.boussoleSource == "BLE") {
                        Text(t("bouss_modele"), color = Cyan, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(t("bouss_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TEL" to t("bouss_tel"), "BLE" to t("bouss_ble")).forEach { (id, label) ->
                    FilterChip(selected = r.boussoleSource == id,
                        onClick = { vm.setBoussoleSource(id) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Cyan.copy(alpha = 0.25f),
                            selectedLabelColor = Cyan))
                }
            }

            if (r.boussoleSource == "BLE") {
            // La notice n'est pas une recommandation : un module posé contre un
            // FT-817 lit l'aimant du haut-parleur, pas la Terre.
            Spacer(Modifier.height(10.dp))
            Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_avert"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }

            // --- le module ---
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("bouss_module"), color = TextHi, fontSize = 13.sp)
                    Text(
                        if (r.boussoleAdresse.isBlank()) t("bouss_aucun")
                        else "${r.boussoleNom.ifBlank { "?" }}  ${r.boussoleAdresse}",
                        color = TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                if (etat == BoussoleBle.Etat.RECHERCHE) {
                    TextButton(onClick = { BoussoleBle.arreteRecherche() }) {
                        Text(t("bouss_recherche"), color = Cyan, fontSize = 12.sp)
                    }
                } else {
                    TextButton(onClick = {
                        if (BoussoleBle.permissionsAccordees(ctx)) BoussoleBle.cherche(ctx)
                        else demande.launch(BoussoleBle.permissionsNecessaires())
                    }) { Text(t("bouss_chercher"), color = Cyan, fontSize = 12.sp) }
                }
            }

            if (BoussoleBle.trouves.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BoussoleBle.trouves.forEach { a ->
                    Row(Modifier.fillMaxWidth()
                        .clickable {
                            vm.setBoussoleModule(a.nom, a.adresse)
                            BoussoleBle.trouves.clear()
                            BoussoleBle.arreteRecherche()
                            BoussoleBle.connecte(ctx, a.adresse)
                        }
                        .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(a.nom, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${a.rssi} dBm", color = TextLo, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = SpaceSurface)
                }
            }

            // --- l'état ---
            Spacer(Modifier.height(10.dp))
            val (couleur, texte) = when (etat) {
                BoussoleBle.Etat.CONNECTE -> Color(0xFF2FB344) to t("bouss_etat_connecte")
                BoussoleBle.Etat.CONNEXION -> Cyan to t("bouss_etat_connexion")
                BoussoleBle.Etat.RECHERCHE -> Cyan to t("bouss_recherche")
                BoussoleBle.Etat.MUET -> Amber to t("bouss_etat_muet")
                BoussoleBle.Etat.ECHEC -> Magenta to messageEchec(raison)
                BoussoleBle.Etat.ARRET -> TextLo to t("bouss_etat_arret")
            }
            Text(texte, color = couleur, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

            if (cap != null) {
                Text(
                    "${t("bouss_cap")} ${Math.round(cap!!)}°   ·   $trames ${t("bouss_trames")}",
                    color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp))
            }

            if (etat != BoussoleBle.Etat.ARRET && etat != BoussoleBle.Etat.RECHERCHE) {
                TextButton(onClick = { BoussoleBle.coupe() }) {
                    Text(t("bouss_couper"), color = TextLo, fontSize = 12.sp)
                }
            }

            // **Un seul cadran, un seul calage.**
            //
            // Cette page portait deux cadrans et quatre calages — deux visées,
            // un azimut tapé à la main, un point cardinal, un retournement à
            // 180° — plus un interrupteur de sens. Tous répondaient à la même
            // question, et ils se contredisaient : un calage résiduel et un sens
            // inversé se superposaient au vecteur, et l'est sortait à l'ouest.
            //
            // Le vecteur de flèche détermine à lui seul le cap **et**
            // l'élévation, et il est insensible à la polarisation. Une seule
            // visée suffit à l'établir exactement. Tout le reste a été retiré,
            // pas désactivé : un réglage qu'on garde « au cas où » finit par
            // reprendre la main sans qu'on sache pourquoi.


            // --- le cadran de contrôle ---
            //
            // Un nombre ne se conteste pas facilement : « 187° » a l'air juste
            // tant qu'on ne le compare à rien. Une aiguille sur un cadran se
            // compare d'un coup d'œil au paysage, et c'est ainsi qu'Olivier a
            // vu que le nord et le sud étaient échangés.
            val capVu = cap
            if (capVu != null) {
                Spacer(Modifier.height(12.dp))
                // Les lettres sont mesurées hors du Canvas : `drawText` a besoin
                // d'un mesureur, et le créer à chaque image coûterait cher pour
                // quatre lettres qui ne changent jamais.
                val mesureur = androidx.compose.ui.text.rememberTextMeasurer()
                val styleCardinal = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace)
                Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    val r = minOf(size.width, size.height) / 2f - 30f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(SpaceSurface, r, androidx.compose.ui.geometry.Offset(cx, cy))
                    drawCircle(Cyan.copy(alpha = 0.35f), r, androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    // Les graduations intermédiaires, tous les trente degrés :
                    // elles donnent l'échelle sans encombrer, et permettent
                    // d'estimer un cap entre deux cardinaux.
                    for (k in 0 until 12) {
                        if (k % 3 == 0) continue
                        val a1 = Math.toRadians((k * 30).toDouble())
                        val sx = cx + (r - 6f) * kotlin.math.sin(a1).toFloat()
                        val sy = cy - (r - 6f) * kotlin.math.cos(a1).toFloat()
                        val ex = cx + r * kotlin.math.sin(a1).toFloat()
                        val ey = cy - r * kotlin.math.cos(a1).toFloat()
                        drawLine(TextLo.copy(alpha = 0.4f),
                            androidx.compose.ui.geometry.Offset(sx, sy),
                            androidx.compose.ui.geometry.Offset(ex, ey), 2f)
                    }
                    // **Les quatre points cardinaux, écrits.**
                    //
                    // Un trait plus long pour le nord se devine ; une lettre se
                    // lit. Sur un cadran qu'on consulte antenne en main, en
                    // plein soleil, le doute n'a pas sa place — et c'est
                    // précisément un nord pris pour un sud qui a coûté une
                    // partie de ces seize versions.
                    val lettres = listOf("N", "E", "S", "O")
                    for (k in 0 until 4) {
                        val a2 = Math.toRadians((k * 90).toDouble())
                        val l = if (k == 0) 16f else 10f
                        val sx = cx + (r - l) * kotlin.math.sin(a2).toFloat()
                        val sy = cy - (r - l) * kotlin.math.cos(a2).toFloat()
                        val ex = cx + r * kotlin.math.sin(a2).toFloat()
                        val ey = cy - r * kotlin.math.cos(a2).toFloat()
                        val teinte = if (k == 0) Magenta else TextHi
                        drawLine(teinte,
                            androidx.compose.ui.geometry.Offset(sx, sy),
                            androidx.compose.ui.geometry.Offset(ex, ey), 3f)
                        val mesure = mesureur.measure(lettres[k],
                            styleCardinal.copy(color = teinte))
                        val lx = cx + (r + 15f) * kotlin.math.sin(a2).toFloat() -
                            mesure.size.width / 2f
                        val ly = cy - (r + 15f) * kotlin.math.cos(a2).toFloat() -
                            mesure.size.height / 2f
                        drawText(mesure,
                            topLeft = androidx.compose.ui.geometry.Offset(lx, ly))
                    }
                    // L'aiguille : elle pointe là où SatMe croit que l'antenne vise.
                    val a3 = Math.toRadians(capVu.toDouble())
                    val sin = kotlin.math.sin(a3).toFloat()
                    val cos = kotlin.math.cos(a3).toFloat()
                    val px = cx + (r - 20f) * sin
                    val py = cy - (r - 20f) * cos
                    // Une queue à l'opposé : sans elle, une aiguille symétrique
                    // se lit aussi bien à cent quatre-vingts degrés près.
                    drawLine(Cyan.copy(alpha = 0.3f),
                        androidx.compose.ui.geometry.Offset(cx, cy),
                        androidx.compose.ui.geometry.Offset(cx - (r - 45f) * sin,
                            cy + (r - 45f) * cos), 4f)
                    drawLine(Cyan, androidx.compose.ui.geometry.Offset(cx, cy),
                        androidx.compose.ui.geometry.Offset(px, py), 6f)
                    // La pointe, en triangle : elle dit où est l'avant.
                    val pointe = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx + r * sin, cy - r * cos)
                        lineTo(cx + (r - 18f) * sin - 7f * cos,
                            cy - (r - 18f) * cos - 7f * sin)
                        lineTo(cx + (r - 18f) * sin + 7f * cos,
                            cy - (r - 18f) * cos + 7f * sin)
                        close()
                    }
                    drawPath(pointe, Cyan)
                    drawCircle(Cyan, 5f, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                Text(
                    "${t("bouss_cardinal")} ${cardinal(capVu)}  ·  ${Math.round(capVu)}°",
                    color = TextHi, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(t("bouss_cadran_desc"), color = TextLo, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            // Repartir de zéro, quand on ne sait plus où l'on en est.
            // Sans ce bouton, un étalonnage raté laisse des valeurs dont on ne
            // peut plus se défaire qu'en réinstallant.
            TextButton(onClick = {
                vm.setBoussoleFleche(null)
                vm.setBoussoleConvention("AXES_ECHANGES")
                vm.setBoussoleCalage(0f)
                messageAppr = t("bouss_remise_ok")
            }) {
                Text(t("bouss_remise"), color = Amber, fontSize = 12.sp)
            }

            // La section « convention du module » a été retirée, ainsi que le
            // calibrage à trois visées.
            //
            // **Elle répondait à un défaut qui n'existait pas.** Olivier a
            // rapporté « le nord donne le sud, l'ouest donne l'est » : les
            // quatre points tournent **ensemble**, donc c'est une rotation de
            // cent quatre-vingts degrés, pas un miroir — un miroir aurait
            // échangé est et ouest en laissant nord et sud en place. Et à ce
            // moment-là, l'horizontale, la verticale et l'élévation étaient
            // justes.
            //
            // Le seul défaut était une flèche apprise à l'envers. Miroirs,
            // repères et ordres de composition ont été empilés par-dessus une
            // cause imaginaire, et ont cassé l'élévation en chemin.

            // --- l'étalonnage sur un azimut connu ---
            //
            // Deux poses de polarisation donnent une droite, pas une direction :
            // le sens doit être deviné, et il peut se tromper de bout. Un azimut
            // connu ne laisse rien à deviner.
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Explore, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_nord_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_nord_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            // --- l'axe de montage, déclaré et non deviné ---
            //
            // C'est de la géométrie : on sait par quelle face le boîtier est
            // vissé sur la flèche. La déduire d'une visée magnétique y
            // incorporait l'erreur du magnétomètre — dix-sept degrés dans le
            // relevé d'Olivier — et l'élévation plafonnait d'autant.
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Straighten, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_axe_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_axe_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            val axes = listOf(
                Triple(t("bouss_axe_avant"), 0f, 1f),
                Triple(t("bouss_axe_arriere"), 0f, -1f),
                Triple(t("bouss_axe_droite"), 1f, 0f),
                Triple(t("bouss_axe_gauche"), -1f, 0f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                axes.forEach { (nom, x, y) ->
                    val v = fr.f4ioz.satcombo.domain.Vec3(x, y, 0f)
                    val choisi = posee != null &&
                        kotlin.math.abs(posee.x - x) < 0.05f &&
                        kotlin.math.abs(posee.y - y) < 0.05f &&
                        kotlin.math.abs(posee.z) < 0.05f
                    TextButton(onClick = {
                        vm.setBoussoleFleche(v)
                        // Le décalage hérité d'un autre axe n'a plus de sens.
                        vm.setBoussoleCalage(0f)
                        messageAppr = t("bouss_axe_ok")
                    }) {
                        Text(nom, color = if (choisi) Cyan else TextLo, fontSize = 12.sp,
                            fontWeight = if (choisi) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            // **L'avertissement qui manquait.**
            //
            // Le calcul était juste : une flèche apprise à trente-huit degrés
            // de l'axe réel donne une élévation qui plafonne à cinquante-deux
            // au lieu de quatre-vingt-dix, et c'est exactement ce qu'Olivier a
            // relevé. L'application ne peut pas savoir si le boîtier est de
            // travers — mais elle peut le dire.
            Surface(color = Amber.copy(alpha = 0.13f),
                shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_axe_avert"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            // **Deux visées, et le sens est mesuré au lieu d'être supposé.**
            //
            // Une seule visée fixe la flèche mais ne dit rien du sens dans
            // lequel le module compte le lacet : les deux hypothèses la
            // satisfont également. D'où le nord juste et l'est à l'ouest. La
            // seconde visée tranche — c'est une mesure, pas une case à cocher.
            // **Une seule visée, et elle est exacte.**
            //
            // Le calibrage à deux puis trois visées a été retiré : il servait à
            // mesurer une convention dont le module n'avait pas besoin. Une
            // visée cardinale détermine la flèche exactement — `b = Rᵀ·p` — et
            // si le bout est le mauvais, le bouton de retournement suffit.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(0f to t("bouss_nord"), 90f to t("bouss_est"),
                       180f to t("bouss_sud"), 270f to t("bouss_ouest"))
                    .forEach { (az, nom) ->
                    // **La visée ne touche plus à la flèche.**
                    //
                    // Elle l'apprenait, et c'était l'erreur de fond : une visée
                    // magnétique porte l'erreur du magnétomètre, et l'inscrire
                    // dans le vecteur désaligne celui-ci de l'axe réel du
                    // boîtier. Le relevé d'Olivier l'a chiffré — l'élévation
                    // plafonnait à 73° au lieu de 90, exactement l'effet d'une
                    // flèche déviée de dix-sept degrés.
                    //
                    // La flèche est désormais **déclarée** (la géométrie du
                    // montage, qu'on connaît), et la visée ne règle plus que le
                    // décalage de cap (le magnétisme, qu'on mesure). Deux
                    // questions distinctes, deux réglages distincts.
                    TextButton(enabled = attitude != null && posee != null, onClick = {
                        val pose = attitude ?: return@TextButton
                        val f2 = posee ?: return@TextButton
                        val lu = PointageAntenne.pointageLibre(
                            pose, f2,
                            PointageAntenne.ConventionLibre.decode(r.boussoleConvention)
                        ).azimutDeg
                        var d = (az - lu) % 360f
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        vm.setBoussoleCalage(d)
                        messageAppr = tf("bouss_nord_ok", nom)
                    }) {
                        Text(nom, color = if (attitude != null && posee != null) Cyan
                                          else TextLo,
                            fontSize = 13.sp)
                    }
                }
            }

            // **Le remède en un geste, et il est de retour.**
            //
            // Je l'avais supprimé en croyant qu'une visée cardinale faisait
            // mieux. C'était vrai en théorie et faux en pratique : quand la
            // flèche a été apprise par les deux poses de polarisation, elle
            // donne un axe — une droite, pas une direction — et le bout peut
            // être le mauvais. Rien d'autre n'est faux alors, et ce bouton
            // suffit.
            TextButton(enabled = r.boussoleFleche.isNotBlank(), onClick = {
                val f = PointageAntenne.depuisTexte(r.boussoleFleche)
                if (f == null) messageAppr = t("bouss_nord_echec")
                else {
                    vm.setBoussoleFleche(PointageAntenne.retourne(f))
                    messageAppr = t("bouss_retourne_ok")
                }
            }) {
                Text(t("bouss_retourne"), color = Amber, fontSize = 12.sp)
            }

            // --- la séquence de calibrage guidée ---
            //
            // **On ne devine plus, on relève.** Six versions ont tenté de
            // deviner la convention du module à partir d'une ou deux poses ;
            // chaque hypothèse tenait à l'horizontale et tombait ailleurs. Une
            // pose unique ne contraint qu'une partie de la rotation.
            //
            // La séquence prend les neuf poses qui, ensemble, déterminent tout :
            // le lacet sur un tour complet, le roulis dans les deux sens, le
            // tangage jusqu'à la verticale. Le relevé s'exporte, parce que
            // personne n'analyse neuf triplets de tête et que les recopier à la
            // main les corromprait.
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = SpaceSurface)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Rule, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_seq_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_seq_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))

            val releves = SequenceCalibrage.decode(r.boussoleReleves)
            val prochaine = SequenceCalibrage.prochaine(releves)

            if (prochaine != null) {
                Surface(color = Cyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(prochaine.titre, color = Cyan, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                        Text(prochaine.aide, color = TextHi, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        // Le dessin sous la consigne : une phrase se relit de
                        // travers, une image beaucoup moins. Deux relevés ont
                        // été perdus parce que l'arête visée à plat n'était pas
                        // celle qu'on a levée ensuite.
                        DessinPose(prochaine.cle)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Le bouton reste éteint tant qu'aucune trame n'arrive : relever
                // une attitude absente écrirait des zéros qu'on prendrait
                // ensuite pour une mesure.
                TextButton(enabled = attitude != null, onClick = {
                    attitude?.let { a ->
                        vm.ajouteReleve(prochaine.cle, a.roulis, a.tangage, a.lacet)
                    }
                }) {
                    Text(t("bouss_seq_relever"),
                        color = if (attitude != null) Cyan else TextLo,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(t("bouss_seq_complet"), color = Amber, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }

            Text(tf("bouss_seq_avancement", releves.size,
                    SequenceCalibrage.ETAPES.size),
                color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))

            // Le tableau, visible au fur et à mesure : on voit ce qu'on a fait,
            // et une valeur aberrante saute aux yeux avant l'export.
            SequenceCalibrage.ETAPES.forEach { e ->
                val v = releves.firstOrNull { it.cle == e.cle }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(if (v == null) "○" else "●",
                        color = if (v == null) TextLo else Cyan, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(e.cle, color = TextLo, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(78.dp))
                    Text(
                        if (v == null) "—"
                        else "%+7.1f %+7.1f %+7.1f".format(v.roulis, v.tangage, v.lacet),
                        color = if (v == null) TextLo else TextHi, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // --- le verdict, calculé sur place ---
            //
            // Le premier relevé a dû m'être envoyé pour être analysé. C'était
            // un aller-retour de trop : l'application sait faire ce calcul, et
            // l'opérateur a besoin du verdict pendant qu'il a encore l'antenne
            // en main.
            if (SequenceCalibrage.complete(releves)) {
                val an = SequenceCalibrage.analyse(releves)
                Spacer(Modifier.height(10.dp))
                // Le verdict porte sur le **pire** écart, pas sur la seule
                // dispersion des poses à plat : une convention qui les réussit
                // et rate la verticale est fausse, pas « plutôt bonne ».
                val franc = an.scoreDeg < 15f
                Text(
                    tf(if (franc) "bouss_an_bonne" else "bouss_an_dispersee",
                        Math.round(an.scoreDeg)),
                    color = if (franc) Cyan else Magenta, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                an.convention?.let { c ->
                    Text("${t("bouss_an_conv")} ${c.encode()}", color = TextLo,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (!franc) {
                    // La cause est presque toujours la même, et elle se dit.
                    Text(t("bouss_an_arete"), color = Amber, fontSize = 11.sp)
                }
                an.controles.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(if (c.bon) "✓" else "✗",
                            color = if (c.bon) Cyan else Magenta, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(c.cle, color = TextLo, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(72.dp))
                        Text(c.lu, color = if (c.bon) TextHi else Magenta,
                            fontSize = 11.sp)
                    }
                }
                val f = an.fleche
                if (f != null && franc) {
                    TextButton(onClick = {
                        vm.setBoussoleFleche(f)
                        vm.setBoussoleConvention(
                            an.convention?.encode()
                                ?: PointageAntenne.ConventionLibre.PAR_DEFAUT.encode())
                        // Le calage mesuré annule le reliquat d'étalonnage
                        // magnétique du module, que la pose verticale a permis
                        // de chiffrer. Le poser à zéro le réintroduirait.
                        vm.setBoussoleCalage(an.calageDeg)
                        messageAppr = t("bouss_an_applique")
                    }) {
                        Text(t("bouss_an_appliquer"), color = Cyan, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 6.dp)) {
                TextButton(enabled = releves.isNotEmpty(), onClick = {
                    val texte = SequenceCalibrage.rapport(
                        releves, r.boussoleNom, "20.04")
                    enregistreReleve(
                        "satme-boussole.txt", "text/plain", depuisTexte(texte))
                }) {
                    Text(t("bouss_seq_export"),
                        color = if (releves.isNotEmpty()) Cyan else TextLo, fontSize = 12.sp)
                }
                TextButton(enabled = releves.isNotEmpty(), onClick = { vm.videReleves() }) {
                    Text(t("bouss_seq_vider"), color = Amber, fontSize = 12.sp)
                }
            }

            // La vérification, écrite noir sur blanc. Un étalonnage qu'on ne
            // sait pas contrôler est un étalonnage auquel on ne peut pas se
            // fier — et c'est ce contrôle-là qui révèle un boîtier de travers.
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Cyan,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("bouss_verif_titre"), color = TextHi, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(t("bouss_verif"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp))
            // Ce que SatMe ne peut pas réparer, il doit au moins le nommer.
            Spacer(Modifier.height(8.dp))
            Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                Text(t("bouss_magneto"), color = Amber, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }

            Spacer(Modifier.height(12.dp))
            // L'état, dit sans jargon : soit le module donne le pointage
            // complet, soit il ne donne que le cap et l'élévation reste au
            // téléphone. Pas de troisième cas silencieux.
            Text(
                if (posee == null) t("bouss_fleche_absente")
                else "${t("bouss_fleche_presente")}  (%.2f, %.2f, %.2f)"
                    .format(posee.x, posee.y, posee.z),
                color = if (posee == null) TextLo else Cyan, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp))

            // **Les trois angles bruts, tels que le module les envoie.**
            //
            // Une élévation qui plafonne sous quatre-vingt-dix degrés ne vient
            // pas d'un décalage mais d'une matrice fausse : le calage à
            // l'horizontale la masque à la pose d'étalonnage et elle se révèle
            // dès qu'on s'en éloigne. Cinq hypothèses de convention ont été
            // tentées et réfutées ; celle-ci est la donnée qui manquait.
            //
            // Ces trois nombres disent sans ambiguïté ce que fait le module :
            // relevés à plat puis à la verticale, ils identifient l'axe qui
            // porte l'inclinaison, son signe et ses bornes. On ne devine plus.
            attitude?.let { a ->
                Text(
                    "R %+6.1f   T %+6.1f   L %+6.1f".format(
                        a.roulis, a.tangage, a.lacet),
                    color = Amber, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp))
                Text(t("bouss_bruts_desc"), color = TextLo, fontSize = 10.sp)
            }

            val elv = BoussoleBle.elevation.value
            if (elv != null) {
                Text("${t("bouss_elev")} ${Math.round(elv)}°",
                    color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp))
            }
            }
        }
    }
}

/**
 * La raison brute, rendue lisible.
 *
 * Les causes qu'on peut corriger soi-même ont leur phrase ; les autres tombent
 * dans un message générique qui **garde le code**. Un « Échec (liaison_perdue_8)
 * » ne dit rien à l'opérateur mais tout à celui qui devra le réparer, et c'est
 * l'opérateur qui le recopiera.
 */
/** Le nom lisible d'une convention : personne ne doit lire « AXES_ET_LACET ». */
private fun messageEchec(raison: String): String = when {
    raison == "permissions" -> t("bouss_err_permissions")
    raison == "pas_de_bluetooth" -> t("bouss_err_pas_de_bluetooth")
    raison == "bluetooth_eteint" -> t("bouss_err_bluetooth_eteint")
    raison == "rien_trouve" -> t("bouss_err_rien_trouve")
    raison == "service_absent" -> t("bouss_err_service_absent")
    raison.startsWith("liaison_perdue") -> t("bouss_err_liaison_perdue")
    raison.isBlank() -> t("bouss_err_autre")
    else -> "${t("bouss_err_autre")} ($raison)"
}

/** Le point cardinal le plus proche : « N », « NNE », « NE »… */
private fun cardinal(azimut: Float): String {
    val noms = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                       "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    var a = azimut % 360f
    if (a < 0f) a += 360f
    return noms[(((a + 11.25f) / 22.5f).toInt()) % 16]
}

/**
 * Le nom du point de compas le plus proche.
 *
 * « 187° » ne se vérifie pas d'un coup d'œil ; « S » si. C'est ce mot qui
 * permet de voir tout de suite qu'on regarde au sud en croyant viser le nord.
 */
private fun rose(capDeg: Float): String {
    val noms = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                      "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    var a = capDeg % 360f
    if (a < 0f) a += 360f
    return noms[(Math.round(a / 22.5f) % 16)]
}
