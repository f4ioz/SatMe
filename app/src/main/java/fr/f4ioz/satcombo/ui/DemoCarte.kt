/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fr.f4ioz.satcombo.demo.ServeurDemo
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Le mode démonstration : laisser le public suivre le passage sur son téléphone.
 *
 * **Le contexte dicte tout.** L'opérateur est en 4G ou en partage de connexion,
 * jamais sur un réseau d'établissement. C'est donc son téléphone qui porte le
 * point d'accès, et les spectateurs s'y connectent. Rien ne sort du local, et
 * aucun internet n'est nécessaire — ce qui explique pourquoi la page servie
 * n'appelle aucune ressource extérieure : elle ne pourrait pas les charger.
 */
@Composable
fun DemoCarte(vm: fr.f4ioz.satcombo.MainViewModel) {
    val etat by ServeurDemo.etat.collectAsState()
    // Le partage Wi-Fi vit dans l'état du serveur : c'est là qu'il sert, et
    // c'est la seule copie. Un second porteur avait été ajouté par un autre
    // tour ; il a été retiré plutôt que gardé à côté.
    val qrw = remember(etat.ssid, etat.motDePasse) { ServeurDemo.qrWifi() }
    var ssid by remember(etat.ssid) { mutableStateOf(etat.ssid) }
    var mdp by remember(etat.motDePasse) { mutableStateOf(etat.motDePasse) }
    val presse = LocalClipboardManager.current

    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cast, null, tint = Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("demo_titre"), color = TextHi, fontWeight = FontWeight.Bold)
            }
            Text(t("demo_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("demo_activer"), color = TextHi, fontSize = 13.sp)
                    Text(
                        if (etat.actif) tf("demo_spectateurs", etat.spectateurs)
                        else t("demo_arrete"),
                        color = if (etat.actif) Cyan else TextLo, fontSize = 11.sp)
                }
                Switch(checked = etat.actif, colors = SwitchDefaults.colors(
                    checkedTrackColor = Cyan),
                    onCheckedChange = {
                        if (it) ServeurDemo.demarre() else ServeurDemo.arrete()
                    })
            }

            if (etat.panne == "port_occupe") {
                Text(t("demo_port_occupe"), color = Magenta, fontSize = 11.sp)
            }

            // --- le point d'accès à annoncer ---
            //
            // Android ne laisse plus une application lire le mot de passe de
            // son propre partage depuis la version 10. Il faut donc le saisir
            // une fois — ce qui reste préférable à le dicter à voix haute
            // devant une salle.
            Spacer(Modifier.height(12.dp))
            Text(t("demo_wifi_titre"), color = TextHi, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text(t("demo_wifi_desc"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp))
            var ssid by remember(etat.ssid) { mutableStateOf(etat.ssid) }
            var mdp by remember(etat.motDePasse) { mutableStateOf(etat.motDePasse) }
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it; vm.setDemoWifi(it, mdp) },
                label = { Text(t("demo_ssid"), fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = mdp,
                onValueChange = { mdp = it; vm.setDemoWifi(ssid, it) },
                // Le mot de passe s'affiche en clair, et c'est voulu : il va de
                // toute façon être montré en QR code à toute la salle, et le
                // masquer empêcherait de vérifier une faute de frappe.
                label = { Text(t("demo_mdp"), fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            // Le QR du Wi-Fi était dessiné **deux fois** : ici, et plus bas
            // dans le bloc qui l'ordonne avec celui de la page. Deux tours
            // l'avaient écrit chacun de son côté, l'un lisant `etat.wifiQr`,
            // l'autre `qrWifi()`. Celui-ci a disparu : il s'affichait hors de la
            // séquence « 1 puis 2 », ce qui donnait deux codes numérotés 1.

            // Le réseau à rejoindre est tenu par `ServeurDemo`, pas par
            // `UiState` : son constructeur frôle les 255 registres que la
            // machine virtuelle sait écrire, et deux champs de plus le feraient
            // mourir sans un mot du compilateur. La persistance passe par le
            // ViewModel, qui écrit dans les réglages et recharge au démarrage.

            if (etat.actif) {
                if (etat.adresse.isBlank()) {
                    // Sans adresse, le serveur tourne pour personne : c'est le
                    // cas quand le partage de connexion n'est pas encore allumé.
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Amber.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(t("demo_sans_reseau"), color = Amber, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                } else {
                    // **Deux codes, et l'ordre compte.** Le premier fait
                    // rejoindre le réseau, le second ouvre la page. Un seul QR
                    // ne peut pas faire les deux : les formats sont distincts,
                    // et aucun lecteur ne les enchaîne.
                    if (qrw.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(t("demo_qr1"), color = TextHi, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        QrCode(qrw, Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (qrw.isBlank()) t("demo_qr_desc") else t("demo_qr2"),
                        color = TextHi, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    QrCode(etat.url, Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                    Text(etat.url, color = Cyan, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { presse.setText(AnnotatedString(etat.url)) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(t("demo_copier"), color = Cyan, fontSize = 12.sp)
                    }
                }

                // Le bloc du partage Wi-Fi figurait ici une seconde fois :
                // deux tours interrompus l'avaient écrit chacun de leur côté.
                // Un seul suffit, et il est plus haut.

                Spacer(Modifier.height(8.dp))
                Surface(color = Cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(t("demo_son_enreg"), color = Cyan, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                // --- diagnostic ---
                //
                // L'opérateur n'a qu'un téléphone : pas de console de
                // navigateur pour savoir pourquoi le son se tait. Ces quatre
                // nombres le disent depuis l'application même.
                // --- le poste de commande ---
                //
                // Indépendant de la diffusion : on pilote souvent sans public,
                // et l'inverse arrive aussi. Deux interrupteurs, deux jetons.
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = SpaceBg)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("cmd_titre"), color = TextHi, fontWeight = FontWeight.Bold)
                        Text(t("cmd_desc"), color = TextLo, fontSize = 11.sp)
                    }
                    Switch(checked = etat.commandeActive,
                        onCheckedChange = {
                            if (it && !etat.actif) ServeurDemo.demarre()
                            ServeurDemo.commande(it)
                        })
                }
                if (etat.commandeActive && etat.urlCommande.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(etat.urlCommande, color = Cyan, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = {
                        presse.setText(AnnotatedString(etat.urlCommande))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(t("demo_copier"), color = Cyan, fontSize = 13.sp)
                    }
                    Text(t("cmd_code"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(etat.codeCommande, color = Amber, fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(t("cmd_aide"), color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(Modifier.height(10.dp))
                Text(t("demo_diag"), color = TextHi, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                val tic by produceState(0) {
                    while (true) { kotlinx.coroutines.delay(1000); value++ }
                }
                @Suppress("UNUSED_EXPRESSION") tic
                Text(
                    "écoutes %d · trames %d · encodé %d ko · envoyé %d ko".format(
                        ServeurDemo.clientsSon, ServeurDemo.tramesSon,
                        ServeurDemo.octetsEncodes / 1024,
                        ServeurDemo.octetsEnvoyes / 1024),
                    color = TextLo, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
                if (ServeurDemo.dernierePanneSon.isNotBlank()) {
                    Text(ServeurDemo.dernierePanneSon, color = Magenta, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }

                Spacer(Modifier.height(8.dp))
                Surface(color = Amber.copy(alpha = 0.13f), shape = RoundedCornerShape(8.dp)) {
                    Text(t("demo_marche"), color = Amber, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}


/**
 * Dessine un QR code.
 *
 * Pas d'image intermédiaire : la matrice se dessine directement en carrés sur
 * le canevas, ce qui évite d'allouer un bitmap à chaque recomposition et donne
 * un rendu net quelle que soit la taille.
 */
@Composable
private fun QrCode(contenu: String, modifier: Modifier = Modifier) {
    val matrice = remember(contenu) {
        runCatching {
            QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 45, 45)
        }.getOrNull()
    }
    Canvas(modifier) {
        val m = matrice ?: return@Canvas
        // Fond blanc obligatoire, et une marge : un QR sombre sur fond sombre
        // ne se lit pas, et sans marge les lecteurs peinent à le cadrer.
        drawRect(Color.White, Offset.Zero, size)
        val marge = size.minDimension * 0.06f
        val utile = size.minDimension - 2 * marge
        val p = utile / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) {
                drawRect(Color.Black,
                    Offset(marge + x * p, marge + y * p),
                    Size(p + 0.6f, p + 0.6f))
            }
        }
    }
}
