/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.sonde

/**
 * Décodage des sondes Meteomodem M10 et M20, celles que lâche Météo-France.
 *
 * C'est le décodeur qui compte pour un Breton : Brest-Guipavas lâche des M20
 * sur 404,000 MHz deux fois par jour, et c'est la sonde la plus susceptible de
 * tomber dans le Finistère.
 *
 * Ce qu'il a fallu comprendre pour que la M10 se décode enfin, en 18.7 :
 *
 * 1. Elle ne module pas en Manchester. Le codage est celui que rs1729 appelle
 *    `psk_bpm`, un bi-phase à marque : deux chips **identiques** valent un
 *    zéro, deux chips **différents** valent un un, et le niveau bascule à
 *    chaque frontière de bit. Le décodage Manchester faisait exactement
 *    l'inverse — il choisissait la phase qui minimise les paires plates, alors
 *    que les paires plates sont ici des données légitimes. C'est pour cela
 *    qu'un signal parfaitement propre rendait du charabia, et que le charabia
 *    avait l'air d'un bon décodage : quatre pour cent de paires plates, un
 *    chiffre rassurant et rigoureusement trompeur.
 *
 * 2. L'alignement ne se cherche pas sur les octets mais sur les chips. La M10
 *    émet quatre cents chips d'alternances, puis un motif de synchronisation de
 *    trente-deux chips, et la trame commence trente et un chips après le début
 *    de ce motif — pas trente-deux : le dernier chip du motif est déjà le
 *    premier demi-bit de la trame. Un chip de trop et tout sort à `FF`.
 *
 * 3. Elle n'émet pas en continu. Elle module par tout ou rien, deux cent neuf
 *    millisecondes de porteuse par seconde, le reste au repos. Un opérateur qui
 *    regarde la cascade voit un trait pointillé, et c'est normal.
 *
 * 4. Contrairement à ce que l'on croyait, la M10 porte bien un contrôle : deux
 *    octets en 0x63, calculés par l'algorithme de Meteomodem. Une trame M10
 *    n'est donc plus retenue sur sa seule vraisemblance physique, elle est
 *    prouvée. Sur l'enregistrement de référence de radiosonde_auto_rx, vingt
 *    trames sur vingt passent le contrôle.
 *
 * La M20, elle, reste en 2-FSK simple à 9600 bits par seconde, sans contrôle
 * reproductible : c'est la vraisemblance qui lui sert de garde-fou.
 */
object Meteomodem {

    /**
     * Débit de chips de la M10 sur l'air, en chips par seconde.
     *
     * La 18.6 a corrigé un facteur deux qui traînait depuis le début : le nombre
     * 9616 est bien celui des chips, et non celui des bits. La M10 code en
     * Manchester, donc son débit binaire utile est la moitié, 4808 bits par
     * seconde. Les versions précédentes prenaient 9616 pour un débit binaire et
     * réglaient donc le démodulateur sur 19232 chips par seconde, c'est-à-dire
     * deux fois trop vite : chaque chip était lu deux fois, le décodage
     * Manchester ne voyait que des paires plates et jetait tout.
     *
     * La mesure a tranché. Sur l'enregistrement de référence de
     * radiosonde_auto_rx, le spectre des impulsions de transition montre une
     * raie à 9614,7 Hz à vingt-deux décibels au-dessus du fond, la raie binaire
     * à 4807,5 Hz, et celle de 19228,5 Hz n'est que l'harmonique deux.
     */
    const val M10_CHIP_RATE = 9616.0

    /** Débit binaire utile de la M10 : moitié des chips, codage bi-phase. */
    const val M10_BAUD = M10_CHIP_RATE / 2.0

    /** Débit de la M20, en bits par seconde. */
    const val M20_BAUD = 9600.0

    /** Largeur de filtre conseillée pour les deux, en hertz. */
    const val BANDWIDTH_HZ = 22_000

