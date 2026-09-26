/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.*

/**
 * Credits, in the "About" section of the settings.
 *
 * **This is the only place for them.** A separate screen in the overflow menu
 * once duplicated it, and users opened the settings one and found nothing.
 *
 * SatMe builds on published protocols, open logbooks, orbital elements and free
 * libraries. Crediting them is the normal return for what was received, and for
 * some sources a condition of use.
 */

private data class Remerciement(
    val nom: String,
    val qui: String,
    val quoi: String,
    val lien: String? = null
)

private val SOURCES = listOf(
    Remerciement(
        "ft8_lib",
        "Kārlis Goba — licence MIT",
        "Les tables du code correcteur (174, 91) de FT8 et FT4 : matrice " +
            "génératrice et graphe de parité. La licence MIT autorise cet usage " +
            "à condition d'en conserver la mention — c'est cette ligne.",
        "https://github.com/kgoba/ft8_lib"
    ),
    Remerciement(
        "Wavelog",
        "Ein Wavelog et ses contributeurs",
        "Le carnet de trafic auquel SatMe verse ses contacts, et qui lui dit " +
            "quels carrés voisins restent à faire. Wavelog est un fork de " +
            "Cloudlog, dont il a repris et prolongé le travail.",
        "https://www.wavelog.org/"
    ),
    Remerciement(
        "Cloudlog",
        "Peter Goodhall 2M0SQL",
        "Le carnet d'origine, dont Wavelog est issu et dont SatMe parle encore " +
            "l'interface.",
        "https://www.magicbug.co.uk/cloudlog/"
    ),
    Remerciement(
        "QRZ.com",
        "QRZ LLC",
        "L'annuaire qui met un nom et un carré derrière un indicatif entendu.",
        "https://www.qrz.com/"
    ),
    Remerciement(
        "Logbook of the World",
        "ARRL",
        "La confirmation des contacts, et la source des carrés confirmés.",
        "https://lotw.arrl.org/"
    ),
    Remerciement(
        "AMSAT et SatNOGS",
        "AMSAT-NA, Libre Space Foundation",
        "L'état de santé des satellites : lesquels répondent encore, sur quelle " +
            "bande, et lesquels se sont tus.",
        "https://db.satnogs.org/"
    ),
    Remerciement(
        "CelesTrak",
        "T.S. Kelso",
        "Les éléments orbitaux, tenus à jour depuis plus de trente ans.",
        "https://celestrak.org/"
    ),
    Remerciement(
        "SGP4",
        "David Vallado et le NORAD",
        "Le modèle de propagation qui calcule les passages, en local et sans réseau.",
        "https://celestrak.org/publications/AIAA/2006-6753/"
    ),
    Remerciement(
        "WebSDR QO-100",
        "IS0GRB",
        "La référence de fréquence qui sert à étalonner les postes sur QO-100.",
        "https://websdr.is0grb.it:8901/"
    ),
    Remerciement(
        "Maidenhead",
        "John Morris G4ANB",
        "Le système de carrés locateurs, adopté en 1980 et jamais remplacé depuis.",
        null
    )
)

@Composable
fun AProposReferences() {
    val liens = LocalUriHandler.current
    // No scrolling of its own: the settings list already scrolls, and nested
    // scrolls would fight over the finger.
    Surface(color = SpaceCard, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t("apropos_titre"), color = TextHi, fontWeight = FontWeight.Bold)
            Text(t("apropos_intro"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))

            SOURCES.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(
                    color = SpaceSurface, modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    Modifier.fillMaxWidth().then(
                        if (r.lien != null) Modifier.clickable {
                            // Must not crash: some phones have no browser registered.
                            runCatching { liens.openUri(r.lien) }
                        } else Modifier
                    )
                ) {
                    Text(r.nom, color = TextHi, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(r.qui, color = Cyan, fontSize = 11.sp)
                    Text(r.quoi, color = TextLo, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp))
                    if (r.lien != null) {
                        Text(r.lien, color = Cyan, fontSize = 10.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            HorizontalDivider(color = SpaceSurface,
                modifier = Modifier.padding(vertical = 10.dp))
            Text(t("apropos_pied"), color = TextLo, fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
    }
}
