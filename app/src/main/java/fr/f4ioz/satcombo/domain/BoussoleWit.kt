/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.domain

/**
 * Le protocole des modules d'attitude WitMotion, réduit à ce dont une boussole
 * d'antenne a besoin.
 *
 * Le module — un WT9011DCL-BT50 — fusionne gyroscope, accéléromètre et
 * magnétomètre dans sa propre puce et rend une attitude déjà compensée en
 * inclinaison. C'est tout l'intérêt : un magnétomètre nu ne donne un cap juste
 * que posé à plat, et une flèche d'antenne ne l'est jamais.
 *
 * Cette partie-ci ne connaît ni Bluetooth ni Android. Elle prend des octets et
 * rend des angles, ce qui la rend éprouvable à la table.
 */

/** Les identifiants GATT du module. Relevés sur la notice, pas devinés. */
object GattWit {
    /**
     * Attention à la base : WitMotion publie `...-00805f9a34fb`, avec un **9a**
     * là où la base Bluetooth normalisée porte un **9b**. Ce n'est pas une
     * coquille de leur documentation — c'est bien ce que le module annonce, et
     * une base normalisée ne trouverait rien.
     */
    const val SERVICE = "0000ffe5-0000-1000-8000-00805f9a34fb"
    const val NOTIFICATION = "0000ffe4-0000-1000-8000-00805f9a34fb"
    const val ECRITURE = "0000ffe9-0000-1000-8000-00805f9a34fb"

    /** Le descripteur d'abonnement, celui-ci parfaitement normalisé. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Les noms diffusés par la famille : WT901BLE, WT9011DCL… */
    fun nomPlausible(nom: String?): Boolean {
        val n = nom?.uppercase() ?: return false
        return n.startsWith("WT") || n.contains("WITMOTION") || n.contains("HWT")
    }
}

/** Une attitude, en degrés. Le lacet est le seul qui nous serve. */
data class AttitudeWit(
    val roulis: Float,
    val tangage: Float,
    val lacet: Float
)

/** Ce qu'une trame reçue peut être. */
sealed class TrameWit {
    data class Attitude(val valeur: AttitudeWit) : TrameWit()
    /** Une trame connue mais sans intérêt ici (champ magnétique, quaternion…). */
    data class Ignoree(val drapeau: Int) : TrameWit()
}

object BoussoleWit {

    const val ENTETE = 0x55
    const val DRAPEAU_ATTITUDE = 0x61
    const val LONGUEUR = 20

    /**
     * Les autres drapeaux que le module peut émettre. Ils font la même
     * longueur ; les reconnaître évite de les prendre pour du bruit et de
     * resynchroniser inutilement au milieu d'une trame valide.
     */
    private val DRAPEAUX_CONNUS = setOf(0x51, 0x52, 0x53, 0x59, 0x61, 0x71)

    private fun entier16(bas: Byte, haut: Byte): Int {
        val v = (haut.toInt() and 0xFF shl 8) or (bas.toInt() and 0xFF)
        return if (v >= 32768) v - 65536 else v
    }

    /** `angle = brut / 32768 × 180°`, la formule de la notice. */
    private fun angle(bas: Byte, haut: Byte): Float =
        entier16(bas, haut) / 32768f * 180f

    /**
     * Lit **une** trame d'attitude posée à l'indice [debut].
     *
     * Rend `null` si l'entête ne colle pas ou s'il manque des octets. La trame
     * 0x61 ne porte **aucune somme de contrôle** — contrairement à la 0x53 —
     * donc la seule garde possible est l'entête et la longueur. C'est la raison
     * pour laquelle [Accumulateur] resynchronise plutôt que d'insister.
     */
    fun litAttitude(octets: ByteArray, debut: Int = 0): AttitudeWit? {
        if (debut + LONGUEUR > octets.size) return null
        if ((octets[debut].toInt() and 0xFF) != ENTETE) return null
        if ((octets[debut + 1].toInt() and 0xFF) != DRAPEAU_ATTITUDE) return null
        // 2..7 accélération, 8..13 vitesse angulaire, 14..19 les angles.
        return AttitudeWit(
            roulis = angle(octets[debut + 14], octets[debut + 15]),
            tangage = angle(octets[debut + 16], octets[debut + 17]),
            lacet = angle(octets[debut + 18], octets[debut + 19])
        )
    }

