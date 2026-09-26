/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Logbook of the World : les carrés confirmés en satellite.
 *
 * LoTW ne répond pas carré par carré comme Wavelog : il rend un fichier ADIF
 * complet. On le télécharge donc **une fois**, on en extrait les carrés, et
 * l'on garde le résultat sur le disque. Un rafraîchissement se demande à la
 * main ou après plusieurs jours — la charge est du côté d'ARRL, pas du nôtre.
 *
 * Deux ensembles en sortent, qui ne disent pas la même chose :
 *
 * - **confirmés** : le correspondant a confirmé, le carré compte pour un
 *   diplôme ;
 * - **travaillés** : le contact existe dans le journal, sans confirmation.
 *
 * Le mot de passe LoTW est celui du compte ARRL, et il ouvre bien plus que la
 * lecture d'un journal. Il est conservé tel quel dans les réglages, comme le
 * reste : c'est un pis-aller que l'opérateur doit connaître, LoTW n'offrant
 * pas de jeton en lecture seule.
 */
object Lotw {

    class Etat(
        val confirmes: Set<String>, val travailles: Set<String>, val quandMs: Long,
        /**
         * Les indicatifs de contacts satellite **confirmés mais sans carré**
         * dans LoTW : le correspondant n'avait pas déclaré son locator. Ils
         * sont 33 chez F4IOZ, et c'est l'essentiel de l'écart avec le compte
         * de Gridmaster, qui les complète depuis ses propres données. SatMe a
         * de quoi faire de même : la mémoire des indicatifs connaît des
         * milliers de carrés.
         */
        val sansCarre: Set<String> = emptySet(),
        /**
         * **Mes** carrés : ceux d'où j'ai émis (`MY_GRIDSQUARE`). Dix-sept
         * chez F4IOZ. Ce n'est pas la même information que les carrés
         * contactés, et cela ne se peint pas de la même couleur : l'un dit où
         * je suis allé, l'autre qui j'ai joint.
         */
        val activés: Set<String> = emptySet(),
    )

    /** Taille des réponses brutes, pour comprendre un compte trop bas. */
    @Volatile var diag: String = ""

    @Volatile private var cache: Etat? = null

    private fun fichier(ctx: Context) = File(ctx.filesDir, "lotw_carres.txt")

    /** Ce qui est déjà connu, sans rien demander au réseau. */
    fun charge(ctx: Context): Etat {
        cache?.let { return it }
        val f = fichier(ctx)
        val e = if (!f.exists()) Etat(emptySet(), emptySet(), 0L) else runCatching {
            val l = f.readLines()
            Etat(
                l.getOrElse(1) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(2) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(0) { "0" }.toLongOrNull() ?: 0L,
                l.getOrElse(3) { "" }.split(",").filter { it.isNotBlank() }.toSet(),
                l.getOrElse(4) { "" }.split(",").filter { it.isNotBlank() }.toSet())
        }.getOrDefault(Etat(emptySet(), emptySet(), 0L))
        cache = e
        return e
    }

