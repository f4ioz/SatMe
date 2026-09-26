/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Génération ADIF, isolée du stockage.
 *
 * Le fichier ADIF est le seul format que tous les carnets de trafic savent
 * lire — LoTW, Club Log, QRZ, N1MM, Log4OM. Autant qu'il soit exact : une
 * ligne mal formée et l'import entier est refusé, sans dire laquelle.
 *
 * Rien ici ne touche à Android, donc tout est vérifiable sur machine.
 */
object Adif {

    /**
     * Bande amateur correspondant à une fréquence en mégahertz, telle que
     * l'ADIF l'attend (minuscules, sans espace). Une fréquence hors bande
     * renvoie une chaîne vide plutôt qu'une bande fausse : un champ absent
     * s'importe, un champ faux fait rejeter le contact.
     */
    fun band(freqMhz: Double): String = when {
        freqMhz <= 0.0 -> ""
        freqMhz in 1.8..2.0 -> "160m"
        freqMhz in 3.5..4.0 -> "80m"
        freqMhz in 7.0..7.3 -> "40m"
        freqMhz in 10.1..10.15 -> "30m"
        freqMhz in 14.0..14.35 -> "20m"
        freqMhz in 18.068..18.168 -> "17m"
        freqMhz in 21.0..21.45 -> "15m"
        freqMhz in 24.89..24.99 -> "12m"
        freqMhz in 28.0..29.7 -> "10m"
        freqMhz in 50.0..54.0 -> "6m"
        freqMhz in 144.0..148.0 -> "2m"
        freqMhz in 222.0..225.0 -> "1.25m"
        freqMhz in 420.0..450.0 -> "70cm"
        freqMhz in 902.0..928.0 -> "33cm"
        freqMhz in 1240.0..1300.0 -> "23cm"
        freqMhz in 2300.0..2450.0 -> "13cm"
        // Les bandes hautes s'arrêtaient à 13 cm, et QO-100 descend sur
        // 10 489 MHz : tous les contacts par satellite géostationnaire
        // partaient donc sans BAND_RX, avec une descente que le carnet d'en
        // face ne savait ranger sur aucune bande.
        freqMhz in 3300.0..3500.0 -> "9cm"
        freqMhz in 5650.0..5925.0 -> "6cm"
        freqMhz in 10000.0..10500.0 -> "3cm"
        freqMhz in 24000.0..24250.0 -> "1.25cm"
        else -> ""
    }

    /**
     * Lettre de bande au sens satellite : c'est ce qu'on écrit dans SAT_MODE,
     * « U/V » voulant dire montée en 70 cm et descente en 2 m.
     */
    fun satBandLetter(freqMhz: Double): String = when {
        freqMhz in 21.0..21.45 -> "H"
        freqMhz in 28.0..29.7 -> "A"
        freqMhz in 144.0..148.0 -> "V"
        freqMhz in 420.0..450.0 -> "U"
        freqMhz in 1240.0..1300.0 -> "L"
        freqMhz in 2300.0..2450.0 -> "S"
        freqMhz in 5650.0..5925.0 -> "C"
        freqMhz in 10000.0..10500.0 -> "X"
        else -> ""
    }

    /** « U/V » à partir de la montée et de la descente ; vide si l'une manque. */
    fun satMode(uplinkMhz: Double, downlinkMhz: Double): String {
        val u = satBandLetter(uplinkMhz)
        val d = satBandLetter(downlinkMhz)
        return if (u.isBlank() || d.isBlank()) "" else "$u/$d"
    }

    /**
     * Mode ADIF à partir de ce que raconte SatNOGS, qui mélange modes de
     * modulation et noms de protocoles. La BLU se déclare MODE=SSB avec un
     * SUBMODE USB ou LSB ; le reste tombe sur FM, CW ou DATA.
     */
    fun modeOf(raw: String?): Pair<String, String> {
        val s = (raw ?: "").uppercase()
        return when {
            s.contains("USB") -> "SSB" to "USB"
            s.contains("LSB") -> "SSB" to "LSB"
            s.contains("SSB") -> "SSB" to ""
            s.contains("CW") -> "CW" to ""
            s.startsWith("FM") || s.contains("FM") -> "FM" to ""
            s.contains("AM") -> "AM" to ""
            s.contains("SSTV") -> "SSTV" to ""
            s.contains("FT4") -> "MFSK" to "FT4"
            s.contains("FT8") -> "MFSK" to "FT8"
            s.contains("APRS") || s.contains("AFSK") || s.contains("FSK") ||
                s.contains("BPSK") || s.contains("GMSK") || s.contains("DATA") -> "PKT" to ""
            else -> "" to ""
        }
    }

    /**
     * Un champ ADIF. La longueur annoncée est comptée en octets UTF-8 : les
     * programmes qui lisent l'ADIF octet par octet — la majorité — se
     * décaleraient d'un caractère à chaque accent si on comptait autrement.
     */
    fun field(tag: String, value: String): String {
        if (value.isBlank()) return ""
        val v = value.replace('\n', ' ').replace('\r', ' ').trim()
        return "<$tag:${v.toByteArray(Charsets.UTF_8).size}>$v"
    }

