/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo.rotor

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import fr.f4ioz.satcombo.cat.CatJournal
import fr.f4ioz.satcombo.cat.CatScan
import fr.f4ioz.satcombo.cat.PortRef
import fr.f4ioz.satcombo.cat.SerialLink
import fr.f4ioz.satcombo.cat.UsbSerialLink
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.usb.UsbPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Le dialecte GS-232, celui des contrôleurs Yaesu — et de tous ceux qui l'ont
 * copié depuis trente ans.
 *
 * C'est de l'ASCII sur un fil série, terminé par un retour chariot, et c'est
 * d'une simplicité désarmante : `W180 045` pour viser, `C2` pour demander où
 * l'on en est, `S` pour tout arrêter. La simplicité a un prix — il n'y a ni
 * somme de contrôle, ni accusé de réception, ni longueur annoncée. Le
 * contrôleur ne dit jamais « je n'ai pas compris » : il ne fait rien, et rien
 * ne ressemble davantage à un mât qui n'a pas fini de tourner qu'à un mât qui
 * n'a pas reçu l'ordre.
 *
 * D'où le soin porté ici à deux détails que l'on croit sans importance.
 *
 * Le premier : **trois chiffres, toujours**. Le contrôleur lit des positions
 * fixes dans la chaîne. `W180 45` n'est pas « presque bon », c'est une consigne
 * d'élévation de 450 degrés lue sur un champ décalé. On formate donc avec un
 * zéro de tête, en [Locale.US] pour que rien ne dépende de la langue du
 * téléphone, et l'on refuse net ce qui ne tient pas en trois chiffres.
 *
 * Le second : **deux formes de réponse**. Un GS-232B répond `AZ=180 EL=045`,
 * un GS-232A `+0180+0045`, et le même contrôleur peut passer de l'une à
 * l'autre selon un cavalier interne que personne ne se souvient d'avoir
 * déplacé. Les deux se relisent ici, sans réglage à faire.
 *
 * Et l'on ne relit pas la lettre du manuel, mais ce que les contrôleurs
 * envoient vraiment : les émulateurs sur Arduino, qui sont aujourd'hui la
 * moitié du parc, écrivent volontiers `AZ=155EL=016` sans l'espace que le
 * manuel montre. Exiger cet espace revient à refuser une réponse juste.
 */
object Gs232Codec {

    /** Demande de position, forme longue (azimut ET élévation). */
    const val QUERY = "C2\r"

    /** Arrêt immédiat de tous les moteurs. */
    const val STOP = "S\r"

    /**
     * La consigne `Waaa eee`, ou null si elle ne tient pas dans le format.
     *
     * Rendre null plutôt que de tronquer est le même choix que dans
     * [RotorMath] : une consigne mal formée ne fait pas revenir une erreur, elle
     * fait tourner le mât ailleurs.
     */
    fun moveCommand(azDeg: Double, elDeg: Double): String? {
        if (azDeg.isNaN() || elDeg.isNaN()) return null
        val az = azDeg.roundToInt()
        val el = elDeg.roundToInt()
        if (az < 0 || az > 999 || el < 0 || el > 999) return null
        return String.format(Locale.US, "W%03d %03d\r", az, el)
    }

