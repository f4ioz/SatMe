/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 */
package fr.f4ioz.satcombo.demo

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the globes put on the Earth besides its coastline: cities, mountain
 * ranges, landmarks, and the cartoon's scenery (boats, whales, forests,
 * penguins). The same list for the page's globes ([OrbitesJs], as JSON) and
 * the journal's videos drawn by SatMe-PC, so both show the same world.
 * Positions in degrees; written here by hand (public facts, no data set).
 */
object GlobeDonnees {

    /** A city: [rang] 1 the world's great ones, 2 other capitals and space centres, 3 the smaller ones near home (shown with more detail). */
    data class Ville(val nom: String, val lat: Double, val lon: Double, val rang: Int)

    /** A mountain range along its crest, [hauteur] from 0.3 (old hills) to 1 (the Himalaya). */
    data class Relief(val nom: String, val hauteur: Double, val crete: List<Pair<Double, Double>>)

    /** A landmark drawn on the cartoon globe: [id] says which drawing. */
    data class Monument(val id: String, val nom: String, val lat: Double, val lon: Double)

    /** The cartoon's scenery: [type] "bateau", "baleine", "foret", "manchot". */
    data class Decor(val type: String, val lat: Double, val lon: Double)

    val VILLES = listOf(
        Ville("Paris", 48.857, 2.352, 1), Ville("Londres", 51.507, -0.128, 1), Ville("New York", 40.713, -74.006, 1),
        Ville("Tokyo", 35.690, 139.692, 1), Ville("Moscou", 55.756, 37.617, 1), Ville("Pékin", 39.904, 116.407, 1),
        Ville("Sydney", -33.869, 151.209, 1), Ville("Rio de Janeiro", -22.907, -43.173, 1), Ville("Le Caire", 30.044, 31.236, 1),
        Ville("Los Angeles", 34.052, -118.244, 1), Ville("Mumbai", 19.076, 72.878, 1), Ville("Le Cap", -33.925, 18.424, 1),
        Ville("Buenos Aires", -34.604, -58.382, 1), Ville("Mexico", 19.433, -99.133, 1), Ville("Singapour", 1.352, 103.820, 1),
        Ville("Dubaï", 25.205, 55.271, 1), Ville("Lagos", 6.524, 3.379, 1), Ville("Istanbul", 41.008, 28.978, 1),
        Ville("Delhi", 28.614, 77.209, 1), Ville("Shanghai", 31.230, 121.474, 1),
        Ville("Rome", 41.903, 12.496, 2), Ville("Madrid", 40.417, -3.704, 2), Ville("Berlin", 52.520, 13.405, 2),
        Ville("Reykjavik", 64.147, -21.942, 2), Ville("Anchorage", 61.218, -149.900, 2), Ville("Honolulu", 21.307, -157.858, 2),
        Ville("Santiago", -33.449, -70.669, 2), Ville("Lima", -12.046, -77.043, 2), Ville("Nairobi", -1.292, 36.822, 2),
        Ville("Bangkok", 13.756, 100.502, 2), Ville("Séoul", 37.567, 126.978, 2), Ville("Jakarta", -6.208, 106.846, 2),
        Ville("Johannesburg", -26.204, 28.047, 2), Ville("Kinshasa", -4.441, 15.266, 2), Ville("Montréal", 45.502, -73.567, 2),
        Ville("Vancouver", 49.283, -123.121, 2), Ville("Perth", -31.951, 115.861, 2), Ville("Auckland", -36.848, 174.763, 2),
        Ville("Toronto", 43.653, -79.383, 2), Ville("Houston", 29.760, -95.370, 2), Ville("Baïkonour", 45.965, 63.305, 2),
        Ville("Kourou", 5.160, -52.650, 2), Ville("Cap Canaveral", 28.392, -80.608, 2), Ville("Alger", 36.754, 3.059, 2),
        Ville("Casablanca", 33.573, -7.590, 2), Ville("Dakar", 14.716, -17.467, 2), Ville("Stockholm", 59.329, 18.069, 2),
        Ville("Athènes", 37.984, 23.728, 2), Ville("Lisbonne", 38.722, -9.139, 2), Ville("Varsovie", 52.230, 21.012, 2),
        Ville("Lyon", 45.764, 4.836, 3), Ville("Marseille", 43.296, 5.370, 3), Ville("Toulouse", 43.605, 1.444, 3),
        Ville("Bordeaux", 44.838, -0.579, 3), Ville("Lille", 50.629, 3.057, 3), Ville("Nantes", 47.218, -1.554, 3),
        Ville("Strasbourg", 48.573, 7.752, 3), Ville("Brest", 48.390, -4.486, 3), Ville("Nice", 43.710, 7.262, 3),
        Ville("Rennes", 48.117, -1.678, 3), Ville("Ajaccio", 41.919, 8.739, 3), Ville("Bruxelles", 50.850, 4.352, 3),
        Ville("Genève", 46.204, 6.143, 3), Ville("Amsterdam", 52.370, 4.895, 3), Ville("Barcelone", 41.385, 2.173, 3),
        Ville("Munich", 48.135, 11.582, 3), Ville("Vienne", 48.208, 16.373, 3), Ville("Oslo", 59.913, 10.752, 3),
        Ville("Dublin", 53.350, -6.260, 3), Ville("Milan", 45.464, 9.190, 3))

