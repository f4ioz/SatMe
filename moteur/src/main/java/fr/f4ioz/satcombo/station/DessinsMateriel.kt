/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.station

import fr.f4ioz.satcombo.station.SceneMontage.Pinceau

/**
 * The hardware's drawings, as the « Montage » page draws them (its
 * `MT_D`): SatMe's own stylised front panels, never a maker's picture or
 * mark. The same shapes, colours and proportions, for a drawing made away
 * from the page (the event's report).
 */
object DessinsMateriel {

    private fun f(v: Double) = SceneMontage.n(v)
    private fun f(v: Int) = v.toString()

    private fun Pinceau.sma(x: Double, y: Double, v: Boolean = false) =
        if (v) R(x - 3, y, 6.0, 7.0, 1.0, "#C9A54A", "#8A6E2A", .6) else R(x, y - 3, 7.0, 6.0, 1.0, "#C9A54A", "#8A6E2A", .6)
    private fun Pinceau.fco(x: Double, y: Double) { R(x, y - 4, 8.0, 8.0, 1.5, "#B9C2CC", "#6E7883", .6); C(x + 4, y, 1.4, "#3A434D") }
    private fun Pinceau.usbC(x: Double, y: Double) = R(x, y - 3, 5.0, 6.0, 1.5, "#1A1F24", "#7D8893", .7)
    private fun Pinceau.led(x: Double, y: Double, c: String) {
        C(x, y, 1.8, c)
        groupe { opacite(.25); C(x, y, 3.2, c) }
    }
    private fun Pinceau.knob(x: Double, y: Double, r: Double) {
        C(x, y, r, "url(#P-bouton)", "#0B0F13", 1.0); C(x, y, r * .72, "none", "rgba(255,255,255,.08)", 1.0); C(x + r * .45, y - r * .35, r * .12, "#0B0F13")
    }
    private fun Pinceau.ecran(x: Double, y: Double, w: Double, h: Double, teinte: String?) {
        R(x, y, w, h, 2.0, teinte ?: "url(#P-ecran)", "#05080B", 1.2)
        P("M${f(x + 3)} ${f(y + h * .78)} l${f(w * .12)} -${f(h * .18)} l${f(w * .1)} ${f(h * .1)} l${f(w * .1)} -${f(h * .34)} l${f(w * .1)} ${f(h * .3)} " +
            "l${f(w * .15)} -${f(h * .12)} l${f(w * .1)} ${f(h * .08)} l${f(w * .2)} -${f(h * .1)}", "none", "rgba(63,224,200,.85)", 1.0)
        R(x + 3, y + 3, w * .45, h * .2, 1.0, "rgba(232,240,247,.55)")
    }
    private fun Pinceau.corps(w: Double, h: Double, fond: String? = null) {
        R(1.0, 1.0, w - 2, h - 2, 5.0, fond ?: "url(#P-noir)", "#0B0F13", 1.2); R(3.0, 3.0, w - 6, 3.0, 1.5, "rgba(255,255,255,.06)")
    }

    private class Poste(val ex: Double, val ew: Double, val eh: Double, val kx: Double, val kr: Double, val bt: Int,
                        val petits: List<DoubleArray> = emptyList(), val teinte: String? = null)

    private fun Pinceau.poste(w: Double, h: Double, o: Poste) {
        corps(w, h); R(4.0, h - 4, 10.0, 3.0, 1.0, "#0B0F13"); R(w - 14, h - 4, 10.0, 3.0, 1.0, "#0B0F13")
        val ex = w * o.ex; val ey = h * .14; val ew = w * o.ew; val eh = h * o.eh
        ecran(ex, ey, ew, eh, o.teinte)
        knob(w * o.kx, h * .54, h * o.kr)
        for (i in 0 until o.bt) R(ex + i * (ew / o.bt) + 1, ey + eh + 5, ew / o.bt - 3, 4.0, 1.0, "#3A4450")
        o.petits.forEach { k -> C(w * k[0], h * k[1], h * k[2], "url(#P-bouton)", "#0B0F13", .8) }
        R(5.0, h * .2, 7.0, 7.0, 3.5, "#3A4450"); led(w * o.kx, h * .14, "#5BD68A")
    }

