/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.site.pages

import fr.f4ioz.site.EntreeMenu
import fr.f4ioz.site.Groupe
import fr.f4ioz.site.ModuleSite
import fr.f4ioz.site.Site
import fr.f4ioz.site.http.Routeur
import fr.f4ioz.site.vue.Icones
import fr.f4ioz.site.vue.Page
import org.json.JSONArray
import org.json.JSONObject

/** SatMe's own page: what it is, on the phone and on the PC, its pictures (the repository's, © F4IOZ). */
class PageSatme(private val s: Site) : ModuleSite {

    override val menu = listOf(EntreeMenu(Groupe.SITE, 50, "/satme", "SatMe", "satme", "L'appli de poursuite de satellites"))
    override fun plan() = listOf("/satme" to 0.8)

    override fun installe(r: Routeur) {
        r.get("/satme") { q ->
            val ld = JSONObject().put("@context", "https://schema.org").put("@type", "SoftwareApplication").put("name", "SatMe")
                .put("applicationCategory", "UtilitiesApplication").put("operatingSystem", "Android, Linux, Windows")
                .put("description", "Poursuite de satellites radioamateurs sur Android et sur PC — passages, pointage, Doppler, carnet.")
                .put("license", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
                .put("offers", JSONObject().put("@type", "Offer").put("price", "0").put("priceCurrency", "EUR"))
                .put("author", JSONObject().put("@type", "Person").put("name", s.config.nom).put("alternateName", s.config.indicatif))
                .put("url", s.config.urlBaseNette + "/satme").put("sameAs", JSONArray(listOf("https://github.com/f4ioz/SatMe")))
            s.gabarit.reponse(q, Page("SatMe — poursuite de satellites radioamateurs",
                "SatMe, l'appli libre de ${s.config.indicatif} pour la poursuite des satellites radioamateurs sur Android et sur PC : passages, pointage, Doppler, carnet, SSTV, QO-100.",
                corps(), "/satme", ogImage = s.statique.url("img/satme/pc-demo.webp").substringBefore('?'), jsonLd = ld.toString())).cache("public, max-age=300")
        }
    }

    private fun img(nom: String) = s.statique.url("img/satme/$nom")

    private fun shot(nom: String, w: Int, h: Int, alt: String, legende: String) =
        """<figure class="shot"><a href="${img(nom)}"><img src="${img(nom)}" width="$w" height="$h" loading="lazy" alt="$alt"></a><figcaption>$legende</figcaption></figure>"""

    private fun corps() = """<section class="sm-hero" aria-labelledby="sm-title">
<img src="${img("icon.png")}" width="112" height="112" alt="Icône de l'application SatMe">
<div><h1 id="sm-title">SatMe</h1>
<p class="tagline">Poursuite de satellites radioamateurs sur Android et sur PC — passages, pointage, Doppler, carnet. Gratuit, sans compte, sans publicité, logiciel libre.</p>
<div class="actions"><a class="btn solid" href="https://github.com/f4ioz/SatMe" rel="noopener">${Icones.svg("external")}Le projet sur GitHub</a>
<a class="btn" href="https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo" rel="noopener">Google Play</a>
<a class="btn" href="https://github.com/f4ioz/SatMe/wiki/Accueil" rel="noopener">Documentation (wiki)</a></div></div></section>
<div class="sm-trio">
<article class="card"><div class="card-body"><h2>SatMe</h2><p>Dans la poche pour trafiquer partout : prochains passages, boussole et ciel, fréquences corrigées du Doppler, carnet.</p></div></article>
<article class="card"><div class="card-body"><h2>SatMe-PC</h2><p>Toute la station dans un navigateur : poste en CAT, rotor, SDR, SSTV, QO-100, journal des passages et tableau plein écran pour le stand du club. C'est lui qui publie sur ce site les images SSTV et les planches.</p></div></article>
<article class="card"><div class="card-body"><h2>SatMe-Node</h2><p>Un Raspberry Pi au pied du mât, piloté par le PC à travers le réseau. Le même moteur partout.</p></div></article>
</div>
<p class="sm-essayer">Les outils satellites de ce site sont ceux de SatMe-PC, tournant dans votre navigateur :
<a href="/sat/orbites">Orbites</a>, <a href="/sat/sked">Sked</a>, <a href="/sat/pass">Passages</a>, <a href="/sat/radar">Ciel</a>.</p>
<h2 class="sm-section-title">Sur le PC</h2>
<div class="sm-shots">
${shot("pc-demo.webp", 1400, 876, "SatMe-PC en démonstration : l'onglet Suivi avec le globe, la station fictive QF4DMO, l'IC-9700 et le rotor simulés",
        "<b>Le Suivi.</b> Prochains passages, satellite suivi sur le globe, fréquences Doppler, polarisation et émission — ici en mode démonstration (station fictive).")}
${shot("pc-orbites-tableau.webp", 1400, 788, "Orbites en plein écran, thème Dessin animé : le globe avec l'ISS et son orbite, l'heure, le Soleil et la Lune, les prochains passages",
        "<b>Orbites et son tableau.</b> La Terre en 3D avec les satellites, leurs empreintes et leurs orbites ; en plein écran, un écran mural pour le club.")}
${shot("pc-journal-editeur-169.webp", 1400, 788, "L'éditeur du Journal sur une session de quatre passages de l'ISS, au format 16:9",
        "<b>Le Journal.</b> Un petit éditeur vidéo : un passage ou une session, l'image SSTV et les incrustations placées à la souris, export MP4.")}
${shot("pc-polarisation-suivi.webp", 1400, 876, "Onglet Suivi sur SO-50 : le panneau Polarisation en AUTO après un sondage H contre V",
        "<b>Polarisation d'antenne.</b> Un boîtier ESP32 en USB commute V, H ou circulaire à l'AOS, et SatMe apprend laquelle marche le mieux.")}
${shot("pc-qo100-emission.webp", 1400, 876, "Cockpit QO-100 sur le générateur d'essai du SDR : cascade, plan de bande et panneau Émission",
        "<b>QO-100 bande étroite.</b> Cascade, plan de bande AMSAT-DL, émission avec l'ADALM-PLUTO ou l'IC-9700 et un convertisseur.")}
${shot("pc-orbites-vitrine-passage.webp", 1400, 788, "Thème Vitrine : une carte annonce qu'AO-91 passe au-dessus de la station dans 30 minutes",
        "<b>Vitrine.</b> Pour le stand d'un club : « AO-91 passe au-dessus de nous dans 30 minutes, venez écouter ».")}
</div>
<h2 class="sm-section-title">Sur le téléphone</h2>
<div class="sm-phones">
${shot("fr-pass.webp", 360, 780, "Page d'un passage de RS-44 : boussole, élévation, fréquences RX et TX corrigées du Doppler", "<b>Un passage</b> et son Doppler.")}
${shot("fr-passes-prepare.webp", 540, 1170, "Préparer n'importe quel passage", "<b>Préparer</b> n'importe quel passage.")}
${shot("fr-journal-sky.webp", 540, 1170, "Journal des passages : le ciel et une image SSTV qui arrive", "<b>Le journal</b> : le ciel, une image SSTV qui arrive.")}
</div>
<h2 class="sm-section-title">Une station, trois SatMe</h2>
<div class="card sm-diagram"><img src="${img("satme-ensemble-fr.svg")}" width="1000" height="640" loading="lazy"
 alt="Les trois SatMe : SatMe sur le téléphone, SatMe-PC sur un PC ou un Raspberry Pi, SatMe-Node au pied du mât ; le téléphone rejoint le PC par un QR code ; le même moteur partout"></div>
<p class="sm-note">SatMe est distribué sous licence GPL-2.0-ou-ultérieure ; sources, versions et documentation sur
<a href="https://github.com/f4ioz/SatMe" rel="noopener">github.com/f4ioz/SatMe</a>. Captures d'écran et schéma : dépôt SatMe (docs/images), © F4IOZ ;
les captures « démo » montrent une station fictive (QF4DMO).</p>"""
}