    /**
     * Le fichier complet. [station] est l'indicatif de la station : il part
     * dans STATION_CALLSIGN et OPERATOR, sans quoi un import LoTW ne sait pas
     * à qui attribuer les contacts.
     */
    fun export(entries: List<LogEntry>, station: String = "", programVersion: String = ""): String {
        val sb = StringBuilder()
        sb.append("ADIF export — SatMe\n")
        sb.append(field("ADIF_VER", "3.1.4"))
        sb.append(field("PROGRAMID", "SatMe"))
        if (programVersion.isNotBlank()) sb.append(field("PROGRAMVERSION", programVersion))
        sb.append("\n<EOH>\n")
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        val tf = SimpleDateFormat("HHmmss", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        tf.timeZone = TimeZone.getTimeZone("UTC")
        // **Sans indicatif, pas d'enregistrement.**
        //
        // Le garde est ici, au seul endroit qui fabrique l'ADIF, et non chez
        // les trois appelants : il en manquait un — l'export du carnet
        // complet — et quatre relevés anonymes sont partis dans le fichier du
        // 25 août. Un QSO sans CALL n'est pas un contact incomplet, ce n'est
        // pas un contact du tout.
        entries.filter { it.callsign.isNotBlank() }.forEach { e ->
            sb.append(enregistrement(e, station))
        }
        return sb.toString()
    }

    /**
     * Un contact, seul, sans en-tête de fichier.
     *
     * L'envoi au carnet en ligne pousse les contacts **un par un** : le
     * serveur veut un enregistrement, pas un fichier. Le corps est donc
     * extrait ici plutôt que recopié — un second endroit qui fabriquerait de
     * l'ADIF finirait par diverger du premier, et c'est exactement ainsi que
     * FREQ et FREQ_RX ont pu rester inversés sans que rien ne le montre.
     *
     * Rend une chaîne vide sans indicatif : la règle est la même partout.
     */
    fun enregistrement(e: LogEntry, station: String = ""): String {
        if (e.callsign.isBlank()) return ""
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyyMMdd", Locale.US)
        val tf = SimpleDateFormat("HHmmss", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        tf.timeZone = TimeZone.getTimeZone("UTC")
            val d = Date(e.timeMs)
            sb.append(field("QSO_DATE", df.format(d)))
            sb.append(field("TIME_ON", tf.format(d)))
            sb.append(field("SAT_NAME", e.satName))
            sb.append(field("PROP_MODE", "SAT"))
            if (station.isNotBlank()) {
                sb.append(field("STATION_CALLSIGN", station.uppercase()))
                sb.append(field("OPERATOR", station.uppercase()))
            }
            sb.append(field("CALL", e.callsign))
            val (mode, sub) = modeOf(e.mode)
            sb.append(field("MODE", mode))
            sb.append(field("SUBMODE", sub))
            sb.append(field("RST_SENT", e.rstSent))
            sb.append(field("RST_RCVD", e.rstRcvd))
            // **FREQ est la fréquence d'émission, FREQ_RX celle de réception.**
            //
            // La norme ADIF les définit du point de vue de la station qui
            // journalise : FREQ et BAND décrivent ce sur quoi elle émet,
            // FREQ_RX et BAND_RX ce sur quoi elle écoute. Les deux étaient
            // inversés ici — la descente partait dans FREQ.
            //
            // Sur un contact V/U, tout QSO exporté déclarait donc 435 MHz
            // comme bande d'émission alors que le poste montait sur 145. Le
            // carnet d'en face range le contact sur la mauvaise bande, et LoTW
            // n'a aucune chance d'apparier ce que l'autre station a déclaré
            // correctement.
            if (e.uplinkMhz > 0.0) {
                sb.append(field("FREQ", "%.6f".format(Locale.US, e.uplinkMhz)))
                sb.append(field("BAND", band(e.uplinkMhz)))
            }
            if (e.downlinkMhz > 0.0) {
                sb.append(field("FREQ_RX", "%.6f".format(Locale.US, e.downlinkMhz)))
                sb.append(field("BAND_RX", band(e.downlinkMhz)))
            }
            // L'azimut et l'élévation du satellite au moment du contact.
            //
            // La norme les destine à l'antenne de la station, et c'est bien de
            // cela qu'il s'agit : en trafic satellite, l'antenne pointe le
            // satellite. Ces deux nombres sont déjà au carnet — ils ne
            // sortaient simplement pas.
            if (e.elevationDeg >= 0.0) {
                sb.append(field("ANT_AZ", "%.1f".format(Locale.US,
                    ((e.azimuthDeg % 360.0) + 360.0) % 360.0)))
                sb.append(field("ANT_EL", "%.1f".format(Locale.US, e.elevationDeg)))
            }
            sb.append(field("SAT_MODE", satMode(e.uplinkMhz, e.downlinkMhz)))
            if (e.myLocator.isNotBlank()) sb.append(field("MY_GRIDSQUARE", e.myLocator))
            if (e.theirLocator.isNotBlank()) sb.append(field("GRIDSQUARE", e.theirLocator))
            // Ce que l'annuaire a appris du correspondant. Sans ces champs, le
            // carnet d'en face reste vide et il faut aller le remplir à la
            // main, contact par contact — alors que l'information était là.
            if (e.nom.isNotBlank()) sb.append(field("NAME", e.nom))
            if (e.qth.isNotBlank()) sb.append(field("QTH", e.qth))
            if (e.courriel.isNotBlank()) sb.append(field("EMAIL", e.courriel))
            // Opérer sur une ligne de carré est une situation réelle, et l'ADIF
            // a un champ pour ça, MY_VUCC_GRIDS — mais la norme n'accepte que
            // deux ou quatre carrés adjacents. Trois (un coin dont un carré est
            // hors de portée) serait refusé par le carnet d'en face : on le met
            // alors dans le commentaire plutôt que de le perdre en silence.
            val grids = e.myGrids.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (grids.size == 2 || grids.size == 4) {
                sb.append(field("MY_VUCC_GRIDS", grids.joinToString(",")))
            }
            val note = if (grids.size == 3) (e.note + " " + grids.joinToString("/")).trim() else e.note
            sb.append(field("COMMENT", note))
            sb.append("<EOR>\n")
        return sb.toString()
    }
}