    /**
     * Longueur d'une trame M10, en octets.
     *
     * Le premier octet vaut 0x64, soit cent, et c'est ce qui a longtemps induit
     * en erreur : c'est la longueur annoncée par la sonde, et la trame en
     * compte un de plus, les deux octets de contrôle finissant en 0x63 et 0x64.
     */
    const val M10_LEN = 101

    /** Longueur d'une trame M20, en octets. */
    const val M20_LEN = 0x45

    /** En-tête de trame M10 : longueur annoncée, puis type. */
    val M10_HEADER = intArrayOf(0x64, 0x9F, 0x20)

    /**
     * Motif de synchronisation de la M10, en chips.
     *
     * Il se cherche dans le flux de demi-bits, pas dans les octets, et il se
     * cherche aussi bien à l'endroit qu'à l'envers : selon le sens de la
     * démodulation FM, le flux peut sortir inversé. Cela ne change rien au
     * décodage lui-même — une paire de chips identiques le reste après
     * inversion — mais cela change tout à la reconnaissance du motif.
     */
    val M10_SYNC = byteArrayOf(
        1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1,
        0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1)

    /**
     * Distance en chips entre le début du motif et le premier demi-bit de la
     * trame. Trente et un, et non trente-deux : le dernier chip du motif
     * appartient déjà à la trame.
     */
    const val M10_SYNC_TO_FRAME = 31

    /**
     * Échelle des coordonnées M10 : le tour complet tient sur trente-deux bits.
     *
     * rs1729 divise par 0xB60B60, qui est l'arrondi entier de ce nombre. La
     * différence est de six centièmes de millionième, soit un demi-mètre au
     * pôle — sans conséquence sur le terrain, mais assez pour qu'une latitude
     * de quatre-vingt-dix degrés ressorte à 90,000004 et se fasse jeter par le
     * contrôle de vraisemblance. On garde donc la valeur exacte.
     */
    const val M10_DEG = 4_294_967_296.0 / 360.0

    /**
     * Les cinq octets de numéro de série d'une vraie M10, relevés sur
     * l'enregistrement de référence de radiosonde_auto_rx. Ils se lisent
     * « 803-2-10732 » et servent à la mire comme aux essais : une sonde de
     * synthèse qui s'annonce sous un nom crédible évite d'avoir à vérifier deux
     * fois si l'on regarde une vraie trame ou une trame fabriquée.
     */
    val M10_SERIAL_DEMO = byteArrayOf(0x02, 0x14, 0x83.toByte(), 0xDC.toByte(), 0x22)

    /** En-tête de trame M20. */
    val M20_HEADER = intArrayOf(0x45, 0x20)

    private fun b(f: ByteArray, i: Int) = f[i].toInt() and 0xff

    /** Entier trente-deux bits signé, poids fort en tête (les Meteomodem sont big endian). */
    fun be32(f: ByteArray, i: Int): Int =
        (b(f, i) shl 24) or (b(f, i + 1) shl 16) or (b(f, i + 2) shl 8) or b(f, i + 3)