    private fun d(vararg v: Double) = v

    /** [dessin] (a model's drawing's name) drawn in [w]×[h] from (0, 0); [modele] picks a variant (a converter's bands). */
    fun dessine(p: Pinceau, dessin: String, w: Double, h: Double, modele: String) = with(p) {
        when (dessin) {
            "parabole" -> {
                val cx = w * .46; val cy = h * .44
                R(w * .55, h * .6, 6.0, h * .4, 1.0, "#59636E"); R(w * .42, h - 5, w * .34, 5.0, 2.0, "#3A4450")
                groupe {
                    tourne(-24.0, cx, cy)
                    E(cx, cy, w * .15, h * .42, "url(#P-metal)", "#5D6873", 1.2)
                    E(cx + 3, cy, w * .1, h * .36, "rgba(11,15,19,.18)")
                }
                P("M${f(w * .5)} ${f(h * .8)} L${f(w * .14)} ${f(h * .6)}", "none", "#7D8893", 2.4)
                R(w * .04, h * .5, 13.0, 16.0, 2.0, "#E3E8ED", "#7D8893", .8); R(w * .02, h * .53, 4.0, 10.0, 1.0, "#FFB454")
                E(w * .17, h * .58, 7.0, 5.0, "#C9D2DB", "#7D8893", .8)
            }
            "yagi" -> {
                val y = h * .42; val n = 9
                R(w / 2 - 2, y, 4.0, h - y, 1.0, "#59636E"); R(w * .06, y - 1.5, w * .88, 3.0, 1.5, "#9AA7B4")
                for (i in 0 until n) { val x = w * .1 + i * w * .8 / (n - 1); val l = h * (.86 - i * .045); R(x - 1, y - l / 2, 2.2, l, 1.0, if (i == 1) "#E8F0F7" else "#C9D2DB") }
                R(w * .1 + w * .8 / (n - 1) - 5, y + 3, 10.0, 6.0, 1.0, "#1A1F24", "#7D8893", .6)
            }
            "xquad" -> {
                val y = h * .44; val a = h * .34
                R(w / 2 - 2, y, 4.0, h - y, 1.0, "#59636E"); R(w * .08, y - 1.5, w * .84, 3.0, 1.5, "#9AA7B4")
                P("M${f(w * .32)} ${f(y - a)} l${f(a)} ${f(a)} l-${f(a)} ${f(a)} l-${f(a)} -${f(a)}z", "none", "#C9D2DB", 1.8)
                P("M${f(w * .32)} ${f(y - a)} v${f(2 * a)} M${f(w * .32 - a)} ${f(y)} h${f(2 * a)}", "none", "rgba(201,210,219,.55)", 1.0)
                val b = a * .82
                P("M${f(w * .7)} ${f(y - b)} l${f(b)} ${f(b)} l-${f(b)} ${f(b)} l-${f(b)} -${f(b)}z", "none", "#9AA7B4", 1.4)
            }
            "colineaire" -> {
                R(w / 2 - 3, 2.0, 6.0, h * .78, 3.0, "#E3E8ED", "#9AA7B4", .6); R(w / 2 - 4, h * .3, 8.0, 4.0, 1.0, "#9AA7B4"); R(w / 2 - 4, h * .55, 8.0, 4.0, 1.0, "#9AA7B4")
                R(w / 2 - 4, h * .78, 8.0, h * .22, 1.0, "#59636E")
                P("M${f(w / 2)} ${f(h * .8)} l-${f(w * .38)} ${f(h * .12)} M${f(w / 2)} ${f(h * .8)} l${f(w * .38)} ${f(h * .12)}", "none", "#9AA7B4", 1.6)
            }
            "interieure" -> {
                R(w * .2, h * .74, w * .6, h * .22, 4.0, "url(#P-noir)", "#0B0F13")
                groupe { tourne(12.0, w / 2, h * .74); R(w / 2 - 3, h * .06, 6.0, h * .7, 3.0, "#1A1F24", "#3A4450") }
                led(w * .7, h * .85, "#3FE0C8")
            }
            "lnb" -> {
                P("M2 ${f(h * .22)} L${f(w * .3)} ${f(h * .36)} L${f(w * .3)} ${f(h * .64)} L2 ${f(h * .78)}z", "#C9D2DB", "#7D8893", 1.0)
                R(w * .3, h * .3, w * .56, h * .4, 6.0, "url(#P-metal)", "#7D8893", 1.0); fco(w * .86, h * .5); R(w * .4, h * .4, w * .3, 3.0, 1.0, "#7D8893")
            }
            "convertisseur" -> {
                val up = modele.startsWith("up-")
                val txt = when (modele) { "dc-144" -> "10G→144"; "dc-432" -> "10G→432"; "dc-739" -> "10G→739"; "up-144" -> "144→2,4G"; "up-432" -> "432→2,4G"; else -> "" }
                R(7.0, h * .12, w - 14, h * .76, 4.0, "url(#P-alu)", "#5D6873", 1.0); sma(0.0, h * .5); sma(w - 7, h * .5); R(12.0, h * .2, w - 24, h * .22, 2.0, "rgba(11,15,19,.55)")
                T(w / 2, h * .37, txt, 8.5, "#E8F0F7"); led(w - 16, h * .72, if (up) "#FF5A36" else "#3FE0C8"); P("M${f(w / 2 - 6)} ${f(h * .72)} h12", "none", "#3A4450", 2.0)
            }
            "ampli" -> {
                R(6.0, h * .26, w - 12, h * .62, 3.0, "url(#P-noir)", "#0B0F13", 1.0)
                for (i in 0 until 9) R(10 + i * (w - 22) / 8, h * .06, 3.0, h * .22, 1.0, "#59636E")
                sma(0.0, h * .56); R(w - 7, h * .5, 7.0, 10.0, 1.5, "#B9C2CC", "#6E7883", .6); T(w / 2, h * .66, "PA 2,4G", 9.0, "#FFB454"); led(16.0, h * .78, "#FF5A36")
            }
            "biast" -> {
                R(10.0, h * .2, w - 20, h * .5, 6.0, "url(#P-metal)", "#7D8893"); fco(2.0, h * .45); sma(w - 7, h * .45)
                P("M${f(w / 2)} ${f(h * .7)} q 0 ${f(h * .2)} 12 ${f(h * .26)}", "none", "#E5484D", 1.6)
                P("M${f(w / 2 + 4)} ${f(h * .7)} q 0 ${f(h * .16)} 14 ${f(h * .22)}", "none", "#1A1F24", 1.6); T(w / 2, h * .52, "DC", 8.0, "#3A4450")
            }
            "filtre" -> {
                R(7.0, h * .14, w - 14, h * .72, 3.0, "url(#P-alu)", "#5D6873"); sma(0.0, h * .5); sma(w - 7, h * .5)
                for (k in listOf(.32, .5, .68)) C(w * k, h * .5, h * .18, "none", "#3A4450", 1.4)
            }
            "attenuateur" -> { R(9.0, h * .2, w - 18, h * .6, 5.0, "url(#P-metal)", "#7D8893"); sma(1.0, h * .5); sma(w - 8, h * .5); T(w / 2, h * .62, "−dB", 9.0, "#3A4450") }
            "charge" -> {
                R(w * .14, h * .28, w * .7, h * .5, 3.0, "url(#P-noir)", "#0B0F13")
                for (i in 0 until 7) R(w * .18 + i * w * .09, h * .14, 3.0, h * .78, 1.0, "#59636E")
                R(2.0, h * .44, w * .14, h * .18, 2.0, "#B9C2CC", "#6E7883", .6); T(w * .5, h * .58, "50 Ω", 8.0, "#E8F0F7")
            }
            "repartiteur" -> {
                R(7.0, h * .12, w - 14, h * .76, 4.0, "url(#P-alu)", "#5D6873"); fco(-1.0, h * .5); fco(w - 7, h * .3); fco(w - 7, h * .7)
                P("M${f(w * .25)} ${f(h * .5)} H${f(w * .5)} L${f(w * .78)} ${f(h * .3)} M${f(w * .5)} ${f(h * .5)} L${f(w * .78)} ${f(h * .7)}", "none", "#3A4450", 1.6)
            }
            "rotor" -> {
                P("M${f(w * .06)} ${f(h * .86)} L${f(w * .12)} ${f(h * .38)} H${f(w * .4)} L${f(w * .46)} ${f(h * .86)}z", "url(#P-metal)", "#7D8893")
                R(w * .23, h * .06, 6.0, h * .34, 1.0, "#59636E"); R(w * .52, h * .42, w * .44, h * .48, 4.0, "url(#P-noir)", "#0B0F13")
                C(w * .64, h * .62, h * .1, "#E3E8ED", "#59636E"); C(w * .84, h * .62, h * .1, "#E3E8ED", "#59636E")
                P("M${f(w * .64)} ${f(h * .62)} l3 -5 M${f(w * .84)} ${f(h * .62)} l-4 -4", "none", "#E5484D", 1.2); T(w * .74, h * .86, "AZ  EL", 7.0, "#9AA7B4")
            }
            "ic9700" -> poste(w, h, Poste(.2, .42, .5, .8, .26, 5, listOf(d(.1, .62, .07), d(.1, .82, .06), d(.68, .86, .05))))
            "ic705" -> poste(w, h, Poste(.16, .46, .52, .8, .22, 4, listOf(d(.08, .78, .06))))
            "ts2000" -> poste(w, h, Poste(.26, .38, .36, .45, .24, 0, listOf(d(.12, .55, .08), d(.12, .8, .06), d(.76, .55, .08), d(.88, .55, .06), d(.76, .8, .06)), "url(#P-ambre)"))
            "ft991" -> poste(w, h, Poste(.24, .4, .56, .82, .24, 4, listOf(d(.1, .6, .07), d(.1, .82, .06)), "url(#P-bleu)"))
            "ft818" -> { poste(w, h, Poste(.14, .44, .34, .78, .2, 4, listOf(d(.07, .7, .05)), "url(#P-vert)")); R(w * .02, h * .02, w * .06, h * .9, 2.0, "#3A4450") }
            "poste" -> poste(w, h, Poste(.18, .44, .46, .8, .24, 4))
            "rtlsdr" -> { R(10.0, h * .18, w - 30, h * .64, 7.0, "url(#P-noir)", "#0B0F13"); R(w - 22, h * .3, 18.0, h * .4, 1.5, "#B9C2CC", "#6E7883", .7); sma(1.0, h * .5); led(w - 32, h * .5, "#E5484D") }
            "pluto" -> {
                R(10.0, h * .1, w - 20, h * .8, 7.0, "url(#P-bleu-boite)", "#163A63", 1.2); R(14.0, h * .14, w - 28, 3.0, 1.5, "rgba(255,255,255,.18)")
                sma(3.0, h * .34); sma(3.0, h * .66); T(22.0, h * .38, "RX", 7.0, "#D6E6F7", 's'); T(22.0, h * .7, "TX", 7.0, "#D6E6F7", 's')
                usbC(w - 11, h * .5); led(w - 22, h * .26, "#5BD68A"); led(w - 22, h * .4, "#FFB454")
            }
            "bladerf" -> {
                R(4.0, h * .16, w - 8, h * .74, 5.0, "url(#P-alu-sombre)", "#0B0F13", 1.2)
                for (i in 0 until 4) { sma(w * (.18 + i * .2), h * .04, true); T(w * (.18 + i * .2), h * .32, listOf("RX1", "RX2", "TX1", "TX2")[i], 6.5, "#9AA7B4") }
                R(w * .14, h * .44, w * .62, h * .3, 2.0, "url(#P-pcb)", "#0B0F13"); R(w * .3, h * .5, w * .14, h * .18, 1.0, "#1A1F24"); R(w * .5, h * .52, w * .08, h * .14, 1.0, "#1A1F24")
                usbC(w - 9, h * .58); led(w * .86, h * .78, "#3FE0C8"); led(w * .78, h * .78, "#5BD68A")
            }
            "hackrf" -> {
                R(4.0, h * .12, w - 8, h * .76, 6.0, "#15181C", "#0B0F13", 1.2); R(10.0, h * .2, w - 20, h * .46, 3.0, "url(#P-verre)")
                listOf("#5BD68A", "#5BD68A", "#FFB454", "#E5484D", "#6F8CFF").forEachIndexed { i, c -> led(w * (.3 + i * .1), h * .78, c) }
                sma(-2.0, h * .44); usbC(w - 9, h * .44); C(w * .16, h * .78, 3.0, "#3A4450"); C(w * .84, h * .78, 3.0, "#3A4450")
            }
            "minitiouner" -> {
                R(8.0, h * .1, w - 16, h * .8, 4.0, "url(#P-alu)", "#5D6873", 1.2)
                for (i in 0 until 6) R(w * .3 + i * 7, h * .2, 3.0, h * .28, 1.0, "rgba(11,15,19,.35)")
                fco(0.0, h * .34); fco(0.0, h * .66); T(17.0, h * .38, "A", 7.0, "#1A1F24", 's'); T(17.0, h * .7, "B", 7.0, "#1A1F24", 's'); usbC(w - 9, h * .5)
                led(w * .78, h * .3, "#6F8CFF"); R(w * .3, h * .62, w * .4, h * .12, 2.0, "rgba(11,15,19,.5)"); T(w * .5, h * .72, "DVB-S2", 6.5, "#E8F0F7")
            }
            "noeud" -> {
                R(6.0, h * .12, w - 12, h * .76, 6.0, "url(#P-noir)", "#0B0F13", 1.2)
                for (i in 0 until 5) R(w * .2 + i * 8, h * .2, 4.0, h * .26, 1.0, "#22303B")
                R(w - 18, h * .56, 12.0, 10.0, 1.0, "#B9C2CC", "#6E7883", .6); R(w - 32, h * .58, 10.0, 7.0, 1.0, "#1A1F24", "#7D8893", .6); R(w - 44, h * .58, 10.0, 7.0, 1.0, "#1A1F24", "#7D8893", .6)
                led(16.0, h * .6, "#5BD68A"); led(24.0, h * .6, "#3FE0C8"); P("M${f(w * .8)} ${f(h * .12)} v-${f(h * .1)}", "none", "#9AA7B4", 1.6); T(w * .3, h * .82, "NODE", 7.0, "#3FE0C8")
            }
            "carteson" -> {
                R(6.0, h * .14, w - 26, h * .72, 6.0, "url(#P-noir)", "#0B0F13"); C(18.0, h * .5, 4.5, "#1A1F24", "#E58BB2", 1.6); C(32.0, h * .5, 4.5, "#1A1F24", "#5BD68A", 1.6)
                R(w - 20, h * .38, 16.0, h * .24, 1.5, "#B9C2CC", "#6E7883", .6); led(w - 30, h * .5, "#5BD68A")
            }
            "esp32" -> {
                R(6.0, h * .12, w - 12, h * .76, 5.0, "url(#P-noir)", "#0B0F13"); R(w * .22, h * .26, w * .4, h * .44, 2.0, "#2F3A45", "#59636E")
                P("M${f(w * .66)} ${f(h * .3)} h8 v6 h-6 v6 h6 v6", "none", "#C9A54A", 1.2); usbC(w - 11, h * .64); led(w * .16, h * .76, "#3FE0C8"); T(w * .42, h * .54, "S3", 7.0, "#9AA7B4")
            }
            "pc" -> {
                val sh = h * .7
                R(w * .08, 2.0, w * .84, sh, 5.0, "#15181C", "#3A4450", 1.2); R(w * .12, 6.0, w * .76, sh - 10, 2.0, "#0A0F14")
                R(w * .12, 6.0, w * .07, sh - 10, 1.0, "#0F161D"); R(w * .22, 10.0, w * .3, sh * .38, 2.0, "#131C24", "#22303B", .6); R(w * .55, 10.0, w * .3, sh * .38, 2.0, "#131C24", "#22303B", .6)
                R(w * .22, 14 + sh * .38, w * .63, sh * .4, 2.0, "#131C24", "#22303B", .6)
                P("M${f(w * .24)} ${f(sh * .7)} l${f(w * .1)} -${f(sh * .14)} l${f(w * .1)} ${f(sh * .08)} l${f(w * .12)} -${f(sh * .2)} l${f(w * .14)} ${f(sh * .1)} l${f(w * .12)} -${f(sh * .06)}", "none", "#3FE0C8", 1.2)
                C(w * .37, 10 + sh * .19, sh * .12, "none", "#3FE0C8", 1.0)
                P("M0 ${f(h - 3)} L${f(w * .06)} ${f(sh + 3)} H${f(w * .94)} L${f(w)} ${f(h - 3)}z", "url(#P-alu-sombre)", "#3A4450", 1.0); R(w * .4, h - 7, w * .2, 3.0, 1.5, "#22303B")
            }
            "dxpup", "dxpdown" -> {
                val up = dessin == "dxpup"
                R(5.0, h * .1, w - 10, h * .8, 4.0, "url(#P-alu)", "#5D6873", 1.2); R(5.0, h * .1, w - 10, 4.0, 2.0, if (up) "#C2412A" else "#1E9C8C"); sma(0.0, h * .45); sma(w - 7, h * .45)
                R(14.0, h * .26, w - 28, h * .28, 2.0, "rgba(11,15,19,.62)"); T(w / 2, h * .46, if (up) "FI → 2,4 GHz" else "739 → FI", 8.0, if (up) "#FFD9CF" else "#C8FFF6")
                T(w * .42, h * .8, if (modele == (if (up) "dxp-up-mk3" else "dxp-down-mk3")) "MK3" else "MK4", 8.0, "#1A1F24")
                led(w - 16, h * .76, if (up) "#FF5A36" else "#3FE0C8"); R(w * .7 - 3, h * .86, 6.0, 5.0, 1.0, "#C9A54A")
            }
            "pa" -> {
                R(8.0, h * .3, w - 16, h * .58, 3.0, "url(#P-noir)", "#0B0F13", 1.0)
                for (i in 0 until 11) R(11 + i * (w - 24) / 10, h * .04, 3.0, h * .28, 1.0, "#6A7581")
                sma(0.0, h * .56); R(w - 8, h * .48, 8.0, 12.0, 1.5, "#B9C2CC", "#6E7883", .6); T(w / 2, h * .64, "PA " + (if (modele == "dxp-pa-15w") "15" else "20") + " W", 9.0, "#FFB454")
                T(w / 2, h * .8, "2,4 GHz", 6.5, "#9AA7B4"); led(18.0, h * .78, "#FF5A36"); C(w * .3, h * .94, 2.4, "#E5484D"); C(w * .38, h * .94, 2.4, "#1A1F24", "#59636E", .6); C(w * .7, h * .94, 2.2, "#C9A54A")
            }
            "lnb2" -> {
                P("M2 ${f(h * .2)} L${f(w * .28)} ${f(h * .34)} L${f(w * .28)} ${f(h * .66)} L2 ${f(h * .8)}z", "#C9D2DB", "#7D8893", 1.0)
                R(w * .28, h * .26, w * .58, h * .48, 6.0, "url(#P-metal)", "#7D8893", 1.0); R(w * .28, h * .46, w * .58, 5.0, 1.0, "#3E9B5C"); fco(w * .86, h * .36); fco(w * .86, h * .64)
                T(w * .56, h * .4, "TWIN", 7.0, "#3A4450")
            }
            "gpsdo" -> {
                R(5.0, h * .08, w - 10, h * .84, 4.0, "url(#P-noir)", "#0B0F13", 1.2); R(14.0, h * .2, w * .5, h * .3, 2.0, "#08130E", "#22303B", .8); T(14 + w * .25, h * .41, "10.000000", 7.5, "#5BD68A")
                for (i in 0 until 4) sma(w - 7, h * (.2 + i * .19))
                sma(0.0, h * .5); T(18.0, h * .78, "GPS", 6.5, "#9AA7B4", 's'); led(w * .5, h * .74, "#5BD68A"); T(w * .5 + 6, h * .78, "LOCK", 6.0, "#5BD68A", 's')
            }
            "bodnar" -> {
                R(6.0, h * .14, w - 12, h * .72, 5.0, "url(#P-alu-sombre)", "#0B0F13", 1.2); sma(w - 7, h * .34); sma(w - 7, h * .66); sma(0.0, h * .5)
                usbC(w * .45, h * .88); led(w * .3, h * .36, "#5BD68A"); T(w * .5, h * .58, "GPSDO", 8.0, "#C9D2DB"); T(w * .5, h * .76, "f1 f2", 6.0, "#9AA7B4")
            }
            "gps" -> {
                E(w / 2, h * .55, w * .4, h * .32, "#E3E8ED", "#7D8893", 1.0)
                R(w * .1, h * .62, w * .8, h * .22, 3.0, "#1A1F24"); T(w / 2, h * .52, "GPS", 8.0, "#3A4450"); R(w * .9, h * .66, 6.0, 4.0, 1.0, "#C9A54A")
            }
            "injecteur" -> {
                R(8.0, h * .16, w - 16, h * .56, 5.0, "url(#P-metal)", "#7D8893"); fco(0.0, h * .44); fco(w - 8, h * .44); T(w / 2, h * .5, "DC + RÉF", 7.5, "#3A4450")
                C(w * .36, h * .86, 3.0, "#E5484D"); R(w * .62 - 3, h * .8, 6.0, 7.0, 1.0, "#C792FF")
            }
            "dcblock" -> { R(8.0, h * .24, w - 16, h * .52, 4.0, "#C9A54A", "#8A6E2A", .8); sma(0.0, h * .5); sma(w - 7, h * .5); T(w / 2, h * .62, "DC ⊘", 8.0, "#3A2A0E") }
            "alim" -> {
                R(4.0, h * .08, w - 8, h * .84, 4.0, "url(#P-noir)", "#0B0F13", 1.2); R(10.0, h * .18, w * .42, h * .42, 2.0, "#E8E0C8", "#59636E", .8)
                P("M${f(10 + w * .06)} ${f(h * .54)} L${f(10 + w * .28)} ${f(h * .28)}", "none", "#E5484D", 1.2); T(10 + w * .21, h * .78, "13,8 V", 7.5, "#FFB454")
                C(w * .72, h * .66, 4.0, "#E5484D", "#0B0F13"); C(w * .86, h * .66, 4.0, "#1A1F24", "#59636E"); led(w * .79, h * .28, "#5BD68A")
            }
            "sequenceur" -> {
                R(5.0, h * .12, w - 10, h * .76, 4.0, "url(#P-alu-sombre)", "#0B0F13"); T(w / 2, h * .42, "SEQ", 8.0, "#C9D2DB")
                led(w * .3, h * .66, "#5BD68A"); led(w * .5, h * .66, "#FFB454"); led(w * .7, h * .66, "#FF5A36")
            }
            "molette" -> { R(w * .12, h * .72, w * .76, h * .2, 4.0, "url(#P-noir)", "#0B0F13"); knob(w / 2, h * .44, h * .32); usbC(w * .84, h * .82) }
            "boite" -> {
                R(1.0, 1.0, w - 2, h - 2, 8.0, "#141D26", "#3A4A5A", 1.2)
                when (modele.removePrefix("boite-")) {
                    "wavelog" -> { R(12.0, 9.0, 18.0, 22.0, 2.0, "#2C5E8C", "#7FB3E0", 1.0); P("M16 15h10 M16 19h10 M16 23h7", "none", "#D6E6F7", 1.2) }
                    "qrz" -> { C(19.0, 18.0, 7.0, "none", "#FFD166", 2.0); P("M24 23l6 6", "none", "#FFD166", 2.4) }
                    "psk" -> { C(21.0, 20.0, 10.0, "none", "#6F8CFF", 1.4); P("M11 20h20 M21 10c-5 6-5 14 0 20 M21 10c5 6 5 14 0 20", "none", "#6F8CFF", 1.0); C(16.0, 15.0, 1.8, "#FF5A36"); C(26.0, 23.0, 1.8, "#5BD68A") }
                    "chat" -> { P("M10 11h22v14h-12l-5 5v-5h-5z", "#22303B", "#9AA7B4", 1.2); P("M14 16h14 M14 20h9", "none", "#9AA7B4", 1.0) }
                    "carnets" -> { R(11.0, 10.0, 20.0, 20.0, 2.0, "#22303B", "#9AA7B4", 1.0); P("M14 15h4 M20 15h8 M14 20h4 M20 20h8 M14 25h4 M20 25h8", "none", "#C9D2DB", 1.1) }
                    "lien" -> P("M10 16h18l-4 -4 M32 24h-18l4 4", "none", "#3FE0C8", 1.8)
                    "telephone" -> { R(15.0, 8.0, 13.0, 24.0, 3.0, "#15181C", "#9AA7B4", 1.2); R(17.0, 11.0, 9.0, 16.0, 1.0, "#0F3A44") }
                    "noeud" -> { R(10.0, 15.0, 22.0, 13.0, 3.0, "#15181C", "#9AA7B4", 1.2); P("M27 15v-6", "none", "#9AA7B4", 1.4); led(15.0, 22.0, "#5BD68A") }
                    "ecoute" -> { R(9.0, 10.0, 24.0, 18.0, 2.0, "#22303B", "#9AA7B4", 1.0); R(9.0, 10.0, 24.0, 4.0, 1.0, "#3A4450"); P("M17 18h3l4 -3v10l-4 -3h-3z", "#C9D2DB"); P("M27 17q2 3 0 6", "none", "#C9D2DB", 1.0) }
                    "espace" -> { C(21.0, 20.0, 6.0, "#FFB454"); P("M21 8v4 M21 28v4 M9 20h4 M29 20h4 M12.5 11.5l2.8 2.8 M26.7 25.7l2.8 2.8 M12.5 28.5l2.8 -2.8 M26.7 14.3l2.8 -2.8", "none", "#FFB454", 1.4) }
                    else -> C(21.0, 20.0, 9.0, "none", "#9AA7B4", 1.4)
                }
            }
            "telephone" -> {
                R(3.0, 2.0, w - 6, h - 4, 9.0, "#15181C", "#3A4450", 1.4); R(7.0, 9.0, w - 14, h - 20, 3.0, "#0A0F14"); R(w / 2 - 6, 4.0, 12.0, 2.5, 1.2, "#3A4450")
                C(w / 2, h * .36, w * .26, "none", "#22303B", 1.0); C(w / 2, h * .36, w * .14, "none", "#22303B", 1.0); C(w * .6, h * .3, 2.6, "#FFD166")
                R(11.0, h * .6, w - 22, 5.0, 2.0, "#22303B"); R(11.0, h * .68, (w - 22) * .6, 5.0, 2.0, "#22303B"); R(w / 2 - 7, h - 9, 14.0, 2.5, 1.2, "#3A4450")
            }
            else -> {
                // « sdr » and any drawing this side does not know: the generic receiver.
                R(8.0, h * .14, w - 18, h * .72, 5.0, "url(#P-alu-sombre)", "#0B0F13"); sma(0.0, h * .5); usbC(w - 11, h * .5); led(w - 22, h * .3, "#3FE0C8"); T(w / 2, h * .6, "SDR", 9.0, "#9AA7B4")
            }
        }
    }

    /** The drawings this side knows (the page's `MT_D`). */
    val CONNUS = setOf("parabole", "yagi", "xquad", "colineaire", "interieure", "lnb", "convertisseur", "ampli", "biast", "filtre", "attenuateur",
        "charge", "repartiteur", "rotor", "ic9700", "ic705", "ts2000", "ft991", "ft818", "poste", "rtlsdr", "pluto", "bladerf", "hackrf", "sdr",
        "minitiouner", "noeud", "carteson", "esp32", "pc", "dxpup", "dxpdown", "pa", "lnb2", "gpsdo", "bodnar", "gps", "injecteur", "dcblock",
        "alim", "sequenceur", "molette", "boite", "telephone")
}
