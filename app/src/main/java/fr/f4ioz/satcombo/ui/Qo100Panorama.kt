/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.ui.theme.Magenta
import fr.f4ioz.satcombo.ui.theme.SpaceSurface
import fr.f4ioz.satcombo.ui.theme.TextHi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Largeur du panorama en colonnes. 500 kHz / 512 ≈ 977 Hz par colonne. */
private const val PCOLS = 512

/** Hauteur de l'histoire gardée : à une trame toutes les 300 ms, une demi-minute. */
private const val PROWS = 80

/**
 * Valeur écrite dans une colonne que la clé ne reçoit pas.
 *
 * Ce n'est pas un niveau : c'est une absence. Une colonne hors fenêtre ne doit
 * ni compter dans la mise à l'échelle, ni être peinte comme du bruit très bas —
 * les deux mentiraient. Elle se peint en gris sombre, ce qui se lit tout de
 * suite comme « je ne regarde pas là ».
 */
private const val HORS = -999f

/** Le gris des colonnes non reçues. Assez clair pour ne pas passer pour du noir. */
private const val COULEUR_HORS = 0xFF202430.toInt()

/**
 * Le transpondeur étroit vu par la clé, à l'échelle de la réglette.
 *
 * ### Pourquoi cette vue existe
 *
 * La cascade ordinaire de l'application montre ce que la clé reçoit *autour de
 * son accord* : une fenêtre étroite qui se déplace avec le VFO. C'est ce qu'il
 * faut pour suivre un correspondant, et c'est inutilisable pour comprendre un
 * transpondeur. Ici on veut l'inverse : un axe **fixe**, gradué en fréquences
 * du ciel, du bas au haut du plan de bande, sur lequel les balises sont
 * toujours au même endroit et où l'on voit d'un coup d'œil où il y a du monde.
 *
 * ### La coïncidence qui rend la chose possible
 *
 * La clé numérise 1 058 400 Hz d'un coup, soit ±529 200 Hz autour de sa boucle.
 * Le plan de bande étroit en fait 500 000. Autrement dit : **où que la clé soit
 * accordée dans le transpondeur, le transpondeur entier reste dans sa fenêtre**.
 * Il n'y a donc ni balayage, ni changement de PLL, ni recollage de morceaux —
 * on lit une seule FFT et on la range dans un axe absolu. La marge dans le pire
 * cas est de 29,2 kHz, et les bords d'un tuner sont mous : les colonnes
 * extrêmes peuvent s'assombrir un peu quand l'accord est tout en bout de bande.
 * C'est visible et sans conséquence, la fenêtre restant centrée sur le trafic.
 *
 * ### Comment l'axe reste immobile
 *
 * Le rééchantillonnage se fait **avant** l'empilement dans l'histoire, pas au
 * dessin. Chaque trame arrive avec le centre du ciel qui lui correspond, et
 * elle est immédiatement projetée sur les colonnes absolues de la réglette. Si
 * l'opérateur change de fréquence, ou si le Doppler bouge la PLL, les lignes
 * déjà empilées restent justes — elles avaient été rangées avec leur propre
 * centre. Une cascade qui glisserait à chaque coup de VFO ne servirait à rien.
 *
 * @param pan la FFT brute de la clé, rangée du plus bas au plus haut *indice*.
 * @param centreCielHz la fréquence du ciel qui tombe au milieu de [pan].
 * @param etendueCielHz la largeur couverte, **signée** : négative derrière une
 *        injection haute, auquel cas le tableau se parcourt à l'envers.
 * @param descenteHz la fréquence de travail, dessinée en curseur.
 */
