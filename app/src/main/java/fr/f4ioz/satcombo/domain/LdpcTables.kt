package fr.f4ioz.satcombo.domain

/**
 * Les tables du code correcteur (174, 91) de FT8 et FT4.
 *
 * **Provenance et licence.** Elles sont reprises de `ft8_lib` de Kārlis Goba,
 * publié sous licence MIT :
 *
 * ```
 * MIT License — Copyright (c) 2018 Kārlis Goba
 * ```
 *
 * La licence MIT autorise cet usage dans une application propriétaire à
 * condition d'en conserver la mention — d'où ce bloc, et l'entrée
 * correspondante dans l'écran « À propos ». Le protocole lui-même est décrit
 * par K9AN, G4WJS et K1JT dans QEX, où ses auteurs l'ont placé dans le domaine
 * public.
 *
 * **Pourquoi des tables et non des formules.** Une matrice de contrôle de
 * parité n'est pas calculable : c'est un objet de conception, choisi pour ses
 * propriétés de distance et l'absence de cycles courts dans son graphe. Les
 * inventer donnerait un code qui corrige mal et se tairait à ce sujet.
 *
 * Les valeurs sont rangées en chaînes plutôt qu'en tableaux littéraux : mille
 * cent constantes dans un initialiseur statique approcheraient la limite de
 * soixante-quatre kilo-octets par méthode de la machine virtuelle, et la
 * dépasser ne se voit qu'à l'exécution.
 */
object LdpcTables {

    /** 174 bits émis, 91 utiles (message et contrôle), 83 de parité. */
    const val N = 174
    const val K = 91
    const val M = 83

    /**
     * La matrice génératrice : une ligne de 91 bits par bit de parité, rangée
     * en hexadécimal, poids fort en tête. Les cinq derniers bits du douzième
     * octet ne servent pas.
     */
    private val GENERATEUR_HEX = arrayOf(
        "8329CE11BF31EAF509F27FC0",
        "761C264E25C2593354931320",
        "DC265902FB277C6410A1BDC0",
        "1B3F417858CD2DD33EC7F620",
        "09FDA4FEE04195FD034783A0",
        "077CCCC11B8873ED5C3D48A0",
        "29B62AFE3CA036F4FE1A9DA0",
        "6054FAF5F35D96D3B0C8C3E0",
        "E20798E4310EED27884AE900",
        "775C9C08E80E26DDAE563180",
        "B0B811028C2BF997213487C0",
        "18A0C9231FC60ADF5C5EA320",
        "76471E8302A0721E01B12B80",
        "FFBCCB80CA8341FAFB47B2E0",
        "66A72A158F9325A2BF671700",
        "C4243689FE85B1C51363A180",
        "0DFF739414D1A1B34B1C2700",
        "15B48830636C8B99894972E0",
        "29A89C0D3DE81D665489B0E0",
        "4F126F37FA51CBE61BD6B940",
        "99C47239D0D97D3C84E09400",
        "1919B75119765621BB4F1E80",
        "09DB12D731FAEE0B86DF6B80",
        "488FC33DF43FBDEEA4EAFB40",
        "827423EE40B675F756EB5FE0",
        "ABE197C484CB74757144A9A0",
        "2B500E4BC0EC5A6D2BDBDD00",
        "C474AA53D702187616693600",
        "8EBA1A13DB3390BD6718CEC0",
        "753844673A27782CC42012E0",
        "06FF83A145C37035A5C12680",
        "3B37417858CC2DD33EC3F620",
        "9A4A5A28EE17CA9C324842C0",
        "BC29F465309C977E89610A40",
        "2663AE6DDF8B5CE2BB294880",
        "46F231EFE457034C18144180",
        "3FB2CE85ABE9B0C72E06FBE0",
        "DE87481F282C153971A0A2E0",
        "FCD7CCF23C69FA99BBA14120",
        "F0261447E9490CA8E474CEC0",
        "4410115818196F95CDD70120",
        "088FC31DF4BFBDE2A4EAFB40",
        "B8FEF1B6307729FB0A078C00",
        "5AFEA7ACCCB77BBC9D99A900",
        "49A7016AC653F65ECDC90760",
        "1944D085BE4E7DA8D6CC7D00",
        "251F62ADC4032F0EE7140020",
        "56471F8702A0721E00B12B80",
        "2B8E4923F2DD51E2D537FA00",
        "6B550A40A66F4755DE95C260",
        "A18AD28D4E27FE92A4F6C840",
        "10C2E586388CB82A3D807580",
        "EF34A41817EE02133DB2EB00",
        "7E9C0C54325A9C15836E0000",
        "3693E572D1FDE4CDF079E860",
        "BFB2CEC5ABE1B0C72E07FBE0",
        "7EE18230C583CCCC57D4B080",
        "A066CB2FEDAFC9F526641260",
        "BB23725ABC47CC5F4CC4CD20",
        "DED9DBA3BEE40C59B5609B40",
        "D9A7016AC653E6DECDC90360",
        "9AD46AED5F707F280AB5FC40",
        "E5921C77822587316D7D3C20",
        "4F14DA8242A8B86DCA733520",
        "8B8B507AD467D4441DF770E0",
        "22831C9CF1169467AD04B680",
        "213B838FE2AE54C38EE71800",
        "5D926B6DD71F085181A4E120",
        "66AB79D4B29EE6E69509E560",
        "958148682D748A38DD68BAA0",
        "B8CE020CF069C32A723AB140",
        "F4331D6D461607E957527460",
        "6DA23BA424B9596133CF9C80",
        "A636BCBC7B30C5FBEAE67FE0",
        "5CB0D86A07DF654A9089A200",
        "F11F106848780FC9ECDD80A0",
        "1FBB5364FB8D2C9D730D5BA0",
        "FCB86BC70A50C9D02A5D0340",
        "A534433029EAC15F322E34C0",
        "C989D9C7C3D3B8C55D751300",
        "7BB38B2F0186D46643AE9620",
        "2644EBADEB44B9467D1F42C0",
        "608CC857594BFBB55D696000",
    )