    /**
     * Le lacet du module, ramené à un azimut de boussole : 0 à 360°, le nord
     * en tête.
     *
     * Deux corrections, et elles ont chacune leur raison d'exister :
     *
     * **Le calage** [offsetDeg] — le zéro du module n'est pas forcément le nord
     * de l'antenne. Il dépend de la façon dont le boîtier est fixé sur la
     * flèche, et il change dès qu'on démonte. La notice impose d'ailleurs un
     * étalonnage à chaque changement de monture ; ce réglage-ci en est le
     * pendant logiciel.
     *
     * **Le sens** [inverse] — le module compte dans le repère nord-est-ciel,
     * où les rotations positives tournent dans le sens trigonométrique, tandis
     * qu'un azimut tourne dans le sens des aiguilles. Selon le micrologiciel et
     * l'orientation du boîtier, le lacet peut donc suivre l'azimut ou lui être
     * opposé. **On ne devine pas** : l'opérateur tourne l'antenne d'un quart de
     * tour vers la droite, et si le nombre descend, il coche la case.
     *
     * La déclinaison n'entre pas ici : SatMe l'applique déjà en aval, et la
     * poser deux fois la doublerait.
     */
    fun azimutDepuisLacet(lacetDeg: Float, offsetDeg: Float = 0f, inverse: Boolean = false): Float {
        val signe = if (inverse) -lacetDeg else lacetDeg
        var a = (signe + offsetDeg) % 360f
        if (a < 0f) a += 360f
        return a
    }

    /**
     * Le calage à déduire d'un relevé : l'antenne pointe un azimut connu
     * [azimutVrai], le module dit [lacetDeg] ; voici ce qu'il faut ajouter.
     *
     * Rendu dans −180..180 plutôt que 0..360, pour qu'un petit écart s'affiche
     * comme un petit nombre — un « −3° » se relit, un « 357° » se conteste.
     */
    fun calageDepuisReleve(lacetDeg: Float, azimutVrai: Float, inverse: Boolean = false): Float {
        val signe = if (inverse) -lacetDeg else lacetDeg
        var d = (azimutVrai - signe) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // ---- L'ancienne lecture par angles séparés : retirée ----
    //
    // Elle prenait le lacet pour l'azimut et l'un des deux autres angles pour
    // l'élévation, avec un sélecteur d'axe, un calage et un sens à régler à la
    // main. Juste en polarisation horizontale, fausse partout ailleurs : dès
    // qu'on tourne la flèche sur son axe, l'élévation migre d'un angle vers
    // l'autre, et à mi-chemin elle est partagée entre les deux.
    //
    // `PointageAntenne` répond à la même question par le vecteur de visée, et
    // la polarisation n'a plus de prise. Garder les deux aurait laissé deux
    // réponses divergentes à une seule question.

    /**
     * Le tampon qui recolle les trames.
     *
     * Une notification BLE ne porte pas forcément une trame entière et une seule
     * : selon la MTU négociée, elle peut en couper une en deux ou en livrer deux
     * d'un coup. Accumuler puis découper est la seule façon sûre.
     *
     * Sans somme de contrôle, un octet 0x55 tombé au milieu du bruit peut faire
     * croire à un entête. On avance donc d'**un seul octet** quand l'entête ne
     * mène nulle part, au lieu de sauter vingt octets — un faux départ coûte
     * alors une trame, pas la synchronisation.
     */
    class Accumulateur(private val plafond: Int = 256) {
        private val tampon = ArrayList<Byte>(plafond)

        /** Ce que le tampon retient, pour les essais. */
        val enAttente: Int get() = tampon.size

        fun vide() { tampon.clear() }

        /** Verse [morceau] et rend les attitudes complètes qu'il a permis de lire. */
        fun verse(morceau: ByteArray): List<AttitudeWit> {
            for (b in morceau) tampon.add(b)
            // Un tampon qui enfle est un tampon qui ne se resynchronise pas :
            // on ne garde que de quoi reconstituer deux trames.
            while (tampon.size > plafond) tampon.removeAt(0)

            val sorties = ArrayList<AttitudeWit>()
            var i = 0
            while (i + LONGUEUR <= tampon.size) {
                if ((tampon[i].toInt() and 0xFF) != ENTETE) { i++; continue }
                val drapeau = tampon[i + 1].toInt() and 0xFF
                if (drapeau !in DRAPEAUX_CONNUS) { i++; continue }
                if (drapeau == DRAPEAU_ATTITUDE) {
                    val octets = ByteArray(LONGUEUR) { k -> tampon[i + k] }
                    val a = litAttitude(octets)
                    if (a == null) { i++; continue }
                    sorties.add(a)
                }
                i += LONGUEUR
            }
            // Ce qui reste est soit une trame incomplète, soit des miettes.
            repeat(i) { tampon.removeAt(0) }
            return sorties
        }
    }

    /**
     * Le lissage du cap.
     *
     * Le module annonce 0,2° et il les tient, mais une antenne tenue à bout de
     * bras tremble. On applique le même filtre du premier ordre que les
     * capteurs du téléphone, avec le passage par le plus court chemin pour que
     * 359° → 1° ne fasse pas faire un tour complet à l'aiguille.
     */
    fun lisse(precedent: Float, nouveau: Float, k: Float = 0.25f): Float {
        if (precedent.isNaN()) return nouveau
        var d = ((nouveau - precedent + 540f) % 360f) - 180f
        var r = (precedent + k * d) % 360f
        if (r < 0f) r += 360f
        return r
    }
}