    val RELIEFS = listOf(
        Relief("Alpes", 0.8, listOf(45.8 to 6.9, 46.5 to 8.0, 46.8 to 10.5, 47.2 to 12.5, 47.3 to 14.5)),
        Relief("Pyrénées", 0.6, listOf(42.9 to -1.2, 42.65 to 0.6, 42.5 to 2.2)),
        Relief("Himalaya", 1.0, listOf(35.5 to 74.5, 33.0 to 78.0, 30.0 to 81.0, 28.3 to 84.0, 27.99 to 86.93, 27.6 to 89.0, 28.0 to 92.5)),
        Relief("Tian Shan", 0.7, listOf(41.5 to 74.0, 42.2 to 78.0, 43.0 to 82.0, 43.3 to 86.0)),
        Relief("Andes", 0.9, listOf(10.0 to -73.0, 2.0 to -77.0, -5.0 to -79.0, -13.0 to -73.5, -17.0 to -69.5, -23.0 to -67.5,
            -30.0 to -70.0, -36.0 to -70.5, -42.0 to -72.0, -48.0 to -73.2)),
        Relief("Rocheuses", 0.7, listOf(60.0 to -130.0, 55.0 to -122.0, 50.0 to -116.0, 45.0 to -111.0, 40.0 to -106.0, 36.0 to -106.0)),
        Relief("Sierra Nevada", 0.5, listOf(47.0 to -121.5, 40.5 to -122.0, 37.0 to -118.8)),
        Relief("Alaska", 0.7, listOf(63.07 to -151.0, 61.5 to -146.0)),
        Relief("Appalaches", 0.3, listOf(44.5 to -71.0, 40.5 to -78.0, 35.5 to -83.0)),
        Relief("Atlas", 0.5, listOf(31.0 to -8.0, 32.5 to -5.0, 34.0 to -1.0, 35.5 to 3.0)),
        Relief("Caucase", 0.6, listOf(43.6 to 40.5, 43.35 to 42.45, 42.5 to 45.5)),
        Relief("Oural", 0.4, listOf(64.0 to 59.0, 58.0 to 59.3, 53.0 to 58.5)),
        Relief("Zagros", 0.5, listOf(35.5 to 46.0, 31.5 to 50.0, 28.5 to 54.0)),
        Relief("Hauts plateaux d'Éthiopie", 0.5, listOf(13.0 to 38.0, 9.0 to 38.6, 7.0 to 38.5)),
        Relief("Kilimandjaro", 0.7, listOf(-3.07 to 37.36)),
        Relief("Alpes scandinaves", 0.4, listOf(69.0 to 19.0, 64.0 to 14.0, 61.5 to 8.0)),
        Relief("Alpes du Sud", 0.5, listOf(-43.5 to 170.5, -45.0 to 168.5)))