    /** Entier seize bits signé, poids fort en tête. */
    fun be16(f: ByteArray, i: Int): Int {
        val v = (b(f, i) shl 8) or b(f, i + 1)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /** Entier seize bits non signé, poids fort en tête. */
    fun beu16(f: ByteArray, i: Int): Int = (b(f, i) shl 8) or b(f, i + 1)

    /**
     * Décodage bi-phase à marque de la M10 : deux chips font un bit.
     *
     * Deux chips identiques valent zéro, deux chips différents valent un. Rien
     * n'est rejeté ici, et c'est voulu : contrairement au Manchester, aucune
     * combinaison de chips n'est illégale, donc aucun comptage de paires plates
     * ne peut dire si la phase est bonne. C'est le motif de synchronisation qui
     * décide de la phase, et la somme de contrôle qui décide de la trame.
     *
     * Rend le nombre de bits écrits.
     */
    fun biphase(chips: ByteArray, from: Int, count: Int, out: ByteArray): Int {
        var n = 0
        var k = from
        while (k + 1 < count && n < out.size) {
            out[n++] = if (chips[k] == chips[k + 1]) 0 else 1
            k += 2
        }
        return n
    }

    /**
     * Cherche le motif de synchronisation M10 dans un flux de chips.
     *
     * Les deux polarités sont éprouvées en un seul passage : [maxErrors] chips
     * faux sont tolérés, ce qui laisse passer un front mou sans ouvrir la porte
     * au bruit — sur trente-deux chips, deux erreurs tolérées donnent une
     * fausse alarme toutes les quatre millions de positions, et la somme de
     * contrôle balaie le reste. Rend l'indice du premier chip du motif, ou -1.
     */
    fun findSync(chips: ByteArray, count: Int, from: Int, maxErrors: Int = 2): Int {
        val n = M10_SYNC.size
        var i = if (from < 0) 0 else from
        val last = count - n
        while (i <= last) {
            var wrong = 0
            var right = 0
            for (k in 0 until n) {
                if ((chips[i + k].toInt() and 1) != M10_SYNC[k].toInt()) wrong++ else right++
                if (wrong > maxErrors && right > maxErrors) break
            }
            if (wrong <= maxErrors || right <= maxErrors) return i
            i++
        }
        return -1
    }

    /**
     * Assemble une trame M10 à partir d'un flux de chips calé sur son motif.
     *
     * Rend null si le flux est trop court ou si la somme de contrôle refuse.
     */
    fun frameFromChips(chips: ByteArray, syncAt: Int, out: ByteArray): Boolean {
        val start = syncAt + M10_SYNC_TO_FRAME
        if (out.size < M10_LEN) return false
        if (start + M10_LEN * 16 > chips.size) return false
        var p = start
        for (i in 0 until M10_LEN) {
            var v = 0
            for (j in 0 until 8) {
                v = (v shl 1) or (if (chips[p] == chips[p + 1]) 0 else 1)
                p += 2
            }
            out[i] = v.toByte()
        }
        return checkOkM10(out)
    }

    // ------------------------------------------------------ somme de contrôle

    /**
     * Un octet de plus dans la somme de contrôle M10, telle que Meteomodem la
     * calcule. Portage direct de `update_checkM10` de rs1729 : ce n'est ni un
     * CRC connu ni une somme simple, et il n'y a rien à en comprendre — il faut
     * la reproduire au bit près, ce que vérifie [MeteomodemM10Test].
     */
    fun updateCheckM10(c: Int, byteIn: Int): Int {
        val c1 = c and 0xFF
        var b = ((byteIn shr 1) or ((byteIn and 1) shl 7)) and 0xFF
        b = b xor ((b shr 2) and 0xFF)
        val t6 = (c and 1) xor ((c shr 2) and 1) xor ((c shr 4) and 1)
        val t7 = ((c shr 1) and 1) xor ((c shr 3) and 1) xor ((c shr 5) and 1)
        val t = (c and 0x3F) or (t6 shl 6) or (t7 shl 7)
        var sh = (c shr 7) and 0xFF
        sh = sh xor ((sh shr 2) and 0xFF)
        val c0 = (b xor t xor sh) and 0xFF
        return ((c1 shl 8) or c0) and 0xFFFF
    }

    /** La somme de contrôle des [n] premiers octets d'une trame M10. */
    fun checkM10(f: ByteArray, n: Int): Int {
        var c = 0
        for (i in 0 until n) c = updateCheckM10(c, f[i].toInt() and 0xff)
        return c and 0xFFFF
    }

    /** La trame porte-t-elle une somme de contrôle juste ? */
    fun checkOkM10(f: ByteArray): Boolean =
        f.size >= M10_LEN &&
            checkM10(f, M10.CHECK) == ((b(f, M10.CHECK) shl 8) or b(f, M10.CHECK + 1))

    /** Écrit la bonne somme de contrôle dans une trame fabriquée (mire, essais). */
    fun stampCheckM10(f: ByteArray) {
        val c = checkM10(f, M10.CHECK)
        f[M10.CHECK] = ((c shr 8) and 0xff).toByte()
        f[M10.CHECK + 1] = (c and 0xff).toByte()
    }

    /**
     * Le numéro de série imprimé sur la sonde, reconstitué depuis les cinq
     * octets de 0x5D. La mise en forme est celle de rs1729, les espaces en
     * moins : ils passent mal dans un fichier de journal.
     */
    fun serialM10(f: ByteArray): String {
        if (f.size < M10.SN + 5) return ""
        val s2 = b(f, M10.SN + 2)
        val v = b(f, M10.SN + 3) or (b(f, M10.SN + 4) shl 8)
        return "%X%02d-%X-%d%04d".format(
            (s2 shr 4) and 0xF, s2 and 0xF,
            b(f, M10.SN) and 0xF, (v shr 13) and 0x7, v and 0x1FFF)
    }

    /**
     * Position des champs dans une trame M20.
     *
     * Les décalages sont regroupés ici, et pas éparpillés dans le code, parce
     * qu'ils dépendent de la version du firmware : le jour où Meteomodem change
     * quelque chose, c'est cette table qu'il faudra corriger, et elle seule.
     */
    object M20 {
        const val ALT = 0x08          // altitude, 3 octets, centimètres
        const val VE = 0x0C           // vitesse est, 2 octets, 0,01 m/s
        const val VN = 0x0E           // vitesse nord
        const val VU = 0x10           // vitesse verticale
        const val LAT = 0x1C          // latitude, 4 octets, 1e-6 degré
        const val LON = 0x20          // longitude
        const val SERIAL = 0x2C       // numéro de série, 2 octets
        const val SATS = 0x30
    }

    /**
     * Position des champs dans une trame M10.
     * Même remarque que pour la M20 : table unique, corrigible d'un endroit.
     */
    object M10 {
        const val VE = 0x04           // vitesse est, 2 octets, 1/200 m/s
        const val VN = 0x06           // vitesse nord
        const val VU = 0x08           // vitesse verticale
        const val TOW = 0x0A          // heure GPS de la semaine, 4 octets, ms
        const val LAT = 0x0E          // latitude, 4 octets, tour = 2^32
        const val LON = 0x12          // longitude, même échelle
        const val ALT = 0x16          // altitude, 4 octets, millimètres
        const val WEEK = 0x20         // semaine GPS, 2 octets
        const val SN = 0x5D           // numéro de série, 5 octets
        const val CNT = 0x62          // compteur de trames, 1 octet
        const val CHECK = 0x63        // somme de contrôle, 2 octets
    }

    /**
     * Décode une trame M20 déjà alignée sur son en-tête.
     * Rend null si les chiffres obtenus ne tiennent pas debout.
     */
    fun parseM20(f: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (f.size < M20_LEN) return null
        val altCm = (b(f, M20.ALT) shl 16) or (b(f, M20.ALT + 1) shl 8) or b(f, M20.ALT + 2)
        val alt = altCm / 100.0
        val ve = be16(f, M20.VE) / 100.0
        val vn = be16(f, M20.VN) / 100.0
        val vu = be16(f, M20.VU) / 100.0
        val lat = be32(f, M20.LAT) * 1e-6
        val lon = be32(f, M20.LON) * 1e-6
        val enu = Geo.Enu(ve, vn, vu)
        val sats = b(f, M20.SATS) and 0x3F
        val serial = "%d".format(beu16(f, M20.SERIAL))
        val out = SondeFrame(
            type = "M20", serial = serial,
            lat = lat, lon = lon, altM = alt,
            speedMps = enu.groundMps, headingDeg = enu.headingDeg, climbMps = vu,
            sats = sats, freqHz = freqHz, heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /**
     * Décode une trame M10 déjà alignée sur son en-tête.
     *
     * La somme de contrôle est exigée : une trame qui ne la passe pas est du
     * bruit qui a eu de la chance, et il n'y a aucune raison de l'afficher.
     */
    fun parseM10(f: ByteArray, freqHz: Long = 0L, nowMs: Long = 0L): SondeFrame? {
        if (f.size < M10_LEN) return null
        if (!checkOkM10(f)) return null
        val lat = (be32(f, M10.LAT) / M10_DEG).coerceIn(-90.0, 90.0)
        val lon = (be32(f, M10.LON) / M10_DEG).coerceIn(-180.0, 180.0)
        val alt = be32(f, M10.ALT) / 1000.0
        val ve = be16(f, M10.VE) / 200.0
        val vn = be16(f, M10.VN) / 200.0
        val vu = be16(f, M10.VU) / 200.0
        val enu = Geo.Enu(ve, vn, vu)
        val tow = (be32(f, M10.TOW).toLong() and 0xFFFF_FFFFL)
        val week = beu16(f, M10.WEEK)
        val time = if (week in 1..4095) Geo.gpsToUnixMs(week, tow) else 0L
        val out = SondeFrame(
            type = "M10",
            serial = serialM10(f),
            frameNo = b(f, M10.CNT),
            timeUtcMs = time,
            lat = lat, lon = lon, altM = alt,
            speedMps = enu.groundMps, headingDeg = enu.headingDeg, climbMps = vu,
            sats = 0, satsUnknown = true, freqHz = freqHz, heardAtMs = nowMs)
        return if (out.plausible) out else null
    }

    /** Cherche un en-tête donné dans un tampon d'octets. */
    fun find(buf: ByteArray, header: IntArray, from: Int, to: Int): Int {
        val last = to - header.size
        var i = from
        while (i <= last) {
            var ok = true
            for (k in header.indices) {
                if ((buf[i + k].toInt() and 0xff) != header[k]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /**
     * Cherche et décode la première trame M20 d'un tampon.
     *
     * Séparé du M10 parce que les deux ne se démodulent pas de la même façon :
     * la M20 est une FSK à deux états toute simple, la M10 code un bit par
     * paire de demi-bits.
     * Les faire sortir du même démodulateur revenait à en sacrifier une, et
     * c'est la M20 qui était sacrifiée — celle que lâche Météo-France.
     */
    fun scanM20(buf: ByteArray, from: Int, to: Int,
                freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h = find(buf, M20_HEADER, at, to)
            if (h < 0) return null
            if (h + M20_LEN > to) return null
            val parsed = parseM20(buf.copyOfRange(h, h + M20_LEN), freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, h + M20_LEN)
            at = h + 1
        }
        return null
    }

    /** Cherche et décode la première trame M10 d'un tampon. */
    fun scanM10(buf: ByteArray, from: Int, to: Int,
                freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h = find(buf, M10_HEADER, at, to)
            if (h < 0) return null
            if (h + M10_LEN > to) return null
            val parsed = parseM10(buf.copyOfRange(h, h + M10_LEN), freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, h + M10_LEN)
            at = h + 1
        }
        return null
    }

    /**
     * Cherche et décode la première trame Meteomodem d'un tampon, M20 d'abord
     * puis M10. Rend la trame et l'indice de reprise.
     */
    fun scan(buf: ByteArray, from: Int, to: Int, freqHz: Long = 0L, nowMs: Long = 0L): Rs41.Hit? {
        var at = from
        while (at < to) {
            val h20 = find(buf, M20_HEADER, at, to)
            val h10 = find(buf, M10_HEADER, at, to)
            val first = when {
                h20 < 0 && h10 < 0 -> return null
                h20 < 0 -> h10
                h10 < 0 -> h20
                else -> minOf(h20, h10)
            }
            val isM20 = first == h20 && h20 >= 0
            val len = if (isM20) M20_LEN else M10_LEN
            if (first + len > to) return null
            val f = buf.copyOfRange(first, first + len)
            val parsed = if (isM20) parseM20(f, freqHz, nowMs) else parseM10(f, freqHz, nowMs)
            if (parsed != null) return Rs41.Hit(parsed, first + len)
            at = first + 1
        }
        return null
    }
}
