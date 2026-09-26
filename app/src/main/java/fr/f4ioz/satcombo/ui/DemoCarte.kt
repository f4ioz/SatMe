/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
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
 * Demo mode: let the audience follow the pass on their own phones.
 *
 * **Context drives everything.** The operator is on 4G or tethering, never on
 * a venue network, so their phone is the access point and viewers join it.
 * Nothing leaves the local network and no internet is needed — which is why
 * the served page loads no external resource: it could not.
 */
@Composable
fun DemoCarte(vm: fr.f4ioz.satcombo.MainViewModel) {
    val etat by ServeurDemo.etat.collectAsState()
    // The Wi-Fi share lives in the server state only. Keep a single copy.
    val qrw = remember(etat.ssid, etat.motDePasse) { ServeurDemo.qrWifi() }
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

            // --- the access point to advertise ---
            //
            // Since Android 10 an app cannot read its own hotspot password, so
            // it is typed once — still better than reading it out to a room.
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
                // Shown in clear on purpose: it goes on a QR code for the
                // whole room anyway, and masking it hides typos.
                label = { Text(t("demo_mdp"), fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            // The Wi-Fi QR is drawn only once, below, in the "1 then 2"
            // sequence. A second copy here once produced two codes numbered 1.

            // The network is held by `ServeurDemo`, not `UiState`: that
            // constructor is close to the VM's 255-register limit, and two more
            // fields would break it with no compiler warning. Persistence goes
            // through the ViewModel (saved in settings, reloaded at startup).

            if (etat.actif) {
                if (etat.adresse.isBlank()) {
                    // No address: tethering is not switched on yet.
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Amber.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(t("demo_sans_reseau"), color = Amber, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                } else {
                    // **Two codes, order matters.** The first joins the
                    // network, the second opens the page. One QR cannot do
                    // both: different formats, and no reader chains them.
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

                Spacer(Modifier.height(8.dp))
                Surface(color = Cyan.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(t("demo_son_enreg"), color = Cyan, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                // --- remote control ---
                //
                // Independent of the broadcast: you often control without an
                // audience, and the reverse happens too. Two switches, two tokens.
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

                // --- diagnostics ---
                //
                // The operator has only a phone, no browser console to tell why
                // the sound is silent. These four numbers show it in the app.
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
 * Draws a QR code straight onto the canvas: no bitmap allocated on each
 * recomposition, and sharp at any size.
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
        // White background and a quiet zone are required: dark-on-dark does
        // not scan, and without a margin readers struggle to frame it.
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