    /** Les 83 lignes de la génératrice, en bits. */
    val generateur: Array<BooleanArray> = Array(M) { m ->
        val h = GENERATEUR_HEX[m]
        BooleanArray(K) { k ->
            val octet = h.substring(k / 8 * 2, k / 8 * 2 + 2).toInt(16)
            (octet shr (7 - k % 8)) and 1 == 1
        }
    }

    /**
     * Pour chaque contrôle, les bits qui y participent — **numérotés à partir
     * de un**, un zéro signalant une place inutilisée. La convention vient de
     * la source ; la changer silencieusement serait la meilleure façon de se
     * tromper d'un rang.
     */
    private val NM_PLAT = (        "4,31,59,91,92,96,153,5,32,60,93,115,146,0,6,24,61,94,122,151,0,7,33,62,95,96,143,0,8,25,63,83,93,96,148,6,32,64,97,126,1" +
        "38,0,5,34,65,78,98,107,154,9,35,66,99,139,146,0,10,36,67,100,107,126,0,11,37,67,87,101,139,158,12,38,68,102,105,155,0,13" +
        ",39,69,103,149,162,0,8,40,70,82,104,114,145,14,41,71,88,102,123,156,15,42,59,106,123,159,0,1,33,72,106,107,157,0,16,43,7" +
        "3,108,141,160,0,17,37,74,81,109,131,154,11,44,75,110,121,166,0,45,55,64,111,130,161,173,8,46,71,112,119,166,0,18,36,76,8" +
        "9,113,114,143,19,38,77,104,116,163,0,20,47,70,92,138,165,0,2,48,74,113,128,160,0,21,45,78,83,117,121,151,22,47,58,118,12" +
        "7,164,0,16,39,62,112,134,158,0,23,43,79,120,131,145,0,19,35,59,73,110,125,161,20,36,63,94,136,161,0,14,31,79,98,132,164," +
        "0,3,44,80,124,127,169,0,19,46,81,117,135,167,0,7,49,58,90,100,105,168,12,50,61,118,119,144,0,13,51,64,114,118,157,0,24,5" +
        "2,76,129,148,149,0,25,53,69,90,101,130,156,20,46,65,80,120,140,170,21,54,77,100,140,171,0,35,82,133,142,171,174,0,14,30," +
        "83,113,125,170,0,4,29,68,120,134,173,0,1,4,52,57,86,136,152,26,51,56,91,122,137,168,52,84,110,115,145,168,0,7,50,81,99,1" +
        "32,173,0,23,55,67,95,172,174,0,26,41,77,109,141,148,0,2,27,41,61,62,115,133,27,40,56,124,125,126,0,18,49,55,124,141,167," +
        "0,6,33,85,108,116,156,0,28,48,70,85,105,129,158,9,54,63,131,147,155,0,22,53,68,109,121,174,0,3,13,48,78,95,123,0,31,69,1" +
        "33,150,155,169,0,12,43,66,89,97,135,159,5,39,75,102,136,167,0,2,54,86,101,135,164,0,15,56,87,108,119,171,0,10,44,82,91,1" +
        "11,144,149,23,34,71,94,127,153,0,11,49,88,92,142,157,0,29,34,87,97,147,162,0,30,50,60,86,137,142,162,10,53,66,84,112,128" +
        ",165,22,57,85,93,140,159,0,28,32,72,103,132,166,0,28,29,84,88,117,143,150,1,26,45,80,128,147,0,17,27,89,103,116,153,0,51" +
        ",57,98,163,165,172,0,21,37,73,138,152,169,0,16,47,76,130,137,154,0,3,24,30,72,104,139,0,9,40,90,106,134,151,0,15,58,60,7" +
        "4,111,150,163,18,42,79,144,146,152,0,25,38,65,99,122,160,0,17,42,75,129,170,172,0").split(",").map { it.toInt() }