@Composable
fun PanoramaQo100(
    pan: FloatArray,
    centreCielHz: Double,
    etendueCielHz: Double,
    descenteHz: Long,
    modifier: Modifier = Modifier,
    hauteur: Dp = 130.dp,
    onTune: (Long) -> Unit,
) {
    val history = remember { ArrayList<FloatArray>(PROWS) }
    var version by remember { mutableIntStateOf(0) }
    val pixels = remember { IntArray(PCOLS * PROWS) }
    val bitmap = remember { Bitmap.createBitmap(PCOLS, PROWS, Bitmap.Config.ARGB_8888) }
    val image: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // Une trame neuve arrive : on la projette tout de suite sur l'axe absolu,
    // puis on l'empile. Le tableau est neuf à chaque fois, donc l'égalité
    // d'identité de Compose suffit à déclencher l'effet.
    LaunchedEffect(pan) {
        val col = colonnes(pan, centreCielHz, etendueCielHz)
        if (col != null) {
            history.add(col)
            while (history.size > PROWS) history.removeAt(0)
            version++
        }
    }

    val bas = Qo100.REGLETTE_BAS_HZ
    val etendue = (Qo100.REGLETTE_HAUT_HZ - bas).toDouble()

    fun hz(x: Float, largeur: Int): Long {
        val r = (x / largeur.coerceAtLeast(1)).coerceIn(0f, 1f)
        return bas + (r * etendue).toLong()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(hauteur)
            .clip(RoundedCornerShape(8.dp))
            .background(SpaceSurface)
            .pointerInput(Unit) {
                detectTapGestures { p -> onTune(hz(p.x, size.width)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onTune(hz(change.position.x, size.width))
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(hauteur)) {
            @Suppress("UNUSED_EXPRESSION") version
            if (history.isNotEmpty()) {
                peindre(history, pixels)
                bitmap.setPixels(pixels, 0, PCOLS, 0, 0, PCOLS, PROWS)
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(PCOLS, PROWS),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low)
            }

            val l = size.width
            fun x(f: Long): Float = (((f - bas) / etendue) * l).toFloat()

            // Les repères du plan de bande : quatre balises et deux fréquences
            // réservées, aux mêmes abscisses que sur la réglette juste au-dessus.
            // Ils sont la preuve visuelle que l'étalonnage est bon : si la raie
            // de la balise ne tombe pas sur son trait, le LNB a dérivé.
            Qo100.SEGMENTS.forEach { s ->
                val r = s.repereHz ?: return@forEach
                drawLine(
                    color = Magenta.copy(alpha = 0.55f),
                    start = Offset(x(r), 0f),
                    end = Offset(x(r), size.height),
                    strokeWidth = 1.dp.toPx())
            }

            // Le curseur d'accord.
            val xc = x(descenteHz).coerceIn(0f, l)
            drawLine(
                color = TextHi,
                start = Offset(xc, 0f),
                end = Offset(xc, size.height),
                strokeWidth = 2.dp.toPx())
            drawCircle(color = TextHi, radius = 3.dp.toPx(), center = Offset(xc, 3.dp.toPx()))
        }
    }
}

/**
 * Projette une FFT sur les [PCOLS] colonnes fixes de la réglette.
 *
 * Chaque colonne prend le **maximum** des raies qui tombent dedans, jamais leur
 * moyenne : une porteuse SSB tient dans deux ou trois raies sur la quinzaine
 * que couvre une colonne, et une moyenne la noierait dans le bruit voisin. Une
 * cascade qui efface les signaux faibles n'a aucun intérêt.
 *
 * Rend `null` quand il n'y a rien d'exploitable, pour que l'appelant n'empile
 * pas une ligne vide dans l'histoire.
 */
private fun colonnes(pan: FloatArray, centreHz: Double, etendueHz: Double): FloatArray? {
    val n = pan.size
    if (n < 16 || etendueHz == 0.0 || centreHz <= 0.0) return null
    val hzParRaie = etendueHz / n
    val bas = Qo100.REGLETTE_BAS_HZ.toDouble()
    val haut = Qo100.REGLETTE_HAUT_HZ.toDouble()
    val out = FloatArray(PCOLS)
    var utiles = 0
    for (c in 0 until PCOLS) {
        val f0 = bas + (haut - bas) * c / PCOLS
        val f1 = bas + (haut - bas) * (c + 1) / PCOLS
        val i0 = n / 2.0 + (f0 - centreHz) / hzParRaie
        val i1 = n / 2.0 + (f1 - centreHz) / hzParRaie
        var a = floor(min(i0, i1)).toInt()
        var b = ceil(max(i0, i1)).toInt()
        if (b < 0 || a >= n) { out[c] = HORS; continue }
        a = a.coerceIn(0, n - 1)
        b = b.coerceIn(0, n - 1)
        var m = -160f
        for (k in a..b) if (pan[k] > m) m = pan[k]
        out[c] = m
        utiles++
    }
    // Tout hors fenêtre : l'accord est ailleurs, la ligne ne dirait rien.
    return if (utiles < PCOLS / 8) null else out
}

/** Remplit [pixels] avec l'histoire, la ligne récente en haut. */
private fun peindre(history: List<FloatArray>, pixels: IntArray) {
    val rows = history.size
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (r in history) for (v in r) {
        if (v == HORS) continue
        if (v < lo) lo = v
        if (v > hi) hi = v
    }
    if (lo == Float.MAX_VALUE) lo = -120f
    // Au moins vingt décibels d'échelle, sinon du bruit seul se peindrait en
    // montagnes et donnerait l'illusion d'un transpondeur plein.
    if (hi < lo + 20f) hi = lo + 20f
    val inv = 1f / (hi - lo)
    for (r in 0 until PROWS) {
        val base = r * PCOLS
        if (r >= rows) {
            java.util.Arrays.fill(pixels, base, base + PCOLS, 0xFF000000.toInt())
            continue
        }
        val c = history[rows - 1 - r]
        for (x in 0 until PCOLS) {
            val v = c[x]
            pixels[base + x] =
                if (v == HORS) COULEUR_HORS
                else heat(((v - lo) * inv).coerceIn(0f, 1f))
        }
    }
}
