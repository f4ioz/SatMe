/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.pc

import fr.f4ioz.satcombo.demo.ApiCorrectionSstv
import fr.f4ioz.satcombo.sstv.ClassementGalerie
import fr.f4ioz.satcombo.sstv.CorrectionsGalerie
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The corrected SSTV pictures on the PC ([CorrectionsGalerie]: kept apart in
 * `corrigees/`, the received ones untouched): the viewer's « Corrigée »,
 * « Re-décoder depuis l'enregistrement », « Fusionner les copies », and the
 * sheet's « versions corrigées ».
 */
class CorrectionsSstvPc(sstv: SstvPc, classement: ClassementSstvPc) {

    private fun pixels(f: File): ClassementGalerie.Pixels? = runCatching {
        val b = ImageIO.read(f) ?: return null
        ClassementGalerie.Pixels(b.getRGB(0, 0, b.width, b.height, null, 0, b.width), b.width, b.height)
    }.getOrNull()

    private fun ecrit(f: File, px: IntArray, w: Int, h: Int): Boolean = runCatching {
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        b.setRGB(0, 0, w, h, px, 0, w)
        // Written beside, then put in place: a page asking for it meanwhile never gets half a PNG.
        val t = File(f.parentFile, f.name + ".tmp")
        ImageIO.write(b, "png", t) && (t.renameTo(f) || run { f.delete(); t.renameTo(f) })
    }.getOrDefault(false)

    val api = ApiCorrectionSstv(CorrectionsGalerie(::pixels, ::ecrit), { sstv.dossier },
        { sstv.images().filter { it.source != SstvPc.ESSAI } }, { classement.etat().groupes })
}