    val MONUMENTS = listOf(
        Monument("tour_eiffel", "Tour Eiffel", 48.858, 2.294), Monument("liberte", "Statue de la Liberté", 40.689, -74.045),
        Monument("pyramides", "Pyramides de Gizeh", 29.979, 31.134), Monument("big_ben", "Big Ben", 51.501, -0.125),
        Monument("taj_mahal", "Taj Mahal", 27.175, 78.042), Monument("opera_sydney", "Opéra de Sydney", -33.857, 151.215),
        Monument("christ", "Christ Rédempteur", -22.952, -43.211), Monument("fuji", "Mont Fuji", 35.361, 138.727),
        Monument("kremlin", "Kremlin", 55.752, 37.617), Monument("colisee", "Colisée", 41.890, 12.492),
        Monument("golden_gate", "Golden Gate", 37.820, -122.478), Monument("moulin", "Moulins de Hollande", 52.0, 4.7),
        Monument("muraille", "Grande Muraille", 40.43, 116.57), Monument("moai", "Moaï de l'île de Pâques", -27.12, -109.35),
        Monument("fusee", "Kourou", 5.236, -52.768), Monument("fusee", "Cap Canaveral", 28.573, -80.649),
        Monument("fusee", "Baïkonour", 45.920, 63.342), Monument("phare", "Phare d'Eckmühl", 47.797, -4.372))

    val DECOR = listOf(
        Decor("bateau", 45.0, -35.0), Decor("bateau", 30.0, -48.0), Decor("bateau", 50.0, -20.0), Decor("bateau", -20.0, -15.0),
        Decor("bateau", -35.0, 5.0), Decor("bateau", 20.0, -150.0), Decor("bateau", -10.0, -120.0), Decor("bateau", 35.0, 160.0),
        Decor("bateau", 2.0, 170.0), Decor("bateau", -12.0, 82.0), Decor("bateau", -30.0, 60.0), Decor("bateau", 35.8, 18.0),
        Decor("bateau", 15.0, 63.0), Decor("bateau", 47.0, -6.5), Decor("bateau", 57.0, 3.0), Decor("bateau", -45.0, -140.0),
        Decor("baleine", -55.0, 0.0), Decor("baleine", 58.0, -165.0), Decor("baleine", 20.0, -40.0), Decor("baleine", -40.0, 110.0),
        Decor("foret", -5.0, -62.0), Decor("foret", -8.0, -55.0), Decor("foret", 0.5, 22.0), Decor("foret", 60.0, 100.0),
        Decor("foret", 62.0, 75.0), Decor("foret", 55.0, -100.0), Decor("foret", 52.0, -80.0), Decor("foret", 1.0, 114.0),
        Decor("foret", 63.0, 26.0), Decor("foret", 48.5, 10.0), Decor("foret", 45.0, -122.5),
        Decor("manchot", -74.0, 0.0), Decor("manchot", -70.0, 140.0), Decor("manchot", -72.0, -100.0), Decor("manchot", -67.0, 60.0))

    /** All of it for the page: `{villes: [[nom, lat, lon, rang]], reliefs: [{nom, h, crete: [[lat, lon]]}], monuments: [[id, nom, lat, lon]], decor: [[type, lat, lon]]}`. */
    fun json(): String = JSONObject()
        .put("villes", JSONArray(VILLES.map { JSONArray(listOf(it.nom, it.lat, it.lon, it.rang)) }))
        .put("reliefs", JSONArray(RELIEFS.map { r -> JSONObject().put("nom", r.nom).put("h", r.hauteur).put("crete", JSONArray(r.crete.map { JSONArray(listOf(it.first, it.second)) })) }))
        .put("monuments", JSONArray(MONUMENTS.map { JSONArray(listOf(it.id, it.nom, it.lat, it.lon)) }))
        .put("decor", JSONArray(DECOR.map { JSONArray(listOf(it.type, it.lat, it.lon)) }))
        .toString()
}