    /**
     * Télécharge le journal satellite et en extrait les carrés.
     *
     * `qso_query=1` demande les contacts, `qso_qsl=no` les rend tous
     * (confirmés ou non), et l'on trie ensuite sur `QSL_RCVD`. Le filtre par
     * mode de propagation se fait ici plutôt que dans la requête : toutes les
     * installations ne l'acceptent pas, et le fichier reste petit.
     */
    suspend fun rafraichis(ctx: Context, indicatif: String, motDePasse: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                // Deux demandes, parce qu'elles ne rendent pas la même chose.
                //
                // Le carré du correspondant n'apparaît que dans le **détail
                // des confirmations** : sans `qso_qsl=yes&qso_qsldetail=yes`,
                // LoTW rend les contacts sans leur locator, et l'on croit
                // n'avoir presque rien travaillé alors qu'on a des centaines
                // de carrés confirmés. C'était le défaut.
                //
                // La seconde demande (tous les contacts) sert aux carrés
                // travaillés non confirmés, quand ils portent un locator.
                fun demande(params: String): String {
                    val url = "https://lotw.arrl.org/lotwuser/lotwreport.adi" +
                        "?login=" + URLEncoder.encode(indicatif.trim(), "UTF-8") +
                        "&password=" + URLEncoder.encode(motDePasse, "UTF-8") +
                        "&qso_query=1" + params
                    val co = URL(url).openConnection() as HttpURLConnection
                    co.connectTimeout = 15000
                    // Le rapport complet fait plusieurs mégaoctets et met
                    // plusieurs minutes : le délai doit tenir jusqu'au bout,
                    // sinon on lit un fichier tronqué sans le savoir.
                    co.readTimeout = 420000
                    co.setRequestProperty("User-Agent", "SatMe (f4ioz.fr)")
                    return co.inputStream.bufferedReader().use { it.readText() }
                }
                // Cinq contacts rendus pour un journal qui en compte des
                // milliers : la réponse est tronquée, pas l'analyse. On garde
                // les fichiers bruts et l'on montre leur taille — la cause est
                // dans ce que LoTW renvoie, il faut pouvoir le lire.
                //
                // Les dates forcent la période complète : sans elles,
                // certaines réponses ne rendent qu'un fragment récent.
                // **Le paramètre est `qso_qslsince`**, pas `qso_qsorxsince`.
                //
                // Sans lui, LoTW l'écrit lui-même en tête de sa réponse :
                // « QSL RX SINCE: <hier> (system supplied default) » — il ne
                // rend que les confirmations reçues depuis la veille, d'où un
                // seul enregistrement. Avec la date, la même requête rapporte
                // 3396 contacts et 308 carrés satellite confirmés.
                //
                // La réponse portait donc son propre diagnostic dans ses cinq
                // premières lignes ; il suffisait de les lire.
                val depuis = "&qso_qslsince=1990-01-01&qso_startdate=1990-01-01"
                // **Une seule demande.**
                //
                // Vérifié en direct sur le compte d'Olivier : la requête des
                // confirmations rend 1,7 Mo, 2193 contacts, 277 carrés
                // satellite. La même logique d'analyse, appliquée à ce
                // fichier, les trouve tous — le code de lecture était donc
                // juste depuis le début.
                //
                // Ce qui ne l'était pas : demander **deux journaux complets
                // coup sur coup**. LoTW bride les téléchargements répétés et
                // rend alors un rapport minuscule — d'où les « 5 contacts ».
                // Les carrés sont dans les confirmations ; la seconde demande
                // n'apportait presque rien et coûtait tout.
                val confirme = runCatching {
                    demande("&qso_qsl=yes&qso_qsldetail=yes&qso_mydetail=yes" + depuis)
                }.getOrDefault("")
                val tous = ""
                // LoTW annonce le nombre d'enregistrements : on le compare à
                // ce qu'on a lu. Un écart signifie un fichier tronqué — à
                // dire, pas à taire.
                val annonce = Regex("<APP_LoTW_NUMREC:\\d+>(\\d+)")
                    .find(confirme)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val lus = Regex("<eor>", RegexOption.IGNORE_CASE).findAll(confirme).count()
                diag = (confirme.length / 1024).toString() + " Ko · " + lus + "/" + annonce +
                    (if (annonce > 0 && lus < annonce) " TRONQUÉ" else "")
                runCatching {
                    File(ctx.getExternalFilesDir(null), "lotw_confirme.adi").writeText(confirme)
                    File(ctx.getExternalFilesDir(null), "lotw_tous.adi").writeText(tous)
                }
                val txt = confirme + "\n" + tous
                if (!txt.contains("<call:", true)) {
                    return@withContext if (txt.contains("password", true))
                        "identifiants refusés" else "réponse inattendue de LoTW"
                }
                val conf = HashSet<String>()
                val trav = HashSet<String>()
                // Compteurs de diagnostic : un total bas peut venir du filtre
                // satellite, de l'absence de carré dans les contacts non
                // confirmés (LoTW ne connaît le carré du correspondant que
                // lorsqu'il a confirmé), ou d'un journal réellement court. Il
                // faut pouvoir distinguer les trois.
                var enregistrements = 0
                var sat = 0
                var satSansCarre = 0
                val orphelins = HashSet<String>()
                val miens = HashSet<String>()
                // Un enregistrement par <eor>, champs ADIF <nom:longueur>.
                for (bloc in txt.split(Regex("<eor>", RegexOption.IGNORE_CASE))) {
                    if (bloc.contains("<call:", true)) enregistrements++
                    val prop = champ(bloc, "prop_mode")
                    val satName = champ(bloc, "sat_name")
                    // Certains contacts satellite portent le nom du satellite
                    // sans le mode de propagation : les compter aussi.
                    if (!prop.equals("SAT", true) && satName.isBlank()) continue
                    sat++
                    // Le carré principal, **et** les carrés VUCC : une station
                    // posée sur une ligne de séparation en annonce jusqu'à
                    // quatre dans `VUCC_GRIDS`, et ils comptent tous. Onze
                    // contacts dans ce cas chez F4IOZ, treize carrés de plus.
                    champ(bloc, "my_gridsquare").uppercase().take(4)
                        .takeIf { it.length == 4 }?.let { miens.add(it) }
                    val g = champ(bloc, "gridsquare").uppercase().take(4)
                    val vucc = champ(bloc, "vucc_grids").uppercase()
                        .replace(" ", "").split(",")
                        .map { it.take(4) }.filter { it.length == 4 }
                    if (g.length < 4 && vucc.isEmpty()) {
                        satSansCarre++
                        champ(bloc, "call").uppercase().takeIf { it.isNotBlank() }
                            ?.let { orphelins.add(it) }
                        continue
                    }
                    val confirmeIci = champ(bloc, "qsl_rcvd").equals("Y", true) ||
                        champ(bloc, "qslrdate").isNotBlank()
                    for (v in vucc) {
                        trav.add(v)
                        if (confirmeIci) conf.add(v)
                    }
                    if (g.length < 4) continue
                    trav.add(g)
                    // Dans le premier fichier tout est confirmé par
                    // construction ; le champ QSL_RCVD confirme quand il est
                    // présent.
                    if (champ(bloc, "qsl_rcvd").equals("Y", true) ||
                        champ(bloc, "qslrdate").isNotBlank()) conf.add(g)
                }
                val e = Etat(conf, trav, System.currentTimeMillis(), orphelins, miens)
                fichier(ctx).writeText(
                    "${e.quandMs}\n${conf.joinToString(",")}\n${trav.joinToString(",")}" +
                        "\n${orphelins.joinToString(",")}" +
                        "\n${miens.joinToString(",")}")
                cache = e
                "${conf.size} confirmés · ${trav.size} travaillés · " +
                    "$sat sat / $enregistrements contacts · $diag"
            }.getOrElse { "échec : " + it.javaClass.simpleName }
        }

    fun oublie(ctx: Context) {
        cache = null
        runCatching { fichier(ctx).delete() }
    }

    private fun champ(bloc: String, nom: String): String {
        val m = Regex("<$nom:(\\d+)(?::[^>]*)?>", RegexOption.IGNORE_CASE).find(bloc)
            ?: return ""
        val n = m.groupValues[1].toIntOrNull() ?: return ""
        val i = m.range.last + 1
        return bloc.substring(i, minOf(i + n, bloc.length)).trim()
    }
}
