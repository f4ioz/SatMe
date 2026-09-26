/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.f4ioz.satcombo.domain.Indicatifs
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.ui.theme.Amber
import fr.f4ioz.satcombo.ui.theme.Aurora
import fr.f4ioz.satcombo.ui.theme.Cyan
import fr.f4ioz.satcombo.ui.theme.SpaceCard
import fr.f4ioz.satcombo.ui.theme.SpaceSurface
import fr.f4ioz.satcombo.ui.theme.TextHi
import fr.f4ioz.satcombo.ui.theme.TextLo

/**
 * Callsign keyboard.
 *
 * Thirty-six keys, A–Z and 0–9, nothing else: a callsign needs no
 * punctuation, shift or number row, and each would steal room from real keys.
 *
 * Six columns rather than ten is not aesthetic: on a 360 dp phone ten columns
 * give 34 dp keys, below the finger minimum and hopeless with gloves; six give
 * 56 dp. A phone-style grouped keypad would not save presses: with
 * suggestions from the third character, `F4H` + tap on the suggestion is four
 * presses; grouped keys would need five plus disambiguation.
 *
 * **The keyboard never removes a key.** It highlights keys that extend a known
 * callsign and leaves the rest in place. Dropping unlikely keys would make it
 * impossible to log the rare DX never worked before — the very one you set up
 * the antenna for.
 */
@Composable
fun ClavierIndicatif(
    saisie: String,
    memoire: List<Indicatifs.Connu>,
    mainGauche: Boolean,
    /** Key layout: "abc", "azerty" or "qwerty". */
    disposition: String = "abc",
    onCaractere: (Char) -> Unit,
    onBarre: () -> Unit,
    onSuffixe: () -> Unit,
    onEfface: () -> Unit,
    onValide: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * TX border colour, or `null` when not transmitting. The keyboard knows
     * nothing about TX; the screen computes the pulse together with the main
     * frame's so both beat in sync — two reds blinking independently read as
     * two separate alarms.
     */
    liseretTx: Color? = null,
) {
    val suites = remember2(saisie, memoire) { Indicatifs.suitesConnues(saisie, memoire) }

    Column(
        modifier.fillMaxWidth()
            .then(
                if (liseretTx != null)
                    Modifier.border(2.dp, liseretTx, RoundedCornerShape(10.dp)).padding(3.dp)
                else Modifier
            )
    ) {
        // AZERTY/QWERTY rows differ in length (10, 10, 6). `weight` works per
        // row, so keys in a short row are wider.
        fr.f4ioz.satcombo.domain.DispositionClavier.rangees(disposition).forEach { rangee ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rangee.forEach { c ->
                    Touche(
                        libelle = c.toString(),
                        misEnValeur = c in suites,
                        modifier = Modifier.weight(1f),
                    ) { onCaractere(c) }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Command row: one-handed, the thumb reaches its own edge, not the far
        // one, so save and delete go on the holding hand's side.
        val commandes = @Composable {
            // Plain slash, separate from the suffix key: it opens a **country
            // prefix** (`EA5/F5RRO`, a French operator in Spain) whereas the
            // other appends an **operating suffix**. One key for both would
            // guess wrong half the time.
            Touche(
                libelle = "/",
                misEnValeur = false,
                teinte = Amber,
                modifier = Modifier.weight(0.9f),
                onClick = onBarre,
            )
            Touche(
                libelle = t("kb_suffix"),
                misEnValeur = false,
                teinte = Amber,
                modifier = Modifier.weight(1.1f),
                onClick = onSuffixe,
            )
            Touche(
                libelle = "⌫",
                misEnValeur = false,
                teinte = Amber,
                modifier = Modifier.weight(1f),
                onClick = onEfface,
            )
        }
        val valider = @Composable {
            Touche(
                libelle = t("kb_save"),
                misEnValeur = false,
                teinte = Aurora,
                modifier = Modifier.weight(2f),
                onClick = onValide,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (mainGauche) { valider(); commandes() } else { commandes(); valider() }
        }
    }
}

/**
 * Suggestion row.
 *
 * Placed **between the field and the keyboard**, where the thumb reaches best.
 * Three suggestions at most: more needs reading instead of a glance. Each shows
 * grid square and contact count, to pick between similar callsigns at once.
 */
@Composable
fun LigneSuggestions(
    suggestions: List<Indicatifs.Connu>,
    onChoisir: (Indicatifs.Connu) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) {
        Spacer(modifier.height(46.dp))
        return
    }
    Row(
        modifier.fillMaxWidth().height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        suggestions.forEach { c ->
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Cyan.copy(alpha = 0.18f))
                    .clickable { onChoisir(c) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.indicatif, color = TextHi, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        listOfNotNull(
                            c.nom.ifBlank { null }?.substringBefore(' '),
                            c.locatorPrincipal.ifBlank { null },
                            "×${c.contacts}",
                        ).joinToString("  "),
                        color = TextLo, fontSize = 10.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Entry field and its status dot. The dot informs, never blocks: "worked
 * before", "plausible format", "unusual format". Unusual never prevents saving.
 */
@Composable
fun ChampIndicatif(
    saisie: String,
    etat: Indicatifs.Etat,
    modifier: Modifier = Modifier,
    /**
     * The other station's name, display only. Recognising "Olivier" at a
     * glance beats re-reading five characters mid-pass.
     */
    nom: String = "",
) {
    val couleur = when (etat) {
        Indicatifs.Etat.DEJA_CONTACTE -> Aurora
        Indicatifs.Etat.PLAUSIBLE -> Cyan
        Indicatifs.Etat.INHABITUEL -> Amber
        Indicatifs.Etat.VIDE -> TextLo
    }
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SpaceSurface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            saisie.ifBlank { t("kb_call_hint") },
            color = if (saisie.isBlank()) TextLo else TextHi,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        if (nom.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                nom,
                color = Aurora,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier.height(10.dp).aspectRatio(1f)
                .clip(RoundedCornerShape(5.dp)).background(couleur)
        )
    }
}

@Composable
private fun Touche(
    libelle: String,
    misEnValeur: Boolean,
    modifier: Modifier = Modifier,
    teinte: Color? = null,
    onClick: () -> Unit,
) {
    val fond = teinte?.copy(alpha = 0.22f)
        ?: if (misEnValeur) Cyan.copy(alpha = 0.24f) else SpaceCard
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fond)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            libelle,
            color = teinte ?: if (misEnValeur) Cyan else TextHi,
            fontSize = if (libelle.length > 2) 13.sp else 20.sp,
            fontWeight = if (misEnValeur) FontWeight.Black else FontWeight.SemiBold,
            fontFamily = if (libelle.length > 2) FontFamily.Default else FontFamily.Monospace,
        )
    }
}

// Layouts live in the domain layer with their tests: a letter missing from a
// grid would otherwise only show up mid-pass, when a callsign needs it.

/** Two-key `remember`, so suggestions aren't recomputed every frame. */
@Composable
private fun <T> remember2(a: Any?, b: Any?, calcul: () -> T): T =
    androidx.compose.runtime.remember(a, b) { calcul() }