    // Le séparateur entre les deux champs est **facultatif**, et c'est le
    // détail qui a coûté une version. Un GS-232B de Yaesu écrit
    // `AZ=180 EL=045`, mais l'émulateur sur Arduino le plus répandu écrit
    // `AZ=155EL=016`, tout collé. L'ancien `\D+` réclamait au moins un
    // caractère entre l'azimut et le `EL` : la trame était rejetée, et
    // l'écran annonçait « réponse illisible » alors que le contrôleur venait
    // de dire exactement la bonne chose. Le signe `=` devient facultatif lui
    // aussi — certains croquis écrivent `AZ 155 EL 016`.
    private val B_FORM = Regex(
        """AZ\s*[=:]?\s*([+-]?\d+(?:\.\d+)?)\s*\D*?EL\s*[=:]?\s*([+-]?\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE)
    private val A_FORM = Regex("""([+-]\d{3,4})\s*([+-]\d{3,4})""")

    /**
     * Relit une position, dans l'une ou l'autre des deux formes.
     *
     * Rend null quand la réponse n'est ni l'une ni l'autre — un fragment reçu
     * trop tôt, un écho de la commande, ou du bruit sur le câble. L'appelant
     * réessaiera à la seconde suivante ; il ne doit surtout pas croire que le
     * mât est à l'azimut zéro.
     */
    fun parsePosition(reply: String): RotorPos? {
        B_FORM.find(reply)?.let { m ->
            val az = m.groupValues[1].toDoubleOrNull() ?: return@let
            val el = m.groupValues[2].toDoubleOrNull() ?: return@let
            return RotorPos(az, el)
        }
        A_FORM.find(reply)?.let { m ->
            val az = m.groupValues[1].toDoubleOrNull() ?: return@let
            val el = m.groupValues[2].toDoubleOrNull() ?: return@let
            return RotorPos(az, el)
        }
        return null
    }

    /** La même trame en français, pour le journal. */
    fun describe(text: String, out: Boolean): String {
        val t = text.trim()
        if (out) {
            val m = Regex("""^W\s*(\d{3})\s+(\d{3})$""", RegexOption.IGNORE_CASE).find(t)
            if (m != null) return "consigne → azimut " + m.groupValues[1].toInt() +
                "°, élévation " + m.groupValues[2].toInt() + "°"
            if (t.startsWith("C")) return "demande de position"
            if (t.startsWith("S")) return "arrêt immédiat"
            return "→ $t"
        }
        val p = parsePosition(t)
        return if (p != null) String.format(Locale.US,
            "position : azimut %.0f°, élévation %.0f°", p.azDeg, p.elDeg)
        else "réponse : $t"
    }
}

/**
 * Le pilote GS-232, branché sur n'importe quel fil série — un vrai câble USB,
 * ou un contrôleur simulé.
 *
 * L'adaptateur USB se choisit **par indice**, et c'est délibéré : une station
 * complète a souvent deux adaptateurs identiques, l'un vers le poste, l'autre
 * vers le rotor. Prendre « le premier » revient tôt ou tard à envoyer `W180
 * 045` à un IC-9700, qui n'en pensera rien de bon.
 */
class Gs232Rotor(private val context: Context? = null) : RotorDriver {

    private var link: SerialLink? = null
    override val isOpen: Boolean get() = link != null

    /** Silence entre deux trames. Mis à zéro au banc : il n'y a personne à ménager. */
    var pacingMs: Long = 20L

    /**
     * Le temps qu'on laisse à la carte pour revenir à elle après l'ouverture.
     *
     * Sur une carte Arduino, la ligne DTR n'est pas un simple signal de
     * courtoisie : elle est reliée au RESET par un condensateur. L'hôte qui
     * ouvre le port et lève DTR **redémarre le microcontrôleur**. Suit une
     * seconde et demie d'amorçage pendant laquelle le croquis ne tourne pas
     * encore et où tout ce qui arrive sur le fil est perdu.
     *
     * D'où le symptôme, parfaitement trompeur : « ouvert, mais le contrôleur ne
     * répond pas ». Le port est bon, le câble est bon, la vitesse est bonne —
     * seul le `C2` était arrivé trop tôt. On attend donc, et on redemande
     * plusieurs fois ; c'est le prix d'une convention vieille de quinze ans.
     *
     * Mis à zéro au banc, où il n'y a ni carte ni condensateur.
     */
    var settleMs: Long = 1500L

    /** Branche un fil série déjà ouvert — un câble, ou un contrôleur simulé. */
    fun attach(l: SerialLink) { link = l }

    /**
     * La raison du dernier échec, en clair.
     *
     * « J'ai l'impression que ça ne marche pas » : jusqu'ici l'écran répondait
     * « ouverture impossible » et rien d'autre. Permission pas encore accordée,
     * appareil déjà pris, port inexistant, boîtier muet : quatre causes, un
     * seul message. On les distingue, comme du côté du poste.
     */
    var lastError: String = ""
        private set

    /** La dernière trame reçue, telle quelle, pour la montrer à l'opérateur. */
    var lastReply: String = ""
        private set

    /** La dernière trame envoyée, telle quelle. */
    var lastSent: String = ""
        private set

    /**
     * Tous les ports série visibles, appareil par appareil, mis à plat.
     *
     * Un montage Arduino n'expose généralement qu'un port ; un adaptateur à
     * deux canaux en expose deux, et le rotor n'est pas toujours sur le premier.
     * Le pilote n'ouvrait que le port 0 du n-ième **appareil** — l'indice
     * choisi désignait donc un appareil, alors que la liste affichée à l'écran
     * ne comptait, elle aussi, que les appareils. Deux ports derrière une seule
     * prise, et le second était inatteignable.
     */
    fun availablePorts(): List<PortRef> {
        val ctx = context ?: return emptyList()
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val out = ArrayList<PortRef>()
        UsbSerialProber.getDefaultProber().findAllDrivers(um).forEachIndexed { d, drv ->
            val n = drv.ports.size.coerceAtLeast(1)
            for (i in 0 until n) {
                out.add(PortRef(d, i, CatScan.etiquette(
                    drv.device.productName, drv.device.deviceName, i, n)))
            }
        }
        return out
    }

    /**
     * Demande la permission pour le port n° [index] **et attend la réponse**.
     *
     * C'est la moitié qui manquait. L'ancienne suite affichait la boîte de
     * dialogue puis ouvrait le port dans la foulée, alors que l'autorisation
     * n'était pas encore accordée : `hasPermission` répondait non, l'ouverture
     * échouait, et il fallait recommencer une fois la boîte refermée pour que
     * ça marche enfin. Le même défaut, mot pour mot, que celui qui rendait le
     * poste injoignable avant la 18.20.
     */
    suspend fun ensurePermission(index: Int): Boolean {
        val ctx = context ?: return false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val ref = availablePorts().getOrNull(index) ?: return false
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um)
            .getOrNull(ref.deviceIndex) ?: return false
        if (um.hasPermission(driver.device)) return true
        return UsbPermission.await(ctx, um, driver.device, UsbPermission.ACTION_CAT,
            driver.device.deviceId)
    }

    /**
     * Ouvre le port n° [index] à [baud] bauds, 8N1.
     *
     * Le GS-232 travaille en 9600 bauds d'usine, mais les cavaliers du boîtier
     * permettent 1200, 2400 et 4800 : le réglage reste à l'opérateur. Un
     * émulateur sur Arduino, lui, suit ce que dit son croquis — souvent 9600.
     */
    suspend fun open(index: Int, baud: Int): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um)
        val refs = availablePorts()
        val ref = refs.getOrNull(index) ?: refs.firstOrNull()
        if (ref == null) { lastError = t("cat_err_no_device"); return@withContext false }
        val driver = drivers.getOrNull(ref.deviceIndex)
        if (driver == null) { lastError = t("cat_err_no_device"); return@withContext false }
        if (!um.hasPermission(driver.device)) {
            lastError = t("cat_err_denied"); return@withContext false
        }
        val conn = um.openDevice(driver.device)
        if (conn == null) { lastError = t("cat_err_open_device"); return@withContext false }
        val p = driver.ports.getOrNull(ref.portIndex)
        if (p == null) { lastError = t("cat_err_no_port"); return@withContext false }
        runCatching {
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Les deux lignes de contrôle du terminal. Un Arduino se réinitialise
            // quand DTR monte ; c'est désagréable mais c'est la convention, et
            // beaucoup de croquis n'écrivent rien tant que l'hôte ne s'est pas
            // annoncé. Sans elles, le port s'ouvre, la consigne part, et rien ne
            // revient jamais : le symptôme exact décrit sur le poste avant 18.20.
            runCatching { p.setDTR(true); p.setRTS(true) }
            val l = UsbSerialLink(p)
            // La carte vient peut-être de redémarrer : on lui laisse le temps
            // de finir son amorçage, puis on jette ce que l'amorceur a pu
            // cracher (« Arduino », des zéros, un écho) pour que la première
            // réponse lue soit bien une réponse à notre question.
            if (settleMs > 0L) {
                delay(settleMs)
                vide(l)
            }
            link = l
            lastError = ""
            true
        }.getOrElse {
            lastError = tf("cat_err_open_port", it.message ?: "?")
            runCatching { p.close() }
            false
        }
    }

    override fun close() {
        runCatching { link?.close() }
        link = null
    }

    /** Les ports reconnus, dans l'ordre où l'indice les désigne. */
    fun listDevices(): List<String> = availablePorts().map { it.label }

    /**
     * Cet appareil qu'on vient de brancher est-il un pont série ?
     *
     * La question se pose au branchement : la même intention réveille
     * l'application pour une clé SDR, pour le poste et pour le rotor, et il
     * serait fâcheux d'aller demander sa position à un récepteur.
     */
    fun recognises(dev: android.hardware.usb.UsbDevice?): Boolean {
        if (dev == null) return false
        return runCatching {
            UsbSerialProber.getDefaultProber().probeDevice(dev) != null
        }.getOrDefault(false)
    }

    /** Demande la permission pour tout appareil qui ne l'a pas encore, sans attendre. */
    fun requestPermissions() {
        val ctx = context ?: return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbSerialProber.getDefaultProber().findAllDrivers(um).forEach { d ->
            UsbPermission.ensure(ctx, um, d.device, UsbPermission.ACTION_CAT, d.device.deviceId)
        }
    }

    private fun send(l: SerialLink, s: String): Boolean {
        val ok = l.write(s.toByteArray(Charsets.US_ASCII), 500)
        lastSent = s.trim()
        CatJournal.log(true, s.toByteArray(Charsets.US_ASCII), Gs232Codec.describe(s, out = true))
        return ok
    }

    /**
     * Lit une ligne complète, en la rassemblant morceau par morceau.
     *
     * Sur un vrai câble à 9600 bauds, `AZ=180 EL=045` met une douzaine de
     * millisecondes à passer et n'arrive presque jamais d'un seul coup. Une
     * lecture unique attrapait `AZ=1` et le pilote concluait « pas de
     * réponse » — la même faute que celle qui avait coûté des passages du côté
     * CAT, et il n'y a aucune raison de la refaire ici.
     *
     * **Une ligne terminée n'est pas forcément la réponse.** C'est la panne du
     * 2 août : le tour précédent avait envoyé un `W155 016`, l'émulateur en
     * renvoie l'écho, et cet écho arrive terminé par un retour chariot avant la
     * position. On rendait donc `W155 016` comme réponse au `C2` ; le décodeur
     * n'y trouvait rien, la position disparaissait de l'écran, et la boussole
     * repassait au satellite le temps d'un battement. On continue maintenant de
     * lire jusqu'à tenir une ligne **qui se relit**, ou jusqu'à l'échéance ; la
     * dernière ligne illisible est conservée pour le journal, parce que
     * « quelque chose, mais pas ça » et « rien du tout » ne se réparent pas de
     * la même façon.
     */
    private fun readLine(l: SerialLink, timeoutMs: Long): String? {
        val sb = StringBuilder()
        val scratch = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        var silences = 0
        // La dernière ligne complète que le décodeur a refusée : elle ne sert
        // pas à pointer le mât, elle sert à dire pourquoi il ne pointe pas.
        var illisible: String? = null
        while (true) {
            val n = runCatching { l.read(scratch, 200) }.getOrDefault(0)
            if (n > 0) {
                silences = 0
                for (i in 0 until n) {
                    val c = scratch[i].toInt().toChar()
                    if (c == '\r' || c == '\n') {
                        if (sb.isNotEmpty()) {
                            val ligne = sb.toString()
                            if (Gs232Codec.parsePosition(ligne) != null) return ligne
                            illisible = ligne
                            sb.setLength(0)
                        }
                    } else sb.append(c)
                }
            } else {
                // Le tuyau s'est tu. Certains contrôleurs ne terminent pas leur
                // dernière ligne : c'est ici, et seulement ici, qu'on accepte
                // une réponse sans retour chariot.
                //
                // L'accepter plus tôt — dès que ce qu'on tient « se relit » —
                // est un piège qui coûte cher : sur un vrai câble la trame
                // arrive caractère par caractère, et `AZ=090 EL=0` se relit
                // parfaitement. On rendrait alors une élévation de zéro degré
                // au beau milieu d'une réponse qui disait trente, et le mât
                // repartirait vers l'horizon pendant que le satellite monte.
                // Deux silences d'affilée, donc : un seul peut n'être qu'un
                // creux entre deux octets.
                silences++
                if (silences >= 2) {
                    if (Gs232Codec.parsePosition(sb.toString()) != null) return sb.toString()
                    if (pacingMs == 0L) break
                }
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        // Rien de lisible n'est venu. On rend quand même ce qu'on a entendu :
        // le reliquat non terminé s'il y en a un, sinon la dernière ligne
        // refusée par le décodeur.
        return if (sb.isNotEmpty()) sb.toString() else illisible
    }

    /** Jette tout ce qui traîne dans le tampon de réception, sans l'interpréter. */
    private fun vide(l: SerialLink) {
        val scratch = ByteArray(64)
        var garde = 0
        while (garde++ < 32) {
            val n = runCatching { l.read(scratch, 50) }.getOrDefault(0)
            if (n <= 0) return
        }
    }

    /** Le nombre d'interrogations qu'il a fallu pour obtenir la première réponse. */
    var lastTries: Int = 0
        private set

    /**
     * Interroge le contrôleur jusqu'à [tries] fois avant de le déclarer muet.
     *
     * Une seule question ne prouve rien. Un GS-232 réel répond du premier coup,
     * mais un émulateur sur Arduino sort tout juste de son amorçage ; un
     * contrôleur qui vient d'être allumé peut aussi mettre une seconde à
     * s'occuper du port série. Déclarer le silence sur une seule tentative,
     * c'est envoyer l'opérateur vérifier un câble qui n'a rien.
     *
     * On garde la dernière trame reçue, même illisible : c'est elle qui
     * distingue « rien du tout » de « quelque chose, mais pas ça », et ces deux
     * pannes ne se réparent pas de la même façon.
     */
    suspend fun probePosition(tries: Int = 3, gapMs: Long = 400L): RotorPos? {
        var dernier = ""
        for (i in 1..tries.coerceAtLeast(1)) {
            lastTries = i
            val pos = runCatching { readPosition() }.getOrNull()
            if (pos != null) return pos
            if (lastReply.isNotBlank()) dernier = lastReply
            if (i < tries && gapMs > 0L) delay(gapMs)
        }
        // On rend à l'appelant la meilleure trace disponible : mieux vaut une
        // réponse illisible affichée à l'écran qu'un silence supposé.
        if (dernier.isNotBlank()) lastReply = dernier
        return null
    }

    override suspend fun moveTo(azDeg: Double, elDeg: Double): Boolean =
        withContext(Dispatchers.IO) {
            val l = link ?: return@withContext false
            val cmd = Gs232Codec.moveCommand(azDeg, elDeg) ?: return@withContext false
            send(l, cmd)
        }

    override suspend fun readPosition(): RotorPos? = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext null
        // On jette ce qui traîne avant de poser la question. Le tour précédent
        // a pu laisser un écho de consigne ou une réponse arrivée trop tard :
        // relus ici, ils passeraient pour la réponse au `C2` d'aujourd'hui.
        vide(l)
        if (!send(l, Gs232Codec.QUERY)) { lastReply = ""; return@withContext null }
        val line = readLine(l, 800)
        // Le silence est une information, et c'est même la seule qui compte
        // quand rien ne marche : on la garde pour l'écrire à l'écran plutôt que
        // de laisser l'opérateur deviner entre « pas branché », « mauvais port »
        // et « mauvaise vitesse ».
        lastReply = line ?: ""
        if (line == null) return@withContext null
        CatJournal.log(false, line.toByteArray(Charsets.US_ASCII),
            Gs232Codec.describe(line, out = false))
        Gs232Codec.parsePosition(line)
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val l = link ?: return@withContext false
        send(l, Gs232Codec.STOP)
    }
}

/**
 * Un contrôleur GS-232 qui n'existe pas, et un mât qui met du temps à tourner.
 *
 * Un simulateur qui obéirait instantanément ne prouverait rien : c'est
 * justement parce qu'un rotor est **lent** que les questions intéressantes se
 * posent. Six degrés par seconde, c'est la vitesse d'un G-5500 ; un demi-tour
 * d'azimut prend donc une minute, pendant laquelle le satellite, lui, a
 * continué son chemin. Toute la valeur du recouvrement et de l'hystérésis tient
 * dans ces secondes-là.
 *
 * L'horloge n'avance pas toute seule : c'est l'essai qui l'avance à la main,
 * avec [advance]. Un essai qui dépend d'une vraie horloge est un essai qui
 * échouera un jour sur une machine chargée, et l'on ne saura pas pourquoi.
 *
 * Deux compteurs retiennent les degrés **réellement parcourus** — pas les
 * consignes envoyées, ce qui ne prouverait rien. C'est ce que l'on veut
 * comparer entre deux stratégies de visée.
 */
class Gs232Simulator(
    val azMaxDeg: Double = 450.0,
    val elMaxDeg: Double = 180.0,
    /** Degrés par seconde, azimut comme élévation. */
    val speedDegPerSec: Double = 6.0
) : SerialLink {

    var azDeg: Double = 0.0; private set
    var elDeg: Double = 0.0; private set
    private var azTarget: Double = 0.0
    private var elTarget: Double = 0.0

    /** Degrés d'azimut réellement parcourus depuis le début. */
    var azTravelDeg: Double = 0.0; private set

    /** Degrés d'élévation réellement parcourus depuis le début. */
    var elTravelDeg: Double = 0.0; private set

    /** Le nombre de consignes que le contrôleur a refusées. */
    var refusals: Int = 0; private set

    /** Tout ce que le contrôleur a reçu, ligne par ligne — pour les essais. */
    val received = ArrayList<String>()

    private val outbox = ArrayDeque<Byte>()
    private val inbox = StringBuilder()
    private var closed = false
    val isClosed: Boolean get() = closed

    /** Vrai tant que le mât n'est pas arrivé. */
    val isMoving: Boolean
        get() = abs(azTarget - azDeg) > 1e-9 || abs(elTarget - elDeg) > 1e-9

    /**
     * Fait passer [ms] millisecondes : le mât avance vers sa consigne, et pas
     * plus vite que sa mécanique.
     */
    fun advance(ms: Long) {
        val budget = speedDegPerSec * ms / 1000.0
        val az = step(azDeg, azTarget, budget)
        val el = step(elDeg, elTarget, budget)
        azTravelDeg += abs(az - azDeg)
        elTravelDeg += abs(el - elDeg)
        azDeg = az
        elDeg = el
    }

    private fun step(from: Double, to: Double, budget: Double): Double {
        val d = to - from
        return if (abs(d) <= budget) to else from + budget * sign(d)
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean {
        if (closed) return false
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c == '\r' || c == '\n') { handle(inbox.toString()); inbox.setLength(0) }
            else inbox.append(c)
        }
        return true
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outbox.isNotEmpty()) buf[n++] = outbox.removeFirst()
        return n
    }

    override fun close() { closed = true; outbox.clear(); inbox.setLength(0) }

    private fun emit(s: String) { s.toByteArray(Charsets.US_ASCII).forEach { outbox.addLast(it) } }

    private fun handle(rawLine: String) {
        val line = rawLine.trim()
        if (line.isEmpty()) return
        received += line
        when (line.first().uppercaseChar()) {
            'W' -> {
                // `Waaa eee` : trois chiffres de chaque côté, pas deux, pas
                // quatre. Un contrôleur réel lit des colonnes fixes ; tout ce
                // qui n'est pas à la bonne place est ignoré en silence — ici,
                // c'est compté.
                val m = Regex("""^W\s*(\d{3})\s+(\d{3})$""", RegexOption.IGNORE_CASE).find(line)
                if (m == null) { refusals++; return }
                val az = m.groupValues[1].toDouble()
                val el = m.groupValues[2].toDouble()
                if (az > azMaxDeg || el > elMaxDeg) { refusals++; return }
                azTarget = az; elTarget = el
            }
            'C' -> emit(String.format(Locale.US, "AZ=%03d EL=%03d\r",
                azDeg.roundToInt(), elDeg.roundToInt()))
            'S' -> { azTarget = azDeg; elTarget = elDeg }
            else -> refusals++
        }
    }
}