    /** Pour chaque bit, les trois contrôles où il figure. Même convention. */
    private val MN_PLAT = (        "16,45,73,25,51,62,33,58,78,1,44,45,2,7,61,3,6,54,4,35,48,5,13,21,8,56,79,9,64,69,10,19,66,11,36,60,12,37,58,14,32,43,15," +
        "63,80,17,28,77,18,74,83,22,53,81,23,30,34,24,31,40,26,41,76,27,57,70,29,49,65,3,38,78,5,39,82,46,50,73,51,52,74,55,71,72" +
        ",44,67,72,43,68,78,1,32,59,2,6,71,4,16,54,7,65,67,8,30,42,9,22,31,10,18,76,11,23,82,12,28,61,13,52,79,14,50,51,15,81,83," +
        "17,29,60,19,33,64,20,26,73,21,34,40,24,27,77,25,55,58,35,53,66,36,48,68,37,46,75,38,45,47,39,57,69,41,56,62,20,49,53,46," +
        "52,63,45,70,75,27,35,80,1,15,30,2,68,80,3,36,51,4,28,51,5,31,56,6,20,37,7,40,82,8,60,69,9,10,49,11,44,57,12,39,59,13,24," +
        "55,14,21,65,16,71,78,17,30,76,18,25,80,19,61,83,22,38,77,23,41,50,7,26,58,29,32,81,33,40,73,18,34,48,13,42,64,5,26,43,47" +
        ",69,72,54,55,70,45,62,68,10,63,67,14,66,72,22,60,74,35,39,79,1,46,64,1,24,66,2,5,70,3,31,65,4,49,58,1,4,5,6,60,67,7,32,7" +
        "5,8,48,82,9,35,41,10,39,62,11,14,61,12,71,74,13,23,78,11,35,55,15,16,79,7,9,16,17,54,63,18,50,57,19,30,47,20,64,80,21,28" +
        ",69,22,25,43,13,22,37,2,47,51,23,54,74,26,34,72,27,36,37,21,36,63,29,40,44,19,26,57,3,46,82,14,15,58,33,52,53,30,43,52,6" +
        ",9,52,27,33,65,25,69,73,38,55,83,20,39,77,18,29,56,32,48,71,42,51,59,28,44,79,34,60,62,31,45,61,46,68,77,6,24,76,8,10,78" +
        ",40,41,70,17,50,53,42,66,68,4,22,72,36,64,81,13,29,47,2,8,81,56,67,73,5,38,50,12,38,64,59,72,80,3,26,79,45,76,81,1,65,74" +
        ",7,18,77,11,56,59,14,39,54,16,37,66,10,28,55,15,60,70,17,25,82,20,30,31,12,67,68,23,75,80,27,32,62,24,69,75,19,21,71,34," +
        "53,61,35,46,47,33,59,76,40,43,83,41,42,63,49,75,83,20,44,48,42,49,57").split(",").map { it.toInt() }

    /** Combien de bits participent réellement à chaque contrôle. */
    val nbParContole: IntArray = intArrayOf(7,6,6,6,7,6,7,6,6,7,6,6,7,7,6,6,6,7,6,7,6,7,6,6,6,7,6,6,6,7,6,6,6,6,7,6,6,6,7,7,6,6,6,6,7,7,6,6,6,6,7,6,6,6,7,6,6,6,6,7,6,6,6,7,6,6,6,7,7,6,6,7,6,6,6,6,6,6,6,7,6,6,6)

    /** Les bits de chaque contrôle, ramenés à une numérotation à partir de zéro. */
    val bitsDuControle: Array<IntArray> = Array(M) { m ->
        IntArray(nbParContole[m]) { i -> NM_PLAT[m * 7 + i] - 1 }
    }

    /** Les trois contrôles de chaque bit, à partir de zéro. */
    val controlesDuBit: Array<IntArray> = Array(N) { n ->
        IntArray(3) { i -> MN_PLAT[n * 3 + i] - 1 }
    }
}
