/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
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
 * Le clavier des indicatifs.
 *
 * Trente-six touches — A à Z, 0 à 9 — et rien d'autre. Pas de ponctuation, pas
 * de bascule majuscules, pas de rangée de chiffres à aller chercher : un
 * indicatif n'a besoin d'aucun des trois, et chacun coûterait de la place aux
 * touches qui servent vraiment.
 *
 * Le choix de six colonnes plutôt que dix n'est pas esthétique. Sur un
 * téléphone de 360 dp, dix colonnes donnent des touches de 34 dp — sous le
 * minimum recommandé pour un doigt, et très loin de ce qu'il faut avec des
 * gants. Six colonnes donnent 56 dp, soit près du double d'un clavier système.
 *
 * On aurait pu chercher à réduire le **nombre** de touches — un clavier groupé
 * façon téléphone. Le calcul dit le contraire : avec les suggestions au
 * troisième caractère, taper `F4H` puis appuyer sur la proposition fait quatre
 * appuis, carré compris. Un clavier groupé en demanderait cinq, plus les levées
 * d'ambiguïté sur les indicatifs inconnus. La prédiction a déjà pris le gain ;
 * il ne restait aux grosses touches qu'à agrandir la cible.
 *
 * Et une règle qui ne souffre aucune exception : **le clavier ne retire jamais
 * une touche**. Il grossit celles qui prolongent quelque chose de connu, il
 * laisse toutes les autres en place. Un clavier qui supprimerait les touches
 * improbables interdirait de noter le DX rare jamais contacté, c'est-à-dire
 * exactement celui pour lequel on avait sorti l'antenne.
 */
@Composable
fun ClavierIndicatif(
    saisie: String,
    memoire: List<Indicatifs.Connu>,
    mainGauche: Boolean,
    /** Disposition des touches : « abc », « azerty » ou « qwerty ». */
    disposition: String = "abc",
    onCaractere: (Char) -> Unit,
    onBarre: () -> Unit,
    onSuffixe: () -> Unit,
    onEfface: () -> Unit,
    onValide: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * La couleur du liseré d'émission, ou `null` quand le poste ne transmet
     * pas.
     *
     * Le clavier ne sait pas ce qu'est une émission et n'a pas à l'apprendre :
     * il reçoit une couleur ou rien. La pulsation est calculée par l'écran, en
     * même temps que celle du cadre général, pour que les deux battent
     * ensemble — deux rouges qui clignotent chacun de son côté se liraient
     * comme deux alarmes distinctes.
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
        // Les rangées n'ont pas toutes la même longueur en AZERTY et en
        // QWERTY — dix, dix, six. `weight` répartit dans chaque rangée
        // indépendamment : les touches d'une rangée courte sont donc plus
        // larges, exactement comme sur un clavier physique.
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

        // La rangée de commande, et le côté qui compte. Sur un écran tenu d'une
        // main, le pouce atteint bien son propre bord et mal celui d'en face :
        // validation et effacement doivent tomber du côté de la main qui tient.
        val commandes = @Composable {
            // La barre seule, distincte de la touche à suffixes.
            //
            // Les deux servent à des choses opposées : celle-ci ouvre un
            // **préfixe de pays** — `EA5/F5RRO`, l'indicatif d'un opérateur
            // français en Espagne — quand l'autre ajoute un **suffixe
            // d'exploitation** à la fin. Une seule touche pour les deux
            // obligerait à deviner lequel des deux on veut, et se tromperait la
            // moitié du temps.
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
 * La ligne de suggestions.
 *
 * Placée **entre le champ et le clavier**, et non au-dessus du champ : c'est la
 * zone que le pouce atteint le mieux, et c'est là que se joue toute l'économie
 * de temps. Trois propositions au maximum — au-delà, la ligne demande une
 * lecture au lieu d'un coup d'œil, et l'on a reperdu les secondes qu'on venait
 * gagner.
 *
 * Chaque proposition porte son carré et son nombre de contacts, parce que c'est
 * ce qui permet de choisir sans réfléchir entre deux indicatifs voisins.
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
 * Le champ de saisie et sa pastille d'état.
 *
 * La pastille informe, elle n'interdit rien : « déjà contacté », « format
 * plausible », « format inhabituel ». Le troisième cas n'empêche jamais la
 * validation.
 */
@Composable
fun ChampIndicatif(
    saisie: String,
    etat: Indicatifs.Etat,
    modifier: Modifier = Modifier,
    /**
     * Le nom du correspondant, à droite sur la même ligne.
     *
     * Il ne participe à rien : il se lit. Reconnaître « Olivier » d'un coup
     * d'œil vaut mieux que relire cinq caractères, et c'est ce qui permet de
     * valider sans hésiter au milieu d'un passage. La place existait, elle ne
     * servait à rien.
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

// Les dispositions vivent au domaine, avec leur banc : une lettre absente de
// la grille ne se verrait qu'au moment où un indicatif la réclame, en plein
// passage, et l'on croirait à une panne du clavier.

/** `remember` avec deux clés, pour ne pas recalculer les suites à chaque trame. */
@Composable
private fun <T> remember2(a: Any?, b: Any?, calcul: () -> T): T =
    androidx.compose.runtime.remember(a, b) { calcul() }
