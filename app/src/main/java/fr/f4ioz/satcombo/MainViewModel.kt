/*
 * SatMe — poursuite de satellites radioamateurs
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Logiciel libre sous GNU GPL, version 2 ou ultérieure. Sans aucune garantie.
 * Le texte complet de la licence se trouve dans le fichier LICENSE.
 */
package fr.f4ioz.satcombo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.f4ioz.satcombo.data.FavoritesStore
import fr.f4ioz.satcombo.data.LocationMode
import fr.f4ioz.satcombo.data.Observer
import fr.f4ioz.satcombo.data.SatPass
import fr.f4ioz.satcombo.data.SatPosition
import fr.f4ioz.satcombo.data.Geocoder
import fr.f4ioz.satcombo.data.GeoResult
import fr.f4ioz.satcombo.data.PotaHit
import fr.f4ioz.satcombo.data.PotaPark
import fr.f4ioz.satcombo.data.PotaRegion
import fr.f4ioz.satcombo.data.PassPdf
import fr.f4ioz.satcombo.data.PotaRepository
import fr.f4ioz.satcombo.data.SatConfig
import fr.f4ioz.satcombo.data.SatConfigStore
import fr.f4ioz.satcombo.data.SettingsStore
import fr.f4ioz.satcombo.data.SkedAlert
import fr.f4ioz.satcombo.data.SkedPlan
import fr.f4ioz.satcombo.data.SkedSample
import fr.f4ioz.satcombo.data.SkedStationTrack
import fr.f4ioz.satcombo.data.SkedRepository
import fr.f4ioz.satcombo.data.LogStore
import fr.f4ioz.satcombo.domain.SuiviPosition
import fr.f4ioz.satcombo.data.LogEntry
import fr.f4ioz.satcombo.notify.TleRefreshWorker
import fr.f4ioz.satcombo.data.Sources
import fr.f4ioz.satcombo.data.SourcesStore
import fr.f4ioz.satcombo.data.TleCache
import fr.f4ioz.satcombo.data.TleEntry
import fr.f4ioz.satcombo.data.TleRepository
import fr.f4ioz.satcombo.data.Transmitter
import fr.f4ioz.satcombo.data.TransmittersRepository
import fr.f4ioz.satcombo.domain.PassPredictor
import fr.f4ioz.satcombo.domain.Qo100
import fr.f4ioz.satcombo.domain.Doppler
import fr.f4ioz.satcombo.domain.DopplerPass
import fr.f4ioz.satcombo.i18n.t
import fr.f4ioz.satcombo.i18n.tf
import fr.f4ioz.satcombo.location.LocationProvider
import fr.f4ioz.satcombo.location.Maidenhead
import fr.f4ioz.satcombo.notify.PassAlertWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PASSES, SETTINGS, LOCATOR, SKED, TIMELINE, PHOTO, ACTIVATION, SSTV, SDR, APT, AGENDA, SONDE, ROTOR, QO100, NOMMAGE, GLOBE, FT8 }

/**
 * L'état du carnet express, tenu hors de [UiState].
 *
 * Même raison que pour l'état du rotor : la machine virtuelle d'Android code le
 * nombre de registres d'un appel `invoke/range` sur un seul octet, et un
 * `data class` dont le constructeur en réclame plus de 255 compile sans un mot
 * puis fait mourir l'application à la seconde où elle demande son premier état.
 */
/**
 * Les réglages de l'accord fin, hors de [UiState] pour la même raison que
 * [CarnetExpress] : chaque champ posé directement sur l'état principal
 * rapproche le constructeur de la falaise des 255 registres.
 */
data class AccordUi(
    // Le boîtier à trois touches loge ici et non à plat dans UiState : le
    // constructeur d'UiState frôle les 255 registres que la machine virtuelle
    // sait écrire. AccordUi est le bon porteur — lui aussi répond à « ce qu'on
    // est en train d'accorder ».
    /** Ce que la molette commande : « VFO », « SHIFT_RX » ou « SHIFT_TX ». */
    val moletteCible: String = "VFO",
    val macroCodeA: Int = 0,
    val macroCodeB: Int = 0,
    val macroCodeC: Int = 0,
    val macroCibleA: String = "SHIFT_RX",
    val macroCibleB: String = "SHIFT_TX",
    val macroCibleC: String = "VFO",
    /** Le poussoir de la molette : sa touche et son action. */
    val macroCodeD: Int = 164,
    val macroActionD: String = "PAS",
    /** Loupe : seconde vue du spectre, large de quelques kilohertz. */
    val loupe: Boolean = true,
    val loupeSpanHz: Int = 5_000,
    /** Vernier : cadran à défilement, accord relatif au doigt. */
    val vernier: Boolean = true,
    val vernierHzParCm: Int = 200,
    /** Bouton de calage sur la voix reçue. */
    val calageVoix: Boolean = true,
)

/**
 * Ce que le suivi de position peut raconter de lui-même.
 *
 * Deux correctifs successifs n'ont pas suffi à régler le défaut du premier
 * démarrage, et à chaque fois on a diagnostiqué sans voir. Ceci met fin aux
 * suppositions : l'opérateur lit l'état à l'écran et le rapporte, au lieu qu'on
 * devine à distance.
 */
/**
 * Les réglages du dialogue avec le poste.
 *
 * Regroupés, comme le reste : la falaise des 255 registres de Dalvik se frôle à
 * chaque champ ajouté sur `UiState`.
 */
data class CatUi(
    /**
     * Le poste émet-il ? Lu au CAT, donc vrai aussi quand l'émission part du
     * VOX — c'est justement le cas où l'opérateur n'a rien commandé et où
     * l'avertissement compte.
     *
     * Dans le porteur du poste et non dans `UiState` : la falaise des 255
     * registres a mordu pour la troisième fois de la session sur ce champ-là.
     */
    val enEmission: Boolean = false,
    /** Le liseré d'émission est-il demandé ? */
    val liseret: Boolean = true,
    /**
     * L'émission suit la molette de réception sans attendre le silence.
     *
     * Réglable : qui trouve l'écriture trop bavarde sur sa liaison peut
     * revenir à l'ancien comportement.
     */
    val txSuitVite: Boolean = true,
    /** Cadence du sondage, en millisecondes. */
    val sondeMs: Int = 500,
    /**
     * Ce que la lecture PTT a répondu, en clair.
     *
     * Le liseré ne s'allume pas : impossible de savoir, sans le poste sous la
     * main, si la commande n'est pas envoyée, si le poste ne répond pas, ou si
     * la réponse est mal lue. On montre donc la réponse brute.
     */
    val txDiag: String = "",
    /**
     * Silence exigé, en millisecondes, avant que le logiciel ne reprenne la
     * molette de réception. Deux secondes par défaut ; réglable à une seconde
     * ou une demi-seconde pour qui cherche vite.
     */
    val holdMs: Int = 2_000,
    /**
     * La molette d'émission tient lieu de commande de décalage.
     *
     * Quand c'est actif, tourner le VFO du poste d'émission revient à appuyer
     * sur les boutons de décalage : l'écart entre ce qu'on a commandé et ce
     * qu'on relit devient le nouveau Shift TX. Les boutons restent actifs en
     * parallèle — c'est le dernier geste qui l'emporte, le décalage étant une
     * valeur unique et non une somme de deux sources.
     */
    val txVfoShift: Boolean = false,
    /**
     * Le témoin vivant des réglages CAT : ce que chaque poste répond, en Hz.
     *
     * Il n'existait aucun moyen de vérifier la liaison depuis l'écran qui sert
     * à l'établir. Pour savoir si le poste suivait la molette, il fallait
     * quitter les réglages, retrouver une page de passage, regarder si le
     * curseur bougeait — et revenir si non. Trois écrans pour répondre à une
     * question qui se pose là où on est.
     *
     * Ces deux nombres se rafraîchissent tant que la section CAT est à
     * l'écran : tourner le VFO les fait bouger sous les yeux, et la liaison se
     * prouve d'elle-même. Ils s'arrêtent dès qu'on sort — le fil appartient au
     * Doppler, pas à un témoin.
     */
    val veilleRxHz: Long? = null,
    val veilleTxHz: Long? = null,
    /** Le témoin a-t-il obtenu une réponse au dernier tour ? */
    val veilleVivante: Boolean = false,
    /**
     * Les décalages mémorisés pour le satellite affiché, s'il y en a.
     *
     * Dans ce porteur et non dans `UiState` : la falaise des 255 registres du
     * constructeur a déjà mordu trois fois.
     */
    val refCalibShiftHz: Long? = null,
    val refTxShiftHz: Long? = null,
    /**
     * Les décalages **tels qu'ils étaient à l'arrivée sur ce satellite**.
     *
     * La référence explicite ne protège que ce que l'opérateur a pensé à
     * mémoriser. Or la fausse manœuvre arrive précisément à celui qui n'y a
     * pas pensé : la molette d'émission tient lieu de commande de décalage, et
     * `setTxShift` écrit au disque à chaque cran. La valeur d'hier — celle qui
     * marchait — est donc perdue au premier effleurement, avant même qu'on
     * ait compris qu'on a touché quelque chose.
     *
     * On retient donc, sans rien demander, ce qui était enregistré au moment
     * où le satellite a été ouvert. Ce n'est pas « la bonne valeur » — nul ne
     * peut le savoir — mais « celle d'avant ce passage », ce qui suffit à
     * revenir en arrière et ne demande aucun jugement.
     *
     * En mémoire seulement : cela ne survit pas à la fermeture, et c'est
     * voulu. Persister ferait un troisième décalage à comprendre, là où deux
     * suffisent — celui qui court, et celui qu'on a choisi de garder.
     */
    val arriveeCalibShiftHz: Long? = null,
    val arriveeTxShiftHz: Long? = null,
    /** Le bilan de la dernière fusion de lot, en clair, ou vide. */
    val lotBilan: String = "",
)

data class SuiviUi(
    /** Dernière chose qui est arrivée au suivi, en clair. */
    val etat: String = "",
    /** Points reçus depuis le lancement. Zéro est le symptôme. */
    val points: Int = 0,
    /** Relances décidées par le veilleur. */
    val relances: Int = 0,
    /** La permission de localisation est-elle accordée ? */
    val permission: Boolean = false,
)

/** La carte du pays posée sur la photo QRV. */
/** Le porteur des surimpressions de la photo QRV : carte du pays et POTA. */
/**
 * Le carnet en ligne et ce qu'il répond.
 *
 * Dans son porteur dès le premier jour : trois champs posés dans `UiState`
 * auraient franchi la falaise des 255 registres, qui a déjà mordu trois fois
 * cette session.
 */
data class CarnetUi(
    val url: String = "",
    val cle: String = "",
    val slug: String = "",
    /** Le résultat du dernier essai de connexion, pour l'écran. */
    val essai: String = "",
    /** L'identifiant du profil de station, pour déposer les contacts. */
    val profil: String = "",
    /** Ce qu'on moissonne : « sat », « phonie », « cw » ou « tout ». */
    val filtre: String = "sat",
    /** Ce que le dernier dépôt a donné, en clair. */
    val depot: String = "",
    /** Un dépôt est en cours : le bouton ne se réappuie pas. */
    val depotEnCours: Boolean = false,
    /** LoTW : identifiants et résumé du dernier rafraîchissement. */
    val lotwCall: String = "",
    val lotwMdp: String = "",
    val lotwEtat: String = "",
    val lotwConfirmes: Set<String> = emptySet(),
    val lotwTravailles: Set<String> = emptySet(),
    /** Les carrés d'où j'ai émis, à peindre d'une autre couleur. */
    val lotwActives: Set<String> = emptySet(),
    /** Peindre les carrés sur les cartes. */
    val peindre: Boolean = true,
    /** Carré → état, tel qu'affiché sur la page locator. */
    val carres: Map<String, fr.f4ioz.satcombo.data.CarnetEnLigne.Etat> = emptyMap(),
    /**
     * Carré tronqué → identifiant de profil de station, ou `null`.
     *
     * Wavelog range un contact d'après son profil et **ignore le
     * `MY_GRIDSQUARE`** du fichier. Sans cette table, un opérateur qui active
     * plusieurs carrés voit toutes ses sorties reclassées sous celui du profil
     * unique — en silence, et c'est le carré du profil qui compte pour les
     * diplômes.
     */
    /**
     * Emplacement → identifiant de profil. La clé est un **ensemble** de
     * carrés : posé sur une ligne, on est dans les deux à la fois, et ces
     * contacts-là ne peuvent pas partager le profil de ceux faits dans un
     * seul des deux.
     */
    val profils: Map<Set<String>, String?> = emptyMap(),
    /**
     * Les profils tels que Wavelog les rend, gardés en clair.
     *
     * La table ci-dessus ne retient que l'identifiant. Or un identifiant est
     * un nombre que personne ne reconnaît : avec vingt-six profils déclarés,
     * le taper de mémoire est une devinette, et une erreur ne se voit nulle
     * part — le contact part, il est accepté, et il est rangé sous le carré
     * d'un autre emplacement.
     */
    val profilsListe: List<fr.f4ioz.satcombo.domain.ProfilsStation.Profil> = emptyList(),
    /** La maille d'appariement : 4 comme le VUCC, ou 6. */
    val maille: Int = 4,
    /** Ce que le dernier relevé de profils a donné. */
    val profilsEtat: String = "",
    /** QRZ.com : identifiants, et ce que le dernier essai ou comblement a dit. */
    val qrzUser: String = "",
    val qrzMdp: String = "",
    val qrzEtat: String = "",
    val qrzEnCours: Boolean = false,
) {
    val configure: Boolean get() = url.isNotBlank() && cle.isNotBlank() && slug.isNotBlank()
}

data class CarteUi(
    /**
     * La liste des contacts du satellite est-elle dépliée ?
     *
     * Logée dans un porteur existant plutôt que dans un nouveau : créer
     * `DetailUi` ajoutait un registre à `UiState`, qui est à sa limite. La
     * falaise a mordu cinq fois cette session — la règle n'est pas « un
     * porteur », c'est « pas un champ de plus dans `UiState` ».
     */
    val listeContactsOuverte: Boolean = false,
    /**
     * Le second drapeau de la photo. Déplacé ici depuis `UiState` : c'est un
     * réglage de la photo, et la falaise des 255 registres réclamait sa place.
     */
    val flagRight: String = "",
    val affichee: Boolean = false,
    /** La ligne POTA : référence et nom trouvés autour de la position. */
    val potaAffiche: Boolean = false,
    val potaRef: String = "",
    val potaNom: String = "",
    /** Un parc proche, proposé à l'opérateur mais jamais inscrit d'office. */
    val potaPropose: String = "",
    val potaProposeNom: String = "",
    /** Ce que le dernier préchargement de contours a donné. */
    val contoursEtat: String = "",
    val taille: Float = 0.42f,
    val x: Float = 0.5f,
    val y: Float = 0.52f,
    val remplissage: String = "DRAPEAU",
    /** « PAYS » ou « ZONE » : ce que la silhouette montre. */
    val contenu: String = "PAYS",
    val potaTaille: Float = 1f,
    val potaMonte: Float = 0f,
    val couleur: Int = 0x66FFFFFF,
    val bandeauAccueil: Boolean = true,
    /** Les villes autour de la zone, pour se repérer. */
    val villes: List<Triple<String, Double, Double>> = emptyList(),
    val fondu: Boolean = true,
    val potaNomAffiche: Boolean = true,
    val qrgAffiche: Boolean = false,
    val qrgScale: Float = 1f,
    val qrgTexte: String = "",
    val passScale: Float = 1f,
    val fondUni: Int = 0xFF102030.toInt(),
    /** Les anneaux du pays où l'on se trouve, chargés à la demande. */
    val anneaux: List<DoubleArray> = emptyList(),
    val paysNom: String = "",
    /**
     * La zone POTA dont l'emprise contient la position, si elle existe.
     * Jugée au polygone (pota-map.fr), pas au rayon : être « au parc » est
     * une affaire de limite, pas de distance au centre.
     */
    val zoneRef: String = "",
    val zoneNom: String = "",
    val zoneAnneaux: List<DoubleArray> = emptyList(),
)

data class CarnetExpress(
    /**
     * La base d'indicatifs embarquée est-elle servie au clavier ?
     *
     * Le champ vit ici et non dans UiState : c'est un réglage du clavier
     * express, et surtout le 222e champ direct de UiState portait son
     * constructeur à 241 registres — un au-dessus du seuil de l'essai, quinze
     * sous le plantage silencieux de Dalvik.
     */
    /** La mémoire des correspondants : carnet local plus index importé. */
    val memoire: List<fr.f4ioz.satcombo.domain.Indicatifs.Connu> = emptyList(),
    /** Clavier des indicatifs : validation du côté de la main qui tient. */
    val mainGauche: Boolean = false,
    /** Disposition des touches : « abc », « azerty » ou « qwerty ». */
    val disposition: String = "abc",
)

/** One satellite's elevation curve over the timeline window. */
data class TimelineTrack(
    val catnum: Int,
    val name: String,
    /** (epoch ms, elevation °) sampled at a regular step over the window. */
    val samples: List<Pair<Long, Double>>
) {
    val peakDeg: Double get() = samples.maxOfOrNull { it.second } ?: -90.0
}

/**
 * L'etat du rotor, tenu a part.
 *
 * Ce n'est pas un rangement de confort. La machine virtuelle d'Android code
 * le nombre de registres d'un appel `invoke/range` sur un seul octet : au-dela
 * de 255, l'instruction ne peut pas s'ecrire. Un `data class` dont le
 * constructeur reclame plus de 255 registres compile sans un mot, passe les
 * essais tant qu'aucun d'eux ne le construit, et fait mourir l'application a
 * la seconde ou elle demande son premier etat. C'est arrive en 18.22, a six
 * champs pres. Un `Double` ou un `Long` non nul coute deux registres, tout le
 * reste en coute un, et l'objet lui-meme en coute un de plus.
 *
 * Grouper les champs d'un meme sujet dans un objet imbrique ramene le compte
 * a un seul registre pour les quarante-cinq. Les lectures gardent leur nom
 * d'origine (`ui.rotorEnabled`) grace aux accesseurs delegants de [UiState] ;
 * seules les ecritures passent par [UiState.rot].
 */
data class RotorUi(
    // --- rotor az/el ---
    val rotorEnabled: Boolean = false,
    val rotorConnected: Boolean = false,
    val rotorStatus: String = "",
    val rotorType: String = "GS232",
    val rotorBaud: Int = 9600,
    val rotorUsbIndex: Int = 0,
    val rotorPort: Int = 4533,
    val rotorMaxAz: Int = 450,
    val rotorMaxEl: Int = 90,
    /** Où le mât a son point mort : « NORTH » ou « SOUTH ». */
    val rotorAzStop: String = "NORTH",
    /** Le contrôleur compte-t-il depuis sa butée plutôt qu'au nord vrai ? */
    val rotorAzFromStop: Boolean = false,
    /** Écart de pointage toléré avant de considérer le satellite perdu. */
    val rotorMaxError: Int = 15,
    val rotorMinEl: Int = 0,
    val rotorFlip: Boolean = false,
    val rotorAzOnly: Boolean = false,
    /**
     * « Il faut qu'il soit positionné avant le début du passage, x minutes en
     * paramètre. » Combien de minutes avant l'acquisition le mât part attendre
     * le satellite à son point de lever.
     */
    val rotorPreAos: Int = 3,
    /** Vrai pendant que le mât attend le satellite à son point de lever. */
    val rotorPrePositioning: Boolean = false,
    val rotorPark: Boolean = true,
    val rotorParkAz: Int = 0,
    val rotorParkEl: Int = 0,
    val rotorSimulated: Boolean = false,
    val rotorPosAz: Double? = null,
    val rotorPosEl: Double? = null,
    val rotorFlipped: Boolean = false,
    val rotorMoves: Int = 0,
    val rotorDevices: List<String> = emptyList(),
    /** Part du passage en cours réellement pointable, entre 0 et 1. */
    val rotorCoverage: Double? = null,
    val rotorLink: String = "GS232",
    val rotorHost: String = "192.168.1.10",
    val rotorDeadband: Int = 2,
    val rotorSim: Boolean = false,
    val rotorTargetAz: Double? = null,
    val rotorTargetEl: Double? = null,
    val rotorActualAz: Double? = null,
    val rotorActualEl: Double? = null,
    /**
     * Où l'antenne pointe, telle que la boussole doit la montrer : azimut
     * ramené dans le tour, retournement d'élévation défait. Null quand le mât
     * ne dit rien — la boussole revient alors au téléphone, sans rien changer
     * d'autre. [rotorAimEl] reste null sur un rotor d'azimut seul.
     */
    val rotorAimAz: Double? = null,
    val rotorAimEl: Double? = null,
    val rotorOutOfRange: Boolean = false,

    /**
     * Le compte rendu de la dernière tentative de connexion, ligne par ligne.
     *
     * Sans lui, « ça ne marche pas » n'a qu'une seule réponse possible :
     * réessayer. Avec lui, on sait lequel des ports a été essayé, lequel a
     * refusé la permission, lequel s'est ouvert sans jamais répondre.
     */
    val rotorDiag: List<String> = emptyList(),

    /**
     * Essayer les autres ports quand celui qui est choisi ne répond pas.
     *
     * Le même service que du côté du poste, et pour la même raison : l'indice
     * du bon port dépend de l'ordre de branchement, que personne ne contrôle.
     */
    val rotorAutoPort: Boolean = true,

    /** L'azimut saisi à la main, pour l'essai sans satellite. */
    val rotorManualAz: Int = 0,

    /** L'élévation saisie à la main, pour l'essai sans satellite. */
    val rotorManualEl: Int = 0,

    /** La dernière trame reçue du contrôleur, telle quelle — vide s'il se tait. */
    val rotorLastReply: String = "",

    /** La dernière trame envoyée au contrôleur, telle quelle. */
    val rotorLastSent: String = "",
    /** Écart entre le point visé et le point atteignable, en degrés. */
    val rotorErrorDeg: Double = 0.0,

    // ---- La boussole déportée ----
    // Elle loge ici et non à plat dans UiState : le constructeur d'UiState
    // frôle les 255 registres que la machine virtuelle sait écrire, et quatre
    // champs de plus le feraient mourir sans un mot du compilateur. Le rotor
    // est le bon porteur — lui aussi répond à « où pointe l'antenne ».
    /** « TEL » = capteurs du téléphone, « BLE » = module sur la flèche. */
    val boussoleSource: String = "TEL",
    val boussoleAdresse: String = "",
    val boussoleNom: String = "",
    val boussoleCalage: Float = 0f,
    /** La convention du module, mesurée : « DIRECTE », « LACET_OPPOSE »… */
    val boussoleConvention: String = "DIRECTE",
    /** Le relevé de calibrage, une ligne par pose. */
    val boussoleReleves: String = "",
    /** « TANGAGE », « ROULIS » ou « AUCUN » : d'où vient l'élévation. */
    /** La flèche dans le repère du boîtier, « x,y,z ». Vide = pas apprise. */
    val boussoleFleche: String = "",
)

/**
 * L'état de l'écran QO-100, tenu à part.
 *
 * Même raison que [RotorUi], et cette fois sans marge : le constructeur de
 * [UiState] réclamait déjà 227 registres d'arguments sur les 255 que la
 * machine virtuelle sait écrire. Une quinzaine de champs à plat auraient suffi
 * à faire mourir l'application au premier état construit, sans un mot du
 * compilateur. Groupés ici, ils en coûtent un.
 *
 * Contrairement à [RotorUi], aucun accesseur délégant n'est posé sur
 * [UiState] : rien d'existant ne lit ces champs, ils se lisent donc
 * directement par `ui.qo100.` — et les écritures passent par [UiState.qo].
 *
 * Tout ce qui est ici est en fréquences du ciel. La traduction vers le poste
 * et vers la clé se fait au dernier moment, par les convertisseurs, comme
 * partout ailleurs — à deux exceptions près, [posteRxHz] et [posteTxHz], qui
 * ne servent qu'à être affichées et sont nommées pour qu'on ne s'y trompe pas.
 */
data class Qo100Ui(
    /**
     * Les appareils de réception et leur écart propre, en ppm.
     *
     * Ils vivent ici plutôt que dans l'état principal, déjà au bord des deux
     * cent cinquante-cinq registres. Le sujet est d'ailleurs le même que la
     * chaîne de station — décrire son matériel — et l'écran les montre côte à
     * côte.
     */
    val materiels: List<fr.f4ioz.satcombo.domain.MaterielRx.Materiel> =
        fr.f4ioz.satcombo.domain.MaterielRx.parDefaut(),
    val materielPoste: String = "FT-817 A",
    val materielCle: String = "Clé SDR 1",
    /** La clé du transpondeur choisi : « nb » ou « wb ». Voir [Qo100.TRANSPONDEURS]. */
    val transpondeur: String = "nb",
    /** Vrai quand on balaie tout QO-100 au lieu du seul transpondeur. */
    val sansBride: Boolean = false,

    /**
     * Où l'on écoute, dans le ciel. La montée s'en déduit exactement par le
     * décalage du transpondeur : il n'y a donc rien d'autre à régler.
     */
    val descenteHz: Long = Qo100.BALISE_MEDIANE_HZ,

    /** Le poste suit-il l'écran ? Indépendant de [aLaCle], et volontairement. */
    val auPoste: Boolean = false,

    /** La clé SDR suit-elle l'écran ? */
    val aLaCle: Boolean = false,

    /**
     * Ce qui est réellement affiché sur le poste, après convertisseurs.
     *
     * Sur la station visée : 145 en réception, 432 en émission. Zéro tant
     * qu'aucune conversion n'a été faite. C'est le nombre qu'on compare à
     * l'écran de la radio pour savoir si tout est en place, et c'est la seule
     * vérification possible avant d'entendre quoi que ce soit.
     */
    val posteRxHz: Long = 0L,
    val posteTxHz: Long = 0L,

    /** Idem côté clé SDR, qui peut être branchée derrière un autre montage. */
    val cleRxHz: Long = 0L,

    /**
     * Les raccourcis posés par l'opérateur.
     *
     * Les repères du plan de bande ne sont **pas** ici : ils se déduisent des
     * segments, qui sont des faits publiés. Les mélanger obligerait à les
     * recopier, donc à les maintenir à deux endroits.
     */
    val memoires: List<fr.f4ioz.satcombo.domain.MemoiresQo100.Memoire> = emptyList(),

    /** Les chaînes de conversion mémorisées, et celle en service. */
    val chaines: List<fr.f4ioz.satcombo.domain.ChaineQo100.Chaine> = emptyList(),
    val chaine: String = "Fixe",

    /**
     * Le poste peut-il vraiment aller là ? Faux quand le convertisseur n'est
     * pas réglé, ou quand la fréquence obtenue tombe hors de ses bandes.
     */
    val posteAtteignable: Boolean = false,
    val cleAtteignable: Boolean = false,

    /**
     * Le décalage d'étalonnage courant, en hertz, tel qu'il est rangé pour le
     * NORAD 43700. Il absorbe la dérive de l'oscillateur du convertisseur de
     * descente, et rien d'autre — surtout pas l'oscillateur lui-même, qui vit
     * dans [fr.f4ioz.satcombo.domain.Convertisseur].
     */
    val calageHz: Long = 0L,

    /** Le curseur est posé sur la balise médiane, à la tolérance d'affichage près. */
    val surBalise: Boolean = false,

    /** Le pointage de la parabole, calculé une fois depuis le QTH. Null tant qu'il ne l'est pas. */
    val azDeg: Double? = null,
    val elDeg: Double? = null,
    val skewDeg: Double? = null,

    /**
     * Le prochain instant où le Soleil est dans l'azimut du satellite — celui
     * où l'ombre d'un piquet vertical donne l'axe de la parabole sans
     * boussole. Null tant que le calcul n'a pas eu lieu, ou quand le
     * satellite n'est pas visible du QTH.
     */
    val soleilAzimutMs: Long? = null,

    /**
     * Les prochains passages du Soleil *devant* le satellite : le réglage fin
     * des deux angles à la fois, et l'explication d'une réception qui
     * s'effondre quelques minutes autour des équinoxes. Vide hors calcul.
     */
    val soleilTransits: List<fr.f4ioz.satcombo.domain.SoleilQo100.Transit> = emptyList(),

    /**
     * La balise médiane telle que la clé la voit en ce moment : de combien
     * elle est décalée, et de combien elle dépasse le bruit. Null tant qu'on
     * ne la trouve pas — clé arrêtée, panorama pas encore calculé, parabole à
     * côté, ou simplement pas de convertisseur derrière la clé.
     *
     * C'est le témoin permanent de la station : l'écart dit si l'étalonnage
     * tient, le rapport dit si le pointage est bon. Les deux se lisent d'un
     * coup d'œil pendant qu'on tourne quelque chose.
     */
    val balise: fr.f4ioz.satcombo.domain.MesureBalise.Mesure? = null,

    /** La dernière ligne de compte rendu affichée sous les commandes. */
    val statut: String = "",
)

data class UiState(
    val loading: Boolean = false,
    val screen: Screen = Screen.PASSES,
    val observer: Observer? = null,
    val locationMode: LocationMode = LocationMode.AUTO,
    val manualLocator: String = "JN18FS",
    /** Exact point picked on the map; null = fall back to the square centre. */
    val manualLat: Double? = null,
    val manualLon: Double? = null,
    val locatorError: String? = null,
    val aimMode: String = "EDGE",
    val showAimModeChips: Boolean = false,
    val compassHeadUp: Boolean = true,
    val compassStyle: String = "NEEDLE",
    val tleCacheHours: Int = 24,
    /**
     * D’où vient l’état affiché à côté du satellite. AMSAT seul par défaut :
     * c’est le relevé que les OM alimentent eux-mêmes en temps réel, et c’est
     * celui qu’ils citent sur l’air.
     */
    val statusSource: String = "AMSAT",
    val darkTheme: Boolean = true,
    // Display scaling — see SettingsStore/Theme: keeps the layout identical
    // across handsets instead of letting each screen size rearrange it.
    val uniformUi: Boolean = true,
    val uiScaleStep: Int = 0,
    val uiFollowSystemFont: Boolean = false,
    val language: String = "auto",
    val mapStyle: String = "OSM",
    val settingsSection: String? = null,
    val skedsEnabled: Boolean = false,
    val skedsToken: String = "",
    val skedsAuthed: Boolean = false,
    val skeds: List<SkedAlert> = emptyList(),
    val skedsMutualOnly: Boolean = false,
    // --- Mutual-sked page ---
    val skedSatCat: Int? = null,          // satellite chosen for the sked
    val skedOtherLoc: String = "",        // DX station locator
    val skedMinElOther: Int = 0,          // DX station minimum elevation
    val skedComputing: Boolean = false,
    val skedComputed: Boolean = false,    // a computation has completed at least once
    val skedError: String? = null,        // "bad" = invalid locator
    val skedPlans: List<SkedPlan> = emptyList(),
    val skedSelectedIndex: Int = 0,
    /** Appuis rapides sur la boussole qui ouvrent l'écran de saisie. */
    val logTaps: Int = 3,
    val logEditTimeMs: Long? = null,  // entry awaiting callsign/grid input
    val minElevDeg: Int = 5,
    /** How far back the pass lists reach: 0 = only what is still to come. */
    val pastPassHours: Int = 0,
    val useUtc: Boolean = false,
    val locatorDetails: Boolean = true,
    // --- compass colours (ARGB ints) ---
    val compassTraceColor: Int = 0xFF6C8BFF.toInt(),
    val needleFarColor: Int = 0xFFD6336C.toInt(),
    val needleNearColor: Int = 0xFFF59F00.toInt(),
    val needleCloseColor: Int = 0xFF2FB344.toInt(),
    val bubbleFarColor: Int = 0xFFD6336C.toInt(),
    val bubbleNearColor: Int = 0xFFF59F00.toInt(),
    val bubbleCloseColor: Int = 0xFF2FB344.toInt(),
    val compassTraceWidth: Float = 2.4f,
    val ringAzNearColor: Int = 0xFFF59F00.toInt(),
    val ringAzCloseColor: Int = 0xFF2FB344.toInt(),
    val ringElNearColor: Int = 0xFFF59F00.toInt(),
    val ringElCloseColor: Int = 0xFF2FB344.toInt(),
    // --- audio recorder ---
    val recorderEnabled: Boolean = true,
    val recorderSource: String = "MIC",   // MIC, BT (Bluetooth HFP) or USB (sound card)
    val recorderUnprocessed: Boolean = false,
    /**
     * Pause Doppler : le calcul, l'affichage et le suivi continuent, mais plus
     * une seule fréquence ne part vers le poste. Volontairement absent des
     * réglages enregistrés — une pause oubliée d'un passage à l'autre ne
     * pourrait que faire perdre le suivant.
     */
    val dopplerHold: Boolean = false,
    val monitorSpectre: Boolean = false,
    val monitorSpeaker: Boolean = false,
    val sstvEnabled: Boolean = true,
    val aptEnabled: Boolean = false,
    // --- clé RTL-SDR (bêta) ---
    val sdrSstv: Boolean = true,
    val sdrRecord: Boolean = true,
    val sdrAudio: Boolean = true,
    val sdrAgc: Boolean = false,
    val sdrPpm: Int = 0,
    val sdrMode: String = "NFM",
    val sdrBandwidthHz: Int = 0,
    val sdrSquelchDb: Int = -120,
    val sdrSpanHz: Int = 48_000,
    /** Les trois aides à l'accord fin, tenues à part (falaise des registres). */
    val accord: AccordUi = AccordUi(),
    /**
     * Le carnet express, tenu à part.
     *
     * Quatre champs de plus sur [UiState] ont franchi la falaise des 255
     * registres de Dalvik, et l'essai de garde l'a dit avant que l'appareil ne
     * le dise à sa façon — c'est-à-dire en mourant au démarrage, comme en
     * 18.22. Le regroupement dans un porteur ramène quatre paramètres à un.
     */
    val express: CarnetExpress = CarnetExpress(),
    /** L'état du suivi de position, pour qu'il puisse se raconter. */
    val suivi: SuiviUi = SuiviUi(),
    /** 0 sombre, 1 clair, 2 soleil. */
    val themeIndex: Int = 0,
    /** La molette USB pilote le VFO, et son pas courant en hertz. */
    val moletteVfo: Boolean = false,
    val molettePasHz: Long = 100L,
    /** Les réglages du dialogue avec le poste. */
    val catUi: CatUi = CatUi(),
    val carnet: CarnetUi = CarnetUi(),

    /** Désaccentuation FM (écoute radiodiffusion) ; fermée par défaut. */
    val sdrDeemph: Boolean = false,
    /** Suivi Doppler automatique de la clé pendant le passage. */
    val sdrDopplerTrack: Boolean = true,
    /** Petite cascade sous la boussole, sur la page du passage. */
    val sdrInlineWaterfall: Boolean = true,
    val recordingsTreeUri: String = "",   // SAF export folder ("" = app dir only)
    /** Ce que la bande image du passage suit : "SSTV" ou "NOAA". */
    val rxImageMode: String = "SSTV",
    val recording: Boolean = false,
    // Freeze the detail screen scroll position (field use: no accidental scrolls
    // while tracking a bird; taps/buttons still work). Not persisted.
    val uiLocked: Boolean = false,
    val recordStartMs: Long = 0L,
    val recordFileName: String? = null,   // current or last recording
    val recordAutoStopMs: Long? = null,   // LOS + 5 s, null = manual only
    val potaEnabled: Boolean = false,
    val nearbyPota: List<PotaHit> = emptyList(),
    val potaRadiusKm: Int = 8,
    val potaUpdating: Boolean = false,
    val potaRegionLabel: String? = null,
    val potaCount: Int = 0,
    val selectedPassKeys: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val geoResults: List<GeoResult> = emptyList(),
    val geoSearching: Boolean = false,
    val notifyEnabled: Boolean = true,
    val notifyLeadMin: Int = 5,
    val notifyMode: String = "FAV",
    val notifiedPassKeys: Set<String> = emptySet(),
    val groundTrack: List<Pair<Double, Double>> = emptyList(),
    val dateFilter: Pair<Long, Long>? = null, // [startMs, endMs] local-day bounds
    /**
     * Profondeur de la liste des passages, en heures.
     *
     * On ouvre sur quarante-huit heures, parce que c'est ce qu'on regarde
     * quatre-vingt-dix-neuf fois sur cent, et parce que calculer quinze jours
     * pour tout le monde à chaque démarrage serait payé par tous pour l'usage
     * de quelques-uns. Descendre en bas de la liste ajoute deux jours de plus,
     * autant de fois qu'il le faut, jusqu'à la limite de quinze jours au-delà
     * de laquelle les éléments orbitaux ne valent plus grand-chose.
     */
    val passHorizonHours: Int = 48,
    val showDatePicker: Boolean = false,
    val satellites: List<TleEntry> = emptyList(),
    val favorites: Set<Int> = emptySet(),
    val enabledSources: Set<String> = Sources.DEFAULT_IDS,
    val query: String = "",
    val selected: TleEntry? = null,
    val passes: List<SatPass> = emptyList(),
    val passTrack: List<Pair<Double, Double>> = emptyList(),
    val focusedPassAos: Long? = null,  // the specific pass the user tapped
    val transmitters: List<Transmitter> = emptyList(),
    val transmittersLoading: Boolean = false,
    val selectedTxIndex: Int = 0,
    val rxRestHz: Long? = null,  // chosen downlink (rest) freq within passband
    val satStatus: String? = null,  // SatNOGS operational status
    val calibShiftHz: Long = 0L, // operator's fixed per-sat RX correction
    val txShiftHz: Long = 0L,    // operator's fixed per-sat TX (uplink) correction
    val invertOverride: Boolean? = null, // null = use SatNOGS; true/false = manual NOR/REV
    val opMode: String = "VOICE",        // VOICE or CW (linear operating sub-mode)
    val rxOffsetVoiceHz: Long = 0L,
    val rxOffsetCwHz: Long = 0L,
    // --- convertisseurs (LNB en descente, transverter en montée) ---
    // Ce sont les seules pièces qui séparent la fréquence du satellite de
    // celle qu'on lit sur le poste. Elles vivent ici pour que l'écran de
    // réglage montre la fréquence intermédiaire pendant qu'on tape l'OL.
    val convRx: fr.f4ioz.satcombo.domain.Convertisseur =
        fr.f4ioz.satcombo.domain.Convertisseur.AUCUN,
    val convTx: fr.f4ioz.satcombo.domain.Convertisseur =
        fr.f4ioz.satcombo.domain.Convertisseur.AUCUN,
    /** La descente traverse le poste piloté en CAT. */
    val convRxPoste: Boolean = false,
    /** La descente traverse la clé SDR. */
    val convRxCle: Boolean = true,
    val showSatConfig: Boolean = false,
    val favoritePasses: List<SatPass> = emptyList(),
    val favPassesLoading: Boolean = false,
    val livePosition: SatPosition? = null,
    val log: List<LogEntry> = emptyList(),
    val lastLogMs: Long = 0L,
    // --- operator identity + QRV photo overlay ---
    val callsign: String = "",
    /** Contenu du champ « Extensions » des réglages (mots-clés de déverrouillage). */
    val extensionsCode: String = "",
    /** Fonctions en bêta ouvertes pour cet opérateur (voir Extensions). */
    val extensions: Set<String> = emptySet(),
    val photoShowCallsign: Boolean = true,
    val photoShowDate: Boolean = true,
    val photoShowGrids: Boolean = true,
    val photoShowCoords: Boolean = false,
    val photoShowSat: Boolean = false,
    val photoShowPolar: Boolean = false,
    val photoShowPass: Boolean = true,
    val photoPolarScale: Float = 1f,
    val photoSatLabelScale: Float = 1f,
    /** Altitude du QTH sur la photo, quand le téléphone la connaît. */
    val photoShowAlt: Boolean = false,
    /** Couleur de l’indicatif sur la photo QRV (ARGB). */
    val photoCallColor: Int = 0xFFFFC65C.toInt(),
    /** Taille de l’indicatif sur la photo QRV, 1.0 = référence. */
    val photoCallScale: Float = 1f,
    /** True = the picture shows "JN18" only, false = "JN18fv". */
    val photoLoc4: Boolean = false,
    /** Distance (m) under which a neighbouring grid square is announced. */
    val nearGridMeters: Int = 100,
    /** Nombre de carrés voisins imprimés sur la photo QRV, du plus proche. */
    val photoNearCount: Int = 4,
    /** Système d'unités : metric, imperial ou nautical. */
    val units: String = fr.f4ioz.satcombo.data.Units.METRIC,
    /** Fréquence d'écoute des radiosondes, en hertz. */
    val sondeFreqHz: Long = 404_000_000L,
    val sondeSource: String = "SDR",
    /** Modèle de sonde écouté : "AUTO", "RS41", "M20" ou "M10". */
    val sondeModel: String = "AUTO",
    /** Code du drapeau placé devant l’indicatif, vide = aucun. */
    val photoFlag: String = "",
    /**
     * La carte du pays sur la photo QRV, dans son porteur.
     *
     * Sept champs de plus posés directement dans `UiState` ont fait mordre
     * `RegistresTest` : le constructeur franchissait la falaise des 255
     * registres de Dalvik, et l'application serait morte à la première
     * instanciation, sans rien dans le journal. Le porteur ne coûte qu'un
     * registre.
     */
    val carte: CarteUi = CarteUi(),
    /** Code du drapeau placé à droite de l’indicatif, vide = aucun. */
    /** Les rendez-vous de l’agenda, relus à chaque modification de l’écran. */
    val agenda: List<fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent> = emptyList(),
    /**
     * La fréquence de descente imposée par un rendez-vous d’agenda en cours,
     * en hertz. Non nulle, elle veut dire que le VFO affiché ne vient pas du
     * catalogue mais de l’annonce, et l’écran du passage le dit.
     */
    val rxFromAgendaHz: Long? = null,
    /** True while the "your callsign is missing" prompt must be shown. */
    val askCallsign: Boolean = false,
    /** AMSAT status report being sent / just sent ("", "ok", "fail", "busy"). */
    val amsatSubmitState: String = "",
    // Which satellite the QRV photo is about. The photo screen can be reached
    // from anywhere, so the context is carried explicitly instead of being
    // guessed from whatever happens to be selected.
    val photoSatCat: Int? = null,
    val photoSatName: String = "",
    val photoTrack: List<Pair<Double, Double>> = emptyList(),
    val photoPassLabel: String = "",
    /** AOS of the pass the photo is about (epoch ms), and its max elevation. */
    val photoPassAosMs: Long = 0L,
    val photoPassElDeg: Int = 0,
    /**
     * Every pass of that satellite within reach, a day back and two days ahead.
     * The operator does not always shoot during the pass: the picture may be
     * taken while packing up, or prepared the evening before, so the pass has
     * to be pickable instead of guessed.
     */
    val photoPasses: List<SatPass> = emptyList(),
    /** Kept pictures (private storage), newest first. */
    val qrvPhotos: List<fr.f4ioz.satcombo.data.QrvPhoto> = emptyList(),
    /** The kept picture currently open in the photo screen. */
    val photoCurrentId: Long? = null,
    /**
     * True when the photo page was opened from a satellite the operator was
     * looking at. The last kept picture is still reopened so the page is not
     * blank, but its stamped satellite and pass must NOT come back with it —
     * otherwise the choice just made on the previous screen is undone.
     */
    val photoPinnedSat: Boolean = false,
    /** Screen to go back to when leaving the photo page. */
    val photoReturn: Screen = Screen.PASSES,
    // --- Play Store update ---
    /** versionCode waiting on the Store, 0 when there is nothing to propose. */
    val updateCode: Int = 0,
    /** Last thing the update flow has to say — error, or "already up to date". */
    val updateMsg: String = "",
    // --- field sessions ("activations") ---
    val activations: List<fr.f4ioz.satcombo.data.Activation> = emptyList(),
    // --- timeline (rolling elevation curves of the favourites) ---
    val timelineHours: Int = 6,
    val timelineTracks: List<TimelineTrack> = emptyList(),
    val timelineLoading: Boolean = false,
    val timelineFromMs: Long = 0L,
    val catEnabled: Boolean = false,      // user wants CAT control
    val catConnected: Boolean = false,    // serial port open

    val catStatus: String = "",           // human-readable status/last error
    val catRadioDownlinkHz: Long? = null, // frequency actually read from the rig
    val catRadioUplinkHz: Long? = null,
    /** Vrai quand c'est le logiciel qui tient le VFO de réception du poste. */
    val catRxDriven: Boolean = false,
    /** Suivre le Doppler en réception, et pas seulement en émission. */
    val catRxDoppler: Boolean = true,
    /**
     * Le mode relu dans le poste — « USB », « LSB », « FM »… — ou null tant
     * qu'on ne l'a pas encore demandé.
     *
     * Il est relu, et non déduit de ce qu'on a écrit : le mode se change aussi
     * à la main, et une bande latérale à l'envers sur un transpondeur inverseur
     * ne s'entend pas, elle s'ignore.
     */
    val catRadioMode: String? = null,
    /** Vrai quand le mode du poste n'est pas celui que le satellite demande. */
    val catModeMismatch: Boolean = false,
    val amsatReports: Map<String, fr.f4ioz.satcombo.data.AmsatReport> = emptyMap(),
    val rigModel: String = "IC9700",
    val civAddress: Int = 0xA2,
    val civBaud: Int = 115200,
    /**
     * Quel adaptateur série porte le poste, quand il y en a plusieurs.
     *
     * « Il faut que je plugue la clé SDR, que j'aille dans le menu SDR, je
     * débranche puis connecte l'IC-9700 et c'est bon. » Le pilote prenait le
     * premier périphérique reconnu, quel qu'il soit : une clé SDR branchée
     * avant le poste lui volait la place, et la seule façon de s'en sortir
     * était cette gymnastique de débranchement.
     */
    val civUsbIndex: Int = 0,
    /** Les adaptateurs série visibles, pour que l'on puisse choisir. */
    val catDevices: List<String> = emptyList(),
    /** Essayer les ports voisins quand celui qui est désigné ne répond pas. */
    val civUsbAuto: Boolean = true,
    /**
     * Le récit de la dernière tentative de connexion, ligne à ligne.
     *
     * « J'ai vraiment du mal a connecter » : sans trace, il n'y a rien à
     * répondre à ça. Avec, l'opérateur voit lequel des ports a été ouvert, s'il
     * a répondu, et sinon pourquoi.
     */
    val catDiag: List<String> = emptyList(),
    /** Le CAT parle à un poste en mémoire au lieu d'un câble. */
    val catSimulated: Boolean = false,
    /** Le journal des trames CAT tourne. */
    val catMonitor: Boolean = false,
    /** Tout l'etat du rotor, groupe : voir [RotorUi]. */
    val rotor: RotorUi = RotorUi(),
    /** Tout l'état de l'écran QO-100, groupé : voir [Qo100Ui]. */
    val qo100: Qo100Ui = Qo100Ui(),
    // Dual FT-817 (full-duplex pair): FTDI serials for the RX/TX rigs, CAT baud,
    // and the currently visible USB adapters for the assignment UI.
    val ft817RxSerial: String = "",
    val ft817TxSerial: String = "",
    val ft817Baud: Int = 4800,
    val usbDevices: List<fr.f4ioz.satcombo.cat.UsbSerialInfo> = emptyList(),
    val ctcssTenthHz: Int = 0,
    val ctcssAuto: Boolean = true,
    val catTestSendAlways: Boolean = false,  // send even below horizon (diagnostics)
    // Banc d'essai : la séquence de début de passage jouée contre un poste en
    // mémoire, et le journal des trames. Le premier permet enfin d'affirmer
    // quelque chose sans radio branchée ; le second de savoir, quand un
    // passage se passe mal, ce qui a précédé le refus.
    val benchRunning: Boolean = false,
    val benchReport: String = "",
    val benchOk: Boolean = false,
    val benchSteps: List<String> = emptyList(),
    val catJournalOn: Boolean = false,
    val trail: List<Pair<Double, Double>> = emptyList(),
    val nowMs: Long = System.currentTimeMillis(),
    val tleCacheAgeMs: Long? = null,
    val error: String? = null,
    val visualOnly: Boolean = false,
    val satActiveOnly: Boolean = false
) {

    // --- Les 45 champs du rotor, relus sous leur nom d'origine. Voir
    //     [RotorUi] : ils sont ranges ailleurs pour tenir sous la limite
    //     des 255 registres d'un appel Dalvik.
    // ---------------------------------------------------------------
    val rotorEnabled: Boolean get() = rotor.rotorEnabled
    val rotorConnected: Boolean get() = rotor.rotorConnected
    val rotorStatus: String get() = rotor.rotorStatus
    val rotorType: String get() = rotor.rotorType
    val rotorBaud: Int get() = rotor.rotorBaud
    val rotorUsbIndex: Int get() = rotor.rotorUsbIndex
    val rotorPort: Int get() = rotor.rotorPort
    val rotorMaxAz: Int get() = rotor.rotorMaxAz
    val rotorMaxEl: Int get() = rotor.rotorMaxEl
    val rotorAzStop: String get() = rotor.rotorAzStop
    val rotorAzFromStop: Boolean get() = rotor.rotorAzFromStop
    val rotorMaxError: Int get() = rotor.rotorMaxError
    val rotorMinEl: Int get() = rotor.rotorMinEl
    val rotorFlip: Boolean get() = rotor.rotorFlip
    val rotorAzOnly: Boolean get() = rotor.rotorAzOnly
    val rotorPreAos: Int get() = rotor.rotorPreAos
    val rotorPrePositioning: Boolean get() = rotor.rotorPrePositioning
    val rotorPark: Boolean get() = rotor.rotorPark
    val rotorParkAz: Int get() = rotor.rotorParkAz
    val rotorParkEl: Int get() = rotor.rotorParkEl
    val rotorSimulated: Boolean get() = rotor.rotorSimulated
    val rotorPosAz: Double? get() = rotor.rotorPosAz
    val rotorPosEl: Double? get() = rotor.rotorPosEl
    val rotorFlipped: Boolean get() = rotor.rotorFlipped
    val rotorMoves: Int get() = rotor.rotorMoves
    val rotorDevices: List<String> get() = rotor.rotorDevices
    val rotorCoverage: Double? get() = rotor.rotorCoverage
    val rotorLink: String get() = rotor.rotorLink
    val rotorHost: String get() = rotor.rotorHost
    val rotorDeadband: Int get() = rotor.rotorDeadband
    val rotorSim: Boolean get() = rotor.rotorSim
    val rotorTargetAz: Double? get() = rotor.rotorTargetAz
    val rotorTargetEl: Double? get() = rotor.rotorTargetEl
    val rotorActualAz: Double? get() = rotor.rotorActualAz
    val rotorActualEl: Double? get() = rotor.rotorActualEl
    val rotorAimAz: Double? get() = rotor.rotorAimAz
    val rotorAimEl: Double? get() = rotor.rotorAimEl
    val rotorOutOfRange: Boolean get() = rotor.rotorOutOfRange
    val rotorDiag: List<String> get() = rotor.rotorDiag
    val rotorAutoPort: Boolean get() = rotor.rotorAutoPort
    val rotorManualAz: Int get() = rotor.rotorManualAz
    val rotorManualEl: Int get() = rotor.rotorManualEl
    val rotorLastReply: String get() = rotor.rotorLastReply
    val rotorLastSent: String get() = rotor.rotorLastSent
    val rotorErrorDeg: Double get() = rotor.rotorErrorDeg

    /** Recopie l'etat en ne changeant que le bloc rotor. */
    fun rot(f: RotorUi.() -> RotorUi): UiState = copy(rotor = rotor.f())

    /** Recopie l'état en ne changeant que le bloc QO-100. */
    fun qo(f: Qo100Ui.() -> Qo100Ui): UiState = copy(qo100 = qo100.f())

    val filteredSatellites: List<TleEntry>
        get() {
            val q = query.trim()
            var list = satellites
            if (q.isNotEmpty()) {
                val asNum = q.toIntOrNull()
                list = list.filter {
                    it.name.contains(q, ignoreCase = true) ||
                            (asNum != null && it.catalogNumber.toString().startsWith(q))
                }
            }
            if (satActiveOnly && amsatReports.isNotEmpty()) {
                list = list.filter { sat ->
                    amsatMatch(sat.name, amsatReports)?.recent == fr.f4ioz.satcombo.data.AmsatStatus.ACTIVE
                }
            }
            return list
        }
}

/** Normalize a designator so AO-07 == AO-7, FO-029 == FO-29. */
fun normalizeDesignator(s: String): String {
    val up = s.uppercase().trim().substringBefore(" (").substringBefore("_").trim()
    return Regex("""([A-Z]+)-0*(\d+)""").replace(up) { m ->
        "${m.groupValues[1]}-${m.groupValues[2]}"
    }
}

/** Match a satellite name against AMSAT reports (shared by VM and UiState). */
fun amsatMatch(
    satName: String, reports: Map<String, fr.f4ioz.satcombo.data.AmsatReport>
): fr.f4ioz.satcombo.data.AmsatReport? {
    if (reports.isEmpty()) return null
    val key = satName.uppercase().trim()
    reports[key]?.let { return it }
    val norm = normalizeDesignator(satName)
    reports.values.firstOrNull { normalizeDesignator(it.name) == norm }?.let { return it }
    if (norm.contains("ISS"))
        reports.values.firstOrNull { it.name.uppercase().contains("ISS") }?.let { return it }
    return null
}


/** One mutual-visibility window between two stations for a satellite. */
data class SkedWindow(val startMs: Long, val endMs: Long, val elA: Int, val elB: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TleRepository()
    private val predictor = PassPredictor()

    private val locationProvider = LocationProvider(app)
    private val favStore = FavoritesStore(app)
    private val srcStore = SourcesStore(app)
    private val settings = SettingsStore(app)
    private val logStore = LogStore(app)
    private val activationStore = fr.f4ioz.satcombo.data.ActivationStore(app)
    private val qrvPhotoStore = fr.f4ioz.satcombo.data.QrvPhotoStore(app)
    private val cat = fr.f4ioz.satcombo.cat.CivController(app)
    private val ft817 = fr.f4ioz.satcombo.cat.Ft817Pair(app)
    /**
     * Le pilote de rotor, choisi à la connexion.
     *
     * Nul tant que rien n'est connecté : c'est la seule façon d'être certain
     * qu'aucune consigne ne peut partir vers un mât qu'on n'a pas ouvert.
     */
    private var rotorDriver: fr.f4ioz.satcombo.rotor.RotorDriver? = null
    /** True when the dual-FT-817 full-duplex rig model is selected. */
    /** Le pilotage passe par le couple FT-817 — deux postes, ou un seul en émission. */
    private val isPairRig: Boolean
        get() = _ui.value.rigModel == "FT817x2" || _ui.value.rigModel == "FT817TX"

    /**
     * Un seul FT-817, en émission, la réception se faisant à la clé SDR.
     *
     * Rien à inventer dans la boucle : elle sait déjà ne piloter qu'une chaîne
     * quand l'autre lui échappe — c'est ce qui fait fonctionner QO-100, où la
     * descente s'écoute forcément à la clé. `descenteAuPoste` demande si le
     * poste peut recevoir la descente ; ici la réponse est non par
     * construction, et c'est le point d'écoute choisi sur la cascade qui mène
     * l'émission.
     *
     * L'accord se fait donc au doigt sur le spectre, et le 817 suit en miroir —
     * à l'envers sur un transpondeur inverse.
     */
    private val isTxOnlyRig: Boolean get() = _ui.value.rigModel == "FT817TX"
    private val satConfigStore = SatConfigStore(app)
    private val skedRepo = SkedRepository()
    private val potaRepo = PotaRepository(app)
    private val geocoder = Geocoder()
    private val tleCache = TleCache(app)
    private val txRepo = TransmittersRepository(app)
    private val amsatRepo = fr.f4ioz.satcombo.data.AmsatStatusRepository(app)

    private val _ui = MutableStateFlow(
        UiState(
            favorites = favStore.load(),
            enabledSources = srcStore.load(),
            sdrSstv = settings.sdrSstv,
            sdrRecord = settings.sdrRecord,
            sdrAudio = settings.sdrAudio,
            sdrAgc = settings.sdrAgc,
            sdrPpm = settings.sdrPpm,
            sdrMode = settings.sdrMode,
            sdrBandwidthHz = settings.sdrBandwidthHz,
            sdrSquelchDb = settings.sdrSquelchDb,
            sdrSpanHz = settings.sdrSpanHz,
            accord = AccordUi(
                moletteCible = settings.moletteCible,
                macroCodeD = settings.macroCodeD,
                macroActionD = settings.macroActionD,
                macroCodeA = settings.macroCodeA,
                macroCodeB = settings.macroCodeB,
                macroCodeC = settings.macroCodeC,
                macroCibleA = settings.macroCibleA,
                macroCibleB = settings.macroCibleB,
                macroCibleC = settings.macroCibleC,
                loupe = settings.sdrLoupe,
                loupeSpanHz = settings.sdrLoupeSpanHz,
                vernier = settings.sdrVernier,
                vernierHzParCm = settings.sdrVernierHzParCm,
                calageVoix = settings.sdrCalageVoix),
            express = CarnetExpress(
                mainGauche = settings.clavierMainGauche,
                disposition = settings.clavierDisposition),
            qo100 = Qo100Ui(memoires = settings.qo100Memoires,
                chaines = settings.qo100Chaines, chaine = settings.qo100Chaine,
                materiels = fr.f4ioz.satcombo.domain.MaterielRx.lit(settings.materielsRx),
                materielPoste = settings.materielPoste,
                materielCle = settings.materielCle,
                sansBride = settings.qo100SansBride),
            carnet = CarnetUi(
                lotwCall = settings.lotwCall,
                peindre = settings.peindreCarres,
                lotwMdp = settings.lotwMdp,
                url = settings.carnetUrl,
                cle = settings.carnetCle,
                slug = settings.carnetSlug,
                profil = settings.carnetProfil,
                filtre = settings.carnetFiltre),
            catUi = CatUi(
                liseret = settings.liseréEmission,
                txSuitVite = settings.txSuitVite,
                sondeMs = settings.sondeTxMs,
                holdMs = settings.catHoldMs,
                txVfoShift = settings.catTxVfoShift),
            sdrDeemph = settings.sdrDeemph,
            sdrDopplerTrack = settings.sdrDopplerTrack,
            convRx = convRxDepuisReglages(),
            convTx = convTxDepuisReglages(),
            convRxPoste = settings.convRxPoste,
            convRxCle = settings.convRxCle,
            locationMode = settings.locationMode,
            manualLocator = settings.manualLocator,
            manualLat = settings.manualLat.takeIf { !it.isNaN() },
            manualLon = settings.manualLon.takeIf { !it.isNaN() },
            aimMode = settings.aimMode,
            showAimModeChips = settings.showAimModeChips,
            compassHeadUp = settings.compassHeadUp,
            compassStyle = settings.compassStyle,
            statusSource = settings.statusSource,
            tleCacheHours = settings.tleCacheHours,
            darkTheme = settings.darkTheme,
            // Sans cette ligne, le sélecteur retombe sur « Sombre » à chaque
            // ouverture, quelle que soit la palette réellement appliquée : la
            // palette était restaurée, son index ne l'était pas.
            themeIndex = settings.themeIndex,
            moletteVfo = settings.moletteVfo,
            molettePasHz = settings.molettePasHz,
            uniformUi = settings.uniformUi,
            uiScaleStep = settings.uiScaleStep,
            uiFollowSystemFont = settings.uiFollowSystemFont,
            language = settings.language,
            rigModel = settings.rigModel,
            civAddress = settings.civAddress,
            civBaud = settings.civBaud,
            civUsbIndex = settings.civUsbIndex,
            civUsbAuto = settings.civUsbAuto,
            catSimulated = settings.catSimulated,
            catMonitor = settings.catMonitor,
            catRxDoppler = settings.catRxDoppler,
            ft817RxSerial = settings.ft817RxSerial,
            ft817TxSerial = settings.ft817TxSerial,
            ft817Baud = settings.ft817Baud,
            ctcssTenthHz = settings.ctcssTenthHz,
            ctcssAuto = settings.ctcssAuto,
            mapStyle = settings.mapStyle,
            skedsEnabled = settings.skedsEnabled,
            skedsMutualOnly = settings.skedsMutualOnly,
            logTaps = settings.logTaps,
            log = logStore.load(),
            callsign = settings.callsign,
            extensionsCode = settings.extensionsCode,
            extensions = fr.f4ioz.satcombo.data.Extensions.unlocked(
                settings.callsign, settings.extensionsCode),
            photoShowCallsign = settings.photoShowCallsign,
            photoShowDate = settings.photoShowDate,
            photoShowGrids = settings.photoShowGrids,
            photoShowCoords = settings.photoShowCoords,
            photoShowSat = settings.photoShowSat,
            photoShowPolar = settings.photoShowPolar,
            photoShowPass = settings.photoShowPass,
            photoPolarScale = settings.photoPolarScale,
            photoSatLabelScale = settings.photoSatLabelScale,
            photoShowAlt = settings.photoShowAlt,
            photoCallColor = settings.photoCallColor,
            photoCallScale = settings.photoCallScale,
            photoLoc4 = settings.photoLoc4,
            nearGridMeters = settings.nearGridMeters,
            photoNearCount = settings.photoNearCount,
            units = settings.units,
            sondeFreqHz = settings.sondeFreqHz,
            sondeSource = settings.sondeSource,
            sondeModel = settings.sondeModel,
            photoFlag = settings.photoFlag,

            carte = CarteUi(
                flagRight = settings.photoFlagRight,
                affichee = settings.photoShowCarte,
                potaAffiche = settings.photoShowPota,
                taille = settings.photoCarteTaille,
                contenu = settings.photoCarteContenu,
                potaTaille = settings.photoPotaTaille,
                potaMonte = settings.photoPotaMonte,
                couleur = settings.photoCarteCouleur,
                bandeauAccueil = settings.potaBandeauAccueil,
                fondu = settings.photoCarteFondu,
                potaNomAffiche = settings.photoPotaNom,
                qrgAffiche = settings.photoShowQrg,
                qrgScale = settings.photoQrgScale,
                qrgTexte = settings.photoQrgTexte,
                passScale = settings.photoPassScale,
                fondUni = settings.photoFondUni,
                x = settings.photoCarteX,
                y = settings.photoCarteY,
                remplissage = settings.photoCarteRemplissage),
            agenda = fr.f4ioz.satcombo.data.AgendaStore.load(getApplication()),
            askCallsign = settings.callsign.isBlank() && !settings.callsignPromptOff,
            qrvPhotos = qrvPhotoStore.load(),
            pastPassHours = settings.pastPassHours,
            activations = activationStore.load(),
            skedsToken = settings.skedsToken,
            skedsAuthed = settings.skedsToken.isNotBlank(),
            minElevDeg = settings.minElevDeg,
            useUtc = settings.useUtc,
            locatorDetails = settings.locatorDetails,
            recorderEnabled = settings.recorderEnabled,
            recorderSource = settings.recorderSource,
            recorderUnprocessed = settings.recorderUnprocessed,
            monitorSpectre = settings.monitorSpectre,
            monitorSpeaker = settings.monitorSpeaker,
            sstvEnabled = settings.sstvEnabled,
            aptEnabled = settings.aptEnabled,
            rxImageMode = settings.rxImageMode,
            recordingsTreeUri = settings.recordingsTreeUri,
            compassTraceColor = settings.compassTraceColor,
            needleFarColor = settings.needleFarColor,
            needleNearColor = settings.needleNearColor,
            needleCloseColor = settings.needleCloseColor,
            bubbleFarColor = settings.bubbleFarColor,
            bubbleNearColor = settings.bubbleNearColor,
            bubbleCloseColor = settings.bubbleCloseColor,
            compassTraceWidth = settings.compassTraceWidth,
            ringAzNearColor = settings.ringAzNearColor,
            ringAzCloseColor = settings.ringAzCloseColor,
            ringElNearColor = settings.ringElNearColor,
            ringElCloseColor = settings.ringElCloseColor,
            potaEnabled = settings.potaEnabled,
            potaRadiusKm = settings.potaRadiusKm,
            notifyEnabled = settings.notifyEnabled,
            notifyLeadMin = settings.notifyLeadMin,
            notifyMode = settings.notifyMode,
            notifiedPassKeys = settings.notifiedPassKeys,
            rotor = RotorUi(
                boussoleSource = settings.boussoleSource,
                boussoleAdresse = settings.boussoleAdresse,
                boussoleNom = settings.boussoleNom,
                boussoleCalage = settings.boussoleCalage,
                boussoleConvention = settings.boussoleConvention,
                boussoleReleves = settings.boussoleReleves,
                boussoleFleche = settings.boussoleFleche,
                rotorEnabled = settings.rotorEnabled,
                rotorLink = settings.rotorLink,
                rotorUsbIndex = settings.rotorUsbIndex,
                rotorBaud = settings.rotorBaud,
                rotorHost = settings.rotorHost,
                rotorPort = settings.rotorPort,
                rotorMaxAz = settings.rotorMaxAz,
                rotorMaxEl = settings.rotorMaxEl,
                rotorDeadband = settings.rotorDeadband,
                rotorFlip = settings.rotorFlip,
                rotorAzOnly = settings.rotorAzOnly,
                rotorParkAz = settings.rotorParkAz,
                rotorParkEl = settings.rotorParkEl,
                rotorMinEl = settings.rotorMinEl,
                rotorPreAos = settings.rotorPreAos,
                rotorSim = settings.rotorSim,
                rotorAzStop = settings.rotorAzStop,
                rotorAzFromStop = settings.rotorAzFromStop,
                rotorMaxError = settings.rotorMaxError)
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Le point d'accès annoncé en démonstration survit au redémarrage : sans
        // cela il faudrait le ressaisir avant chaque séance.
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(
            settings.demoSsid, settings.demoMotDePasse)
        // Le moniteur a besoin du contexte pour choisir sa sortie : sans lui,
        // il ne peut pas forcer le haut-parleur quand une carte USB est là.
        // --- le poste de commande ---
        //
        // Le ViewModel pose ici les seuls gestes qu'un PC peut déclencher. Le
        // serveur ne peut rien appeler d'autre : la surface exposée au réseau
        // local tient en quatre lignes, et se relit d'un coup.
        fr.f4ioz.satcombo.demo.PontCommande.ajouteQso = { call, loc, rse, rsr ->
            val sat = _ui.value.selected
            if (sat == null || call.isBlank()) false
            else {
                ajouteContactDirect(sat, call, loc, rse, rsr, "COMMANDE")
                true
            }
        }
        fr.f4ioz.satcombo.demo.PontCommande.enregistre = { on ->
            if (on != _ui.value.recording) toggleRecording()
        }
        fr.f4ioz.satcombo.demo.PontCommande.satellites = {
            // **Les favoris seuls.** La liste complète compte des dizaines de
            // satellites, dont la plupart qu'on ne travaille jamais : les faire
            // défiler pendant un passage, c'est du temps perdu à côté de ceux
            // qu'on cherche. L'étoile est déjà le geste par lequel l'opérateur
            // dit lesquels comptent.
            val u = _ui.value
            val favoris = u.satellites.filter { it.catalogNumber in u.favorites }
            // Si aucune étoile n'est posée, mieux vaut la liste entière qu'un
            // menu vide : on ne pourrait plus changer de satellite du tout.
            (if (favoris.isEmpty()) u.satellites else favoris).map { it.name }
        }
        fr.f4ioz.satcombo.demo.PontCommande.propose = { saisie ->
            fr.f4ioz.satcombo.domain.Indicatifs.suggestions(
                saisie, _ui.value.express.memoire, System.currentTimeMillis(),
                _ui.value.selected?.name.orEmpty()
            ).map {
                fr.f4ioz.satcombo.demo.PontCommande.Proposition(
                    it.indicatif, it.locatorPrincipal, it.nom, it.contacts)
            }
        }
        fr.f4ioz.satcombo.demo.PontCommande.chercheQrz = { call ->
            // **Ouvrir la session avant de chercher.**
            //
            // `cherche()` ne se connecte pas toute seule : sans clé de session
            // elle rend « pas connecté à QRZ ». Le chemin de l'application se
            // connecte d'abord ; le pont appelait directement, et le poste de
            // commande n'affichait donc jamais d'identité.
            //
            // On ne se reconnecte que si la clé manque : QRZ compte aussi les
            // ouvertures de session.
            val f = runCatching {
                val c = _ui.value.carnet
                var fiche = qrz.cherche(call.trim().uppercase())
                if (fiche.erreur.contains("connect", true) &&
                    c.qrzUser.isNotBlank() && c.qrzMdp.isNotBlank()) {
                    qrz.connecte(c.qrzUser, c.qrzMdp)
                    fiche = qrz.cherche(call.trim().uppercase())
                }
                fiche
            }.getOrNull()
            fr.f4ioz.satcombo.demo.PontCommande.Fiche(
                f?.indicatif.orEmpty(), f?.carre.orEmpty(), f?.nom.orEmpty(),
                f?.prenom.orEmpty(), f?.qth.orEmpty(), f?.pays.orEmpty(),
                f?.erreur ?: "QRZ indisponible")
        }
        fr.f4ioz.satcombo.demo.PontCommande.choisitSatellite = { nom ->
            val t = _ui.value.satellites.firstOrNull { it.name.equals(nom, true) }
            if (t == null) false else { select(t); true }
        }

        fr.f4ioz.satcombo.audio.MoniteurAudio.contexte =
            getApplication<android.app.Application>().applicationContext
        fr.f4ioz.satcombo.demo.ServeurDemo.versionApp = runCatching {
            val a = getApplication<android.app.Application>()
            a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        fr.f4ioz.satcombo.demo.ServeurDemo.nomStation =
            settings.callsign.ifBlank { "SatMe" }
        fr.f4ioz.satcombo.ui.theme.applyTheme(settings.themeIndex)
        fr.f4ioz.satcombo.ui.theme.applyUiScale(
            settings.uniformUi, settings.uiScaleStep, settings.uiFollowSystemFont)
        fr.f4ioz.satcombo.i18n.I18n.apply(settings.language, java.util.Locale.getDefault().language)
        TleRefreshWorker.schedule(app)
        _ui.value = _ui.value.copy(
            potaRegionLabel = potaRepo.downloadedRegion,
            potaCount = potaRepo.downloadedCount)
        // **La télémétrie de démonstration a sa propre boucle.**
        //
        // Elle était publiée depuis la boucle de suivi d'un satellite : elle ne
        // tournait donc que si un satellite était sélectionné **et** sa
        // poursuite active. Le public voyait une page correctement servie mais
        // entièrement vide, sans que rien n'indique pourquoi.
        //
        // Ici elle tourne dès que la diffusion est allumée, quel que soit
        // l'écran affiché et qu'un passage soit en cours ou non. Une seconde de
        // période, et rien n'est calculé quand personne ne diffuse.
        viewModelScope.launch {
            while (true) {
                if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
                    runCatching { publieDemo() }
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                _ui.value = _ui.value.copy(nowMs = System.currentTimeMillis())
                delay(1000)
            }
        }
        // Mirror the foreground recorder service state into the UI.
        viewModelScope.launch {
            fr.f4ioz.satcombo.audio.RecorderService.state.collect { rs ->
                val was = _ui.value.recording
                _ui.value = _ui.value.copy(
                    recording = rs.recording, recordStartMs = rs.startMs,
                    recordFileName = rs.fileName, recordAutoStopMs = rs.autoStopMs)
                if (was && !rs.recording && rs.fileName != null) {
                    android.widget.Toast.makeText(getApplication(),
                        tf("recorded_toast", rs.fileName), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        refreshAmsatStatus()
    }

    /** Fetch the AMSAT operator-reported status (who's actually being heard). */
    fun refreshAmsatStatus(force: Boolean = false) {
        // Show cached status instantly (offline-friendly), then refresh.
        val cached = amsatRepo.loadCache()
        if (cached.isNotEmpty() && _ui.value.amsatReports.isEmpty())
            _ui.value = _ui.value.copy(amsatReports = cached)
        viewModelScope.launch {
            val reports = runCatching { amsatRepo.get(force) }.getOrDefault(emptyMap())
            if (reports.isNotEmpty()) _ui.value = _ui.value.copy(amsatReports = reports)
        }
    }

    /** Operator-reported status for a satellite name, matched loosely. */
    fun amsatFor(satName: String): fr.f4ioz.satcombo.data.AmsatReport? {
        val reports = _ui.value.amsatReports
        if (reports.isEmpty()) return null
        return amsatMatch(satName, reports)
    }

    private suspend fun resolveObserver(): Observer =
        if (_ui.value.locationMode == LocationMode.MANUAL) {
            val loc = _ui.value.manualLocator.trim().uppercase()
            val la = _ui.value.manualLat; val lo = _ui.value.manualLon
            // A point picked on the map wins over the centre of the square, but
            // only while it still belongs to the locator shown in the field:
            // typing a new locator by hand must not keep the old pin.
            if (la != null && lo != null && Maidenhead.fromLatLon(la, lo).startsWith(loc))
                Observer(la, lo, 50.0, Maidenhead.fromLatLon(la, lo))
            else {
                val ll = Maidenhead.toLatLon(loc)
                if (ll != null) Observer(ll.first, ll.second, 50.0, loc)
                else locationProvider.defaultObserver
            }
        } else {
            runCatching { locationProvider.current() }.getOrDefault(locationProvider.defaultObserver)
                .let { it.copy(name = "GPS · " + Maidenhead.fromLatLon(it.latDeg, it.lonDeg)) }
        }

    fun bootstrap(force: Boolean = false) {
        // Cache-first startup: reuse the on-disk orbital elements when they are
        // younger than the user-set max age (Settings → Sources). The network is
        // only hit when the cache is stale, missing, from a different source
        // set, or the user forces a refresh. 0 h = always re-download.
        val ids = _ui.value.enabledSources
        val maxAgeMs = settings.tleCacheHours * 3600_000L
        val age = tleCache.ageMs()
        val cacheFresh = age != null && maxAgeMs > 0 && age <= maxAgeMs &&
            tleCache.cachedSources() == ids
        if (!force && cacheFresh && _ui.value.satellites.isNotEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val obs = resolveObserver()
            val urls = Sources.byIds(ids).map { it.url }
            var sats = emptyList<TleEntry>()
            var cacheAge: Long? = null
            var err: String? = null
            if (!force && cacheFresh) {
                // Instant, offline path.
                val raw = withContext(Dispatchers.IO) { tleCache.loadRaw() }
                if (raw != null) sats = runCatching { repo.parse(raw) }.getOrDefault(emptyList())
            }
            if (sats.isEmpty()) {
                sats = runCatching { repo.fetchGroups(urls) }.getOrDefault(emptyList())
                if (sats.isNotEmpty()) {
                    tleCache.save(sats, ids)
                } else {
                    // Download failed: fall back to the cache whatever its age.
                    val raw = withContext(Dispatchers.IO) { tleCache.loadRaw() }
                    if (raw != null) {
                        sats = repo.parse(raw)
                        cacheAge = tleCache.ageMs()
                        if (tleCache.cachedSources() != ids)
                            err = t("offline_other_sources")
                    } else {
                        err = t("tle_download_failed")
                    }
                }
            }
            _ui.value = _ui.value.copy(
                loading = false,
                observer = obs,
                satellites = sats.sortedBy { it.name },
                tleCacheAgeMs = cacheAge,
                error = err
            )
            qthCalculLat = obs?.latDeg
            qthCalculLon = obs?.lonDeg
            dernierRecalculMs = System.currentTimeMillis()
            computeFavoritePasses()
            refreshPota()
            refreshSkeds()

            // Le suivi commence ici, avec l'application, et non à la première
            // ouverture de la carte.
            startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        }
    }

    // ---------- settings ----------

    fun openSettings() { _ui.value = _ui.value.copy(screen = Screen.SETTINGS) }
    fun closeSettings() {
        _ui.value = _ui.value.copy(screen = Screen.PASSES, query = "", settingsSection = null)
        computeFavoritePasses()
    }

    private var locationUpdatesJob: kotlinx.coroutines.Job? = null

    fun openLocator() {
        _ui.value = _ui.value.copy(screen = Screen.LOCATOR)
        startLiveLocation(SuiviPosition.CADENCE_CARTE_MS)
    }

    /** Cadence courante du suivi, pour ne relancer que si elle change. */
    private var cadenceSuiviMs = 0L

    /** Instant du dernier point reçu, et instant du démarrage du suivi. */
    @Volatile private var dernierPointMs = 0L
    @Volatile private var suiviDemarreMs = 0L

    private fun noteSuivi(quoi: String, point: Boolean = false, relance: Boolean = false) {
        val h = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val s = _ui.value.suivi
        _ui.value = _ui.value.copy(suivi = s.copy(
            etat = "$h  $quoi",
            points = s.points + if (point) 1 else 0,
            relances = s.relances + if (relance) 1 else 0,
            permission = permissionPosition()))
    }

    private fun permissionPosition(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * La permission vient d'être accordée — ou refusée.
     *
     * C'est le chaînon qui manquait au premier démarrage après installation. À
     * ce moment-là, le suivi a déjà tenté de démarrer et s'est fait refuser par
     * le système ; rien ensuite ne le prévenait que la situation avait changé.
     * Le veilleur finissait par le rattraper, mais seulement s'il trouvait la
     * tâche morte — et une demande refusée peut laisser une tâche qui se
     * termine proprement, sans qu'aucun point n'arrive jamais.
     *
     * On ne devine plus : dès que la réponse de l'utilisateur est connue, on
     * repart de zéro et on demande en plus un point immédiat, pour ne pas faire
     * attendre l'opérateur le temps d'un cycle complet.
     */
    fun onPermissionsResult() {
        val ok = permissionPosition()
        noteSuivi(if (ok) "permission accordée" else "permission refusée")
        if (!ok) return
        // En mode manuel, un point GPS n'a rien à dire : l'opérateur a choisi
        // son carré, et le lui reprendre est le seul vrai défaut possible ici.
        //
        // C'est ce qui se passait : cette fonction est appelée au retour de la
        // boîte de permissions, donc aussi quand le suivi est à l'arrêt, et
        // elle écrivait l'observateur sans regarder le mode. Le locator saisi
        // à la main était remplacé par la position du téléphone quelques
        // secondes plus tard.
        if (_ui.value.locationMode != LocationMode.AUTO) {
            noteSuivi("mode manuel : point GPS ignoré")
            return
        }
        stopLiveLocation()
        startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        viewModelScope.launch {
            runCatching {
                val obs = locationProvider.current()
                if (SuiviPosition.vraisemblable(obs.latDeg, obs.lonDeg)) {
                    _ui.value = _ui.value.copy(observer = obs.copy(
                        name = "GPS · " + Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg)))
                    noteSuivi("point immédiat obtenu", point = true)
                    computeFavoritePasses()
                }
            }
        }
    }

    /** Point où la dernière prédiction a été calculée, et quand. */
    private var qthCalculLat: Double? = null
    private var qthCalculLon: Double? = null
    private var dernierRecalculMs = 0L

    /**
     * Suit la position en continu, tant que le mode automatique est actif.
     *
     * Auparavant ce suivi n'était lancé qu'à l'ouverture de la carte et arrêté
     * en la quittant : sur la page des passages — celle qu'on regarde pendant
     * qu'on trafique — la position restait celle du démarrage. Un opérateur qui
     * monte sur une colline ou qui sort en portable voyait ses azimuts calculés
     * pour l'endroit d'où il était parti, sans qu'aucun message ne le lui dise.
     *
     * Deux cadences, parce que le suivi tourne désormais en permanence et que
     * sa cadence est devenue une ligne du bilan de batterie : rapide sur la
     * carte, où le marqueur doit suivre le doigt ; lente ailleurs, où voir un
     * carré changer en vingt secondes suffit largement.
     */
    private fun startLiveLocation(cadenceMs: Long = SuiviPosition.CADENCE_FOND_MS) {
        if (_ui.value.locationMode != LocationMode.AUTO) return
        if (locationUpdatesJob?.isActive == true && cadenceSuiviMs == cadenceMs &&
            !SuiviPosition.doitRelancer(true, true, dernierPointMs, suiviDemarreMs,
                System.currentTimeMillis())
        ) return
        locationUpdatesJob?.cancel()
        cadenceSuiviMs = cadenceMs
        suiviDemarreMs = System.currentTimeMillis()
        noteSuivi("suivi démarré à ${cadenceMs / 1000} s")
        locationUpdatesJob = viewModelScope.launch {
            runCatching {
                locationProvider.updates(cadenceMs).collect { obs ->
                    // Un point aberrant déplacerait le QTH, donc les azimuts,
                    // donc l'antenne, sur la foi d'un accident de récepteur.
                    if (!SuiviPosition.vraisemblable(obs.latDeg, obs.lonDeg)) return@collect
                    // Un point reçu : c'est la seule preuve que le suivi suit.
                    dernierPointMs = System.currentTimeMillis()
                    noteSuivi("point reçu", point = true)

                    // Le mode a pu changer depuis le démarrage du suivi : un
                    // point en vol ne doit pas atterrir sur un QTH manuel.
                    if (_ui.value.locationMode != LocationMode.AUTO) return@collect

                    val named = obs.copy(
                        name = "GPS · " + Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg))
                    _ui.value = _ui.value.copy(observer = named)

                    // Redessiner à chaque point ne coûte rien ; relancer une
                    // prédiction SGP4 sur 48 h pour tous les satellites suivis
                    // en coûte, et ne change rien tant qu'on n'a pas vraiment
                    // bougé. D'où le seuil.
                    val maintenant = System.currentTimeMillis()
                    if (SuiviPosition.doitRecalculer(
                            qthCalculLat, qthCalculLon, obs.latDeg, obs.lonDeg,
                            dernierRecalculMs, maintenant)
                    ) {
                        qthCalculLat = obs.latDeg
                        qthCalculLon = obs.lonDeg
                        dernierRecalculMs = maintenant
                        computeFavoritePasses()
                        // Tout ce qui dépend de la position suit le GPS, pas
                        // seulement les passages.
                        //
                        // C'est le défaut vu à IN78SA : l'accueil annonçait
                        // trois parcs à 0 m pendant que la photo répondait
                        // « aucun parc à moins de 3 km ». Ces trois-là
                        // n'étaient rafraîchis que par `applyLocation`, appelé
                        // sur un changement manuel de locator — jamais en se
                        // déplaçant. La photo restait donc sur le lieu d'où
                        // l'on était parti.
                        chargeZonePota()
                        if (_ui.value.carte.affichee) chargeCartePays()
                        if (_ui.value.carte.potaAffiche) chargePotaPhoto()
                    }
                }
            }.onFailure { noteSuivi("suivi interrompu : " + it.javaClass.simpleName) }
        }
    }

    private fun stopLiveLocation() {
        locationUpdatesJob?.cancel(); locationUpdatesJob = null
        cadenceSuiviMs = 0L
        dernierPointMs = 0L
        suiviDemarreMs = 0L
    }

    /**
     * Le veilleur du suivi de position.
     *
     * Il vit dans l'`init` du modèle de vue et non dans `bootstrap`, et c'est
     * délibéré : `bootstrap` sort par la petite porte quand le cache orbital est
     * frais — `if (!force && cacheFresh && satellites.isNotEmpty()) return` —
     * et tout ce qu'on lui confie peut donc ne jamais s'exécuter. L'`init`,
     * lui, tourne exactement une fois par modèle de vue, sans condition.
     *
     * Toutes les cinq secondes, il vérifie non pas que la tâche existe mais
     * qu'elle **délivre**. C'est la distinction qui manquait : une demande de
     * position adressée aux services Google avant qu'ils ne soient prêts laisse
     * une tâche vivante et muette, que l'ancienne garde refusait de relancer.
     */
    private fun veilleSuiviPosition() {
        viewModelScope.launch {
            while (isActive) {
                runCatching {
                    if (SuiviPosition.doitRelancer(
                            auto = _ui.value.locationMode == LocationMode.AUTO,
                            tacheActive = locationUpdatesJob?.isActive == true,
                            dernierPointMs = dernierPointMs,
                            demarreDepuisMs = suiviDemarreMs,
                            maintenantMs = System.currentTimeMillis())
                    ) {
                        locationUpdatesJob?.cancel()
                        locationUpdatesJob = null
                        noteSuivi("relance par le veilleur", relance = true)
                        startLiveLocation(
                            if (_ui.value.screen == Screen.LOCATOR)
                                SuiviPosition.CADENCE_CARTE_MS
                            else SuiviPosition.CADENCE_FOND_MS)
                    }
                }
                delay(5_000)
            }
        }
    }
    /**
     * Sked predictor (mutual visibility, like f4ioz.fr/sat/passes): crosses the
     * satellite's passes over MY QTH with its passes over another station's
     * locator and returns the overlapping windows for the next 48 h.
     * onResult(null) means the locator could not be parsed.
     */
    fun computeSked(catnum: Int, otherLocator: String, minElOther: Double,
                    onResult: (List<SkedWindow>?) -> Unit) {
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
            ?: return onResult(emptyList())
        val ll = fr.f4ioz.satcombo.location.Maidenhead.toLatLon(otherLocator.trim())
            ?: return onResult(null)
        val obsA = _ui.value.observer ?: locationProvider.defaultObserver
        val obsB = Observer(ll.first, ll.second, 0.0, otherLocator.trim().uppercase())
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val pa = runCatching { predictor.upcomingPasses(sat, obsA, now, 48,
                    settings.minElevDeg.toDouble()) }.getOrDefault(emptyList())
                val pb = runCatching { predictor.upcomingPasses(sat, obsB, now, 48,
                    minElOther) }.getOrDefault(emptyList())
                val out = ArrayList<SkedWindow>()
                for (a in pa) for (b in pb) {
                    val st = maxOf(a.aosEpochMs, b.aosEpochMs)
                    val en = minOf(a.losEpochMs, b.losEpochMs)
                    if (en - st >= 60_000L)
                        out.add(SkedWindow(st, en, a.maxElevationDeg.toInt(), b.maxElevationDeg.toInt()))
                }
                out.sortedBy { it.startMs }
            }
            onResult(res)
        }
    }

    // ---------------------------------------------------------------------
    // Mutual-sked page (full screen, animated, exportable)
    // ---------------------------------------------------------------------

    /** Open the dedicated sked page, pre-selecting a sensible satellite. */
    /**
     * L'écran du sked mutuel, éventuellement ouvert **depuis une annonce**.
     *
     * Sans [depuis], c'est l'entrée par la porte : on choisit le satellite, on
     * tape le locator de l'OM, on calcule.
     *
     * Avec [depuis], tout est déjà connu — hams.at a donné l'indicatif, le
     * satellite, le créneau et le carré. L'écran s'ouvrait pourtant vide, et
     * il fallait recopier à la main un carré qu'on venait de lire deux lignes
     * plus haut. Faire retaper à l'opérateur ce que l'application affiche est
     * la meilleure façon d'introduire une faute de frappe dans un rendez-vous.
     *
     * Le créneau annoncé n'est pas recopié dans un champ : il n'y en a pas —
     * le calcul balaie quarante-huit heures. Il sert de **visée**, retenue le
     * temps du calcul, pour présenter d'emblée la fenêtre du rendez-vous plutôt
     * que la première venue.
     */
    fun openSked(depuis: fr.f4ioz.satcombo.data.SkedAlert? = null) {
        val cat = depuis?.satNorad
            ?: _ui.value.skedSatCat
            ?: _ui.value.selected?.catalogNumber
            ?: _ui.value.satellites.firstOrNull { it.catalogNumber in _ui.value.favorites }?.catalogNumber
            ?: _ui.value.satellites.firstOrNull { it.catalogNumber == 25544 }?.catalogNumber
            ?: _ui.value.satellites.firstOrNull()?.catalogNumber
        val carre = depuis?.grids?.firstOrNull { it.isNotBlank() }.orEmpty()
        skedViseeMs = depuis?.let { it.workableStartMs ?: it.aosMs }
        _ui.value = _ui.value.copy(
            screen = Screen.SKED,
            settingsSection = null,
            skedSatCat = cat,
            skedOtherLoc = carre.ifBlank {
                _ui.value.skedOtherLoc.ifBlank { settings.skedOtherLoc }
            },
            skedPlans = if (carre.isNotBlank()) emptyList() else _ui.value.skedPlans,
            skedError = null
        )
        // Tout est là : le calcul part seul. Un bouton à appuyer alors qu'il
        // ne reste aucun choix à faire n'est pas une confirmation, c'est un
        // obstacle.
        if (carre.trim().length >= 4 && cat != null) computeSkedPlans()
    }

    /**
     * L'instant que le prochain calcul doit mettre en avant, ou null.
     *
     * Ce n'est pas de l'état d'écran — rien ne l'affiche — mais un renvoi
     * d'une action à l'autre, consommé au premier calcul. Le porter dans
     * `UiState` ajouterait un paramètre de plus au constructeur, qui frôle
     * déjà les 255 registres de Dalvik.
     */
    private var skedViseeMs: Long? = null

    fun closeSked() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    fun setSkedSat(cat: Int) {
        _ui.value = _ui.value.copy(
            skedSatCat = cat, skedPlans = emptyList(),
            skedComputed = false, skedError = null, skedSelectedIndex = 0)
    }

    fun setSkedOtherLoc(loc: String) {
        _ui.value = _ui.value.copy(skedOtherLoc = loc.uppercase().trim(), skedError = null)
    }

    fun setSkedMinElOther(v: Int) { _ui.value = _ui.value.copy(skedMinElOther = v) }

    fun setSkedSelectedIndex(i: Int) { _ui.value = _ui.value.copy(skedSelectedIndex = i) }

    /**
     * Compute mutual-visibility sked plans for the chosen satellite and the DX
     * station over the next 48 h. Each plan carries both stations' densely
     * sampled pass tracks so the page can animate the two polar plots and export
     * a shareable card. skedError = "bad" means the locator could not be parsed.
     */
    fun computeSkedPlans() {
        val cat = _ui.value.skedSatCat ?: return
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == cat } ?: return
        val loc = _ui.value.skedOtherLoc.trim().uppercase()
        val ll = Maidenhead.toLatLon(loc)
        if (ll == null) { _ui.value = _ui.value.copy(skedError = "bad"); return }
        val obsA = _ui.value.observer ?: locationProvider.defaultObserver
        val myLoc = Maidenhead.fromLatLon(obsA.latDeg, obsA.lonDeg)
        val obsB = Observer(ll.first, ll.second, 0.0, loc)
        val minElOther = _ui.value.skedMinElOther.toDouble()
        settings.skedOtherLoc = loc
        _ui.value = _ui.value.copy(skedComputing = true, skedError = null, skedPlans = emptyList())
        viewModelScope.launch {
            val plans = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val pa = runCatching {
                    predictor.upcomingPasses(sat, obsA, now, 48, settings.minElevDeg.toDouble())
                }.getOrDefault(emptyList())
                val pb = runCatching {
                    predictor.upcomingPasses(sat, obsB, now, 48, minElOther)
                }.getOrDefault(emptyList())
                val dist = haversineKm(obsA.latDeg, obsA.lonDeg, obsB.latDeg, obsB.lonDeg)
                val out = ArrayList<SkedPlan>()
                for (a in pa) for (b in pb) {
                    val st = maxOf(a.aosEpochMs, b.aosEpochMs)
                    val en = minOf(a.losEpochMs, b.losEpochMs)
                    if (en - st < 60_000L) continue
                    val youSamples = predictor.sampleTrack(sat, obsA, a.aosEpochMs, a.losEpochMs, 96)
                        .map { SkedSample(it.first, it.second, it.third) }
                    val dxSamples = predictor.sampleTrack(sat, obsB, b.aosEpochMs, b.losEpochMs, 96)
                        .map { SkedSample(it.first, it.second, it.third) }
                    out.add(SkedPlan(
                        satName = sat.name, catnum = cat,
                        mutualStartMs = st, mutualEndMs = en,
                        you = SkedStationTrack(myLoc, myLoc, a.aosEpochMs, a.losEpochMs,
                            a.maxElevationDeg, youSamples),
                        dx = SkedStationTrack(loc, loc, b.aosEpochMs, b.losEpochMs,
                            b.maxElevationDeg, dxSamples),
                        distanceKm = dist
                    ))
                }
                out.sortedBy { it.mutualStartMs }
            }
            // La fenêtre du rendez-vous annoncé passe devant, quand il y en a
            // un : celle qui le contient, sinon la plus proche. Sans cela
            // l'écran ouvrait sur le premier créneau des quarante-huit heures,
            // qui n'est pas celui dont on vient de parler.
            val visee = skedViseeMs
            skedViseeMs = null
            val index = fr.f4ioz.satcombo.domain.SkedVisee.index(
                plans.map { it.mutualStartMs..it.mutualEndMs }, visee)
            _ui.value = _ui.value.copy(
                skedComputing = false, skedComputed = true,
                skedPlans = plans, skedSelectedIndex = index)
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    fun setSettingsSection(id: String?) { _ui.value = _ui.value.copy(settingsSection = id) }
    fun closeLocator() {
        // On ne coupe plus le suivi en quittant la carte : on redescend à la
        // cadence de fond. C'était là le défaut — quitter la carte figeait la
        // position jusqu'à la prochaine visite.
        startLiveLocation(SuiviPosition.CADENCE_FOND_MS)
        _ui.value = _ui.value.copy(screen = Screen.PASSES)
    }

    fun setLocationMode(mode: LocationMode) {
        settings.locationMode = mode
        _ui.value = _ui.value.copy(locationMode = mode, locatorError = null)
        if (mode == LocationMode.MANUAL) { stopLiveLocation(); applyLocation() }
        else startLiveLocation(
            if (_ui.value.screen == Screen.LOCATOR) SuiviPosition.CADENCE_CARTE_MS
            else SuiviPosition.CADENCE_FOND_MS)
        applyLocation()
    }

    fun setManualLocator(loc: String) {
        // Hand-typing a locator throws away the exact point: the operator is
        // back to naming a square, not a spot.
        _ui.value = _ui.value.copy(
            manualLocator = loc, manualLat = null, manualLon = null, locatorError = null)
    }

    /**
     * Map picker: keep the EXACT tapped coordinates, force MANUAL mode and
     * apply immediately. The locator is derived from the point, so the two can
     * never disagree.
     */
    fun pickLocator(loc: String, lat: Double? = null, lon: Double? = null) {
        if (Maidenhead.toLatLon(loc) == null) return
        val exact = lat != null && lon != null && Maidenhead.fromLatLon(lat, lon).startsWith(loc.uppercase())
        settings.manualLocator = loc
        settings.manualLat = if (exact) lat!! else Double.NaN
        settings.manualLon = if (exact) lon!! else Double.NaN
        settings.locationMode = LocationMode.MANUAL
        _ui.value = _ui.value.copy(
            manualLocator = loc,
            manualLat = if (exact) lat else null,
            manualLon = if (exact) lon else null,
            locationMode = LocationMode.MANUAL, locatorError = null)
        applyLocation()
    }

    fun applyManualLocator() {
        val loc = _ui.value.manualLocator
        if (Maidenhead.toLatLon(loc) == null) {
            _ui.value = _ui.value.copy(locatorError = t("locator_invalid"))
            return
        }
        settings.manualLocator = loc
        settings.manualLat = _ui.value.manualLat ?: Double.NaN
        settings.manualLon = _ui.value.manualLon ?: Double.NaN
        applyLocation()
    }

    private fun applyLocation() {
        viewModelScope.launch {
            val obs = resolveObserver()
            _ui.value = _ui.value.copy(observer = obs)
            // Le QTH vient de changer : la silhouette du pays doit suivre.
            if (_ui.value.carte.affichee) chargeCartePays()
            chargeZonePota()
            if (_ui.value.carte.potaAffiche) chargePotaPhoto()
            computeFavoritePasses()
            refreshPota()
        }
    }

    fun focusPass(aosMs: Long) {
        _ui.value = _ui.value.copy(focusedPassAos = aosMs)
        val sat = _ui.value.selected ?: return
        val obs = _ui.value.observer ?: return
        viewModelScope.launch { refreshPassTrack(sat, obs) }
    }

    fun setUseUtc(on: Boolean) {
        settings.useUtc = on
        _ui.value = _ui.value.copy(useUtc = on)
    }

    fun setLocatorDetails(on: Boolean) {
        settings.locatorDetails = on
        _ui.value = _ui.value.copy(locatorDetails = on)
    }

    // ---------------------------------------------------------------------
    // Audio recorder (MP3). Records the mic (rig RX audio) during a pass and
    // auto-stops 5 s after LOS unless stopped manually. RECORD_AUDIO must be
    // granted by the caller (UI) before start.
    // ---------------------------------------------------------------------

    /** Set one compass colour by key ("trace", "needle_far"…"bubble_close"). */
    fun setCompassColor(key: String, argb: Int) {
        when (key) {
            "trace" -> { settings.compassTraceColor = argb; _ui.value = _ui.value.copy(compassTraceColor = argb) }
            "needle_far" -> { settings.needleFarColor = argb; _ui.value = _ui.value.copy(needleFarColor = argb) }
            "needle_near" -> { settings.needleNearColor = argb; _ui.value = _ui.value.copy(needleNearColor = argb) }
            "needle_close" -> { settings.needleCloseColor = argb; _ui.value = _ui.value.copy(needleCloseColor = argb) }
            "bubble_far" -> { settings.bubbleFarColor = argb; _ui.value = _ui.value.copy(bubbleFarColor = argb) }
            "bubble_near" -> { settings.bubbleNearColor = argb; _ui.value = _ui.value.copy(bubbleNearColor = argb) }
            "bubble_close" -> { settings.bubbleCloseColor = argb; _ui.value = _ui.value.copy(bubbleCloseColor = argb) }
            "ring_az_near" -> { settings.ringAzNearColor = argb; _ui.value = _ui.value.copy(ringAzNearColor = argb) }
            "ring_az_close" -> { settings.ringAzCloseColor = argb; _ui.value = _ui.value.copy(ringAzCloseColor = argb) }
            "ring_el_near" -> { settings.ringElNearColor = argb; _ui.value = _ui.value.copy(ringElNearColor = argb) }
            "ring_el_close" -> { settings.ringElCloseColor = argb; _ui.value = _ui.value.copy(ringElCloseColor = argb) }
        }
    }

    fun setCompassTraceWidth(w: Float) {
        settings.compassTraceWidth = w
        _ui.value = _ui.value.copy(compassTraceWidth = w)
    }

    fun resetCompassColors() {
        settings.resetCompassColors()
        _ui.value = _ui.value.copy(
            compassTraceColor = settings.compassTraceColor,
            needleFarColor = settings.needleFarColor,
            needleNearColor = settings.needleNearColor,
            needleCloseColor = settings.needleCloseColor,
            bubbleFarColor = settings.bubbleFarColor,
            bubbleNearColor = settings.bubbleNearColor,
            bubbleCloseColor = settings.bubbleCloseColor,
            ringAzNearColor = settings.ringAzNearColor,
            ringAzCloseColor = settings.ringAzCloseColor,
            ringElNearColor = settings.ringElNearColor,
            ringElCloseColor = settings.ringElCloseColor)
    }

    fun setRecorderEnabled(on: Boolean) {
        settings.recorderEnabled = on
        if (!on && _ui.value.recording) stopRecording()
        _ui.value = _ui.value.copy(recorderEnabled = on)
    }

    fun setRecorderSource(src: String) {
        settings.recorderSource = src
        _ui.value = _ui.value.copy(recorderSource = src)
    }

    fun setRecorderUnprocessed(on: Boolean) {
        settings.recorderUnprocessed = on
        _ui.value = _ui.value.copy(recorderUnprocessed = on)
    }

    /**
     * Le spectre du son pendant l'enregistrement.
     *
     * Se rallume et s'éteint en plein passage : le moniteur tourne déjà, il ne
     * fait que se remettre à calculer.
     */
    /**
     * « Un bouton pour couper le doppler (ne pas mettre à jour le poste). »
     *
     * On suspend l'écriture, rien d'autre : la position du satellite, le
     * Doppler, la fréquence de repos et l'affichage continuent exactement comme
     * avant, de sorte qu'en relâchant la pause le poste se retrouve à sa place
     * sans qu'on ait rien à refaire.
     *
     * Au relâchement, tout ce qui sert de mémoire des dernières consignes est
     * effacé : sans cela l'application croirait avoir déjà écrit la bonne
     * fréquence et resterait muette jusqu'au prochain écart de deux cents
     * hertz. L'armement est également oublié, pour que le poste soit remis en
     * mode satellite et en split au premier tour de boucle qui suit.
     */
    fun toggleDopplerHold() {
        val on = !_ui.value.dopplerHold
        _ui.value = _ui.value.copy(dopplerHold = on)
        if (!on) {
            lastSentDl = 0L; lastSentUl = 0L
            catArmedFor = null; catArmedTxDesc = null
            rxArbiter.reset(); fmArbiter.reset()
        }
    }

    fun setMonitorSpectre(on: Boolean) {
        settings.monitorSpectre = on
        fr.f4ioz.satcombo.audio.MoniteurAudio.spectre = on
        _ui.value = _ui.value.copy(monitorSpectre = on)
    }

    /**
     * Le contrôle à l'oreille. N'a d'effet que sur une source extérieure —
     * carte son USB du poste ou liaison Bluetooth : renvoyer le micro du
     * téléphone dans son propre haut-parleur ne ferait que du Larsen.
     */
    fun setMonitorSpeaker(on: Boolean) {
        settings.monitorSpeaker = on
        fr.f4ioz.satcombo.audio.MoniteurAudio.hautParleur = on
        _ui.value = _ui.value.copy(monitorSpeaker = on)
    }

    fun setSstvEnabled(on: Boolean) {
        settings.sstvEnabled = on
        _ui.value = _ui.value.copy(sstvEnabled = on)
    }

    fun setAptEnabled(on: Boolean) {
        settings.aptEnabled = on
        _ui.value = _ui.value.copy(aptEnabled = on)
    }

    /**
     * Choisit ce que la bande image de la page du passage suit.
     *
     * Choisir NOAA allume le décodeur APT au passage : on ne va pas demander à
     * l'opérateur de cocher une case dans les réglages après avoir dit, sur la
     * page du passage, que c'est du NOAA qu'il attend.
     */
    fun setRxImageMode(mode: String) {
        val m = if (mode.equals("NOAA", true) || mode.equals("APT", true)) "NOAA" else "SSTV"
        settings.rxImageMode = m
        if (m == "NOAA" && !_ui.value.aptEnabled) setAptEnabled(true)
        if (m == "SSTV" && !_ui.value.sstvEnabled) setSstvEnabled(true)
        _ui.value = _ui.value.copy(rxImageMode = m)
    }

    /**
     * Le bouton d'enregistrement de la bande image, sur la page du passage.
     *
     * C'est le même magnétophone que partout ailleurs — une seule capture, un
     * seul MP3 — mais lancé depuis l'endroit où l'on regarde l'image arriver.
     * On s'assure au passage que le décodeur correspondant est bien allumé,
     * sinon le bouton donnerait un enregistrement muet d'images.
     */
    fun toggleRxRecording() {
        if (_ui.value.recording) { stopRecording(); return }
        if (!_ui.value.recorderEnabled) setRecorderEnabled(true)
        if (_ui.value.rxImageMode == "NOAA") setAptEnabled(true) else setSstvEnabled(true)
        startRecording()
    }

    fun setRecordingsTree(uri: String) {
        settings.recordingsTreeUri = uri
        _ui.value = _ui.value.copy(recordingsTreeUri = uri)
    }

    fun toggleUiLock() { _ui.value = _ui.value.copy(uiLocked = !_ui.value.uiLocked) }

    fun toggleRecording() { if (_ui.value.recording) stopRecording() else startRecording() }

    /** Start the foreground recorder service (survives screen-off/background). */
    fun startRecording() {
        if (_ui.value.recording) return
        val sel = _ui.value.selected
        val satName = sel?.name ?: "SAT"
        val now = System.currentTimeMillis()
        // Auto-stop target: LOS (+5 s) of the pass in progress for the selected
        // satellite, else its next pass. Null -> record until stopped manually.
        val passes = (_ui.value.passes + _ui.value.favoritePasses)
            .filter { sel == null || it.catalogNumber == sel.catalogNumber }
        val active = passes.firstOrNull { now in it.aosEpochMs..it.losEpochMs }
        val next = passes.filter { it.aosEpochMs >= now }.minByOrNull { it.aosEpochMs }
        val autoStop = (active ?: next)?.losEpochMs?.let { it + 5_000L }
        fr.f4ioz.satcombo.audio.RecorderService.start(
            getApplication(), satName, autoStop,
            _ui.value.recorderSource, _ui.value.recorderUnprocessed)
    }

    fun stopRecording() {
        fr.f4ioz.satcombo.audio.RecorderService.stop(getApplication())
    }

    /** 0, 3, 6 or 12 h of already-finished passes kept in the lists. */
    fun setPastPassHours(h: Int) {
        settings.pastPassHours = h
        _ui.value = _ui.value.copy(pastPassHours = settings.pastPassHours)
        computeFavoritePasses()
        _ui.value.selected?.let { selectByCatnum(it.catalogNumber) }
    }

    fun setMinElev(deg: Int) {
        settings.minElevDeg = deg
        _ui.value = _ui.value.copy(minElevDeg = settings.minElevDeg)
        computeFavoritePasses()
        // Refresh the open satellite's pass list too, if any.
        _ui.value.selected?.let { selectByCatnum(it.catalogNumber) }
    }

    fun setSkedsEnabled(on: Boolean) {
        settings.skedsEnabled = on
        _ui.value = _ui.value.copy(skedsEnabled = on)
        if (on) refreshSkeds() else _ui.value = _ui.value.copy(skeds = emptyList())
    }

    private fun refreshSkeds() {
        if (!_ui.value.skedsEnabled) return
        viewModelScope.launch {
            val list = skedRepo.upcoming(token = settings.skedsToken)
            _ui.value = _ui.value.copy(skeds = list)
        }
    }

    fun setSkedsToken(token: String) {
        settings.skedsToken = token
        _ui.value = _ui.value.copy(skedsToken = token, skedsAuthed = token.isNotBlank())
        viewModelScope.launch {
            val list = skedRepo.upcoming(token = token, force = true)
            _ui.value = _ui.value.copy(skeds = list)
        }
    }

    /**
     * La saisie, et rien d'autre.
     *
     * Un appui sur la boussole ouvrait un contact dans le carnet : heure,
     * satellite, azimut, élévation — et **pas d'indicatif**. Le geste était né
     * pour marquer un contact au vol et le nommer ensuite ; il ne produisait en
     * réalité que des lignes anonymes. Quatre d'entre elles ont été relevées le
     * 25 août, dans le carnet et dans l'export ADIF.
     *
     * Un contact sans indicatif n'est pas un contact incomplet : c'est un
     * indicatif qu'on connaissait à l'instant même et qu'on a perdu. La file
     * d'attente avait été bâtie pour rattraper ces lignes-là ; elle a coûté
     * sept versions de correctifs et n'a jamais servi à Olivier, qui tape et
     * valide. **Le geste ouvre donc le clavier, et c'est la validation qui
     * écrit.** Rien ne se pose au carnet tant qu'un indicatif n'est pas frappé.
     */
    fun ouvreSaisie() {
        _ui.value = _ui.value.copy(screen = Screen.NOMMAGE)
    }

    private fun modeEmission(t: fr.f4ioz.satcombo.data.Transmitter?): String {
        val m = t?.mode.orEmpty().uppercase()
        return when {
            m.contains("FM") -> "FM"
            t != null && t.isTransponder && _ui.value.opMode == "CW" -> "CW"
            m.contains("CW") -> "CW"
            // Sur un transpondeur linéaire, le poste est réglé par le CAT
            // selon une convention fixe — descente USB, montée LSB si le
            // transpondeur est inverse — **sans consulter le mode annoncé par
            // le catalogue**. Le carnet doit dire la même chose, sans quoi il
            // décrit un mode que le VFO n'a jamais eu.
            t != null && t.isTransponder -> if (effectiveInvert(t)) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            m.contains("USB") -> "USB"
            else -> m
        }
    }

    // ---- la silhouette du pays, sur la photo QRV ----
    //
    // Elle est une option de la photo et non un écran : une carte QRV est une
    // photo avec des choses dessus, et la carte en est une de plus.

    /**
     * L'opérateur confirme qu'il est bien dans le parc proposé.
     *
     * C'est lui qui décide, parce que lui seul sait s'il a franchi la limite :
     * aucun contour ne le dira depuis un rayon de trois kilomètres.
     */
    fun accepteParcPropose() {
        val c = _ui.value.carte
        if (c.potaPropose.isBlank()) return
        _ui.value = _ui.value.copy(carte = c.copy(
            potaRef = c.potaPropose, potaNom = c.potaProposeNom,
            potaPropose = "", potaProposeNom = ""))
    }

    fun setPhotoPota(on: Boolean) {
        settings.photoShowPota = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(potaAffiche = on))
        if (on) chargePotaPhoto()
    }

    /**
     * Le parc le plus proche de la position : référence et nom, servis à la
     * photo. Le catalogue régional est déjà dans PotaRepository — celui de la
     * carte des parcs — donc hors connexion une fois la région chargée.
     */
    /**
     * La zone POTA contenant la position, jugée au polygone.
     *
     * Le premier appel charge le fichier embarqué (1 Mo, ~100 ms) : tout se
     * fait hors du fil principal. Les suivants coûtent 0,03 ms — le
     * pré-filtre par boîtes englobantes ne parcourt un polygone que si le
     * point est dans sa boîte.
     */
    fun chargeZonePota() {
        val obs = _ui.value.observer ?: return
        viewModelScope.launch {
            val z = withContext(Dispatchers.IO) {
                runCatching {
                    fr.f4ioz.satcombo.data.PotaZones.zoneContenant(
                        getApplication(), obs.latDeg, obs.lonDeg)
                }.getOrNull()
            }
            // Les villes de la fenêtre du parc, chargées en même temps : un
            // contour sans nom est une tache, avec trois communes c'est un
            // endroit.
            val villes = if (z != null) withContext(Dispatchers.IO) {
                runCatching {
                    var laMin = 90.0; var laMax = -90.0
                    var loMin = 180.0; var loMax = -180.0
                    z.anneaux.forEach { a ->
                        var i = 0
                        while (i < a.size) {
                            if (a[i] < laMin) laMin = a[i]; if (a[i] > laMax) laMax = a[i]
                            if (a[i+1] < loMin) loMin = a[i+1]
                            if (a[i+1] > loMax) loMax = a[i+1]
                            i += 2
                        }
                    }
                    val mLat = (laMax - laMin) * 0.35 + 0.02
                    val mLon = (loMax - loMin) * 0.35 + 0.03
                    fr.f4ioz.satcombo.data.Villes.dansFenetre(getApplication(),
                        laMin - mLat, laMax + mLat, loMin - mLon, loMax + mLon, 6)
                        .map { Triple(it.nom, it.lat, it.lon) }
                }.getOrDefault(emptyList())
            } else emptyList()

            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                zoneRef = z?.ref.orEmpty(),
                zoneNom = z?.nom.orEmpty(),
                zoneAnneaux = z?.anneaux ?: emptyList(),
                villes = villes,
                // La ligne POTA suit la détection par contour, la seule qui
                // prouve qu'on est dans le parc. Hors contour, on efface : une
                // ligne qui survit à la sortie du parc suivrait l'opérateur
                // toute la journée.
                potaRef = z?.ref.orEmpty(),
                potaNom = z?.nom.orEmpty(),
                potaPropose = if (z != null) "" else _ui.value.carte.potaPropose,
                potaProposeNom = if (z != null) "" else _ui.value.carte.potaProposeNom))

            // Rien d'embarqué ici ? On va chercher les contours des parcs
            // proches sur pota-map.fr, un par un, et on les garde. Tant que la
            // récupération complète n'est pas finie, c'est ce qui fait marcher
            // la détection partout ailleurs qu'en Bretagne.
            if (z == null) {
                // Pas de contour : le parc le plus proche renseigne au moins
                // la ligne, et l'on tente le réseau pour la prochaine fois.
                val hit = withContext(Dispatchers.IO) {
                    runCatching {
                        potaRepo.near(obs.latDeg, obs.lonDeg, radiusKm = 3.0,
                            context = getApplication()).firstOrNull()
                    }.getOrNull()
                }
                // **Le parc proche est proposé, jamais affirmé.**
                //
                // Ces deux champs remplissaient directement la ligne de la
                // photo : être à trois kilomètres du point d'un parc suffisait
                // à s'en déclarer activateur. Or une ligne POTA sur une photo
                // partagée est une affirmation d'activation, et une
                // affirmation fausse vaut moins que pas de ligne du tout.
                //
                // Le parc proche devient donc une proposition, que l'opérateur
                // retient d'un geste s'il y est vraiment. Lui seul sait s'il a
                // franchi la limite.
                _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                    potaPropose = hit?.park?.reference.orEmpty(),
                    potaProposeNom = hit?.park?.name.orEmpty()))
                chercheZoneAuReseau(obs.latDeg, obs.lonDeg)
            }
        }
    }

    /**
     * La référence POTA écrite sur la photo.
     *
     * **L'emprise prime sur la distance.** La photo affichait le parc dont le
     * point central est le plus proche — FR-5151 à Kerléguer — pendant que
     * l'accueil annonçait FR-8200, celui dont le polygone contient vraiment la
     * position. Deux chemins pour une même question, donc deux réponses : la
     * photo suit maintenant la zone, et ne retombe sur le plus proche que si
     * aucun contour ne contient le point.
     */
    /**
     * Va chercher au réseau les contours des parcs proches, faute d'embarqué.
     *
     * Un parc à la fois, dans l'ordre de proximité, et l'on s'arrête au
     * premier qui contient la position — le site est celui d'un radioamateur,
     * pas un CDN. Chaque contour récupéré est mis en cache définitivement :
     * le deuxième passage au même endroit ne demandera plus rien.
     */
    /**
     * Précharge les contours des parcs autour de soi.
     *
     * **Pourquoi d'avance.** La détection courante ne cherche un contour qu'au
     * moment où l'on en a besoin, et s'arrête au premier qui contient la
     * position. Sur le terrain, le réseau est souvent là où l'on part, jamais
     * là où l'on arrive : un parc activé au fond d'un vallon sans couverture
     * reste sans contour, et la ligne POTA ne s'affiche pas.
     *
     * **La politesse envers les serveurs fait partie de la fonction.**
     * `pota-map.fr` est un service communautaire, pas une infrastructure. On
     * plafonne à trente parcs, on espace les demandes d'un cinquième de
     * seconde, et l'on passe les contours déjà en cache. Tirer cent contours
     * d'un coup ferait fermer la porte à tout le monde.
     */
    fun chargeContoursAutour(rayonKm: Double) {
        if (contoursEnCours) return
        val obs = _ui.value.observer ?: run {
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                contoursEtat = t("pota_contours_sans_position")))
            return
        }
        contoursEnCours = true
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            contoursEtat = t("pota_contours_cours")))
        viewModelScope.launch {
            val bilan = withContext(Dispatchers.IO) {
                runCatching {
                    val proches = potaRepo.near(obs.latDeg, obs.lonDeg,
                        radiusKm = rayonKm, context = getApplication()).take(30)
                    var pris = 0
                    for (h in proches) {
                        val ref = h.park.reference
                        if (fr.f4ioz.satcombo.data.PotaZones.zone(
                                getApplication(), ref) != null) continue
                        if (fr.f4ioz.satcombo.data.PotaZones.telecharge(
                                getApplication(), ref) != null) pris++
                        kotlinx.coroutines.delay(200)
                    }
                    proches.size to pris
                }.getOrNull()
            }
            contoursEnCours = false
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                contoursEtat = if (bilan == null) t("pota_contours_echec")
                else tf("pota_contours_bilan", bilan.second, bilan.first)))
        }
    }

    @Volatile private var contoursEnCours = false

    private fun chercheZoneAuReseau(lat: Double, lon: Double) {
        if (chercheZoneEnCours) return
        chercheZoneEnCours = true
        viewModelScope.launch {
            val trouve = withContext(Dispatchers.IO) {
                runCatching {
                    val proches = potaRepo.near(lat, lon, radiusKm = 12.0,
                        context = getApplication()).take(6)
                    var z: fr.f4ioz.satcombo.data.PotaZones.Zone? = null
                    for (h in proches) {
                        val zz = fr.f4ioz.satcombo.data.PotaZones.telecharge(
                            getApplication(), h.park.reference) ?: continue
                        if (zz.contient(lat, lon)) {
                            z = fr.f4ioz.satcombo.data.PotaZones.Zone(
                                zz.ref, h.park.name, zz.anneaux)
                            break
                        }
                    }
                    z
                }.getOrNull()
            }
            chercheZoneEnCours = false
            if (trouve != null) {
                _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                    zoneRef = trouve.ref, zoneNom = trouve.nom,
                    zoneAnneaux = trouve.anneaux))
            }
        }
    }

    private var chercheZoneEnCours = false

    /**
     * La référence POTA de la photo est celle de la zone détectée.
     *
     * Elle avait son propre chemin — `zoneContenant` puis repli sur le parc le
     * plus proche — et ce chemin pouvait répondre « aucun parc » pendant que
     * la carte dessinait le contour du parc, tous deux partant pourtant de la
     * même position. Un seul calcul désormais : `chargeZonePota` remplit la
     * zone, la ligne POTA la recopie. Deux affichages, une vérité.
     */
    fun chargePotaPhoto() = chargeZonePota()

    /**
     * Installe un fichier de zones POTA produit par PotaGrab.
     *
     * Le fichier vit dans l'espace privé de l'application, pas dans l'APK :
     * la France entière pèse des dizaines de mégaoctets, qu'il serait absurde
     * d'imposer à quelqu'un qui n'active jamais de parc. Celui qui en veut le
     * pose, les autres gardent l'embarqué.
     */
    fun importeZonesPota(texte: String): Int {
        val n = runCatching {
            val o = org.json.JSONObject(texte)
            if (o.length() == 0) return 0
            fr.f4ioz.satcombo.data.PotaZones.fichierImporte(getApplication())
                .writeText(texte)
            fr.f4ioz.satcombo.data.PotaZones.rafraichis()
            o.length()
        }.getOrDefault(0)
        if (n > 0) chargeZonePota()
        return n
    }

    fun effaceZonesPota() {
        runCatching {
            fr.f4ioz.satcombo.data.PotaZones.fichierImporte(getApplication()).delete()
            fr.f4ioz.satcombo.data.PotaZones.rafraichis()
        }
        chargeZonePota()
    }

    fun compteZonesPota(): Int =
        fr.f4ioz.satcombo.data.PotaZones.compteImporte(getApplication())

    fun setPhotoCarte(on: Boolean) {
        settings.photoShowCarte = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(affichee = on))
        // Recharger à chaque activation, pas seulement quand rien n'est
        // chargé : conditionné à « anneaux vides », le pays restait celui du
        // QTH d'avant — un opérateur passé d'IO86 à EM87 gardait le
        // Royaume-Uni sur une photo américaine, même en décochant-recochant.
        if (on) chargeCartePays()
    }

    fun setPhotoCarteTaille(v: Float) {
        settings.photoCarteTaille = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(taille = settings.photoCarteTaille))
    }

    fun setPhotoCartePosition(x: Float, y: Float) {
        settings.photoCarteX = x; settings.photoCarteY = y
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            x = settings.photoCarteX, y = settings.photoCarteY))
    }

    fun setPhotoCarteContenu(v: String) {
        settings.photoCarteContenu = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(contenu = v))
        if (v == "ZONE") chargeZonePota()
    }

    fun setPhotoPotaTaille(v: Float) {
        settings.photoPotaTaille = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            potaTaille = settings.photoPotaTaille))
    }

    fun setPhotoPotaMonte(v: Float) {
        settings.photoPotaMonte = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            potaMonte = settings.photoPotaMonte))
    }

    fun setPhotoQrg(on: Boolean) {
        settings.photoShowQrg = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgAffiche = on))
    }

    fun setLiseretEmission(on: Boolean) {
        settings.liseréEmission = on
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(liseret = on))
        // Le réglage prend effet tout de suite : on relance ou l'on arrête,
        // sans attendre une reconnexion.
        surveilleEmission()
    }

    // ---- le carnet en ligne (Wavelog / Cloudlog) ----

    fun setCarnetUrl(v: String) {
        settings.carnetUrl = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            url = settings.carnetUrl, carres = emptyMap()))
    }

    fun setCarnetCle(v: String) {
        settings.carnetCle = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            cle = settings.carnetCle, carres = emptyMap()))
    }

    fun setCarnetSlug(v: String) {
        settings.carnetSlug = v
        fr.f4ioz.satcombo.data.CarnetEnLigne.oublie()
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            slug = settings.carnetSlug, carres = emptyMap()))
    }

    fun setCarnetProfil(v: String) {
        settings.carnetProfil = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(profil = settings.carnetProfil))
    }

    /** Combien de contacts attendent d'être déposés au carnet en ligne. */
    fun contactsADeposer(): Int =
        fr.f4ioz.satcombo.domain.EnvoiCarnet.combienAttendent(
            _ui.value.log.map {
                fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.callsign, it.envoyeMs)
            })

    /**
     * Dépose **un seul** contact au carnet en ligne.
     *
     * Le dépôt en lot était le seul chemin, et c'était un défaut de méthode :
     * le premier essai est précisément celui où la clé d'écriture et le profil
     * de station se révèlent faux, et un lot entier déposé de travers se
     * démêle à la main, contact par contact, du côté du serveur.
     *
     * Le même garde qu'en lot : marqué seulement après que le serveur a dit
     * l'avoir pris.
     */
    private val qrz = fr.f4ioz.satcombo.data.Qrz()

    fun setMaille(n: Int) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(maille = n))
    }

    /**
     * Combien de contacts portent encore un nom de satellite brut.
     *
     * Le compte est sur le bouton : c'est la seule façon de savoir, sans
     * appuyer, s'il y a quelque chose à faire.
     */
    fun nomsSatellitesANettoyer(): Int =
        _ui.value.log.count {
            it.satName.isNotBlank() &&
                fr.f4ioz.satcombo.domain.NomSatellite.aNettoyer(it.satName)
        }

    /**
     * La maille d'appariement des carrés : 4 comme le VUCC, ou 6.
     *
     * Changer la maille invalide la table relevée — les identifiants de profil
     * y sont rangés par carré tronqué, et la troncature vient de changer. La
     * vider force un nouveau relevé plutôt que de laisser déposer sur une
     * correspondance qui ne veut plus rien dire.
     */
    fun setMailleCarres(m: Int) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            maille = m.coerceIn(4, 6), profils = emptyMap(), profilsEtat = ""))
    }

    fun setQrzUser(v: String) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(qrzUser = v))
    }

    fun setQrzMdp(v: String) {
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(qrzMdp = v))
    }

    /**
     * Relève les profils de station et les confronte aux carrés du carnet.
     *
     * **À faire avant tout dépôt quand on active plusieurs carrés.** Sans ce
     * relevé, tout part sur le profil unique et se range sous son carré.
     */
    fun relevProfils() {
        val c = _ui.value.carnet
        if (c.url.isBlank() || c.cle.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("carnet_depot_config")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_releve")))
        viewModelScope.launch {
            val liste = runCatching {
                fr.f4ioz.satcombo.data.CarnetEnLigne.profils(c.url, c.cle)
            }.getOrElse {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    profilsEtat = (it.message ?: it.javaClass.simpleName).take(140)))
                return@launch
            }
            val parLieu = emplacementsDuCarnet(c.maille)
            val table = fr.f4ioz.satcombo.domain.ProfilsStation.apparieEmplacements(
                parLieu.keys.toList(), liste, settings.callsign, c.maille)
            val orphelins = parLieu.keys.filter { table[it] == null }
                .map { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it) }
                .sorted()
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profils = table, profilsListe = liste,
                profilsEtat = tf("profils_bilan", liste.size, parLieu.size, orphelins.size) +
                    (if (orphelins.isEmpty()) "" else " · " + orphelins.joinToString(", "))))
        }
    }

    /**
     * Les emplacements d'où le carnet a été fait, avec le nombre de contacts.
     *
     * Une ligne de carrés compte pour un emplacement à part : les contacts
     * qui la revendiquent ne doivent pas se mélanger à ceux faits dans un
     * seul des deux carrés.
     */
    fun emplacementsDuCarnet(maille: Int): Map<Set<String>, Int> {
        val out = LinkedHashMap<Set<String>, Int>()
        _ui.value.log.filter { it.callsign.isNotBlank() }.forEach {
            val k = fr.f4ioz.satcombo.domain.ProfilsStation.emplacement(
                it.myLocator, it.myGrids, maille)
            out[k] = (out[k] ?: 0) + 1
        }
        return out.toList()
            .sortedBy { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it.first) }
            .toMap(LinkedHashMap())
    }

    /** Les emplacements du carnet, nommés, pour l'écran. */
    fun carresDuCarnet(maille: Int): Map<String, Int> =
        emplacementsDuCarnet(maille).mapKeys {
            fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it.key)
        }

    /** Crée chez Wavelog les emplacements que le carnet réclame. */
    fun creeProfilsManquants(dxcc: String, pays: String, cq: String, itu: String) {
        val c = _ui.value.carnet
        if (itu.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_itu")))
            return
        }
        val parLieu = emplacementsDuCarnet(c.maille)
        val orphelins = parLieu.keys.filter { c.profils[it] == null }
            .map { fr.f4ioz.satcombo.domain.ProfilsStation.nomEmplacement(it) }
            .filter { it.isNotBlank() && it != "—" }
            .sorted()
        if (orphelins.isEmpty()) return
        _ui.value = _ui.value.copy(carnet = c.copy(profilsEtat = t("profils_creation")))
        viewModelScope.launch {
            var faits = 0
            var dernier = ""
            for (carre in orphelins) {
                val nom = settings.callsign.trim().uppercase() + " @ " + carre
                val r = fr.f4ioz.satcombo.data.CarnetEnLigne.creeProfil(
                    c.url, c.cle, nom, carre, settings.callsign, dxcc, pays, cq, itu)
                if (fr.f4ioz.satcombo.data.CarnetEnLigne.profilEnPlace(r)) faits++
                else dernier = r.take(90)
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profilsEtat = tf("profils_crees", faits, orphelins.size) +
                    (if (dernier.isBlank()) "" else " · " + dernier)))
            // Les identifiants neufs ne sont connus qu'après relevé.
            relevProfils()
        }
    }

    /**
     * Nettoie les noms de satellites du carnet.
     *
     * « RS-44 & BREEZE-KM R/B » devient « RS-44 ». LoTW et Wavelog apparient
     * sur ce champ : sous le nom long, le contact ne rencontrera jamais celui
     * que l'autre station a déclaré.
     */
    fun nettoieNomsSatellites() {
        var changes = 0
        val neuf = _ui.value.log.map {
            val propre = fr.f4ioz.satcombo.domain.NomSatellite.propre(it.satName)
            if (propre != it.satName) { changes++; it.copy(satName = propre) } else it
        }
        if (changes == 0) {
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                profilsEtat = t("sat_noms_propres")))
            return
        }
        logStore.save(neuf)
        _ui.value = _ui.value.copy(log = neuf, carnet = _ui.value.carnet.copy(
            profilsEtat = tf("sat_noms_nettoyes", changes)))
    }

    /** Combien de contacts attendent un carré que QRZ pourrait donner. */
    fun carresManquants(): Int =
        _ui.value.log.count { it.callsign.isNotBlank() && it.theirLocator.isBlank() }

    /**
     * Comble par QRZ les carrés absents du carnet.
     *
     * **Seuls les carrés absents.** Un carré noté à l'oreille pendant le
     * contact vaut mieux qu'un carré d'annuaire : l'autre était peut-être
     * portable, et QRZ donne son domicile. Écraser remplacerait un fait par
     * une présomption.
     */
    /**
     * Éprouve les identifiants QRZ et le dit en clair.
     *
     * Sans ce bouton, un mot de passe faux ne se découvrait qu'au premier
     * contact, au milieu d'un passage — c'est-à-dire au pire moment.
     */
    fun testeQrz() {
        val c = _ui.value.carnet
        if (c.qrzUser.isBlank() || c.qrzMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_test_vide")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_test_cours")))
        viewModelScope.launch {
            val souci = withContext(Dispatchers.IO) { qrz.connecte(c.qrzUser, c.qrzMdp) }
            val dit = if (souci.isNotBlank()) souci else {
                // Une connexion réussie ne prouve pas que l'abonnement XML est
                // actif : on cherche un indicatif pour le vérifier vraiment.
                val f = withContext(Dispatchers.IO) { qrz.cherche(settings.callsign.ifBlank { "F4IOZ" }) }
                if (f.erreur.isNotBlank()) f.erreur else t("qrz_test_ok")
            }
            _ui.value = _ui.value.copy(
                carnet = _ui.value.carnet.copy(qrzEtat = dit))
        }
    }

    fun combleParQrz() {
        val c = _ui.value.carnet
        if (c.qrzEnCours) return
        if (c.qrzUser.isBlank() || c.qrzMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(qrzEtat = t("qrz_identifiants")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(qrzEnCours = true, qrzEtat = t("qrz_connexion")))
        viewModelScope.launch {
            val souci = withContext(Dispatchers.IO) { qrz.connecte(c.qrzUser, c.qrzMdp) }
            if (souci.isNotBlank()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    qrzEnCours = false, qrzEtat = souci.take(140)))
                return@launch
            }
            // On interroge pour **tout ce qui manque**, pas seulement le
            // carré : le carnet d'en face attend aussi le nom, la ville et le
            // courriel, et les remplir un par un dans son écran quand
            // l'annuaire les a déjà n'a pas de sens.
            val aCombler = _ui.value.log
                .filter {
                    it.callsign.isNotBlank() &&
                        (it.theirLocator.isBlank() || it.nom.isBlank() || it.qth.isBlank())
                }
                .map { it.timeMs to it.callsign }
            var trouves = 0
            var dernier = ""
            for ((quand, indicatif) in aCombler) {
                val f = withContext(Dispatchers.IO) { qrz.cherche(indicatif) }
                if (f.erreur.isNotBlank()) {
                    dernier = f.erreur.take(90)
                    // Session perdue ou abonnement absent : insister ne
                    // servirait qu'à répéter la même erreur cent fois.
                    break
                }
                if (f.vide) continue
                // **On ne comble que le vide.** Un carré noté à l'oreille
                // pendant le passage vaut mieux qu'un carré d'annuaire :
                // l'autre était peut-être portable, et QRZ donne son
                // domicile. La même prudence vaut pour le reste.
                var pose = false
                val neuf = _ui.value.log.map { e ->
                    if (e.timeMs != quand) e else {
                        var v = e
                        if (f.carre.isNotBlank() && v.theirLocator.isBlank()) {
                            v = v.copy(theirLocator = f.carre, locatorOrigine = "QRZ")
                            pose = true
                        }
                        if (f.nom.isNotBlank() && v.nom.isBlank()) {
                            v = v.copy(nom = f.nom); pose = true
                        }
                        if (f.qth.isNotBlank() && v.qth.isBlank()) {
                            v = v.copy(qth = f.qth); pose = true
                        }
                        if (f.courriel.isNotBlank() && v.courriel.isBlank()) {
                            v = v.copy(courriel = f.courriel); pose = true
                        }
                        v
                    }
                }
                if (!pose) continue
                logStore.save(neuf)
                _ui.value = _ui.value.copy(log = neuf)
                trouves++
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                qrzEnCours = false,
                qrzEtat = tf("qrz_bilan", trouves, aCombler.size) +
                    (if (dernier.isBlank()) "" else " · " + dernier)),
                // Les carrés neufs doivent rejoindre la mémoire du clavier :
                // c'est elle qui les proposera au passage suivant.
                express = _ui.value.express.copy(memoire = construitMemoire()))
        }
    }

    fun deposeUnContact(timeMs: Long) {
        val c = _ui.value.carnet
        if (c.depotEnCours) return
        if (c.url.isBlank() || c.cle.isBlank() || c.profil.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_depot_config")))
            return
        }
        val e = _ui.value.log.firstOrNull { it.timeMs == timeMs } ?: return
        if (e.callsign.isBlank()) return
        _ui.value = _ui.value.copy(carnet = c.copy(depotEnCours = true, depot = ""))
        viewModelScope.launch {
            val adif = fr.f4ioz.satcombo.data.Adif.enregistrement(e, settings.callsign)
            val profil = fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                e.myLocator, e.myGrids, c.profils, c.profil, c.maille)
            val r = if (adif.isBlank()) ""
                    else fr.f4ioz.satcombo.data.CarnetEnLigne.depose(c.url, c.cle, profil, adif)
            val pris = fr.f4ioz.satcombo.data.CarnetEnLigne.accepte(r)
            if (pris) {
                _ui.value = _ui.value.copy(
                    log = logStore.marqueEnvoye(timeMs, System.currentTimeMillis()))
            }
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                depotEnCours = false,
                // La réponse du serveur est rendue telle quelle : c'est elle
                // qui apprend que la clé est en lecture seule ou que le profil
                // manque, et un « échec » résumé ne l'apprendrait pas.
                depot = if (pris) tf("carnet_depot_un", e.callsign) else r.take(160)))
        }
    }

    /**
     * Dépose au carnet en ligne les contacts qui n'y sont pas encore.
     *
     * Un par un, du plus ancien au plus récent, et **marqué seulement après
     * que le serveur a répondu qu'il l'avait pris**. Wavelog ne dédoublonne
     * pas : marquer d'avance ferait perdre un contact au premier réseau qui
     * flanche, marquer trop large en ferait un doublon à effacer à la main.
     *
     * Le carnet est réécrit après chaque acceptation plutôt qu'à la fin : une
     * coupure au milieu du lot laisse alors le travail déjà fait, au lieu de
     * le refaire au prochain essai.
     */
    fun deposeAuCarnet() {
        val c = _ui.value.carnet
        if (c.depotEnCours) return
        if (c.url.isBlank() || c.cle.isBlank() || c.profil.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_depot_config")))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(depotEnCours = true, depot = ""))
        viewModelScope.launch {
            val fiches = fr.f4ioz.satcombo.domain.EnvoiCarnet.aDeposer(
                _ui.value.log.map {
                    fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.callsign, it.envoyeMs)
                })
            val station = settings.callsign
            val acceptes = HashSet<Long>()
            var refuses = 0
            var dernier = ""
            var doutes = 0
            for (fiche in fiches) {
                val e = _ui.value.log.firstOrNull { it.timeMs == fiche.timeMs } ?: continue
                val adif = fr.f4ioz.satcombo.data.Adif.enregistrement(e, station)
                if (adif.isBlank()) continue
                // Le profil suit l'emplacement d'où le contact a été fait —
                // ligne de carrés comprise ; sans table relevée, on retombe
                // sur le profil unique des réglages.
                val profil = fr.f4ioz.satcombo.domain.ProfilsStation.profilPourEmplacement(
                    e.myLocator, e.myGrids, c.profils, c.profil, c.maille)
                val r = fr.f4ioz.satcombo.data.CarnetEnLigne.depose(c.url, c.cle, profil, adif)
                when (fr.f4ioz.satcombo.data.CarnetEnLigne.issue(r)) {
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.PRIS -> {
                        acceptes.add(fiche.timeMs)
                        _ui.value = _ui.value.copy(
                            log = logStore.marqueEnvoye(fiche.timeMs, System.currentTimeMillis()))
                    }
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.REFUS -> {
                        refuses++
                        dernier = r.take(90)
                        // Trois refus d'affilée : c'est la configuration ou le
                        // serveur, pas ce contact-là. Insister ne ferait
                        // qu'allonger l'attente sans rien déposer.
                        if (refuses >= 3 && acceptes.isEmpty()) break
                    }
                    fr.f4ioz.satcombo.data.CarnetEnLigne.Issue.DOUTE -> {
                        // **Le doute n'arrête pas le lot.** La requête est
                        // partie ; c'est la réponse qui manque. Le contact
                        // reste à déposer — Wavelog écarte les doublons — mais
                        // renoncer au reste du lot parce que le serveur a
                        // tardé une fois serait perdre le passage entier.
                        doutes++
                        dernier = r.take(90)
                    }
                }
            }
            val b = fr.f4ioz.satcombo.domain.EnvoiCarnet.bilan(
                fiches.map { fr.f4ioz.satcombo.domain.EnvoiCarnet.Fiche(it.timeMs, it.indicatif, 0L) },
                acceptes, refuses)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                depotEnCours = false,
                depot = tf("carnet_depot_bilan", b.deposes, b.restants) +
                    (if (doutes > 0) " · " + tf("carnet_depot_doute", doutes) else "") +
                    (if (dernier.isNotBlank()) " · " + dernier else "")))
        }
    }

    /**
     * Le globe réutilise `groundTrack`, déjà calculé pour la carte des
     * passages : deux affichages d'une même donnée lisent la même variable,
     * ils ne refont pas le calcul chacun de leur côté.
     */
    fun ouvreGlobe() { _ui.value = _ui.value.copy(screen = Screen.GLOBE) }

    fun ouvreFt8() { _ui.value = _ui.value.copy(screen = Screen.FT8) }
    /**
     * Quitter l'écran n'arrête pas l'écoute : une tranche dure quinze secondes,
     * et la couper pour consulter le carnet ferait perdre un cycle entier.
     * C'est le bouton de l'écran qui arrête, et lui seul.
     */
    fun fermeFt8() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }
    fun fermeGlobe() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    fun setLotwCall(v: String) {
        settings.lotwCall = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(lotwCall = settings.lotwCall))
    }

    fun setLotwMdp(v: String) {
        settings.lotwMdp = v
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(lotwMdp = v))
    }

    /** Charge ce que LoTW a déjà donné, sans rien demander au réseau. */
    fun chargeLotwLocal() {
        val e = fr.f4ioz.satcombo.data.Lotw.charge(getApplication())
        if (e.travailles.isEmpty() && e.confirmes.isEmpty()) return
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            lotwConfirmes = e.confirmes + carresDeMemoire(e.sansCarre),
            lotwTravailles = e.travailles + carresDeMemoire(e.sansCarre),
            lotwActives = e.activés + mesCarresLocaux()))
    }

    /**
     * Les carrés des correspondants que LoTW n'a pas su situer.
     *
     * LoTW ne rend le carré que si le correspondant l'a déclaré ; trente-trois
     * contacts satellite confirmés d'Olivier n'en ont aucun. La mémoire des
     * indicatifs — carnet, ADIF importé, base embarquée — en connaît une
     * bonne part. Le contact est confirmé de toute façon : lui rendre son
     * carré ne fabrique rien, cela retrouve ce que LoTW a perdu.
     */
    /**
     * Mes carrés d'après le carnet local : LoTW ne connaît que ce qui y a été
     * téléversé, l'activation d'hier n'y est pas encore.
     */
    private fun mesCarresLocaux(): Set<String> =
        _ui.value.log.mapNotNull {
            it.myLocator.uppercase().take(4).takeIf { g -> g.length == 4 }
        }.toSet()

    /**
     * Corrige le satellite — et l'heure — d'un contact enregistré.
     *
     * L'azimut et l'élévation sont **recalculés** pour le nouveau couple
     * satellite/instant : ils décrivent où pointait l'antenne, pas une donnée
     * saisie. Les garder tels quels après un changement de satellite
     * produirait un carnet cohérent en apparence et faux en fond.
     */
    fun corrigeSatellite(timeMs: Long, sat: TleEntry, nouvelleHeure: Long? = null) {
        val quand = nouvelleHeure ?: timeMs
        val obs = _ui.value.observer
        val pos = if (obs != null) runCatching {
            predictor.positionAt(sat, obs, quand)
        }.getOrNull() else null
        val l = logStore.changeSatellite(timeMs, sat.name, sat.catalogNumber,
            pos?.azimuthDeg, pos?.elevationDeg, nouvelleHeure)
        _ui.value = _ui.value.copy(log = l,
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /**
     * Ajoute un contact après coup, à l'heure indiquée.
     *
     * Un contact noté sur un carnet papier pendant le passage se saisit en
     * rentrant : l'heure est celle du contact, pas celle de la saisie, et
     * l'azimut se recalcule à partir d'elle.
     */
    fun ajouteContact(quandMs: Long, sat: TleEntry, indicatif: String, locator: String) {
        // Le public voit le contact au moment où il est validé : c'est le
        // moment fort d'une démonstration, et il ne se raconte pas après coup.
        if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
            fr.f4ioz.satcombo.demo.ServeurDemo.ajouteContact(indicatif, locator, sat.name)
        }
        // Le même garde que la saisie au clavier : sans indicatif, il n'y a pas
        // de contact à inscrire. La boîte de dialogue le sait déjà — son bouton
        // reste éteint — mais la règle appartient à l'écriture, pas au bouton.
        if (indicatif.isBlank()) return
        val obs = _ui.value.observer
        val pos = if (obs != null) runCatching {
            predictor.positionAt(sat, obs, quandMs)
        }.getOrNull() else null
        val e = fr.f4ioz.satcombo.data.LogEntry(
            timeMs = quandMs,
            satName = sat.name,
            catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0,
            elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) }.orEmpty(),
            callsign = indicatif.trim().uppercase(),
            theirLocator = locator.trim().uppercase())
        val l = logStore.add(e)
        _ui.value = _ui.value.copy(log = l,
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /**
     * Les indicatifs déjà travaillés pendant le passage en cours.
     *
     * Sert à ne pas rappeler deux fois la même station — ce qui, sur un
     * transpondeur encombré, coûte du temps à tout le monde. Encore faut-il
     * que « ce passage » veuille dire quelque chose : la règle est au domaine,
     * avec son banc, parce qu'elle a été fausse deux fois. Ici, on ne fait que
     * lui donner la fenêtre AOS–LOS calculée pour le satellite affiché.
     */
    fun indicatifsDuPassage(): List<String> {
        val choisi = _ui.value.selected ?: return emptyList()
        val sat = choisi.name
        val maintenant = System.currentTimeMillis()
        val obs = _ui.value.observer
        // Le vrai début du passage, calculé pour ce satellite-ci. La liste des
        // passages sert de recours quand on n'a pas de position d'observation :
        // sa fenêtre peut être tronquée en arrière, mais tronquée vaut mieux
        // qu'absente.
        val fenetre = obs?.let {
            runCatching { predictor.currentPass(choisi, it, maintenant) }.getOrNull()
        }?.let { fr.f4ioz.satcombo.domain.Passage.Fenetre(it.first, it.second) }
            ?: fr.f4ioz.satcombo.domain.Passage.enCours(
                _ui.value.passes.map {
                    fr.f4ioz.satcombo.domain.Passage.Fenetre(it.aosEpochMs, it.losEpochMs)
                },
                maintenant)
        return fr.f4ioz.satcombo.domain.Passage.indicatifs(
            _ui.value.log.map {
                fr.f4ioz.satcombo.domain.Passage.Inscrit(
                    it.timeMs, it.satName, it.elevationDeg, it.callsign)
            },
            sat, fenetre)
    }

    fun basculeListeContacts() {
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
            listeContactsOuverte = !_ui.value.carte.listeContactsOuverte))
    }

    /**
     * Enregistre un contact saisi au clavier, ici et maintenant.
     *
     * Le chemin direct : pas de tampon à retrouver, pas de file à faire
     * avancer, pas d'entrée dont hériter le satellite ou l'heure. Le contact
     * porte le satellite affiché, l'instant de la validation, et la géométrie
     * calculée pour cet instant.
     *
     * **C'est désormais le seul chemin qui écrit au carnet**, et il doit donc
     * porter tout ce que portait le relevé d'un appui sur la boussole : le mode
     * d'émission, les deux fréquences au repos, les carrés revendiqués. Ces
     * champs-là ne se retrouvent pas le soir venu — ils décrivent l'état du
     * poste à l'instant du contact — et sans eux l'ADIF part amputé de BAND, de
     * MODE et de SAT_MODE.
     */
    fun ajouteContactDirect(
        sat: TleEntry, indicatif: String, locator: String,
        rstEnvoye: String, rstRecu: String, origine: String,
    ) {
        val call = indicatif.trim().uppercase()
        if (call.isEmpty()) return
        // Le clavier d'indicatifs est le chemin réel du terrain : c'est celui
        // qu'il fallait brancher. Le premier branchement ne couvrait que la
        // saisie manuelle des réglages, que personne n'utilise en passage.
        if (fr.f4ioz.satcombo.demo.ServeurDemo.etat.value.actif) {
            fr.f4ioz.satcombo.demo.ServeurDemo.ajouteContact(call, locator, sat.name)
        }
        val maintenant = System.currentTimeMillis()
        val obs = _ui.value.observer
        val pos = _ui.value.livePosition ?: obs?.let {
            runCatching { predictor.positionAt(sat, it, maintenant) }.getOrNull()
        }
        // Le transpondeur en cours donne le mode et les deux fréquences : c'est
        // ce que le carnet d'en face attend dans MODE, BAND et SAT_MODE, et
        // c'est l'instant où l'information est encore sûre.
        val tx = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val e = fr.f4ioz.satcombo.data.LogEntry(
            timeMs = maintenant,
            satName = sat.name, catnum = sat.catalogNumber,
            azimuthDeg = pos?.azimuthDeg ?: 0.0,
            elevationDeg = pos?.elevationDeg ?: 0.0,
            myLocator = obs?.let { Maidenhead.fromLatLon(it.latDeg, it.lonDeg) }
                ?: _ui.value.manualLocator,
            // Depuis une ligne de carrés, le contact compte dans les deux : la
            // revendication ne tient que si le carnet le dit à l'heure dite.
            myGrids = myGridsCsv(),
            callsign = call,
            theirLocator = locator.trim().uppercase(),
            rstSent = rstEnvoye, rstRcvd = rstRecu,
            locatorOrigine = origine,
            mode = modeEmission(tx),
            downlinkMhz = descenteAuRepos()?.let { it / 1_000_000.0 } ?: 0.0,
            uplinkMhz = monteeAuRepos()?.let { it / 1_000_000.0 } ?: 0.0)
        _ui.value = _ui.value.copy(log = logStore.add(e),
            express = _ui.value.express.copy(memoire = construitMemoire()))
        // Un enregistrement en cours reçoit un repère : le contact se retrouve
        // à la bonne seconde quand on réécoute le passage.
        if (_ui.value.recording) {
            fr.f4ioz.satcombo.audio.RecorderService.addMarker(
                tf("qso_marker_call", call, sat.name, (pos?.elevationDeg ?: 0.0).toInt()))
        }
    }

    fun setPeindreCarres(on: Boolean) {
        settings.peindreCarres = on
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(peindre = on))
    }

    private fun carresDeMemoire(indicatifs: Set<String>): Set<String> {
        if (indicatifs.isEmpty()) return emptySet()
        val memoire = _ui.value.express.memoire
        if (memoire.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (ind in indicatifs) {
            val cle = fr.f4ioz.satcombo.domain.Indicatifs.cle(ind)
            val c = memoire.firstOrNull { it.indicatif == cle }
                ?: memoire.firstOrNull {
                    fr.f4ioz.satcombo.domain.Indicatifs.base(it.indicatif) ==
                        fr.f4ioz.satcombo.domain.Indicatifs.base(ind)
                }
            val g = c?.locatorPrincipal.orEmpty().uppercase().take(4)
            if (g.length == 4) out.add(g)
        }
        return out
    }

    /**
     * Va chercher le journal chez LoTW.
     *
     * Un téléchargement complet, à la demande : LoTW ne répond pas carré par
     * carré, et le fichier peut être long. On ne le fait donc pas tout seul.
     */
    fun rafraichisLotw() {
        val c = _ui.value.carnet
        if (c.lotwCall.isBlank() || c.lotwMdp.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(lotwEtat = "indicatif et mot de passe"))
            return
        }
        _ui.value = _ui.value.copy(carnet = c.copy(lotwEtat = "téléchargement…"))
        viewModelScope.launch {
            val r = fr.f4ioz.satcombo.data.Lotw.rafraichis(
                getApplication(), c.lotwCall, c.lotwMdp)
            val e = fr.f4ioz.satcombo.data.Lotw.charge(getApplication())
            val retrouves = carresDeMemoire(e.sansCarre)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                lotwEtat = r + (if (retrouves.isNotEmpty())
                    " · +${retrouves.size} par la mémoire" else ""),
                lotwConfirmes = e.confirmes + retrouves,
                lotwTravailles = e.travailles + retrouves,
                lotwActives = e.activés + mesCarresLocaux()))
        }
    }

    fun essaieCarnet() {
        val c = _ui.value.carnet
        _ui.value = _ui.value.copy(carnet = c.copy(essai = "…"))
        viewModelScope.launch {
            val r = fr.f4ioz.satcombo.data.CarnetEnLigne.essai(c.url, c.cle, c.slug)
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(essai = r))
        }
    }

    /**
     * Interroge le carnet pour une poignée de carrés.
     *
     * Un carré à la fois et jamais deux fois le même : le cache du connecteur
     * s'en charge, et la documentation demande expressément de ne pas
     * marteler ces routes.
     */
    fun demandeCarres(liste: List<String>) {
        val c = _ui.value.carnet
        if (!c.configure) return
        val aFaire = liste.map { it.uppercase() }.distinct()
            .filter { it.isNotBlank() && !c.carres.containsKey(it) }
        if (aFaire.isEmpty()) return
        viewModelScope.launch {
            for (k in aFaire) {
                val e = fr.f4ioz.satcombo.data.CarnetEnLigne.carre(c.url, c.cle, c.slug, k)
                if (e != fr.f4ioz.satcombo.data.CarnetEnLigne.Etat.INCONNU) {
                    _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                        carres = _ui.value.carnet.carres + (k to e)))
                }
            }
        }
    }

    fun setTxSuitVite(on: Boolean) {
        settings.txSuitVite = on
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txSuitVite = on))
    }

    fun setSondeTxMs(v: Int) {
        settings.sondeTxMs = v
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(sondeMs = settings.sondeTxMs))
    }

    fun setPhotoQrgTexte(v: String) {
        settings.photoQrgTexte = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgTexte = settings.photoQrgTexte))
    }

    fun setPhotoPassScale(v: Float) {
        settings.photoPassScale = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(passScale = settings.photoPassScale))
    }

    fun setPhotoQrgScale(v: Float) {
        settings.photoQrgScale = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(qrgScale = settings.photoQrgScale))
    }

    fun setPhotoPotaNom(on: Boolean) {
        settings.photoPotaNom = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(potaNomAffiche = on))
    }

    fun setPhotoFondUni(v: Int) {
        settings.photoFondUni = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(fondUni = v))
    }

    fun setPhotoCarteFondu(on: Boolean) {
        settings.photoCarteFondu = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(fondu = on))
    }

    fun setPhotoCarteCouleur(v: Int) {
        settings.photoCarteCouleur = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(couleur = v))
    }

    fun setPotaBandeauAccueil(on: Boolean) {
        settings.potaBandeauAccueil = on
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(bandeauAccueil = on))
    }

    fun setPhotoCarteRemplissage(v: String) {
        settings.photoCarteRemplissage = v
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(remplissage = v))
    }

    /**
     * Charge la silhouette du pays où l'on se trouve.
     *
     * Le catalogue pèse 463 Ko : on le lit hors du fil principal, une seule
     * fois, et seulement si l'opérateur demande la carte. Qui ne s'en sert pas
     * ne paie rien.
     */
    fun chargeCartePays() {
        val obs = _ui.value.observer ?: return
        // Si le pays chargé contient déjà la position, rien à relire : l'effet
        // de l'écran Photo rappelle cette fonction à chaque point GPS, et le
        // catalogue pèse 463 Ko.
        val c = _ui.value.carte
        if (c.anneaux.isNotEmpty() &&
            c.anneaux.any { fr.f4ioz.satcombo.domain.Pays.dansAnneau(it, obs.latDeg, obs.lonDeg) }
        ) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    fr.f4ioz.satcombo.data.PaysStore.autour(getApplication(), obs.latDeg, obs.lonDeg)
                }.getOrNull()
            }
            // Pays introuvable : on efface plutôt que de garder l'ancien.
            // Une silhouette périmée est pire qu'un message — elle a l'air
            // juste.
            _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(
                anneaux = r?.second ?: emptyList(),
                paysNom = r?.first?.nom ?: ""))
        }
    }

    fun fermeNommage() {
        _ui.value = _ui.value.copy(screen = Screen.PASSES)
    }

    /**
     * La mémoire des correspondants : le carnet local, plus l'index importé.
     *
     * Reconstruite en entier plutôt que tenue à jour au fil de l'eau. Dix mille
     * contacts se replient en quelques millisecondes, et une mémoire
     * reconstruite d'un bloc ne peut pas diverger de la source — un index
     * entretenu par incréments finit toujours par le faire.
     */
    private fun construitMemoire(): List<fr.f4ioz.satcombo.domain.Indicatifs.Connu> {
        val locaux = _ui.value.log.filter { it.callsign.isNotBlank() }.map {
            fr.f4ioz.satcombo.domain.Indicatifs.Contact(
                indicatif = it.callsign, locator = it.theirLocator,
                quandMs = it.timeMs, satellite = it.satName)
        }
        // La base interne vient EN DERNIER : à indicatif égal, le carnet local
        // et l'ADIF importé par l'opérateur ont déjà parlé, la base ne fait
        // qu'ajouter ce qu'ils ignorent.
        // Deux verrous : l'extension ADIF d'abord — le carnet d'Olivier ne se
        // sert pas d'office —, l'interrupteur des réglages ensuite.
        return fr.f4ioz.satcombo.domain.Indicatifs.memoire(locaux + indexImporte)
    }

    private var indexImporte: List<fr.f4ioz.satcombo.domain.Indicatifs.Contact> = emptyList()

    /**
     * La base embarquée a été retirée.
     *
     * Elle portait 3 763 contacts du carnet F4IOZ, soit 294 Ko dans chaque
     * installation. Depuis que le clavier se nourrit directement du carnet en
     * ligne, elle faisait double emploi : chacun rapatrie son propre carnet,
     * plus à jour et plus pertinent que celui d'un autre opérateur.
     *
     * Le réglage qui la commandait a disparu avec elle : garder un
     * interrupteur sans rien derrière est la meilleure façon de faire croire
     * qu'une fonction existe encore.
     */

    /**
     * Oublie tout ce qui a été rapatrié dans la mémoire du clavier.
     *
     * **Ne touche pas au carnet.** Les contacts que l'opérateur a lui-même
     * enregistrés restent : seul l'index importé — ADIF ou moisson Wavelog —
     * est effacé. Le curseur de moisson repart à zéro, sans quoi le prochain
     * rapatriement ne reprendrait qu'à partir du dernier contact vu et
     * laisserait la mémoire à moitié vide.
     */
    fun purgeIndexImporte() {
        indexImporte = emptyList()
        runCatching { indexStore.enregistre(emptyList()) }
        settings.carnetDernierId = 0L
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()),
            carnet = _ui.value.carnet.copy(depot = t("express_purge_faite")))
    }

    fun importeAdif(texte: String): fr.f4ioz.satcombo.data.AdifImport.Bilan {
        val bilan = fr.f4ioz.satcombo.data.AdifImport.lit(texte)
        indexImporte = bilan.contacts
        runCatching { indexStore.enregistre(bilan.contacts) }
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()))
        return bilan
    }

    /**
     * Va chercher chez Wavelog de quoi nourrir le clavier.
     *
     * Le clavier propose des indicatifs d'après ce qu'on a déjà travaillé.
     * Cette mémoire ne connaissait que le carnet local et ce qu'on avait
     * importé à la main : un téléphone neuf, ou le second téléphone, partait
     * donc sans rien. Or Wavelog **sait déjà tout** — c'est le carnet de
     * référence, alimenté par les deux appareils.
     *
     * Chargement différentiel : on repart du dernier contact rapatrié. Le
     * point d'entrée est fait pour cela, et redemander tout le journal à
     * chaque fois heurterait les limites de débit de l'instance.
     *
     * L'index seul est conservé — indicatif, carré, date, satellite. Le
     * carnet local reste seul maître de ses contacts : aucune règle de fusion
     * à trancher, aucune correction locale à écraser.
     */
    /** L'ensemble demandé, sous une forme stable et comparable. */
    private fun profilsDemandes(
        c: fr.f4ioz.satcombo.CarnetUi, choisis: List<String>
    ): String = choisis.ifEmpty {
        c.profils.values.filterNotNull().distinct()
            .ifEmpty { listOf(c.profil).filter { it.isNotBlank() } }
    }.sorted().joinToString(",")

    fun moissonneCarnet(choisis: List<String> = emptyList()) {
        val c = _ui.value.carnet
        if (c.url.isBlank() || c.cle.isBlank()) {
            _ui.value = _ui.value.copy(carnet = c.copy(depot = t("carnet_reglages")))
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(depotEnCours = true))
            // **Changer d'ensemble de profils remet le curseur à zéro.**
            //
            // Le curseur repère le dernier contact rapatrié ; il n'a de sens
            // que pour un ensemble donné. Rapatrier d'un nouveau profil en
            // repartant du curseur de l'ancien sauterait tout son historique,
            // et l'opérateur ne s'en apercevrait qu'aux carrés manquants.
            val empreinte = profilsDemandes(c, choisis)
            if (empreinte != settings.carnetProfilsVus) {
                settings.carnetDernierId = 0L
                settings.carnetProfilsVus = empreinte
            }
            val depuisId = settings.carnetDernierId
            // Tous les emplacements relevés, à défaut le profil unique : la
            // mémoire du clavier n'a pas à s'arrêter à un seul carré.
            // Les profils que l'opérateur a cochés ; à défaut, tous ceux qui
            // sont connus, à défaut encore le profil unique saisi à la main.
            val profils = choisis.ifEmpty {
                c.profils.values.filterNotNull().distinct()
                    .ifEmpty { listOf(c.profil).filter { it.isNotBlank() } }
            }
            // **Sans profil, on n'appelle pas.**
            //
            // Une liste vide partait comme `station_id: []`, et le serveur
            // répondait « HTTP 400 » — un code qui désigne le corps de la
            // requête et laisse croire à une panne de connexion, alors que
            // « Tester » venait de passer. C'est un réglage manquant, et il
            // faut le dire comme tel.
            if (profils.isEmpty()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false, depot = t("carnet_sans_profil")))
                return@launch
            }
            val m = runCatching {
                fr.f4ioz.satcombo.data.CarnetEnLigne.moissonne(
                    c.url, c.cle, profils, depuisId, settings.carnetFiltre)
            }.getOrElse {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
                    depot = it.message ?: it.javaClass.simpleName))
                return@launch
            }
            if (m.adif.isBlank()) {
                _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
                    depot = m.message.ifBlank { t("carnet_moisson_rien") }))
                return@launch
            }
            // On **ajoute** à l'index plutôt que de le remplacer : une moisson
            // différentielle ne rend que les nouveaux, et repartir d'eux seuls
            // effacerait tout l'historique déjà connu.
            val bilan = fr.f4ioz.satcombo.data.AdifImport.lit(m.adif, settings.carnetFiltre)
            // Les indicatifs que le clavier ne connaissait pas encore : c'est
            // la seule mesure de ce qu'il a gagné. Trente-huit QSO avec le même
            // correspondant n'ajoutent qu'une entrée à sa mémoire, et annoncer
            // les contacts laisserait croire à un enrichissement qui n'a pas eu
            // lieu. Le compte se fait **avant** la fusion, faute de quoi il n'y
            // aurait plus rien à comparer.
            val connus = indexImporte.mapTo(HashSet()) { it.indicatif }
            val nouveaux = bilan.contacts.mapTo(HashSet()) { it.indicatif }
                .count { it !in connus }
            val fusion = (indexImporte + bilan.contacts)
                .associateBy { it.indicatif + "|" + it.quandMs }.values.toList()
            indexImporte = fusion
            runCatching { indexStore.enregistre(fusion) }
            settings.carnetDernierId = m.dernierId
            _ui.value = _ui.value.copy(
                express = _ui.value.express.copy(memoire = construitMemoire()),
                carnet = _ui.value.carnet.copy(
                    depotEnCours = false,
// **Dire ce que les chiffres veulent dire.**
                    //
                    // « Zéro nouveau » avait fait croire à une panne alors que
                    // tout marchait : les contacts rapatriés concernaient des
                    // correspondants déjà connus du clavier. Le compte-rendu
                    // annonçait des nombres sans les interpréter, et un nombre
                    // nu laisse toujours craindre le pire.
                    //
                    // Le curseur reste affiché en petit : c'est lui qui dit si
                    // une moisson est différentielle ou repart de zéro, et il a
                    // servi une fois à trancher.
                    depot = (if (m.nombre == 0) t("carnet_moisson_ajour")
                             else tf("carnet_moisson", m.nombre, bilan.retenus,
                                 nouveaux, fusion.distinctBy { it.indicatif }.size)) +
                        "\n\n" + tf("carnet_moisson_curseur", depuisId, m.dernierId)))
        }
    }

    /**
     * Change ce qu'on moissonne, et **remet le compteur différentiel à zéro**.
     *
     * Sans cette remise, passer de « satellite » à « tout » ne rapporterait
     * que les contacts postérieurs au dernier appel : tout l'historique HF
     * resterait invisible, et l'on croirait le filtre inopérant. Le défaut
     * serait silencieux, et durable.
     */
    fun setCarnetFiltre(f: String) {
        settings.carnetFiltre = f
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            filtre = f, depot = t("carnet_moisson_remise")))
    }

    /** Repart de zéro : la moisson suivante rechargera tout le journal. */
    fun oublieMoisson() {
        settings.carnetDernierId = 0L
        _ui.value = _ui.value.copy(carnet = _ui.value.carnet.copy(
            depot = t("carnet_moisson_remise")))
    }

    private val indexStore = fr.f4ioz.satcombo.data.IndexImporte(app)

    init {
        // L'index importé survit à la fermeture : le relire au démarrage est ce
        // qui le rend digne de confiance.
        runCatching { indexImporte = indexStore.charge() }
        // **La mémoire du clavier se construit au démarrage.**
        //
        // Elle était bâtie par `rafraichitFile()`, appelée ici pour remplir la
        // file d'attente ; la construction de la mémoire y avait été greffée
        // parce que les deux se faisaient au même moment. En 19.11 la file a
        // disparu, la fonction avec elle, et la mémoire est partie dans le même
        // mouvement — sans que rien ne le signale, puisque aucun essai ne
        // couvre le démarrage.
        //
        // Le clavier ouvrait donc un carnet vide après chaque mise à jour :
        // ni ADIF importé, ni base interne, aucune suggestion. Il fallait aller
        // dans les réglages éteindre puis rallumer la base pour que
        // `setBaseInterneIndicatifs` la reconstruise. Ce qu'un écran de
        // réglages sait faire, le démarrage doit savoir le faire.
        runCatching {
            _ui.value = _ui.value.copy(
                express = _ui.value.express.copy(memoire = construitMemoire()))
        }
        runCatching { veilleSuiviPosition() }
    }

    fun updateLogEntry(
        timeMs: Long, callsign: String, theirLocator: String, note: String,
        mode: String = "", rstSent: String = "", rstRcvd: String = ""
    ) {
        _ui.value = _ui.value.copy(
            log = logStore.update(timeMs, callsign.trim().uppercase(),
                theirLocator.trim().uppercase(), note.trim(),
                mode.trim(), rstSent.trim(), rstRcvd.trim()))
    }

    fun deleteLogEntry(timeMs: Long) {
        _ui.value = _ui.value.copy(log = logStore.delete(timeMs))
    }

    fun logAdif(): String = logStore.toAdif(settings.callsign)

    /**
     * Écrit le carnet dans un vrai fichier .adi et rend son URI partageable.
     * Coller de l'ADIF dans un courriel marche, mais beaucoup de carnets
     * n'acceptent qu'un fichier — et le texte collé perd ses retours à la
     * ligne dès qu'une application se croit maligne.
     */
    fun adifFileUri(entries: List<LogEntry>? = null, tag: String = "carnet"): android.net.Uri? {
        val app = getApplication<android.app.Application>()
        val text = LogStore.toAdif(entries ?: _ui.value.log, settings.callsign)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd'_'HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val safe = tag.replace(Regex("[^A-Za-z0-9_-]"), "-")
        return runCatching {
            val dir = java.io.File(app.cacheDir, "export").apply { mkdirs() }
            val f = java.io.File(dir, "SatMe_${safe}_$stamp.adi")
            f.writeText(text)
            androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", f)
        }.getOrNull()
    }

    // ================= station identity / QRV photo =================

    fun setCallsign(cs: String) {
        settings.callsign = cs
        // L'indicatif est aussi une clé : F4IOZ ouvre les fonctions en bêta.
        _ui.value = _ui.value.copy(
            callsign = settings.callsign,
            extensions = fr.f4ioz.satcombo.data.Extensions.unlocked(
                settings.callsign, settings.extensionsCode))
    }

    /**
     * Enregistre le champ « Extensions » et recalcule ce qui est ouvert.
     *
     * Si l'opérateur referme une extension pendant qu'il est dessus, on le
     * ramène à la liste des passages : rester sur un écran devenu invisible
     * dans le menu serait un piège, le bouton retour marcherait mais rien
     * n'indiquerait comment y revenir.
     */
    fun setExtensionsCode(code: String) {
        settings.extensionsCode = code
        val ext = fr.f4ioz.satcombo.data.Extensions.unlocked(settings.callsign, settings.extensionsCode)
        val u = _ui.value
        val lost =
            (u.screen == Screen.SSTV && fr.f4ioz.satcombo.data.Extensions.SSTV !in ext) ||
            (u.screen == Screen.SDR && fr.f4ioz.satcombo.data.Extensions.SDR !in ext)
        if (u.screen == Screen.SDR && fr.f4ioz.satcombo.data.Extensions.SDR !in ext) stopSdr()
        _ui.value = u.copy(
            extensionsCode = settings.extensionsCode,
            extensions = ext,
            screen = if (lost) Screen.PASSES else u.screen)
        // L'extension ADIF ouvre ou ferme la base embarquée : la mémoire du
        // clavier suit tout de suite, pas au prochain démarrage.
        _ui.value = _ui.value.copy(
            express = _ui.value.express.copy(memoire = construitMemoire()))
    }

    /** Vrai si la fonction en bêta [name] est ouverte pour cet opérateur. */
    fun hasExtension(name: String): Boolean = name in _ui.value.extensions

    /**
     * Dismisses the start-up reminder. [never] records that the operator does
     * not want to be asked again — some users run the app purely as a viewer.
     */
    fun dismissCallsignPrompt(never: Boolean) {
        if (never) settings.callsignPromptOff = true
        _ui.value = _ui.value.copy(askCallsign = false)
    }

    /** Distance (m) under which a neighbouring grid square is announced. */
    fun setNearGridMeters(m: Int) {
        settings.nearGridMeters = m
        _ui.value = _ui.value.copy(nearGridMeters = settings.nearGridMeters)
    }

    /**
     * Combien des huit carrés voisins sont écrits sur la photo QRV. Un centre
     * de carré n'en veut aucun, un coin à quatre carrés en veut au moins
     * quatre, et un opérateur qui chasse le carré les veut tous.
     */
    fun setPhotoNearCount(n: Int) {
        settings.photoNearCount = n
        _ui.value = _ui.value.copy(photoNearCount = settings.photoNearCount)
    }

    /**
     * Le système d'unités. Un OM anglo-saxon ne convertit pas de tête pendant
     * un passage de trois minutes ; celui qui chasse un ballon raisonne en
     * milles nautiques et en nœuds.
     */
    fun setUnits(v: String) {
        settings.units = v
        _ui.value = _ui.value.copy(units = settings.units)
    }

    /** Drapeau placé devant l'indicatif sur la photo QRV. Vide = aucun. */
    fun setPhotoFlag(code: String) {
        settings.photoFlag = code
        _ui.value = _ui.value.copy(photoFlag = settings.photoFlag)
    }

    /** Drapeau placé à droite de l'indicatif, choisi dans le même catalogue. */
    fun setPhotoFlagRight(code: String) {
        settings.photoFlagRight = code
        _ui.value = _ui.value.copy(carte = _ui.value.carte.copy(flagRight = settings.photoFlagRight))
    }

    fun setPhotoPolarScale(v: Float) {
        settings.photoPolarScale = v
        _ui.value = _ui.value.copy(photoPolarScale = settings.photoPolarScale)
    }

    /** Taille du nom du satellite inscrit sous le tracé polaire. */
    fun setPhotoSatLabelScale(v: Float) {
        settings.photoSatLabelScale = v
        _ui.value = _ui.value.copy(photoSatLabelScale = settings.photoSatLabelScale)
    }

    /** Couleur de l’indicatif sur la photo QRV (ARGB opaque). */
    fun setPhotoCallColor(argb: Int) {
        settings.photoCallColor = argb
        _ui.value = _ui.value.copy(photoCallColor = settings.photoCallColor)
    }

    /** Taille de l’indicatif sur la photo QRV. Bornée par le réglage. */
    fun setPhotoCallScale(v: Float) {
        settings.photoCallScale = v
        _ui.value = _ui.value.copy(photoCallScale = settings.photoCallScale)
    }

    /**
     * Neighbouring BIG squares within the configured distance, closest first.
     * The search is always run on the 4-character squares — that is the line an
     * operator actually announces — and only the spelling follows the display
     * precision: "JN19" in short mode, "JN19av" in full mode.
     */
    fun nearLocators(): List<String> {
        val s = _ui.value
        if (s.nearGridMeters <= 0) return emptyList()
        val obs = s.observer ?: return emptyList()
        return runCatching {
            Maidenhead.neighbourFields(obs.latDeg, obs.lonDeg, s.nearGridMeters.toDouble())
                .map { (square, full) -> if (s.photoLoc4) square else full }
        }.getOrDefault(emptyList())
    }

    /**
     * Every big square the station legitimately sits in: ours first, then the
     * neighbours within the configured distance. This is the list that follows
     * the QSO into the log, the ADIF file and the activation sheet — always
     * 4-character squares, since that is what a grid claim is made of.
     */
    fun myGridSquares(): List<String> {
        val mine = myLocator().take(4).uppercase()
        val s = _ui.value
        val obs = s.observer
        val near = if (s.nearGridMeters <= 0 || obs == null) emptyList()
        else runCatching {
            Maidenhead.neighbourFields(obs.latDeg, obs.lonDeg, s.nearGridMeters.toDouble())
                .map { it.first }
        }.getOrDefault(emptyList())
        return (listOf(mine) + near.take(3)).filter { it.length == 4 }.distinct()
    }

    /** The same list, comma-separated, as stored in a log entry. */
    fun myGridsCsv(): String {
        val g = myGridSquares()
        return if (g.size <= 1) "" else g.joinToString(",")
    }

    /** The locator at the chosen precision, plus the squares within reach. */
    fun myLocatorFull(): String {
        val base = if (_ui.value.photoLoc4) myLocator().take(4) else myLocator()
        val near = nearLocators()
        return if (near.isEmpty()) base
        // Up to three: a site pinned in the corner of four big squares really
        // does belong to four locators, and the operator announces them all.
        else base + " / " + near.take(3).joinToString(" / ")
    }

    // ---- AMSAT Live OSCAR Status reporting ----

    /**
     * Reports a satellite as heard (or not) on the AMSAT status page. Only ever
     * called with a callsign set: an anonymous report is worthless to the page
     * and would pollute the table.
     */
    fun submitAmsatStatus(satName: String, heard: Boolean) {
        val s = _ui.value
        if (s.callsign.isBlank() || satName.isBlank()) return
        // Use the name AMSAT itself publishes when we can match it, so the
        // report lands on the right row ("RS-44", "AO-91"…).
        val amsatName = amsatMatch(satName, s.amsatReports)?.name ?: satName
        _ui.value = _ui.value.copy(amsatSubmitState = "busy")
        viewModelScope.launch {
            val ok = fr.f4ioz.satcombo.data.AmsatSubmit.send(
                satName = amsatName,
                report = if (heard) fr.f4ioz.satcombo.data.AmsatSubmit.Report.HEARD
                else fr.f4ioz.satcombo.data.AmsatSubmit.Report.NOT_HEARD,
                callsign = s.callsign,
                locator = myLocator())
            _ui.value = _ui.value.copy(amsatSubmitState = if (ok) "ok" else "fail")
            if (ok) refreshAmsatStatus(force = true)
        }
    }

    fun clearAmsatSubmitState() {
        if (_ui.value.amsatSubmitState.isNotBlank())
            _ui.value = _ui.value.copy(amsatSubmitState = "")
    }

    /** Toggles one overlay option of the QRV photo ("call", "date", …). */
    fun setPhotoOption(key: String, on: Boolean) {
        when (key) {
            "call" -> { settings.photoShowCallsign = on; _ui.value = _ui.value.copy(photoShowCallsign = on) }
            "date" -> { settings.photoShowDate = on; _ui.value = _ui.value.copy(photoShowDate = on) }
            "grids" -> { settings.photoShowGrids = on; _ui.value = _ui.value.copy(photoShowGrids = on) }
            "coords" -> { settings.photoShowCoords = on; _ui.value = _ui.value.copy(photoShowCoords = on) }
            "sat" -> { settings.photoShowSat = on; _ui.value = _ui.value.copy(photoShowSat = on) }
            "polar" -> { settings.photoShowPolar = on; _ui.value = _ui.value.copy(photoShowPolar = on) }
            "pass" -> { settings.photoShowPass = on; _ui.value = _ui.value.copy(photoShowPass = on) }
            "loc4" -> { settings.photoLoc4 = on; _ui.value = _ui.value.copy(photoLoc4 = on) }
            "alt" -> { settings.photoShowAlt = on; _ui.value = _ui.value.copy(photoShowAlt = on) }
        }
    }

    /**
     * The SatMe mark is the app's signature on every picture that leaves the
     * phone, so it is not up for negotiation — except on the author's own
     * station, which needs pictures without it for the store listing.
     */
    // `estAuteur()` a été retirée : la section « Lieu » qu'elle gardait est
    // ouverte à tous depuis la 20.58. Une garde sans rien derrière finit par
    // servir à autre chose que ce pour quoi elle avait été écrite.

    /**
     * Le code du drapeau, si le trousseau l'autorise.
     *
     * [gated] dit si l'emplacement demande encore le mot « drapeau » : celui de
     * gauche ne le demande plus, celui de droite si. Le filtre BZH, lui, reste
     * en place des deux côtés — le breton et le bigouden ne sont pas des
     * drapeaux nationaux, et un réglage hérité ne doit pas ressortir sur la
     * photo de quelqu'un qui ne les a pas demandés.
     */
    private fun photoFlagOrNothing(s: UiState, code: String, gated: Boolean): String {
        if (gated && fr.f4ioz.satcombo.data.Extensions.FLAG !in s.extensions) return ""
        val bzh = fr.f4ioz.satcombo.data.Extensions.BZH in s.extensions
        return if (fr.f4ioz.satcombo.data.Flags.allowed(code, bzh)) code else ""
    }

    /**
     * Opens the QRV photo page. [catnum] is the satellite the picture is about:
     * passed explicitly from the satellite screen, left null when the page is
     * opened from the global menu (the operator then picks it on the page).
     */
    fun openPhoto(catnum: Int? = null, passAosMs: Long? = null) {
        val from = _ui.value.screen
        // Arriving with a satellite in hand — tapped in the header, or simply
        // the one open behind the menu — the page is about THAT satellite. The
        // kept picture reopens as a background, but it no longer drags its own
        // satellite and pass back in on top of the choice just made.
        val pinned = catnum != null || _ui.value.selected != null
        _ui.value = _ui.value.copy(
            screen = Screen.PHOTO,
            photoPinnedSat = pinned,
            photoReturn = if (from == Screen.PHOTO) _ui.value.photoReturn else from)
        // The satellite the operator is looking at wins over whatever the photo
        // page was left on: coming from a satellite screen, the picture is about
        // THAT satellite, not the one used the last time the page was opened.
        val cat = catnum ?: _ui.value.selected?.catalogNumber ?: _ui.value.photoSatCat
        // Arriving from a pass page, the picture is about the pass being looked
        // at — the one prepared for tonight as much as the one just worked. The
        // operator has already chosen; the page must not choose again for him.
        val want = passAosMs ?: _ui.value.focusedPassAos
        if (cat != null) setPhotoSat(cat, want)
        else if (_ui.value.photoSatCat == null) setPhotoSat(null)
    }

    // ---- Mise a jour : on detecte, le Play Store fait le reste ----

    /**
     * Le Store a une version plus recente. Proposee une fois par version : un
     * « plus tard » est retenu, donc on ne harcele jamais deux fois pour la
     * meme.
     */
    fun updateFound(code: Int) {
        if (code <= settings.updateSkipped) return
        _ui.value = _ui.value.copy(updateCode = code, updateMsg = "")
    }

    /** Quelque chose n'a pas marche, et il faut le dire. Une version ratee
     *  n'est jamais marquee comme vue : elle doit repasser. */
    fun updateFailed(msg: String) {
        settings.updateSkipped = 0
        _ui.value = _ui.value.copy(updateCode = 0, updateMsg = msg)
    }

    /** Ligne d'etat toute simple (a jour, pas de Store...), affichee puis
     *  balayee d'un doigt. */
    fun updateSay(msg: String) { _ui.value = _ui.value.copy(updateMsg = msg) }
    fun updateMsgClear() { _ui.value = _ui.value.copy(updateMsg = "") }

    /** Verification manuelle : on oublie le « plus tard » pour que la meme
     *  version puisse etre reproposee. */
    fun updateForget() {
        settings.updateSkipped = 0
        _ui.value = _ui.value.copy(updateMsg = "")
    }

    /**
     * On part vers la fiche du Store. La version est classee traitee : si
     * l'operateur revient sans avoir mis a jour, on ne lui remet pas la
     * fenetre au nez a chaque fois qu'il rouvre l'application. La prochaine
     * version publiee, elle, sera bien annoncee.
     */
    fun updateOpened() {
        if (_ui.value.updateCode > 0) settings.updateSkipped = _ui.value.updateCode
        _ui.value = _ui.value.copy(updateCode = 0, updateMsg = "")
    }

    fun updateLater() {
        if (_ui.value.updateCode > 0) settings.updateSkipped = _ui.value.updateCode
        _ui.value = _ui.value.copy(updateCode = 0)
    }

    fun closePhoto() {
        val back = _ui.value.photoReturn
        _ui.value = _ui.value.copy(screen = if (back == Screen.PHOTO) Screen.PASSES else back)
    }

    /**
     * Binds the photo to a satellite and grabs the arc of the pass being worked
     * — the one in progress, otherwise the one that has just finished, otherwise
     * the next one — so the polar plot on the picture is the right one.
     */
    /**
     * Finds the satellite back from the name alone and rebinds the photo to it,
     * WITHOUT moving the pass already stamped on the picture. Used for pictures
     * kept before the catalogue number was stored, and as a safety net whenever
     * the page ends up naming a satellite it cannot identify: that state is what
     * made the pass combo box disappear.
     */
    fun rebindPhotoSatByName(): Boolean {
        val s = _ui.value
        if (s.photoSatCat != null || s.photoSatName.isBlank()) return false
        val want = s.photoSatName.trim()
        val sat = s.satellites.firstOrNull { it.name.trim().equals(want, ignoreCase = true) }
            ?: return false
        setPhotoSat(sat.catalogNumber, s.photoPassAosMs.takeIf { it > 0L }, keepPass = true)
        return true
    }

    /**
     * [keepPass] leaves the pass already chosen alone when the prediction window
     * does not contain it — reopening a picture shot last month must not stamp
     * it with a pass of today.
     */
    fun setPhotoSat(catnum: Int?, wantAosMs: Long? = null, keepPass: Boolean = false) {
        if (catnum == null) {
            _ui.value = _ui.value.copy(photoSatCat = null, photoSatName = "",
                photoTrack = emptyList(), photoPassLabel = "",
                photoPassAosMs = 0L, photoPassElDeg = 0, photoPasses = emptyList())
            return
        }
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return
        _ui.value = _ui.value.copy(photoSatCat = catnum, photoSatName = sat.name,
            photoPasses = emptyList())
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // A day behind and two ahead: enough to cover the pass just worked
            // as well as the one being prepared, without a costly prediction.
            val list = withContext(Dispatchers.Default) {
                runCatching {
                    predictor.upcomingPasses(sat, obs, fromMs = now - 24 * 3600_000L,
                        hours = 72, minElDeg = 0.0)
                }.getOrDefault(emptyList())
            }
            if (_ui.value.photoSatCat != catnum) return@launch
            // A wanted pass comes from the page the operator arrived from. Its
            // AOS is matched loosely: this prediction runs down to 0° elevation
            // while the pass list is filtered higher, so the same pass can start
            // a few minutes earlier here. Beyond half an hour it is another pass
            // and the usual choice applies.
            val wanted = wantAosMs?.let { w ->
                list.minByOrNull { kotlin.math.abs(it.aosEpochMs - w) }
                    ?.takeIf { kotlin.math.abs(it.aosEpochMs - w) < 30 * 60_000L }
            }
            val auto = if (keepPass) null
                else list.firstOrNull { now in it.aosEpochMs..it.losEpochMs }
                    ?: list.lastOrNull { it.losEpochMs <= now }
                    ?: list.firstOrNull { it.aosEpochMs > now }
            val pass = wanted ?: auto
            // Rebinding an old picture: the list of passes is refreshed, the
            // pass burnt into it is not touched.
            if (pass == null && keepPass) {
                _ui.value = _ui.value.copy(photoPasses = list)
                return@launch
            }
            _ui.value = _ui.value.copy(photoPasses = list,
                photoTrack = pass?.track ?: emptyList(),
                photoPassLabel = pass?.let { passLabel(it) } ?: "",
                photoPassAosMs = pass?.aosEpochMs ?: 0L,
                photoPassElDeg = pass?.maxElevationDeg?.toInt() ?: 0)
        }
    }

    /** "28/07 11:03 UTC · 67°" — the one wording used everywhere a pass is named. */
    fun passLabel(aosMs: Long, elDeg: Int): String {
        if (aosMs <= 0L) return ""
        val f = fr.f4ioz.satcombo.ui.tzFormat("dd/MM HH:mm", _ui.value.useUtc)
        return "${f.format(java.util.Date(aosMs))} ${fr.f4ioz.satcombo.ui.tzTag(_ui.value.useUtc)} · ${elDeg}°"
    }

    fun passLabel(p: SatPass): String = passLabel(p.aosEpochMs, p.maxElevationDeg.toInt())

    /**
     * Puts the photo on another pass of the same satellite — the one just
     * finished, or the one coming. Label, arc and stamped values move together,
     * so what the picker shows and what is burnt into the picture can never
     * describe two different passes.
     */
    fun setPhotoPass(aosMs: Long) {
        val p = _ui.value.photoPasses.firstOrNull { it.aosEpochMs == aosMs } ?: return
        _ui.value = _ui.value.copy(
            photoTrack = p.track,
            photoPassLabel = passLabel(p),
            photoPassAosMs = p.aosEpochMs,
            photoPassElDeg = p.maxElevationDeg.toInt())
    }

    // ---- camera capture in flight ----

    /**
     * Remembers where the camera app has been told to write. The ViewModel
     * survives a rotation and the preference survives even a process death, so
     * the shot is never orphaned — which is exactly what happened when the
     * phone was turned to frame a landscape picture.
     */
    fun armCapture(file: java.io.File) { settings.pendingCapture = file.absolutePath }

    fun pendingCaptureFile(): java.io.File? =
        settings.pendingCapture.takeIf { it.isNotBlank() }?.let { java.io.File(it) }

    fun clearCapture() { settings.pendingCapture = "" }

    // ---- kept pictures ----

    /** Stores the untouched shot in the album and makes it the current one. */
    fun keepPhoto(bmp: android.graphics.Bitmap, timeMs: Long) {
        val s = _ui.value
        val obs = s.observer
        val meta = fr.f4ioz.satcombo.data.QrvPhoto(
            id = timeMs, locator = myLocator(), callsign = s.callsign,
            satName = s.photoSatName, satCat = s.photoSatCat ?: 0,
            latDeg = obs?.latDeg ?: 0.0, lonDeg = obs?.lonDeg ?: 0.0,
            track = s.photoTrack, passMs = s.photoPassAosMs, passElDeg = s.photoPassElDeg)
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { qrvPhotoStore.add(bmp, meta) }
            _ui.value = _ui.value.copy(qrvPhotos = list, photoCurrentId = timeMs)
        }
    }

    fun deleteQrvPhoto(id: Long) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { qrvPhotoStore.delete(id) }
            _ui.value = _ui.value.copy(qrvPhotos = list,
                photoCurrentId = if (_ui.value.photoCurrentId == id) null else _ui.value.photoCurrentId)
        }
    }

    /** Re-opens a kept picture, restoring its satellite and its pass arc. */
    fun openQrvPhoto(id: Long, restoreMeta: Boolean = true,
                     onReady: (android.graphics.Bitmap?) -> Unit) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { qrvPhotoStore.bitmapOf(id) }
            val meta = _ui.value.qrvPhotos.firstOrNull { it.id == id }
            // Opening a kept picture on purpose brings its satellite and pass
            // back; reopening it merely to fill the page must not.
            if (restoreMeta) _ui.value = _ui.value.copy(photoPinnedSat = false)
            _ui.value = _ui.value.copy(photoCurrentId = id)
            if (meta != null && restoreMeta) {
                _ui.value = _ui.value.copy(
                    photoSatName = meta.satName, photoTrack = meta.track,
                    // The catalogue number comes back WITH the name. Restoring
                    // the name alone left the page unable to list the passes of
                    // the satellite it was naming, and the pass combo box —
                    // hidden when there is no catalogue number — vanished.
                    photoSatCat = meta.satCat.takeIf { it > 0 } ?: _ui.value.photoSatCat,
                    photoPassAosMs = meta.passMs, photoPassElDeg = meta.passElDeg,
                    // Without this the header kept naming the pass of the live
                    // satellite while the picture showed the one it was shot on.
                    photoPassLabel = passLabel(meta.passMs, meta.passElDeg))
                // Pictures kept before v16.3 only stored the name: find the
                // satellite back from it so the passes can be listed again.
                if (meta.satCat <= 0) rebindPhotoSatByName()
            }
            onReady(bmp)
        }
    }

    fun qrvPhotoFile(id: Long): java.io.File = qrvPhotoStore.fileOf(id)

    /** The locator of the current position (GPS or manual), 6 characters. */
    fun myLocator(): String {
        val obs = _ui.value.observer
        val loc = if (obs != null) Maidenhead.fromLatLon(obs.latDeg, obs.lonDeg)
        else _ui.value.manualLocator.uppercase()
        // Le décodeur SSTV tourne dans un service, loin d'ici, et n'a pas de
        // GPS à lui : on lui laisse le locator au passage pour qu'il puisse
        // l'écrire à côté de l'image reçue.
        fr.f4ioz.satcombo.sstv.SstvHub.qthLocator = loc
        return loc
    }

    /** The launcher icon, decoded once and reused on every render. */
    private val appIcon: android.graphics.Bitmap? by lazy {
        fr.f4ioz.satcombo.data.QthPhoto.appIcon(getApplication())
    }

    /** Overlay options built from the settings + the live position. */
    fun photoOptions(timeMs: Long = System.currentTimeMillis()): fr.f4ioz.satcombo.data.QthPhoto.Options {
        val s = _ui.value
        val obs = s.observer
        val running = s.activations.firstOrNull { it.running }
        // The satellite explicitly attached to the photo wins; otherwise fall
        // back on whatever is open, then on the last one worked in the session.
        val sat = s.photoSatName.ifBlank {
            s.selected?.name
                ?: running?.let { a -> fr.f4ioz.satcombo.data.ActivationStore.satsOf(a, s.log).lastOrNull() }
                ?: ""
        }
        return fr.f4ioz.satcombo.data.QthPhoto.Options(
            callsign = s.callsign,
            locator = myLocator(),
            latDeg = obs?.latDeg ?: 0.0,
            lonDeg = obs?.lonDeg ?: 0.0,
            satName = sat,
            showCallsign = s.photoShowCallsign,
            showDate = s.photoShowDate,
            showGrids = s.photoShowGrids,
            showCoords = s.photoShowCoords,
            // **Le logo est permanent.** La marque SatMe signe chaque photo
            // partagée ; plus personne ne peut la retirer, auteur compris.
            showLogo = true,
            showSat = s.photoShowSat,
            showPolar = s.photoShowPolar,
            showCarte = s.carte.affichee,
            showPota = s.carte.potaAffiche,
            potaRef = s.carte.potaRef,
            potaNom = s.carte.potaNom,
            carteAnneaux = s.carte.anneaux,
            carteTaille = s.carte.taille,
            carteX = s.carte.x,
            carteY = s.carte.y,
            carteRemplissage = s.carte.remplissage,
            carteContenu = s.carte.contenu,
            potaTaille = s.carte.potaTaille,
            potaMonte = s.carte.potaMonte,
            carteCouleur = s.carte.couleur,
            zoneAnneaux = s.carte.zoneAnneaux,
            villes = s.carte.villes,
            carteFondu = s.carte.fondu,
            potaNomAffiche = s.carte.potaNomAffiche,
            showQrg = s.carte.qrgAffiche,
            qrgScale = s.carte.qrgScale,
            qrgTexte = s.carte.qrgTexte,
            passScale = s.carte.passScale,
            upMHz = activeTransmitters().getOrNull(s.selectedTxIndex)
                ?.uplinkLowHz?.let { it / 1_000_000.0 } ?: 0.0,
            downMHz = (s.rxRestHz ?: activeTransmitters().getOrNull(s.selectedTxIndex)
                ?.downlinkLowHz)?.let { it / 1_000_000.0 } ?: 0.0,
            track = s.photoTrack,
            useUtc = s.useUtc,
            timeMs = timeMs,
            nearLocators = nearLocators(),
            passMs = s.photoPassAosMs,
            passElDeg = s.photoPassElDeg,
            showPass = s.photoShowPass,
            polarScale = s.photoPolarScale,
            satLabelScale = s.photoSatLabelScale,
            loc4 = s.photoLoc4,
            // L’altitude n’est écrite que si elle a été mesurée. Un locator
            // saisi à la main laisse l’altitude à zéro, et « 0 m » sur une
            // activation en montagne serait pire que rien du tout.
            altM = if (s.photoShowAlt) obs?.altMeters?.takeIf {
                it != 0.0 && !it.isNaN() } else null,
            callColor = s.photoCallColor,
            callScale = s.photoCallScale,
            nearCount = s.photoNearCount,
            // Les drapeaux sont une extension : sans le mot-clé, ils ne
            // s'impriment pas, même si le réglage est resté en mémoire. Et les
            // deux bretons demandent en plus le mot BZH — un réglage hérité ne
            // doit pas ressortir sur la photo d'un OM qui ne les a plus.
            units = s.units,
            flagLeft = photoFlagOrNothing(s, s.photoFlag, gated = false),
            flagsRight = listOfNotNull(
                photoFlagOrNothing(s, s.carte.flagRight, gated = true)
                    .takeIf { it.isNotBlank() }),
            logoIcon = appIcon
        )
    }

    // ================= activations (field sessions) =================

    fun openActivation() { _ui.value = _ui.value.copy(screen = Screen.ACTIVATION) }
    fun closeActivation() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    // ================= Agenda (rendez-vous personnels) =================

    /**
     * L'agenda n'est pas une extension bêta : un rappel d'horaire ne dépend
     * d'aucun matériel et ne peut rien casser, il est donc ouvert à tous.
     */
    fun openAgenda() { _ui.value = _ui.value.copy(screen = Screen.AGENDA) }
    fun closeAgenda() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    /**
     * Relit l’agenda depuis le disque.
     *
     * Appelé au démarrage et après chaque modification de l’écran Agenda : la
     * liste des passages porte maintenant une pastille pour les rendez-vous, et
     * une pastille qui n’apparaîtrait qu’au prochain lancement ne servirait à
     * personne — on note justement un rendez-vous pour le voir tout de suite.
     */
    fun refreshAgenda() {
        _ui.value = _ui.value.copy(
            agenda = fr.f4ioz.satcombo.data.AgendaStore.load(getApplication()))
    }

    /**
     * Le rendez-vous d’agenda qui tombe pendant ce passage, s’il y en a un.
     *
     * Deux conditions. L’instant du rendez-vous doit tomber dans la fenêtre du
     * passage, avec cinq minutes de battement de part et d’autre : personne ne
     * note un rendez-vous à la seconde, et un « SSTV ISS à 14 h 30 » vaut pour
     * le passage qui commence à 14 h 32. Et le satellite doit correspondre.
     *
     * Un rendez-vous sans satellite ne s’accroche à aucun passage : il vaut
     * pour une heure, pas pour une orbite. Un rendez-vous dont le rappel est
     * coupé reste affiché — couper le rappel, c’est refuser d’être réveillé,
     * pas effacer la note.
     */
    fun agendaForPass(p: SatPass): fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? {
        val list = _ui.value.agenda
        if (list.isEmpty()) return null
        return list.firstOrNull { e ->
            e.satName.isNotBlank() &&
                e.covers(p.aosEpochMs, p.losEpochMs) &&
                sameSatName(e.satName, p.satName)
        }
    }

    /**
     * Le rendez-vous qui concerne ce satellite et qui n’est pas encore fini.
     *
     * Sert à la liste des satellites et à l’en-tête de la page du satellite.
     * Un créneau annoncé trois semaines à l’avance mérite d’être vu tout de
     * suite : on choisit le plus proche encore à venir, pas seulement celui
     * qui a lieu à la seconde présente. Une fois la fin passée, la marque
     * disparaît d’elle-même — un agenda qui garde ses vieilles marques finit
     * par ne plus rien signaler du tout.
     */
    fun agendaForSat(satName: String): fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? {
        val list = _ui.value.agenda
        if (list.isEmpty() || satName.isBlank()) return null
        val now = System.currentTimeMillis()
        return list.filter {
            it.satName.isNotBlank() && it.endOrStartMs >= now && sameSatName(it.satName, satName)
        }.minByOrNull { it.timeMs }
    }

    /**
     * Les rendez-vous qui tombent dans la période demandée.
     *
     * Le filtre par date sert justement à préparer un jour précis ; s’il
     * recouvre un créneau annoncé, le dire en haut de la liste évite d’avoir
     * à repérer la pastille passage par passage.
     */
    fun agendaInRange(fromMs: Long, toMs: Long): List<fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent> =
        _ui.value.agenda.filter { it.overlaps(fromMs, toMs) }.sortedBy { it.timeMs }

    /**
     * Le rendez-vous qui impose une fréquence pour ce satellite à cet instant.
     *
     * Seul un rendez-vous portant une fréquence compte ici : les autres se
     * contentent de marquer le passage. [refMs] est l’instant de référence —
     * l’AOS du passage regardé, ou maintenant à défaut —, parce qu’on ouvre
     * souvent la page d’un passage qui n’a pas encore commencé.
     */
    private fun agendaFreqFor(satName: String, refMs: Long):
        fr.f4ioz.satcombo.data.AgendaStore.AgendaEvent? =
        _ui.value.agenda.firstOrNull {
            it.freqHz > 0L && it.satName.isNotBlank() &&
                it.activeAt(refMs) && sameSatName(it.satName, satName)
        }

    /**
     * Deux noms de satellite qui désignent le même engin.
     *
     * La comparaison est large dans les deux sens, parce que l’opérateur écrit
     * « ISS » là où le catalogue dit « ISS (ZARYA) », et « AO-91 » là où il dit
     * « FOX-1B (AO-91) ». Un rapprochement trop large affiche une pastille de
     * trop ; un rapprochement trop strict fait rater le rendez-vous.
     */
    private fun sameSatName(a: String, b: String): Boolean {
        val x = a.trim().uppercase()
        val y = b.trim().uppercase()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    // ================= SSTV =================

    fun openSstv() {
        // Verrou de bêta : sans la clé, l'écran n'existe pas. Le menu le cache
        // déjà, mais un raccourci futur ou un état restauré ne doit pas passer.
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SSTV)) return
        _ui.value = _ui.value.copy(screen = Screen.SSTV)
    }
    fun closeSstv() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    // ================= APT (images NOAA, bêta) =================

    fun openApt() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.APT)) return
        fr.f4ioz.satcombo.apt.AptHub.qthLocator = myLocator()
        _ui.value = _ui.value.copy(screen = Screen.APT)
    }
    fun closeApt() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    // ================= SDR (clé RTL-SDR, bêta) =================

    fun openSdr() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SDR)) return
        fr.f4ioz.satcombo.sdr.SdrHub.attach(getApplication())
        _ui.value = _ui.value.copy(screen = Screen.SDR)
    }

    fun closeSdr() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    // ===================== radiosondes météo =====================

    fun openSonde() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.SONDE)) return
        _ui.value = _ui.value.copy(screen = Screen.SONDE)
    }

    /** Quitter l'écran n'arrête pas l'écoute : un vol dure trois heures. */
    fun closeSonde() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    /** Se caler sur une fréquence, à chaud si la réception tourne déjà. */
    fun setSondeFreq(hz: Long) {
        if (!fr.f4ioz.satcombo.sonde.SondeSites.inBand(hz)) return
        settings.sondeFreqHz = hz
        _ui.value = _ui.value.copy(sondeFreqHz = hz)
        if (fr.f4ioz.satcombo.sonde.SondeHub.active) {
            fr.f4ioz.satcombo.sdr.SdrHub.setCenter(hz, hz)
        }
    }

    /** Un cran de dix kilohertz : c'est le pas sur lequel les sondes se calent. */
    fun stepSondeFreq(steps: Int) {
        val step = fr.f4ioz.satcombo.sonde.SondeSites.SCAN_STEP_HZ
        setSondeFreq(_ui.value.sondeFreqHz + steps * step)
    }

    /**
     * Démarre l'écoute d'une radiosonde.
     *
     * Trois différences avec une écoute de phonie, et chacune compte : pas de
     * son (on ne veut pas de bruit blanc dans le haut-parleur pendant trois
     * heures), pas de silencieux (une sonde lointaine passe sous le seuil bien
     * avant de cesser d'être décodable), et surtout pas de désaccentuation —
     * elle arrondit les fronts du signal et le décodeur ne trouve plus rien.
     */
    /** Choisit d'où vient le son des sondes : "SDR", "MIC" ou "USB". */
    fun setSondeSource(v: String) {
        settings.sondeSource = v
        _ui.value = _ui.value.copy(sondeSource = settings.sondeSource)
    }

    /**
     * Choisit le modèle écouté : "AUTO", "RS41", "M20" ou "M10".
     *
     * Ce n'est pas un réglage cosmétique. Il commande à la fois la largeur du
     * filtre FM demandée à la chaîne SDR et les décodeurs mis en route : une
     * RS41 tient dans quinze kilohertz, lui en ouvrir vingt-deux revient à
     * laisser entrer la moitié de bruit en plus pour rien.
     */
    fun setSondeModel(v: String) {
        settings.sondeModel = v
        _ui.value = _ui.value.copy(sondeModel = settings.sondeModel)
    }

    /**
     * Démarre la réception d'une sonde par la source choisie.
     *
     * La clé RTL n'est qu'une façon d'entendre le 404 MHz parmi d'autres :
     * celui qui a déjà un récepteur convenable lui prend son audio, par le
     * micro du téléphone ou par une carte son USB, exactement comme pour la
     * SSTV ou les NOAA. Le décodeur, lui, ne voit aucune différence : il reçoit
     * du son échantillonné et y cherche des trames.
     */
    fun startSondeRx() {
        when (_ui.value.sondeSource) {
            "MIC", "USB" -> startSondeAudio(_ui.value.sondeSource)
            else -> startSonde()
        }
    }

    /** Arrête la réception, quelle que soit la source engagée. */
    fun stopSondeRx() {
        if (fr.f4ioz.satcombo.audio.SondeAudioService.running) stopSondeAudio()
        else stopSonde()
    }

    /** Écoute par l'audio du téléphone : aucun fichier n'est écrit, seul le
     *  journal des trames décodées reste sur la carte. */
    fun startSondeAudio(source: String) {
        val hz = _ui.value.sondeFreqHz
        fr.f4ioz.satcombo.sonde.SondeHub.start(
            getApplication(),
            fr.f4ioz.satcombo.audio.SondeAudioService.RATE, hz, source,
            model = _ui.value.sondeModel)
        fr.f4ioz.satcombo.audio.SondeAudioService.start(getApplication(), source)
    }

    fun stopSondeAudio() {
        fr.f4ioz.satcombo.audio.SondeAudioService.stop(getApplication())
        fr.f4ioz.satcombo.sonde.SondeHub.stop()
    }

    fun startSonde() {
        val hz = _ui.value.sondeFreqHz
        val gain = settings.sdrGainTenthDb.takeIf { it >= 0 }
        val ok = fr.f4ioz.satcombo.sdr.SdrHub.start(
            ctx = getApplication(),
            satName = "SONDE",
            restHz = hz,
            gainTenthDb = gain,
            agc = settings.sdrAgc,
            ppm = settings.sdrPpm,
            sstv = false,
            record = false,
            audio = false,
            mode = fr.f4ioz.satcombo.sdr.RxMode.NFM,
            bandwidthHz = fr.f4ioz.satcombo.sonde.SondeModel
                .bandwidthFor(_ui.value.sondeModel),
            squelchDb = -200,
            offsetHz = 0)
        if (!ok) return
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(false)
        fr.f4ioz.satcombo.sonde.SondeHub.start(
            getApplication(), fr.f4ioz.satcombo.sdr.Dsp.AUDIO_RATE, hz,
            model = _ui.value.sondeModel)
    }

    fun stopSonde() {
        fr.f4ioz.satcombo.sonde.SondeHub.stop()
        fr.f4ioz.satcombo.sdr.SdrHub.stop()
        // On rend à la désaccentuation le réglage choisi par l'utilisateur.
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(settings.sdrDeemph)
    }

    fun clearSondeFlight() {
        fr.f4ioz.satcombo.sonde.SondeHub.clearFlight()
        _ui.value = _ui.value.copy()
    }

    /**
     * Une clé USB vient d'être branchée (intention USB_DEVICE_ATTACHED).
     *
     * On ne navigue nulle part : l'opérateur est presque toujours en train de
     * suivre un passage quand il branche sa clé, et le renvoyer à la liste des
     * satellites à cet instant est exactement ce qu'il ne faut pas faire. On se
     * contente de noter la clé ; le panneau SDR apparaît alors tout seul sous
     * la boussole. Bonus de cette route : l'autorisation USB est déjà accordée
     * par le système, il n'y a pas de boîte de dialogue à attendre.
     */
    fun onUsbDeviceAttached(dev: android.hardware.usb.UsbDevice?) {
        if (hasExtension(fr.f4ioz.satcombo.data.Extensions.SDR)) {
            fr.f4ioz.satcombo.sdr.SdrHub.onDeviceAttached(getApplication(), dev)
        }
        onSerialAttached(dev)
    }

    /**
     * Le branchement d'un pont série : le rotor, ou le poste.
     *
     * Jusqu'ici cette intention ne servait qu'à la clé SDR, et elle repartait
     * même sans rien faire quand l'extension SDR n'était pas activée. Or c'est
     * la meilleure occasion de la journée : le système vient d'accorder
     * l'autorisation d'accès en même temps qu'il a désigné l'application, il
     * n'y a donc aucune boîte de dialogue à attendre — ce qui est précisément
     * ce qui bloquait la connexion du rotor.
     *
     * On rafraîchit les deux listes de ports, puis on tente la connexion du
     * rotor si elle a des chances d'aboutir : liaison USB choisie, pas de
     * simulateur, rien de déjà connecté. Un port qui ne répond pas au `C2` est
     * refermé aussitôt, le poste ne risque donc pas de rester pris.
     */
    private fun onSerialAttached(dev: android.hardware.usb.UsbDevice?) {
        val d = runCatching { fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()) }.getOrNull()
            ?: return
        if (!d.recognises(dev)) return
        refreshRotorDevices()
        refreshCatDevices()
        val u = _ui.value
        if (u.rotorConnected || u.rotorSim || u.rotorLink != "GS232") return
        val nom = dev?.productName ?: dev?.deviceName ?: ""
        _ui.value = _ui.value.rot { copy(rotorStatus = tf("rotor_usb_attached", nom)) }
        connectRotor()
    }


    // ==================================================================
    // Convertisseurs
    //
    // Tout ce qui précède, dans cette classe, raisonne en fréquences de
    // satellite : le Doppler, les bornes du transpondeur, l'inversion, le
    // repos choisi dans le passband. Tout cela reste vrai avec un LNB devant
    // le récepteur — c'est toujours 10 489 MHz qui descend du ciel. Seule
    // change la dernière ligne : ce qu'on écrit dans le poste ou dans la
    // clé, et ce qu'on relit d'eux.
    //
    // La conversion est donc posée au plus tard possible, juste avant
    // l'écriture et juste après la lecture, et nulle part ailleurs. C'est ce
    // qui permet de ne toucher à aucun des calculs existants : ils n'ont
    // jamais à savoir qu'il y a une boîte dans le câble.
    // ==================================================================

    private fun convRxDepuisReglages() = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = settings.convRxActif, olHz = settings.convRxOlHz,
        inverseur = settings.convRxInverseur,
        basHz = settings.convRxBasHz, hautHz = settings.convRxHautHz)

    private fun convTxDepuisReglages() = fr.f4ioz.satcombo.domain.Convertisseur(
        actif = settings.convTxActif, olHz = settings.convTxOlHz,
        inverseur = settings.convTxInverseur,
        basHz = settings.convTxBasHz, hautHz = settings.convTxHautHz)

    /** Descente : du ciel vers le poste piloté en CAT. */
    /**
     * L'écart de l'appareil, appliqué **après** le convertisseur.
     *
     * Le quartz d'un récepteur se trompe là où il accorde — sur la fréquence
     * intermédiaire, pas sur celle du ciel. Corriger avant le convertisseur
     * mettrait l'erreur à l'échelle du gigahertz : deux ppm valent vingt
     * kilohertz à 10 GHz, contre trois cents hertz à 144 MHz.
     */
    private fun ppmPoste(): Double =
        fr.f4ioz.satcombo.domain.MaterielRx.choisi(
            _ui.value.qo100.materiels, _ui.value.qo100.materielPoste).ppm

    private fun ppmCle(): Double =
        fr.f4ioz.satcombo.domain.MaterielRx.choisi(
            _ui.value.qo100.materiels, _ui.value.qo100.materielCle).ppm

    private fun posteRx(satHz: Long): Long {
        val fi = if (_ui.value.convRxPoste) _ui.value.convRx.versPoste(satHz) else satHz
        return fr.f4ioz.satcombo.domain.MaterielRx.corrige(fi, ppmPoste())
    }

    /** Descente : ce que le poste affiche, ramené à ce qui est dans le ciel. */
    /**
     * Le convertisseur est-il dans la chaîne du satellite en cours ?
     *
     * **C'est le satellite qui décide, pas la fréquence de l'instant.** Un
     * convertisseur appartient à une bande : celui de QO-100 descend du Ku, et
     * il n'a rien à faire dans la chaîne d'un satellite qui émet en 145 MHz.
     *
     * Sans cette question, la lecture du poste se convertissait à l'aveugle.
     * Sur un LEO, le 817 lit 145,9, l'application ajoute l'oscillateur du LNB
     * et conclut à 10 490,9 — une fréquence QO-100 affichée sur une orbite
     * basse. Le convertisseur avait pourtant des bornes, mais elles portent
     * sur le résultat : 10 490,9 tombe dedans, et la garde laissait passer.
     *
     * On interroge donc la descente du transpondeur sélectionné, qui ne ment
     * pas : elle vaut 435 MHz sur un LEO et 10 489 sur QO-100.
     */
    /**
     * Le convertisseur est-il dans la chaîne **de la clé** ?
     *
     * La même question que pour le poste, et elle manquait. `versSatellite`
     * se contentait de vérifier que le résultat tombe dans la bande — ce qui
     * est toujours vrai et ne prouve rien : une clé posée sur 145,9 MHz plus
     * l'oscillateur d'un LNB donne 10 490 MHz, soit précisément le milieu des
     * bornes de QO-100. La garde se validait elle-même.
     *
     * D'où le symptôme : sur un satellite à défilement, l'écran annonçait une
     * fréquence en gigahertz alors qu'on écoutait du 145. Le convertisseur
     * n'avait rien à faire dans cette chaîne, et rien ne l'en écartait.
     *
     * On pose donc la seule question qui ait un sens : **le satellite qu'on
     * écoute est-il dans la bande de ce convertisseur ?** C'est déjà la règle
     * du poste ; elle vaut mot pour mot pour la clé.
     */
    private fun convRxCleDansLaChaine(): Boolean {
        if (!_ui.value.convRxCle || !_ui.value.convRx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val dl = t.downlinkLowHz ?: return false
        return _ui.value.convRx.couvre(dl)
    }

    private fun convRxDansLaChaine(): Boolean {
        if (!_ui.value.convRxPoste || !_ui.value.convRx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val dl = t.downlinkLowHz ?: return false
        return _ui.value.convRx.couvre(dl)
    }

    private fun satDepuisPoste(posteHz: Long): Long =
        fr.f4ioz.satcombo.domain.MaterielRx.redresse(posteHz, ppmPoste()).let {
            if (convRxDansLaChaine()) _ui.value.convRx.versSatellite(it) else it
        }

    /** Montée : du ciel vers l'excitateur, en amont du transverter. */
    private fun posteTx(satHz: Long): Long = _ui.value.convTx.versPoste(satHz)

    /**
     * Montée : ce que l'excitateur affiche, ramené à ce qui part vers le ciel.
     *
     * L'inverse exact de [posteTx]. Sans elle, un opérateur derrière un
     * transverter verrait sa molette interprétée dans la mauvaise bande, et le
     * décalage déduit vaudrait des mégahertz.
     */
    /** Même question pour la montée : c'est le satellite qui décide. */
    private fun convTxDansLaChaine(): Boolean {
        if (!_ui.value.convTx.configure) return false
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return false
        val ul = t.uplinkLowHz ?: return false
        return _ui.value.convTx.couvre(ul)
    }

    private fun satDepuisPosteTx(posteHz: Long): Long =
        if (convTxDansLaChaine()) _ui.value.convTx.versSatellite(posteHz) else posteHz

    /** Descente : du ciel vers la PLL de la clé SDR. */
    private fun cleRx(satHz: Long): Long {
        val fi = if (convRxCleDansLaChaine()) _ui.value.convRx.versPoste(satHz) else satHz
        return fr.f4ioz.satcombo.domain.MaterielRx.corrige(fi, ppmCle())
    }

    /**
     * Le retour : ce que la clé est en train de recevoir, exprimé dans le
     * ciel. L'écran SDR affiche des fréquences ; avec un LNB il afficherait
     * 739 MHz, ce qui ne veut rien dire pour l'opérateur — il travaille sur
     * 10 489 et c'est cela qu'il note dans son carnet.
     */
    fun cleVersSat(cleHz: Long): Long =
        fr.f4ioz.satcombo.domain.MaterielRx.redresse(cleHz, ppmCle()).let {
            if (convRxCleDansLaChaine()) _ui.value.convRx.versSatellite(it) else it
        }

    /** Vrai quand une boîte est réellement dans la chaîne de la clé. */
    val convertisseurSurLaCle: Boolean
        get() = _ui.value.convRxCle && _ui.value.convRx.configure

    fun setConvRxActif(on: Boolean) {
        settings.convRxActif = on
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxOl(hz: Long) {
        settings.convRxOlHz = hz
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxInverseur(on: Boolean) {
        settings.convRxInverseur = on
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxPlage(basHz: Long, hautHz: Long) {
        settings.convRxBasHz = basHz; settings.convRxHautHz = hautHz
        _ui.value = _ui.value.copy(convRx = convRxDepuisReglages())
    }

    fun setConvRxPoste(on: Boolean) {
        settings.convRxPoste = on
        _ui.value = _ui.value.copy(convRxPoste = on)
    }

    fun setConvRxCle(on: Boolean) {
        settings.convRxCle = on
        _ui.value = _ui.value.copy(convRxCle = on)
    }

    fun setConvTxActif(on: Boolean) {
        settings.convTxActif = on
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxOl(hz: Long) {
        settings.convTxOlHz = hz
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxInverseur(on: Boolean) {
        settings.convTxInverseur = on
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    fun setConvTxPlage(basHz: Long, hautHz: Long) {
        settings.convTxBasHz = basHz; settings.convTxHautHz = hautHz
        _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
    }

    /**
     * Applique un montage tout fait, du bon côté de la chaîne.
     *
     * Un préréglage de descente coche aussi la clé SDR et décoche le poste :
     * un LNB à OL 9 750 sort en 739 MHz, et aucun poste d'amateur ne reçoit
     * là. Le préréglage à 10 057,5, lui, sort en 432 — celui-là, on le donne
     * au poste. Personne n'a envie de deviner cette règle tout seul.
     */
    fun appliquerPreset(cle: String) {
        val p = fr.f4ioz.satcombo.domain.Convertisseur.PRESETS.firstOrNull { it.cle == cle } ?: return
        if (p.descente) {
            settings.convRxActif = true
            settings.convRxOlHz = p.olHz
            settings.convRxInverseur = false
            settings.convRxBasHz = p.basHz
            settings.convRxHautHz = p.hautHz
            val fi = p.vers().versPoste(fr.f4ioz.satcombo.domain.Convertisseur.BALISE_MEDIANE_HZ)
            val recevableParUnPoste = fr.f4ioz.satcombo.cat.BandPlan.band(fi) !=
                fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE
            settings.convRxPoste = recevableParUnPoste
            settings.convRxCle = true
            _ui.value = _ui.value.copy(convRx = convRxDepuisReglages(),
                convRxPoste = settings.convRxPoste, convRxCle = true)
        } else {
            settings.convTxActif = true
            settings.convTxOlHz = p.olHz
            settings.convTxInverseur = false
            settings.convTxBasHz = p.basHz
            settings.convTxHautHz = p.hautHz
            _ui.value = _ui.value.copy(convTx = convTxDepuisReglages())
        }
    }

    /**
     * Fréquence de repos à afficher sur la clé : celle que l'opérateur a déjà
     * choisie dans le passband, sinon le centre du transpondeur sélectionné,
     * sinon la fréquence SSTV de l'ISS — c'est de loin le cas le plus courant
     * pour quelqu'un qui vient de brancher une clé.
     */
    fun sdrRestHz(): Long {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        return _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: 145_800_000L
    }

    private var sdrLoopJob: kotlinx.coroutines.Job? = null

    /**
     * Démarre la réception. La clé se règle sur la fréquence de repos, puis la
     * boucle Doppler la déplace une fois par seconde tant que le satellite est
     * au-dessus de l'horizon. Correction en réception seulement : la clé ne
     * transmet pas.
     */
    fun startSdr() {
        val sat = _ui.value.selected?.name ?: "SAT"
        val rest = sdrRestHz()
        val gain = settings.sdrGainTenthDb.takeIf { it >= 0 }
        val ok = fr.f4ioz.satcombo.sdr.SdrHub.start(
            ctx = getApplication(),
            satName = sat,
            // La clé ne connaît que sa fréquence intermédiaire ; [rest], lui,
            // reste la fréquence du satellite pour toute la boucle Doppler.
            restHz = cleRx(rest),
            gainTenthDb = gain,
            agc = settings.sdrAgc,
            ppm = settings.sdrPpm,
            sstv = settings.sdrSstv,
            record = settings.sdrRecord,
            audio = settings.sdrAudio,
            mode = sdrMode(),
            bandwidthHz = settings.sdrBandwidthHz,
            squelchDb = settings.sdrSquelchDb,
            offsetHz = 0)
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(settings.sdrDeemph)
        if (ok) startSdrLoop(rest)
    }

    /**
     * Le suivi Doppler de la clé, quatre fois par seconde.
     *
     * L'ancienne version reprogrammait la PLL une fois par seconde. En FM cela
     * passait ; en BLU chaque reprogrammation est un saut de phase — un petit
     * « clac » à chaque seconde — et comme la garde de [SdrHub.setCenter] vaut
     * cent hertz, la note montait par marches de cent hertz. Sur un
     * transpondeur linéaire c'était intenable.
     *
     * Maintenant c'est [DopplerTuner] qui décide, et sa réponse est presque
     * toujours la même : ne touche pas à la clé, glisse le décalage logiciel.
     * Ce décalage est appliqué sur le signal brut, avant le filtre de canal ;
     * il est continu et gratuit. Un passage entier sur 435 MHz balaie une
     * dizaine de kilohertz, la fenêtre en encaisse trente : la PLL ne bouge
     * pas une seule fois du lever au coucher.
     *
     * La cadence est passée à quatre fois par seconde parce qu'elle ne coûte
     * plus rien : au point le plus haut d'une orbite basse la dérive atteint la
     * centaine de hertz par seconde, et rafraîchir au quart de seconde garde
     * l'erreur sous trente hertz. Le [DopplerTuner.worthWriting] évite d'écrire
     * pour un ou deux hertz entre deux tours.
     */
    private fun startSdrLoop(restHz: Long) {
        sdrLoopJob?.cancel()
        sdrLoopJob = viewModelScope.launch {
            while (fr.f4ioz.satcombo.sdr.SdrHub.isRunning) {
                runCatching {
                    val rest = _ui.value.rxRestHz ?: restHz
                    if (!_ui.value.sdrDopplerTrack) {
                        // Suivi fermé : la clé reste sur la fréquence de repos.
                        fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(rest), cleRx(rest))
                    } else {
                        val pos = _ui.value.livePosition
                        val rr = if (pos != null && pos.elevationDeg >= 0) pos.rangeRateKmS else 0.0
                        val target = Doppler.downlink(rest, rr) + _ui.value.calibShiftHz
                        // C'est le hub qui décide, entre deux blocs, si la PLL
                        // doit vraiment bouger ou si le mélangeur logiciel
                        // encaisse l'écart : on ne fait que donner la cible.
                        //
                        // Les deux passent par le convertisseur, et c'est ce
                        // qui rend la suite juste sans un cas particulier : un
                        // OL soustractif décale cible et repos du même nombre
                        // de hertz, leur écart — le Doppler — ne bouge pas ;
                        // un OL inverseur retourne cet écart, ce qui est
                        // exactement ce que fait le matériel.
                        fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(target), cleRx(rest))
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /**
     * Ouvre ou ferme le suivi Doppler de la clé.
     *
     * Fermer remet le décalage Doppler à zéro au tour suivant : on retrouve la
     * fréquence de repos, ce qui est exactement ce qu'on veut pour écouter une
     * balise fixe ou pour entendre de combien le satellite dérive seul.
     */
    fun setSdrDopplerTrack(on: Boolean) {
        settings.sdrDopplerTrack = on
        _ui.value = _ui.value.copy(sdrDopplerTrack = on)
        if (!on) fr.f4ioz.satcombo.sdr.SdrHub.setDopplerFine(0)
    }

    fun stopSdr() {
        sdrLoopJob?.cancel(); sdrLoopJob = null
        fr.f4ioz.satcombo.sdr.SdrHub.stop()
    }

    fun setSdrGain(tenthDb: Int?) {
        settings.sdrGainTenthDb = tenthDb ?: -1
        fr.f4ioz.satcombo.sdr.SdrHub.setGain(tenthDb)
    }

    fun setSdrSstv(on: Boolean) { settings.sdrSstv = on; _ui.value = _ui.value.copy(sdrSstv = on) }
    fun setSdrRecord(on: Boolean) { settings.sdrRecord = on; _ui.value = _ui.value.copy(sdrRecord = on) }
    fun setSdrAudio(on: Boolean) {
        settings.sdrAudio = on
        _ui.value = _ui.value.copy(sdrAudio = on)
        fr.f4ioz.satcombo.sdr.SdrHub.setAudio(on)
    }
    fun setSdrAgc(on: Boolean) { settings.sdrAgc = on; _ui.value = _ui.value.copy(sdrAgc = on) }

    /** La petite cascade de la page du passage : utile pour voir le satellite
     *  arriver, mais elle mange de la place sur un petit écran. */
    fun setSdrInlineWaterfall(on: Boolean) {
        settings.sdrInlineWaterfall = on
        _ui.value = _ui.value.copy(sdrInlineWaterfall = on)
    }
    fun setSdrPpm(ppm: Int) {
        val v = ppm.coerceIn(-200, 200)
        settings.sdrPpm = v
        _ui.value = _ui.value.copy(sdrPpm = v)
    }

    /** Mode de démodulation enregistré, relu proprement s'il est abîmé. */
    fun sdrMode(): fr.f4ioz.satcombo.sdr.RxMode =
        runCatching { fr.f4ioz.satcombo.sdr.RxMode.valueOf(_ui.value.sdrMode) }
            .getOrDefault(fr.f4ioz.satcombo.sdr.RxMode.NFM)

    /**
     * Change le mode de réception. La largeur de canal repart sur celle du
     * mode : passer de la FM étroite à la BLU en gardant seize kilohertz de
     * bande passante n'aurait aucun sens.
     */
    fun setSdrMode(m: fr.f4ioz.satcombo.sdr.RxMode) {
        settings.sdrMode = m.name
        settings.sdrBandwidthHz = 0
        _ui.value = _ui.value.copy(sdrMode = m.name, sdrBandwidthHz = 0)
        fr.f4ioz.satcombo.sdr.SdrHub.setBandwidth(0)
        fr.f4ioz.satcombo.sdr.SdrHub.setMode(m)
    }

    /** Largeur de canal en hertz ; zéro laisse le mode décider. */
    fun setSdrBandwidth(hz: Int) {
        val v = if (hz <= 0) 0 else hz.coerceIn(500, 24_000)
        settings.sdrBandwidthHz = v
        _ui.value = _ui.value.copy(sdrBandwidthHz = v)
        fr.f4ioz.satcombo.sdr.SdrHub.setBandwidth(v)
    }

    /** Seuil du silencieux en dBFS ; -120 le coupe. */
    fun setSdrSquelch(db: Int) {
        val v = db.coerceIn(-120, 0)
        settings.sdrSquelchDb = v
        _ui.value = _ui.value.copy(sdrSquelchDb = v)
        fr.f4ioz.satcombo.sdr.SdrHub.setSquelch(v)
    }

    /**
     * Désaccentuation FM. À n'ouvrir que pour écouter de la FM à large bande :
     * sur un répéteur, une balise ou une image SSTV, elle ne fait qu'écraser
     * les aigus.
     */
    fun setSdrDeemph(on: Boolean) {
        settings.sdrDeemph = on
        _ui.value = _ui.value.copy(sdrDeemph = on)
        fr.f4ioz.satcombo.sdr.SdrHub.setDeemphasis(on)
    }

    /**
     * Accord automatique sur la porteuse la plus forte de la fenêtre affichée.
     * C'est le geste qui remplace le tâtonnement au doigt : on voit la raie,
     * on appuie, la chaîne se pose dessus au hertz près que permet la FFT.
     */
    fun tuneSdrPeak(spanHz: Int) {
        val half = (spanHz / 2.0).coerceIn(2_000.0, 80_000.0)
        fr.f4ioz.satcombo.sdr.SdrHub.tunePeak(-half, half)
    }

    /** Largeur affichée par le spectre, en hertz. */
    fun setSdrSpan(hz: Int) {
        val v = hz.coerceIn(6_000, 176_400)
        settings.sdrSpanHz = v
        _ui.value = _ui.value.copy(sdrSpanHz = v)
    }

    // ------------------------------------------------------------ accord fin

    fun setSdrLoupe(on: Boolean) {
        settings.sdrLoupe = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(loupe = on))
    }

    fun setSdrLoupeSpan(hz: Int) {
        val v = hz.coerceIn(1_000, 40_000)
        settings.sdrLoupeSpanHz = v
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(loupeSpanHz = v))
    }

    fun setSdrVernier(on: Boolean) {
        settings.sdrVernier = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(vernier = on))
    }

    fun setSdrVernierRatio(hzParCm: Int) {
        val v = hzParCm.coerceIn(1, 100_000)
        settings.sdrVernierHzParCm = v
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(vernierHzParCm = v))
    }

    fun setClavierMainGauche(on: Boolean) {
        settings.clavierMainGauche = on
        _ui.value = _ui.value.copy(express = _ui.value.express.copy(mainGauche = on))
    }

    /**
     * Un cran de molette USB.
     *
     * L'action dépend de l'écran ouvert, et c'est voulu : la molette commande
     * « ce qu'on est en train d'accorder », pas un champ nommé. Sur QO-100
     * c'est la fréquence de descente, ailleurs le décalage de réception —
     * exactement ce que déplacent les boutons à l'écran.
     */
    fun moletteCran(codeTouche: Int, externe: Boolean): Boolean {
        val M = fr.f4ioz.satcombo.domain.MoletteUsb
        // L'apprentissage passe avant tout : c'est le seul moment où l'on veut
        // qu'une touche soit capturée plutôt qu'interprétée.
        val rang = _apprentissage.value
        if (rang != null && externe) {
            if (M.apprenable(codeTouche)) { apprendMacro(rang, codeTouche); _apprentissage.value = null }
            return true
        }
        val a = _ui.value.accord
        val geste = M.geste(
            codeTouche, settings.moletteVfo, externe, settings.molettePasHz,
            fr.f4ioz.satcombo.domain.MoletteUsb.Touches(
                a.macroCodeA, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleA),
                a.macroCodeB, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleB),
                a.macroCodeC, fr.f4ioz.satcombo.domain.MoletteUsb.Cible.valueOf(a.macroCibleC),
                a.macroCodeD, fr.f4ioz.satcombo.domain.MoletteUsb.Action.valueOf(a.macroActionD)))
        return when (geste) {
            is fr.f4ioz.satcombo.domain.MoletteUsb.Geste.ChoisitCible -> { setMoletteCible(geste.cible.name); true }
            is fr.f4ioz.satcombo.domain.MoletteUsb.Geste.Bouge -> {
                // La cible commande, sauf sur QO-100 où l'écran est déjà tout
                // entier un VFO : y déplacer un décalage n'aurait pas de sens.
                when {
                    _ui.value.screen == Screen.QO100 -> qo100Pas(geste.deltaHz)
                    a.moletteCible == "SHIFT_TX" -> nudgeTxShift(geste.deltaHz)
                    a.moletteCible == "SHIFT_RX" -> nudgeRxOffset(geste.deltaHz)
                    else -> moletteDeplaceVfo(geste.deltaHz)
                }
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.CibleSuivante -> {
                setMoletteCible(
                    fr.f4ioz.satcombo.domain.MoletteUsb.cibleSuivante(_ui.value.accord.moletteCible))
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.RemetZero -> {
                // Remettre à zéro ce qui est en cours, et rien d'autre : une
                // remise à zéro qui toucherait les deux décalages d'un coup
                // effacerait un réglage que l'opérateur voulait garder.
                when (_ui.value.accord.moletteCible) {
                    "SHIFT_TX" -> setTxShift(0L)
                    "SHIFT_RX" -> setRxOffset(0L)
                    else -> Unit          // le VFO n'a pas de zéro à retrouver
                }
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.ChangePas -> {
                settings.molettePasHz =
                    fr.f4ioz.satcombo.domain.MoletteUsb.pasSuivant(settings.molettePasHz)
                _ui.value = _ui.value.copy(molettePasHz = settings.molettePasHz)
                true
            }
            fr.f4ioz.satcombo.domain.MoletteUsb.Geste.Ignore -> false
        }
    }

    fun setMolettePas(hz: Long) {
        settings.molettePasHz = hz
        _ui.value = _ui.value.copy(molettePasHz = hz)
    }

    /**
     * Le rang de la touche en cours d'apprentissage, ou `null`.
     *
     * Hors d'`UiState` : c'est un état passager de réglage, qui n'a rien à
     * faire dans l'état global d'une application de trafic.
     */
    private val _apprentissage = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val apprentissage: kotlinx.coroutines.flow.StateFlow<Int?> = _apprentissage

    fun debuteApprentissage(rang: Int) { _apprentissage.value = rang }
    fun annuleApprentissage() { _apprentissage.value = null }

    fun setMoletteCible(nom: String) {
        settings.moletteCible = nom
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(moletteCible = nom))
    }

    /**
     * Retient une touche apprise.
     *
     * Une même touche ne peut pas servir deux fois : sans cette garde, le
     * boîtier changerait de cible de façon imprévisible, et l'opérateur
     * croirait à une panne du matériel.
     */
    fun apprendMacro(rang: Int, code: Int) {
        if (!fr.f4ioz.satcombo.domain.MoletteUsb.apprenable(code)) return
        var a = _ui.value.accord
        if (a.macroCodeA == code) a = a.copy(macroCodeA = 0)
        if (a.macroCodeB == code) a = a.copy(macroCodeB = 0)
        if (a.macroCodeC == code) a = a.copy(macroCodeC = 0)
        if (a.macroCodeD == code) a = a.copy(macroCodeD = 0)
        a = when (rang) {
            0 -> a.copy(macroCodeA = code)
            1 -> a.copy(macroCodeB = code)
            2 -> a.copy(macroCodeC = code)
            else -> a.copy(macroCodeD = code)
        }
        settings.macroCodeA = a.macroCodeA
        settings.macroCodeB = a.macroCodeB
        settings.macroCodeC = a.macroCodeC
        settings.macroCodeD = a.macroCodeD
        _ui.value = _ui.value.copy(accord = a)
    }

    /** L'action du poussoir : « PAS », « CIBLE » ou « ZERO ». */
    fun setMacroAction(action: String) {
        settings.macroActionD = action
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(macroActionD = action))
    }

    fun setMacroCible(rang: Int, cible: String) {
        var a = _ui.value.accord
        a = when (rang) {
            0 -> a.copy(macroCibleA = cible)
            1 -> a.copy(macroCibleB = cible)
            else -> a.copy(macroCibleC = cible)
        }
        settings.macroCibleA = a.macroCibleA
        settings.macroCibleB = a.macroCibleB
        settings.macroCibleC = a.macroCibleC
        _ui.value = _ui.value.copy(accord = a)
    }

    fun setMoletteVfo(on: Boolean) {
        settings.moletteVfo = on
        _ui.value = _ui.value.copy(moletteVfo = on)
    }

    fun setClavierDisposition(nom: String) {
        settings.clavierDisposition = nom
        _ui.value = _ui.value.copy(express = _ui.value.express.copy(disposition = nom))
    }

    fun setSdrCalageVoix(on: Boolean) {
        settings.sdrCalageVoix = on
        _ui.value = _ui.value.copy(accord = _ui.value.accord.copy(calageVoix = on))
    }

    /**
     * Calage sur la voix du correspondant, en bande latérale.
     *
     * Le mode vient de l'état affiché et non de la chaîne : c'est celui que
     * l'opérateur voit, et si les deux divergent c'est la vue qui a raison du
     * point de vue de sa main.
     */
    fun sdrCaleSurLaVoix() {
        val cible = fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz(_ui.value.sdrMode)
        if (cible == 0) return
        fr.f4ioz.satcombo.sdr.SdrHub.caleVoix(cible)
    }

    /**
     * Le même calage sur la page QO-100, où il demande un pas de plus.
     *
     * Là-bas, la fréquence qui compte est une **descente** — un nombre affiché,
     * reporté au poste et à la clé. Le calage, lui, agit sur le décalage fin du
     * récepteur, que la page QO-100 ne regarde pas. Sans reprise, on entendrait
     * la voix se poser correctement pendant que le nombre à l'écran resterait
     * faux de quelques centaines de hertz — et c'est ce nombre que l'opérateur
     * annonce à son correspondant.
     *
     * On attend donc que le fil de lecture ait résolu la mesure, puis on la
     * verse dans la descente et on rend le décalage fin à zéro. Le bref retour
     * en arrière entre les deux dure un bloc de lecture ; il s'entend à peine
     * et il vaut mieux que deux chiffres qui divergent.
     */
    fun qo100CaleSurLaVoix() {
        val hub = fr.f4ioz.satcombo.sdr.SdrHub
        if (!hub.isRunning) return
        val avant = hub.state.value.tunedAtMs
        // Le transpondeur étroit est en bande latérale supérieure, toujours :
        // la question du mode ne se pose pas ici comme sur la page du SDR.
        hub.caleVoix(fr.f4ioz.satcombo.domain.AccordFin.cibleVoixHz("USB"))
        viewModelScope.launch {
            repeat(25) {
                delay(120)
                val st = hub.state.value
                if (st.tunedAtMs != avant) {
                    val corr = st.offsetHz - st.dopplerFineHz
                    if (corr != 0L) {
                        hub.setOffset(0)
                        setQo100Descente(_ui.value.qo100.descenteHz + corr)
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Déplacement relatif de l'accord fin, en hertz. C'est ce que pousse le
     * vernier sur la page du SDR ; sur QO-100 c'est [qo100Pas] qui s'en charge,
     * parce que là-bas la fréquence affichée est une descente et non un écart.
     */
    fun sdrPasFin(deltaHz: Long) {
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        setSdrOffset((st.offsetHz + deltaHz).toInt().coerceIn(-80_000, 80_000))
    }

    /**
     * Accord fin logiciel, en hertz autour de la fréquence de la clé. C'est ce
     * que déplace le doigt posé sur la cascade.
     */
    fun setSdrOffset(hz: Int) {
        fr.f4ioz.satcombo.sdr.SdrHub.setOffset(hz.coerceIn(-80_000, 80_000))
    }

    val currentActivation: fr.f4ioz.satcombo.data.Activation?
        get() = _ui.value.activations.firstOrNull { it.running }

    fun startActivation(name: String, note: String = "") {
        val obs = _ui.value.observer
        val a = fr.f4ioz.satcombo.data.Activation(
            startMs = System.currentTimeMillis(),
            name = name.trim(),
            locator = myLocator(),
            grids = myGridsCsv(),
            latDeg = obs?.latDeg ?: 0.0,
            lonDeg = obs?.lonDeg ?: 0.0,
            callsign = _ui.value.callsign,
            note = note.trim()
        )
        _ui.value = _ui.value.copy(activations = activationStore.start(a))
    }

    fun stopActivation(startMs: Long? = null) {
        val id = startMs ?: currentActivation?.startMs ?: return
        _ui.value = _ui.value.copy(activations = activationStore.stop(id))
    }

    fun updateActivation(a: fr.f4ioz.satcombo.data.Activation) {
        _ui.value = _ui.value.copy(activations = activationStore.update(a))
    }

    fun deleteActivation(startMs: Long) {
        _ui.value = _ui.value.copy(activations = activationStore.delete(startMs))
    }

    /** QSOs logged inside [a]'s time window. */
    fun activationQsos(a: fr.f4ioz.satcombo.data.Activation): List<LogEntry> =
        fr.f4ioz.satcombo.data.ActivationStore.qsosOf(a, _ui.value.log)

    /** Builds the A4 activation sheet and hands back a shareable URI. */
    fun exportActivationPdf(a: fr.f4ioz.satcombo.data.Activation, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                val file = fr.f4ioz.satcombo.data.ActivationPdf.build(
                    getApplication(), a, activationQsos(a), _ui.value.useUtc)
                fr.f4ioz.satcombo.data.ActivationPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /** ADIF limited to one session — ready to import in the logbook. */
    fun activationAdif(a: fr.f4ioz.satcombo.data.Activation): String =
        LogStore.toAdif(activationQsos(a), settings.callsign)

    // ================= timeline =================

    fun openTimeline() {
        _ui.value = _ui.value.copy(screen = Screen.TIMELINE)
        computeTimeline()
    }

    fun closeTimeline() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    fun setTimelineHours(h: Int) {
        _ui.value = _ui.value.copy(timelineHours = h.coerceIn(1, 24))
        computeTimeline()
    }

    /**
     * Elevation curve of every followed satellite over a rolling window that
     * starts now. One sample per minute-ish, computed off the main thread.
     */
    fun computeTimeline() {
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) {
            _ui.value = _ui.value.copy(timelineTracks = emptyList(), timelineLoading = false)
            return
        }
        val from = System.currentTimeMillis()
        val to = from + _ui.value.timelineHours * 3_600_000L
        _ui.value = _ui.value.copy(timelineLoading = true, timelineFromMs = from)
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.Default) {
                favs.map { sat ->
                    val samples = predictor.sampleTrack(sat, obs, from, to, steps = 240)
                        .map { (t, _, el) -> t to el }
                    TimelineTrack(sat.catalogNumber, sat.name, samples)
                }.sortedByDescending { it.peakDeg }
            }
            _ui.value = _ui.value.copy(timelineTracks = tracks, timelineLoading = false)
        }
    }

    private val configBackup = fr.f4ioz.satcombo.data.ConfigBackup(app)

    /** Full configuration as JSON (satellites suivis, réglages, config par sat, journal). */
    fun exportConfig(): String = configBackup.export()
    fun configFileName(): String = configBackup.suggestedFileName()

    /**
     * Restore a configuration exported by [exportConfig]. Reloads everything
     * in place so the UI reflects the imported settings immediately.
     */
    fun importConfig(json: String) {
        val summary = configBackup.import(json)
        if (summary == null) {
            _ui.value = _ui.value.copy(error = t("config_invalid"))
            return
        }
        // Re-read every store into the UI state.
        _ui.value = _ui.value.copy(
            favorites = favStore.load(),
            enabledSources = srcStore.load(),
            manualLocator = settings.manualLocator,
            manualLat = settings.manualLat.takeIf { !it.isNaN() },
            manualLon = settings.manualLon.takeIf { !it.isNaN() },
            locationMode = settings.locationMode,
            useUtc = settings.useUtc,
            darkTheme = settings.darkTheme,
            // Sans cette ligne, le sélecteur retombe sur « Sombre » à chaque
            // ouverture, quelle que soit la palette réellement appliquée : la
            // palette était restaurée, son index ne l'était pas.
            themeIndex = settings.themeIndex,
            moletteVfo = settings.moletteVfo,
            molettePasHz = settings.molettePasHz,
            uniformUi = settings.uniformUi,
            uiScaleStep = settings.uiScaleStep,
            uiFollowSystemFont = settings.uiFollowSystemFont,
            language = settings.language,
            statusSource = settings.statusSource,
            compassHeadUp = settings.compassHeadUp,
            compassStyle = settings.compassStyle,
            aimMode = settings.aimMode,
            showAimModeChips = settings.showAimModeChips,
            civAddress = settings.civAddress,
            civBaud = settings.civBaud,
            civUsbIndex = settings.civUsbIndex,
            civUsbAuto = settings.civUsbAuto,
            catSimulated = settings.catSimulated,
            catMonitor = settings.catMonitor,
            catRxDoppler = settings.catRxDoppler,
            rigModel = settings.rigModel,
            ctcssTenthHz = settings.ctcssTenthHz,
            ctcssAuto = settings.ctcssAuto,
            skedsEnabled = settings.skedsEnabled,
            skedsMutualOnly = settings.skedsMutualOnly,
            log = logStore.load(),
            error = null,
            rotor = _ui.value.rotor.copy(
                boussoleSource = settings.boussoleSource,
                boussoleAdresse = settings.boussoleAdresse,
                boussoleNom = settings.boussoleNom,
                boussoleCalage = settings.boussoleCalage,
                boussoleConvention = settings.boussoleConvention,
                boussoleReleves = settings.boussoleReleves,
                boussoleFleche = settings.boussoleFleche,
                rotorEnabled = settings.rotorEnabled,
                rotorBaud = settings.rotorBaud,
                rotorUsbIndex = settings.rotorUsbIndex,
                rotorHost = settings.rotorHost,
                rotorPort = settings.rotorPort,
                rotorMaxAz = settings.rotorMaxAz,
                rotorMaxEl = settings.rotorMaxEl,
                rotorAzStop = settings.rotorAzStop,
                rotorAzFromStop = settings.rotorAzFromStop,
                rotorMaxError = settings.rotorMaxError,
                rotorMinEl = settings.rotorMinEl,
                rotorDeadband = settings.rotorDeadband,
                rotorFlip = settings.rotorFlip,
                rotorAzOnly = settings.rotorAzOnly,
                rotorParkAz = settings.rotorParkAz,
                rotorParkEl = settings.rotorParkEl)
        )
        fr.f4ioz.satcombo.ui.theme.applyTheme(settings.themeIndex)
        fr.f4ioz.satcombo.ui.theme.applyUiScale(
            settings.uniformUi, settings.uiScaleStep, settings.uiFollowSystemFont)
        fr.f4ioz.satcombo.i18n.I18n.apply(settings.language, java.util.Locale.getDefault().language)
        _ui.value = _ui.value.copy(catStatus = summary)
        // Recompute passes with the restored QTH/favorites.
        bootstrap(force = true)
    }

    /**
     * Two or three taps to log. Three is the safe default; two suits an
     * operator whose other hand is on the antenna, at the price of the odd
     * accidental entry. Anything else is refused: one tap would fire on every
     * touch of the compass.
     */
    fun setLogTaps(n: Int) {
        val v = n.coerceIn(2, 3)
        settings.logTaps = v
        _ui.value = _ui.value.copy(logTaps = v)
    }

    fun dismissLogEdit() { _ui.value = _ui.value.copy(logEditTimeMs = null) }

    fun editLogEntry(timeMs: Long) { _ui.value = _ui.value.copy(logEditTimeMs = timeMs) }

    fun setSkedsMutualOnly(on: Boolean) {
        settings.skedsMutualOnly = on
        _ui.value = _ui.value.copy(skedsMutualOnly = on)
    }

    /**
     * True if the announced sat is visible from my QTH during the sked window.
     * Samples elevation across [aos,los]; workable if it rises above ~1° here.
     */
    /**
     * Mutual-visibility window for a sked: the time span where the satellite is
     * above the horizon BOTH at my QTH and at the announced station's grid.
     * Returns null if they never see it together (sked not workable from here).
     */
    fun skedMutualWindow(sked: SkedAlert): Pair<Long, Long>? {
        val obs = _ui.value.observer ?: return null
        val tle = _ui.value.satellites.firstOrNull { it.catalogNumber == sked.satNorad } ?: return null
        val grid = sked.grids.firstOrNull() ?: return null
        val (glat, glon) = Maidenhead.toLatLon(grid) ?: return null
        val other = Observer(glat, glon, 0.1, "sked")

        fun bothElev(t: Long): Boolean {
            val a = runCatching { predictor.positionAt(tle, obs, t).elevationDeg }.getOrDefault(-90.0)
            if (a <= 0.0) return false
            val b = runCatching { predictor.positionAt(tle, other, t).elevationDeg }.getOrDefault(-90.0)
            return b > 0.0
        }

        // The announced aos/los is the station's own window. With an aging TLE the
        // real common pass can sit up to ~40 min away, so search a WIDE bracket
        // (±60 min) and keep the common window nearest the announced center.
        val centerMs = (sked.aosMs + sked.losMs) / 2
        val from = sked.aosMs - 60 * 60_000L
        val to = sked.losMs + 60 * 60_000L
        val coarse = 30_000L

        // Collect every contiguous common interval in the bracket, then pick the
        // one whose midpoint is closest to the announced center.
        val intervals = mutableListOf<Pair<Long, Long>>()
        var segStart: Long? = null; var prev: Long? = null
        var t = from
        while (t <= to) {
            if (bothElev(t)) {
                if (segStart == null) segStart = t
                prev = t
            } else if (segStart != null) {
                intervals.add(segStart!! to (prev ?: segStart!!)); segStart = null
            }
            t += coarse
        }
        if (segStart != null) intervals.add(segStart!! to (prev ?: segStart!!))
        if (intervals.isEmpty()) return null

        val best = intervals.minByOrNull { kotlin.math.abs((it.first + it.second) / 2 - centerMs) }!!
        // Refine the edges at 5 s resolution.
        val step = 5_000L
        var s = (best.first - coarse).coerceAtLeast(from)
        while (s < best.first && !bothElev(s)) s += step
        var e = (best.second + coarse).coerceAtMost(to)
        while (e > best.second && !bothElev(e)) e -= step
        return s to e
    }

    fun isSkedWorkable(sked: SkedAlert): Boolean {
        // Unknown TLE or no grid: keep it visible rather than hide wrongly.
        _ui.value.satellites.firstOrNull { it.catalogNumber == sked.satNorad } ?: return true
        if (sked.grids.isEmpty()) return true
        return skedMutualWindow(sked) != null
    }

    private fun skedVisible(s: SkedAlert): Boolean =
        !_ui.value.skedsMutualOnly || isSkedWorkable(s)

    /** Announced callsigns for a satellite (future skeds), soonest first. */
    /** Age in days of the loaded TLE's epoch for a satellite, or null. */
    fun tleEpochAgeDays(catnum: Int): Double? {
        val sat = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum } ?: return null
        val epoch = sat.epochMs ?: return null
        return (System.currentTimeMillis() - epoch) / 86_400_000.0
    }

    /**
     * Ensure a fresh-enough TLE before relying on a LOCAL sked computation
     * (used when not authenticated, or on a different locator). Forces a
     * single-satellite refresh if the loaded TLE epoch is older than 1 day.
     */
    fun ensureFreshTleForLocalSked(catnum: Int) {
        if (_ui.value.skedsAuthed) return  // hams.at provides authoritative windows
        val age = tleEpochAgeDays(catnum) ?: return
        if (age > 1.0) refreshTleFor(catnum)
    }

    fun skedsFor(catnum: Int): List<SkedAlert> =
        _ui.value.skeds.filter {
            it.satNorad == catnum && it.losMs > System.currentTimeMillis() && skedVisible(it)
        }.sortedBy { it.aosMs }

    /**
     * The announced skeds that fall on a given pass, soonest first. Same matching
     * as [skedCountForPass] — which is now written on top of this — so a badge and
     * the card it stands for can never disagree.
     */
    fun skedsForPass(catnum: Int, aosMs: Long, losMs: Long): List<SkedAlert> {
        val passMid = (aosMs + losMs) / 2
        return _ui.value.skeds.filter { s ->
            if (s.satNorad != catnum) return@filter false
            val sAos = s.workableStartMs ?: s.aosMs
            val sLos = s.workableEndMs ?: s.losMs
            val sMid = (sAos + sLos) / 2
            kotlin.math.abs(sMid - passMid) < 50 * 60_000L && skedVisible(s)
        }.sortedBy { it.workableStartMs ?: it.aosMs }
    }

    /** Count of announced skeds overlapping a given pass window (±a few min). */
    fun skedCountForPass(catnum: Int, aosMs: Long, losMs: Long): Int =
        skedsForPass(catnum, aosMs, losMs).size

    fun setPotaEnabled(on: Boolean) {
        settings.potaEnabled = on
        _ui.value = _ui.value.copy(potaEnabled = on)
        if (on) refreshPota() else _ui.value = _ui.value.copy(nearbyPota = emptyList())
    }

    fun updatePotaRegion(region: PotaRegion) {
        _ui.value = _ui.value.copy(potaUpdating = true)
        viewModelScope.launch {
            val n = potaRepo.updateRegion(region)
            _ui.value = _ui.value.copy(
                potaUpdating = false,
                potaRegionLabel = potaRepo.downloadedRegion,
                potaCount = potaRepo.downloadedCount)
            if (n != null) refreshPota()
        }
    }

    /** Parks inside a map window, for the locator map overlay. */
    suspend fun potaInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double):
            List<PotaPark> = potaRepo.inBounds(minLat, maxLat, minLon, maxLon)

    fun setPotaRadius(km: Int) {
        settings.potaRadiusKm = km
        _ui.value = _ui.value.copy(potaRadiusKm = km)
        refreshPota()
    }

    private fun refreshPota() {
        if (!_ui.value.potaEnabled) return
        val obs = _ui.value.observer ?: return
        viewModelScope.launch {
            val near = potaRepo.near(obs.latDeg, obs.lonDeg, context = getApplication(),
                radiusKm = _ui.value.potaRadiusKm.toDouble())
            _ui.value = _ui.value.copy(nearbyPota = near)
        }
    }

    fun searchCity(q: String) {
        if (q.isBlank()) return
        _ui.value = _ui.value.copy(geoSearching = true)
        viewModelScope.launch {
            val res = geocoder.search(q)
            _ui.value = _ui.value.copy(geoResults = res, geoSearching = false)
        }
    }

    fun clearGeoResults() { _ui.value = _ui.value.copy(geoResults = emptyList()) }

    fun requestDatePicker() { _ui.value = _ui.value.copy(showDatePicker = true) }
    fun dismissDatePicker() { _ui.value = _ui.value.copy(showDatePicker = false) }

    fun setDateFilter(startMs: Long?, endMs: Long?) {
        val filter = if (startMs != null && endMs != null && endMs > startMs)
            startMs to minOf(endMs, startMs + MAX_PASS_DAYS * 86_400_000L) else null
        _ui.value = _ui.value.copy(dateFilter = filter, showDatePicker = false)
        computeFavoritePasses()
    }

    fun clearDateFilter() {
        _ui.value = _ui.value.copy(dateFilter = null)
        computeFavoritePasses()
    }

    /**
     * Rallonge la liste de deux jours, à chaque fois qu'on arrive au bas de
     * l'écran, jusqu'à quinze jours.
     *
     * On recalcule tout depuis le début plutôt que de coller la suite au bout :
     * la prédiction est déterministe et son coût reste linéaire, alors qu'un
     * assemblage de morceaux finirait par laisser passer un passage à cheval
     * sur la couture.
     */
    fun extendPassHorizon() {
        if (_ui.value.dateFilter != null) return
        if (_ui.value.favPassesLoading) return
        val cur = _ui.value.passHorizonHours
        val max = MAX_PASS_DAYS * 24
        if (cur >= max) return
        _ui.value = _ui.value.copy(passHorizonHours = minOf(cur + 48, max))
        computeFavoritePasses()
    }

    fun setMapStyle(style: String) {
        settings.mapStyle = style
        _ui.value = _ui.value.copy(mapStyle = style)
    }

    fun setNotifyEnabled(on: Boolean) {
        settings.notifyEnabled = on
        _ui.value = _ui.value.copy(notifyEnabled = on)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    fun setNotifyMode(mode: String) {
        settings.notifyMode = mode
        _ui.value = _ui.value.copy(notifyMode = mode)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    /** Toggle a bell on a specific pass (targeted notifications). */
    fun togglePassNotify(p: fr.f4ioz.satcombo.data.SatPass) {
        val key = passKey(p)
        val cur = _ui.value.notifiedPassKeys.toMutableSet()
        if (!cur.add(key)) cur.remove(key)
        settings.notifiedPassKeys = cur
        _ui.value = _ui.value.copy(notifiedPassKeys = cur)
        schedulePassAlerts(_ui.value.favoritePasses)
    }
    fun isPassNotified(p: fr.f4ioz.satcombo.data.SatPass) = passKey(p) in _ui.value.notifiedPassKeys

    fun setNotifyLead(min: Int) {
        settings.notifyLeadMin = min
        _ui.value = _ui.value.copy(notifyLeadMin = min)
        schedulePassAlerts(_ui.value.favoritePasses)
    }

    private fun schedulePassAlerts(passes: List<SatPass>) {
        val wm = WorkManager.getInstance(getApplication())
        wm.cancelAllWorkByTag("pass_alert")
        if (!_ui.value.notifyEnabled) return
        val lead = _ui.value.notifyLeadMin * 60_000L
        val now = System.currentTimeMillis()

        // In TARGET mode, only bell-marked passes fire; look across favourite
        // AND general passes so any pass the user tapped a bell on is covered.
        val candidates = if (_ui.value.notifyMode == "TARGET") {
            val keys = _ui.value.notifiedPassKeys
            (passes + _ui.value.passes).distinctBy { passKey(it) }
                .filter { passKey(it) in keys }
        } else {
            // Mode FAV, celui par défaut : seuls les satellites cochés en
            // favori réveillent l’opérateur. La liste reçue ici est déjà celle
            // des favoris, mais elle est calculée ailleurs ; le filtre explicite
            // coûte une ligne et garantit qu’aucun passage d’un satellite non
            // choisi ne peut sonner à trois heures du matin.
            val favs = _ui.value.favorites
            passes.filter { it.catalogNumber in favs }
        }

        candidates.filter { it.aosEpochMs - lead > now }
            .sortedBy { it.aosEpochMs }
            .take(20)
            .forEach { p ->
                val req = OneTimeWorkRequestBuilder<PassAlertWorker>()
                    .setInitialDelay(p.aosEpochMs - lead - now, TimeUnit.MILLISECONDS)
                    .addTag("pass_alert")
                    .setInputData(workDataOf(
                        "name" to p.satName, "cat" to p.catalogNumber,
                        "aos" to p.aosEpochMs, "los" to p.losEpochMs,
                        "maxEl" to p.maxElevationDeg,
                        "aosAz" to p.aosAzimuthDeg, "losAz" to p.losAzimuthDeg,
                        "sunlit" to p.sunlit, "night" to p.nightAtObserver))
                    .build()
                wm.enqueue(req)
            }
    }

    fun setLanguage(pref: String) {
        settings.language = pref
        fr.f4ioz.satcombo.i18n.I18n.apply(pref, java.util.Locale.getDefault().language)
        _ui.value = _ui.value.copy(language = pref)
    }

    fun setDarkTheme(on: Boolean) = setThemeIndex(if (on) 0 else 1)

    fun setThemeIndex(i: Int) {
        settings.themeIndex = i
        fr.f4ioz.satcombo.ui.theme.applyTheme(i)
        _ui.value = _ui.value.copy(darkTheme = i == 0, themeIndex = i)
    }

    /** Uniform layout master switch — off falls back to the device's own dp. */
    fun setUniformUi(on: Boolean) {
        settings.uniformUi = on
        fr.f4ioz.satcombo.ui.theme.applyUiScale(on, settings.uiScaleStep, settings.uiFollowSystemFont)
        _ui.value = _ui.value.copy(uniformUi = on)
    }

    /** -1 compact / 0 normal / +1 large. */
    fun setUiScaleStep(step: Int) {
        settings.uiScaleStep = step
        val v = settings.uiScaleStep
        fr.f4ioz.satcombo.ui.theme.applyUiScale(settings.uniformUi, v, settings.uiFollowSystemFont)
        _ui.value = _ui.value.copy(uiScaleStep = v)
    }

    fun setUiFollowSystemFont(on: Boolean) {
        settings.uiFollowSystemFont = on
        fr.f4ioz.satcombo.ui.theme.applyUiScale(settings.uniformUi, settings.uiScaleStep, on)
        _ui.value = _ui.value.copy(uiFollowSystemFont = on)
    }

    fun setStatusSource(s: String) {
        settings.statusSource = s
        _ui.value = _ui.value.copy(statusSource = s)
    }

    fun setTleCacheHours(h: Int) {
        settings.tleCacheHours = h
        _ui.value = _ui.value.copy(tleCacheHours = settings.tleCacheHours)
    }

    fun setCompassStyle(style: String) {
        settings.compassStyle = style
        _ui.value = _ui.value.copy(compassStyle = style)
    }

    fun setCompassHeadUp(on: Boolean) {
        settings.compassHeadUp = on
        _ui.value = _ui.value.copy(compassHeadUp = on)
    }

    fun setShowAimModeChips(on: Boolean) {
        settings.showAimModeChips = on
        _ui.value = _ui.value.copy(showAimModeChips = on)
    }

    fun setAimMode(mode: String) {
        settings.aimMode = mode
        _ui.value = _ui.value.copy(aimMode = mode)
    }

    // ---- La boussole déportée ----

    fun setBoussoleSource(src: String) {
        settings.boussoleSource = src
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleSource = src))
    }

    /** Retient le module choisi dans la liste, sans s'y connecter. */
    fun setBoussoleModule(nom: String, adresse: String) {
        settings.boussoleNom = nom
        settings.boussoleAdresse = adresse
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(
            boussoleNom = nom, boussoleAdresse = adresse))
    }

    fun setBoussoleCalage(deg: Float) {
        settings.boussoleCalage = deg
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleCalage = deg))
    }

    /** Ajoute une pose au relevé, ou remplace celle qui porte déjà cette clé. */
    fun ajouteReleve(cle: String, roulis: Float, tangage: Float, lacet: Float) {
        val liste = fr.f4ioz.satcombo.domain.SequenceCalibrage
            .decode(settings.boussoleReleves)
            .filterNot { it.cle == cle } +
            fr.f4ioz.satcombo.domain.SequenceCalibrage.Releve(cle, roulis, tangage, lacet)
        // Rangées dans l'ordre de la séquence, pas dans celui des gestes :
        // un rapport qui suit l'ordre des poses se relit, un autre non.
        val ordonnees = fr.f4ioz.satcombo.domain.SequenceCalibrage.ETAPES
            .mapNotNull { e -> liste.firstOrNull { it.cle == e.cle } }
        val texte = fr.f4ioz.satcombo.domain.SequenceCalibrage.encode(ordonnees)
        settings.boussoleReleves = texte
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleReleves = texte))
    }

    fun videReleves() {
        settings.boussoleReleves = ""
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleReleves = ""))
    }

    /**
     * Compose la télémétrie envoyée aux spectateurs.
     *
     * Du JSON écrit à la main : la structure tient en six champs, et ajouter un
     * sérialiseur pour cela serait une dépendance de plus à entretenir.
     *
     * La trace du passage est réduite à un point sur trois — soixante points
     * suffisent à dessiner un arc, et trois fois moins de texte à envoyer
     * quatre fois par minute à dix spectateurs.
     */
    /**
     * Compose la télémétrie envoyée aux spectateurs.
     *
     * Du JSON écrit à la main : la structure tient en huit champs, et ajouter
     * un sérialiseur pour cela serait une dépendance de plus à entretenir.
     *
     * Elle lit l'état plutôt que de recevoir des arguments : ainsi elle
     * fonctionne depuis n'importe quel écran, même quand aucune poursuite ne
     * tourne — c'est tout l'objet de la correction.
     *
     * La trace du passage est réduite à un point sur trois : soixante points
     * suffisent à dessiner un arc, et trois fois moins de texte à envoyer
     * chaque seconde à dix spectateurs.
     */
    /**
     * Les deux fréquences affichées : descente et montée, Doppler compris.
     *
     * **Une seule fonction, deux appelants.** L'écran de passage et la page du
     * public la calculaient chacun de leur côté, et elles ont divergé : cinq
     * kilohertz d'écart en réception, presque deux en émission. C'est la
     * troisième fois de la session que deux chemins pour une même question
     * finissent par ne plus dire la même chose, et la règle est toujours la
     * même — il n'en faut qu'un.
     */
    fun freqAffichees(): Pair<Long?, Long?> {
        val u = _ui.value
        val actifs = activeTransmitters()
        if (actifs.isEmpty()) return null to null
        val tx = actifs[u.selectedTxIndex.coerceIn(0, actifs.size - 1)]
        val rr = u.livePosition?.rangeRateKmS
        val dlLow = tx.downlinkLowHz
        val dlHigh = tx.downlinkHighHz
        val ulLow = tx.uplinkLowHz
        val ulHigh = tx.uplinkHighHz
        val rxRest = u.rxRestHz ?: dlLow
        val rxOff = if (u.opMode == "CW") u.rxOffsetCwHz else u.rxOffsetVoiceHz
        val effInvert = u.invertOverride ?: tx.invert
        val rxShown = rxRest?.let { base ->
            (rr?.let { Doppler.downlink(base, it) } ?: base) + u.calibShiftHz +
                (if (tx.isTransponder) rxOff else 0L)
        }
        val txRest = when {
            tx.isTransponder && rxRest != null && dlLow != null && dlHigh != null &&
                ulLow != null && ulHigh != null ->
                Doppler.transponderUplinkRest(rxRest, dlLow, dlHigh, ulLow, ulHigh,
                    effInvert)
            else -> ulLow
        }
        val txShown = txRest?.let { base ->
            (rr?.let { Doppler.uplink(base, it) } ?: base) + u.txShiftHz
        }
        return rxShown to txShown
    }

    private var dernierApercu: android.graphics.Bitmap? = null

    private fun publieDemo() {
        val u = _ui.value
        val pos = u.livePosition
        val nom = u.selected?.name ?: "—"
        val trace = u.passTrack.filterIndexed { i, _ -> i % 3 == 0 }
            .joinToString(",") {
                "[%.1f,%.1f]".format(java.util.Locale.US, it.first, it.second)
            }
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date()) + " UTC"
        val az = pos?.azimuthDeg ?: 0.0
        val el = if (pos == null) "null"
                 else "%.1f".format(java.util.Locale.US, pos.elevationDeg)
        // Les mêmes fréquences que l'écran, parce que c'est la même fonction.
        val (calcRx, calcTx) = freqAffichees()
        val rx = (u.catRadioDownlinkHz ?: calcRx)?.toString() ?: "null"
        val tx = (u.catRadioUplinkHz ?: calcTx)?.toString() ?: "null"

        // Le passage en cours ou le prochain : acquisition et perte du signal,
        // élévation maximale. C'est ce qui donne au public le sens de ce qui se
        // joue — dix minutes, pas davantage.
        val passage = u.focusedPassAos
            ?.let { f -> u.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: u.passes.firstOrNull { it.losEpochMs > u.nowMs }
        val aos = passage?.aosEpochMs?.toString() ?: "null"
        val los = passage?.losEpochMs?.toString() ?: "null"
        val elMax = passage?.let { "%.0f".format(java.util.Locale.US, it.maxElevationDeg) }
            ?: "null"

        // L'orientation réelle de l'antenne, quand la boussole la donne : c'est
        // ce que le public voit bouger dans les mains de l'opérateur, et le
        // rapprocher de la position du satellite rend le pointage évident.
        // Le cap **effectif** : module déporté s'il parle, boussole du
        // téléphone sinon. Lire le module directement ne montrait rien dès que
        // l'opérateur se servait du téléphone, c'est-à-dire la plupart du temps.
        val capAnt = fr.f4ioz.satcombo.domain.CapVivant.azimutDeg
        val elAnt = fr.f4ioz.satcombo.domain.CapVivant.elevationDeg
        val antAz = capAnt?.let { "%.0f".format(java.util.Locale.US, it) } ?: "null"
        val antEl = elAnt?.let { "%.0f".format(java.util.Locale.US, it) } ?: "null"
        val propre = nom.replace("\\", " ").replace("\"", " ")
        // L'image SSTV, encodée seulement quand elle change : un aperçu pèse
        // quelques dizaines de kilo-octets, et le ré-encoder chaque seconde
        // pour rien chaufferait le téléphone toute la durée du passage.
        val apercu = fr.f4ioz.satcombo.sstv.SstvHub.state.value.preview
        if (apercu != null && apercu !== dernierApercu) {
            dernierApercu = apercu
            runCatching {
                val flux = java.io.ByteArrayOutputStream()
                apercu.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, flux)
                fr.f4ioz.satcombo.demo.ServeurDemo.poseImage(flux.toByteArray())
            }
        }
        val station = settings.callsign.replace("\"", " ")
        val grille = myLocator()
        fr.f4ioz.satcombo.demo.ServeurDemo.publie(
            "{\"station\":\"" + station + "\",\"grille\":\"" + grille + "\"," +
                "\"img\":" + fr.f4ioz.satcombo.demo.ServeurDemo.versionImage() + "," +
                "\"qso\":[" + fr.f4ioz.satcombo.demo.ServeurDemo.contactsJson() + "]," +
                "\"sat\":\"" + propre + "\"," +
                "\"az\":" + "%.1f".format(java.util.Locale.US, az) + "," +
                "\"el\":" + el + ",\"rx\":" + rx + ",\"tx\":" + tx + "," +
                "\"antaz\":" + antAz + ",\"antel\":" + antEl + "," +
                "\"aos\":" + aos + ",\"los\":" + los + ",\"elmax\":" + elMax + "," +
                "\"now\":" + u.nowMs + "," +
                "\"son\":" + fr.f4ioz.satcombo.demo.ServeurDemo.sonDisponible() + "," +
                "\"ptt\":" + u.catUi.enEmission + ",\"heure\":\"" + heure + "\"," +
                "\"trace\":[" + trace + "]}")
    }

    /**
     * Retient le partage de connexion annoncé par le QR code.
     *
     * Deux écritures, une seule vérité : le serveur la porte pendant la séance,
     * les réglages ne font que s'en souvenir d'une fois sur l'autre.
     */
    fun setDemoWifi(ssid: String, motDePasse: String) {
        settings.demoSsid = ssid
        settings.demoMotDePasse = motDePasse
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(ssid, motDePasse)
    }

    /** Retient l'adresse de la station écoutée, pour ne pas la ressaisir. */
    fun setEcouteAdresse(v: String) { settings.ecouteAdresse = v }

    fun ecouteAdresse(): String = settings.ecouteAdresse

    fun setBoussoleConvention(nom: String) {
        settings.boussoleConvention = nom
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleConvention = nom))
    }

    /**
     * Retient la flèche apprise, ou l'oublie.
     *
     * `null` remet l'élévation au téléphone et le cap sur le lacet seul : c'est
     * ce qu'il faut après un démontage, tant que le nouveau relevé n'est pas
     * fait. Mieux vaut un cadran qui dit ne pas savoir qu'un cadran faux.
     */
    fun setBoussoleFleche(v: fr.f4ioz.satcombo.domain.Vec3?) {
        val t = v?.let { fr.f4ioz.satcombo.domain.PointageAntenne.enTexte(it) } ?: ""
        settings.boussoleFleche = t
        _ui.value = _ui.value.copy(rotor = _ui.value.rotor.copy(boussoleFleche = t))
    }

    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }
    fun setSatActiveOnly(on: Boolean) { _ui.value = _ui.value.copy(satActiveOnly = on) }

    fun toggleSource(id: String) {
        val cur = _ui.value.enabledSources.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        if (cur.isEmpty()) return
        srcStore.save(cur)
        _ui.value = _ui.value.copy(enabledSources = cur)
        bootstrap(force = true)
    }

    fun toggleFavorite(catnum: Int) {
        val updated = favStore.toggle(catnum)
        _ui.value = _ui.value.copy(favorites = updated)
        computeFavoritePasses()
    }

    fun toggleVisualOnly() {
        _ui.value = _ui.value.copy(visualOnly = !_ui.value.visualOnly)
    }

    // ---------- passes ----------

    private companion object {
        /**
         * Profondeur maximale des prédictions, en jours. Au-delà, un jeu
         * d'éléments orbitaux vieillit assez pour que l'heure annoncée dérive
         * de plusieurs minutes : mieux vaut ne rien promettre.
         */
        const val MAX_PASS_DAYS = 15

        /**
         * Garde-fou sur le nombre de passages gardés en mémoire. Quinze jours,
         * trente favoris et une élévation minimale basse font environ deux
         * mille lignes : le plafond doit rester au-dessus de ce cas réel, sinon
         * il redevient la coupure silencieuse qu'on vient d'enlever.
         */
        const val MAX_PASSES = 4000
    }

    private fun computeFavoritePasses() {
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) {
            _ui.value = _ui.value.copy(favoritePasses = emptyList(), favPassesLoading = false)
            return
        }
        _ui.value = _ui.value.copy(favPassesLoading = true)
        viewModelScope.launch {
            val filter = _ui.value.dateFilter
            val now = System.currentTimeMillis()
            // "Passages passés": the lists reach back this far so the pass just
            // worked stays available (polar plot included) to write up the log.
            val back = maxOf(_ui.value.pastPassHours * 3_600_000L, 20 * 60_000L)
            val from: Long; val hours: Int; val windowEnd: Long
            if (filter != null) {
                from = maxOf(filter.first, now - back)
                windowEnd = filter.second
                hours = (((windowEnd - from) / 3_600_000L).toInt() + 1)
                    .coerceIn(1, MAX_PASS_DAYS * 24 + 24)
            } else {
                from = now - back
                windowEnd = now + _ui.value.passHorizonHours * 3_600_000L
                hours = _ui.value.passHorizonHours + _ui.value.pastPassHours
            }
            val merged = withContext(Dispatchers.Default) {
                favs.flatMap { predictor.upcomingPasses(it, obs, fromMs = from, hours = hours, minElDeg = settings.minElevDeg.toDouble()) }
                    .filter { it.losEpochMs > now - back && it.aosEpochMs < windowEnd }
                    .sortedBy { it.aosEpochMs }
                    // Plafond de sécurité, et rien d'autre. L'ancienne limite de
                    // cent vingt tombait APRÈS le tri : sur une période choisie
                    // au calendrier avec beaucoup de favoris, les derniers jours
                    // disparaissaient sans un mot, et le compteur affiché comptait
                    // la liste déjà coupée, si bien que tout avait l'air normal.
                    .take(MAX_PASSES)
            }
            _ui.value = _ui.value.copy(favoritePasses = merged, favPassesLoading = false)
            // Never ring for something already gone.
            schedulePassAlerts(merged.filter { it.losEpochMs > now })
        }
    }

    private fun centreRx(t: Transmitter): Long? {
        val lo = t.downlinkLowHz ?: return null
        val hi = t.downlinkHighHz ?: lo
        return (lo + hi) / 2
    }

    fun activeTransmitters(): List<Transmitter> =
        _ui.value.transmitters.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }

    /**
     * Se poser n'importe où dans la bande, et **laisser le transpondeur
     * suivre**.
     *
     * La réglette dessine tout le plan de QO-100, mais `setRxRest` ramenait de
     * force dans le transpondeur sélectionné : on voyait où aller sans pouvoir
     * y aller, et il fallait choisir le bon transpondeur dans une liste de
     * quinze avant de pouvoir s'y déplacer. C'est l'inverse du geste — on
     * cherche d'abord une station, on découvre ensuite dans quel segment elle
     * est.
     *
     * Le transpondeur est donc une **conséquence** de la fréquence, non une
     * condition d'y accéder. Si la fréquence visée tombe dans un autre
     * transpondeur, on bascule dessus ; si elle ne tombe dans aucun — une
     * balise, un intervalle — on garde celui qui est choisi et l'on s'y pose
     * quand même, parce qu'écouter n'oblige à rien.
     */
    fun allerLibre(satHz: Long) {
        val liste = activeTransmitters()
        val i = liste.indexOfFirst { t ->
            val lo = t.downlinkLowHz
            val hi = t.downlinkHighHz ?: lo
            lo != null && hi != null && satHz in minOf(lo, hi)..maxOf(lo, hi)
        }
        if (i >= 0 && i != _ui.value.selectedTxIndex) {
            liste.getOrNull(i)?.let { t ->
                _ui.value.selected?.let {
                    satConfigStore.saveTransmitter(it.catalogNumber, t.description)
                }
            }
            catArmedFor = null
            _ui.value = _ui.value.copy(selectedTxIndex = i)
        }
        // Sans bornage au transpondeur : la réglette est le plan de bande
        // entier, et l'on doit pouvoir se poser partout où elle est dessinée.
        _ui.value = _ui.value.copy(rxRestHz = satHz, rxFromAgendaHz = null)
    }

    fun selectTransmitter(index: Int) {
        val t = activeTransmitters().getOrNull(index) ?: return
        _ui.value.selected?.let { satConfigStore.saveTransmitter(it.catalogNumber, t.description) }
        catArmedFor = null
        _ui.value = _ui.value.copy(selectedTxIndex = index, rxRestHz = centreRx(t),
            rxFromAgendaHz = null)
    }

    fun openSatConfig() { _ui.value = _ui.value.copy(showSatConfig = true) }
    fun closeSatConfig() { _ui.value = _ui.value.copy(showSatConfig = false) }

    fun setCalibShift(hz: Long) {
        _ui.value.selected?.let { satConfigStore.saveShift(it.catalogNumber, hz) }
        _ui.value = _ui.value.copy(calibShiftHz = hz)
    }

    /**
     * La montée au repos : la fréquence sur laquelle on émet réellement,
     * Doppler ôté.
     *
     * Le carnet inscrivait jusqu'ici `uplinkLowHz` — le **bord bas** du
     * transpondeur. Sur FO-29, cela revenait à déclarer 145,950 quel que soit
     * l'endroit du passband où le contact a eu lieu : une valeur constante,
     * donc sans information, et fausse pour tout le monde sauf celui qui
     * travaille tout en bas de la bande.
     *
     * La vraie montée se déduit de la descente choisie, par la même règle que
     * le moteur Doppler applique quatre fois par seconde — inversion comprise,
     * puisque sur un transpondeur inverseur monter d'un kilohertz fait
     * descendre d'autant. Le décalage d'émission de l'opérateur s'y ajoute :
     * il fait partie de la fréquence sur laquelle il a réellement émis.
     *
     * C'est la fréquence **au repos** qui part au carnet, et non celle qui a
     * été envoyée au poste : le Doppler d'un instant décrit la géométrie du
     * passage, pas le créneau du transpondeur. Deux stations qui se
     * répondent inscrivent ainsi la même chose.
     */
    fun monteeAuRepos(): Long? {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return null
        val rxRest = _ui.value.rxRestHz ?: centreRx(t) ?: t.downlinkLowHz ?: return null
        val base = when {
            t.isTransponder && t.downlinkLowHz != null && t.uplinkLowHz != null ->
                Doppler.transponderUplinkRest(
                    rxRest, t.downlinkLowHz!!, t.downlinkHighHz ?: t.downlinkLowHz!!,
                    t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
            else -> t.uplinkLowHz
        } ?: return null
        return base + _ui.value.txShiftHz
    }

    /** La descente au repos, telle qu'elle part au carnet. */
    fun descenteAuRepos(): Long? {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        return _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: t?.downlinkLowHz
    }

    /** TX (uplink) shift in Hz, persisted per satellite. */
    fun setTxShift(hz: Long) {
        _ui.value.selected?.let { satConfigStore.saveTxShift(it.catalogNumber, hz) }
        _ui.value = _ui.value.copy(txShiftHz = hz)
    }
    fun nudgeTxShift(deltaHz: Long) = setTxShift(_ui.value.txShiftHz + deltaHz)

    // ---- les décalages de référence ----

    /**
     * Mémorise les décalages courants comme référence pour ce satellite.
     *
     * L'opérateur le fait quand il juge que c'est bon. Un enregistrement
     * automatique ne saurait pas distinguer le réglage qui converge de la
     * molette qu'on a effleurée.
     */
    fun memoriseDecalages() {
        val sat = _ui.value.selected ?: return
        satConfigStore.memoriseReference(
            sat.catalogNumber, _ui.value.calibShiftHz, _ui.value.txShiftHz)
        _ui.value = _ui.value.copy(
            catUi = _ui.value.catUi.copy(
                refCalibShiftHz = _ui.value.calibShiftHz,
                refTxShiftHz = _ui.value.txShiftHz),
            satStatus = tf("shift_saved",
                _ui.value.calibShiftHz, _ui.value.txShiftHz))
    }

    /**
     * Revient aux décalages tels qu'ils étaient en ouvrant ce satellite.
     *
     * Le filet pour qui n'a rien mémorisé. Il ne prétend pas restaurer un bon
     * réglage : il défait ce qui a été fait depuis l'ouverture, ce qui est
     * exactement ce qu'on veut après avoir effleuré la molette.
     */
    fun rappelleArrivee() {
        val sat = _ui.value.selected ?: return
        val calib = _ui.value.catUi.arriveeCalibShiftHz ?: return
        val tx = _ui.value.catUi.arriveeTxShiftHz ?: 0L
        satConfigStore.saveShift(sat.catalogNumber, calib)
        satConfigStore.saveTxShift(sat.catalogNumber, tx)
        _ui.value = _ui.value.copy(calibShiftHz = calib, txShiftHz = tx,
            satStatus = tf("shift_back", calib, tx))
    }

    /** Revient aux décalages mémorisés pour ce satellite. */
    fun rappelleDecalages() {
        val sat = _ui.value.selected ?: return
        val cfg = satConfigStore.load(sat.catalogNumber)
        val calib = cfg.refCalibShiftHz ?: return
        val tx = cfg.refTxShiftHz ?: 0L
        satConfigStore.saveShift(sat.catalogNumber, calib)
        satConfigStore.saveTxShift(sat.catalogNumber, tx)
        _ui.value = _ui.value.copy(calibShiftHz = calib, txShiftHz = tx,
            satStatus = tf("shift_recalled", calib, tx))
    }

    // ---- le lot de contacts, d'un téléphone à l'autre ----

    /** Sérialise les contacts choisis, désignés par leur heure. */
    fun ecritLotContacts(heures: Set<Long>): String =
        fr.f4ioz.satcombo.data.LotContacts.ecrit(
            _ui.value.log.filter { it.timeMs in heures && it.callsign.isNotBlank() },
            settings.callsign)

    /** Nom proposé pour le fichier du lot. */
    fun nomLotContacts(): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "satme-contacts-" + f.format(java.util.Date()) + ".json"
    }

    /**
     * Fond un lot reçu dans le carnet local.
     *
     * Rend le bilan en clair. **Rien n'est écrasé** : la règle est au domaine,
     * avec son banc. Les désaccords sont comptés et non résolus — rien dans une
     * entrée ne dit laquelle des deux valeurs a été corrigée en dernier.
     */
    fun fusionneLotContacts(json: String) {
        val entrant = fr.f4ioz.satcombo.data.LotContacts.lit(json)
        if (entrant == null) {
            _ui.value = _ui.value.copy(catStatus = t("lot_illisible"))
            return
        }
        val b = fr.f4ioz.satcombo.data.LotContacts.fusionne(_ui.value.log, entrant)
        logStore.save(b.fondu)
        _ui.value = _ui.value.copy(log = b.fondu,
            express = _ui.value.express.copy(memoire = construitMemoire()),
            catUi = _ui.value.catUi.copy(lotBilan = tf("lot_bilan", b.ajoutes, b.completes, b.identiques, b.desaccords)))
    }

    fun effaceBilanLot() {
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(lotBilan = ""))
    }

    /**
     * Délai avant que le logiciel ne reprenne la molette de réception.
     *
     * Les deux arbitres sont réglés ensemble : celui du transpondeur linéaire
     * et celui de la FM. Un opérateur qui trouve deux secondes trop longues les
     * trouve trop longues partout.
     */
    fun setCatHold(ms: Int) {
        val v = ms.coerceIn(200, 5_000)
        settings.catHoldMs = v
        rxArbiter.regle(v.toLong())
        fmArbiter.regle(v.toLong())
        txArbiter.regle(v.toLong())
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(holdMs = v))
    }

    /**
     * La molette d'émission tient lieu de commande de décalage.
     *
     * Réservé au duplex à deux postes. En mono-poste avec split, la montée est
     * le VFO B, que le poste n'expose pas de la même façon pendant qu'on écoute
     * sur le A : relire ce VFO-là n'est pas fiable d'un modèle à l'autre, et un
     * interrupteur qui ne marche qu'une fois sur deux vaut moins qu'un
     * interrupteur absent.
     */
    fun setCatTxVfoShift(on: Boolean) {
        settings.catTxVfoShift = on
        txArbiter.reset()
        mainSurMoletteTx = false
        _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txVfoShift = on))
    }

    /** Operating sub-mode for linear birds: VOICE (SSB) or CW. */
    fun setOpMode(mode: String) {
        _ui.value = _ui.value.copy(opMode = mode)
        catArmedFor = null  // re-arm so the rig mode (USB/LSB/CW) updates
    }

    /** Per-mode RX offset, persisted per satellite. */
    fun setRxOffset(hz: Long) {
        if (_ui.value.opMode == "CW") {
            _ui.value.selected?.let { satConfigStore.saveRxOffsetCw(it.catalogNumber, hz) }
            _ui.value = _ui.value.copy(rxOffsetCwHz = hz)
        } else {
            _ui.value.selected?.let { satConfigStore.saveRxOffsetVoice(it.catalogNumber, hz) }
            _ui.value = _ui.value.copy(rxOffsetVoiceHz = hz)
        }
    }
    fun nudgeRxOffset(deltaHz: Long) =
        setRxOffset((if (_ui.value.opMode == "CW") _ui.value.rxOffsetCwHz else _ui.value.rxOffsetVoiceHz) + deltaHz)

    /** The active RX offset for the current op-mode. */
    private fun activeRxOffset(): Long =
        if (_ui.value.opMode == "CW") _ui.value.rxOffsetCwHz else _ui.value.rxOffsetVoiceHz

    /** Manual NOR/REV override for linear transponders (null = use SatNOGS data). */
    fun setInvertOverride(value: Boolean?) {
        _ui.value = _ui.value.copy(invertOverride = value)
    }
    /** Effective inversion: manual override if set, else the transmitter's flag. */
    private fun effectiveInvert(t: fr.f4ioz.satcombo.data.Transmitter): Boolean =
        _ui.value.invertOverride ?: t.invert

    /** Set the chosen RX (downlink) REST frequency, clamped to the passband. */
    /**
     * La molette déplace le VFO, et **la montée suit**.
     *
     * Elle ne bougeait que le décalage d'écoute : on s'éloignait de son
     * correspondant en réception tout en continuant d'émettre au même endroit.
     * Sur un transpondeur linéaire c'est le contraire de ce qu'on veut — le
     * geste naturel est celui du VFO d'un poste satellite, où déplacer la
     * réception déplace l'émission par la loi du transpondeur.
     *
     * On agit donc sur la même valeur que le curseur de l'écran, dont la
     * montée se déduit déjà. Un seul chemin pour un seul geste : ajouter un
     * second calcul de la montée pour la molette aurait fini par diverger de
     * celui du curseur.
     *
     * Hors transpondeur — une balise, un relais FM — il n'y a pas de montée à
     * suivre, et le décalage d'écoute reste le bon réglage : c'est là qu'on
     * corrige une dérive de réception sans toucher à l'émission.
     */
    private fun moletteDeplaceVfo(deltaHz: Long) {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        if (t?.isTransponder != true) {
            nudgeRxOffset(deltaHz)
            return
        }
        val actuel = _ui.value.rxRestHz ?: t.downlinkLowHz ?: return
        // Sur QO-100 la molette traverse les transpondeurs comme la réglette :
        // buter au bord obligerait à lâcher la molette pour aller changer de
        // transpondeur dans une liste, au milieu d'un balayage.
        if (_ui.value.selected?.catalogNumber == fr.f4ioz.satcombo.domain.Qo100.NORAD)
            allerLibre(actuel + deltaHz)
        else setRxRest(actuel + deltaHz)
    }

    fun setRxRest(hz: Long) {
        // **Le transpondeur suit le doigt.**
        //
        // La réglette montre tout le plan de bande, mais le curseur restait
        // bridé au transpondeur sélectionné : on touchait un segment lointain
        // et rien ne bougeait. Pour parcourir la bande il fallait aller
        // choisir chaque transpondeur un par un dans une liste de quinze —
        // c'est-à-dire renoncer à la parcourir.
        //
        // Quand la fréquence visée tombe dans un autre transpondeur, on s'y
        // place. C'est le geste qu'on ferait sur un poste : on tourne, et l'on
        // arrive où l'on arrive. Une coche de déverrouillage n'aurait rien
        // ajouté — il n'y a pas de raison de vouloir rester enfermé.
        val tous = activeTransmitters()
        val ailleurs = tous.indexOfFirst { autre ->
            val b = autre.downlinkLowHz
            val h = autre.downlinkHighHz
            b != null && h != null && hz in minOf(b, h)..maxOf(b, h)
        }
        if (ailleurs >= 0 && ailleurs != _ui.value.selectedTxIndex) {
            _ui.value = _ui.value.copy(selectedTxIndex = ailleurs)
        }
        val t = tous.getOrNull(_ui.value.selectedTxIndex) ?: return
        val lo = t.downlinkLowHz ?: return
        val hi = t.downlinkHighHz ?: lo
        // L’opérateur qui touche au curseur reprend la main : la mention
        // « fréquence de l’agenda » disparaît, elle serait devenue fausse.
        _ui.value = _ui.value.copy(
            rxRestHz = hz.coerceIn(minOf(lo, hi), maxOf(lo, hi)), rxFromAgendaHz = null)
    }

    /**
     * Ce que déplace le vernier de la carte SDR.
     *
     * Il y avait deux accords qui s'ignoraient : le VFO satellite — le canal
     * dans la bande du transpondeur, celui qui produit les lignes RX et TX et
     * qu'on reporte au poste — et l'accord de la clé SDR. Le vernier ne
     * touchait que le second : le curseur ne bougeait pas, la ligne RX ne
     * suivait pas, et l'opérateur ne savait plus sur quelle fréquence il se
     * trouvait réellement.
     *
     * Une seule grandeur désormais. Sur un transpondeur, le vernier déplace le
     * canal : le curseur suit, la ligne RX suit, l'émission part en miroir — à
     * l'envers si le transpondeur est inverse, ce qui est exactement ce qu'il
     * faut pour rester sur son correspondant — et la clé se réaccorde derrière,
     * donc la cascade reste cohérente.
     */
    fun vernierPas(deltaHz: Long) {
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val bas = t?.downlinkLowHz
        val haut = t?.downlinkHighHz
        val cible = fr.f4ioz.satcombo.domain.AccordFin.cibleDuVernier(
            t?.isTransponder == true, bas, haut)
        if (cible == fr.f4ioz.satcombo.domain.AccordFin.Cible.CANAL && bas != null && haut != null) {
            val actuel = _ui.value.rxRestHz ?: centreRx(t!!) ?: bas
            setRxRest(fr.f4ioz.satcombo.domain.AccordFin.nouveauCanal(
                actuel, deltaHz, bas, haut))
        } else {
            sdrPasFin(deltaHz)
        }
    }

    fun passKey(p: fr.f4ioz.satcombo.data.SatPass) = "${p.catalogNumber}@${p.aosEpochMs}"

    // ------------------------------------------------- Doppler du passage

    private var dopplerPassKey: String = ""
    private var dopplerPassTable = fr.f4ioz.satcombo.domain.DopplerPass.Table()

    /**
     * Le tableau du Doppler pour le passage montré, calculé à la demande.
     *
     * Ce chemin-là ne passe par aucun garde `si le satellite est au-dessus de
     * l'horizon` : c'était précisément le défaut. La vitesse radiale existe
     * aussi bien pour un passage à venir, et c'est avant le passage qu'on veut
     * savoir où poser le VFO.
     *
     * Le résultat est gardé sous la clé (satellite, AOS, RX, TX) : recomposer
     * l'écran chaque seconde ne doit pas relancer la centaine de propagations
     * SGP4 que demandent les deux balayages.
     */
    fun dopplerPassRows(passAosMs: Long? = null): fr.f4ioz.satcombo.domain.DopplerPass.Table {
        val sat = _ui.value.selected ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val obs = _ui.value.observer ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val now = System.currentTimeMillis()
        val want = passAosMs ?: _ui.value.focusedPassAos
        val pass = (want?.let { f -> _ui.value.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: _ui.value.passes.firstOrNull { it.losEpochMs > now })
            ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()

        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
        val rxRest = _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: t?.downlinkLowHz
            ?: return fr.f4ioz.satcombo.domain.DopplerPass.Table()
        val txRest = when {
            t == null -> null
            t.isTransponder && t.downlinkLowHz != null && t.uplinkLowHz != null ->
                Doppler.transponderUplinkRest(
                    rxRest, t.downlinkLowHz!!, t.downlinkHighHz ?: t.downlinkLowHz!!,
                    t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
            else -> t.uplinkLowHz
        }

        val key = "${sat.catalogNumber}@${pass.aosEpochMs}/$rxRest/$txRest"
        if (key == dopplerPassKey) return dopplerPassTable
        val table = runCatching {
            fr.f4ioz.satcombo.domain.DopplerPass.build(
                pass.aosEpochMs, pass.losEpochMs, rxRest, txRest
            ) { from, to, step -> predictor.samplePositions(sat, obs, from, to, step) }
        }.getOrDefault(fr.f4ioz.satcombo.domain.DopplerPass.Table())
        dopplerPassKey = key
        dopplerPassTable = table
        return table
    }

    // ---- CAT (IC-9700 CI-V over USB-C) ----

    fun catRequestPermission() =
        if (isPairRig) ft817.requestPermissions() else cat.requestPermission()

    // ---- Dual FT-817 configuration ----

    /** Refresh the list of visible USB-serial adapters (for the RX/TX picker). */
    fun refreshUsbDevices() {
        _ui.value = _ui.value.copy(usbDevices = ft817.listDevices())
        litFrequencesUsb()
    }

    /** Ask USB permission for every adapter, then refresh (serials become readable). */
    fun ft817RequestPermissions() {
        ft817.requestPermissions()
        viewModelScope.launch { delay(1500); refreshUsbDevices() }
    }

    /**
     * Demande aux postes eux-mêmes qui est en réception et qui en émission.
     *
     * Deux câbles identiques sans numéro de série sont indiscernables par leur
     * étiquette, et leur position dans l'arbre USB peut s'échanger d'un
     * branchement à l'autre. Mais les deux postes ne sont pas sur la même bande
     * — l'un sur la descente, l'autre sur la montée — et leur propre réponse
     * tranche là où aucune étiquette ne le peut.
     */
    /**
     * Interroge chaque adaptateur et affiche la fréquence lue à côté de lui.
     *
     * Deux câbles PL2303 identiques ne se distinguent par aucune étiquette :
     * ni numéro de série, ni nom de produit qui diffère. En revanche les postes
     * au bout, eux, sont sur des bandes différentes. Écrire « 145,866 MHz » sur
     * une ligne et « 435,108 MHz » sur l'autre règle la question sans qu'on ait
     * à appuyer sur quoi que ce soit.
     *
     * En tâche de fond, et une ligne à la fois : chaque lecture coûte jusqu'à
     * 600 ms, et la liste doit s'afficher tout de suite quitte à se remplir
     * ensuite.
     */
    fun litFrequencesUsb() {
        if (!isPairRig) return
        viewModelScope.launch {
            val baud = _ui.value.ft817Baud
            _ui.value.usbDevices.forEach { d ->
                if (!d.hasPermission) return@forEach
                val hz = runCatching { ft817.sonde(d.cle, baud) }.getOrNull()
                _ui.value = _ui.value.copy(usbDevices = _ui.value.usbDevices.map {
                    if (it.cle == d.cle) it.copy(freqLueHz = hz, sonde = true) else it
                })
            }
        }
    }

    /**
     * À la connexion, on ne fait confiance à une assignation que si elle repose
     * sur un numéro de série.
     *
     * Une identité bâtie sur l'emplacement USB n'est stable que tant qu'on ne
     * débranche rien ; deux câbles identiques peuvent échanger leur place au
     * branchement suivant. On interroge alors les postes, et on ne corrige que
     * si la réponse est **certaine** — c'est-à-dire si l'un répond dans la bande
     * de descente et l'autre dans celle de montée. Dans le doute, on garde ce
     * qui était enregistré : une correction hasardeuse serait pire que l'erreur
     * qu'elle prétend réparer.
     */
    private suspend fun verifieRolesFt817SiSansNumero() {
        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        if (cleRx.isNullOrBlank() || cleTx.isNullOrBlank()) return
        val sansNumero = fr.f4ioz.satcombo.cat.IdentiteUsb.sansNumeroDeSerie(cleRx) ||
            fr.f4ioz.satcombo.cat.IdentiteUsb.sansNumeroDeSerie(cleTx)
        if (!sansNumero) return

        val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        val descente = _ui.value.rxRestHz ?: t0.downlinkLowHz ?: return
        val montee = t0.uplinkLowHz ?: return

        val baud = _ui.value.ft817Baud
        val sondes = ft817.listDevices().filter { it.hasPermission }.map { d ->
            fr.f4ioz.satcombo.cat.IdentiteUsb.Sonde(
                d.cle, runCatching { ft817.sonde(d.cle, baud) }.getOrNull())
        }
        if (sondes.size < 2) return

        val a = fr.f4ioz.satcombo.cat.IdentiteUsb.attribue(sondes, descente, montee)
        if (!a.certaine) return
        if (a.rx != null && a.tx != null && (a.rx != cleRx || a.tx != cleTx)) {
            setFt817Role(a.rx, "RX")
            setFt817Role(a.tx, "TX")
        }
    }

    /**
     * Vérifie les rôles **une fois les liaisons ouvertes**, et corrige.
     *
     * `verifieRolesFt817SiSansNumero` interroge les adaptateurs avant
     * l'ouverture, et c'est une bonne idée — mais elle abandonne en silence
     * dans trois cas : aucun satellite choisi, transpondeurs pas encore
     * chargés, ou un adaptateur qui ne répond pas à la sonde. On se connecte
     * alors avec l'attribution héritée de l'emplacement USB, laquelle change
     * d'un branchement à l'autre pour deux PL2303 sans numéro de série.
     *
     * Les conséquences ne se ressemblent pas et c'est ce qui rend le défaut
     * difficile à nommer : on écrit la descente dans le poste d'émission, le
     * liseré interroge le poste qui ne transmet jamais et reste donc éteint,
     * et le témoin affiche deux nombres justes attribués à l'envers.
     *
     * Ici, les liaisons sont ouvertes : on demande à chacune sa fréquence par
     * le fil déjà établi — sans rouvrir de port, donc sans conflit — et si la
     * réponse est **nette**, on échange. Nette veut dire : l'un dans la bande
     * de montée, l'autre dans celle de descente, et ce ne sont pas les mêmes.
     * Dans le doute on ne touche à rien et on le dit.
     */
    private suspend fun verifieRolesOuverts() {
        if (!isPairRig || isTxOnlyRig) return
        if (!ft817.rx.isOpen || !ft817.tx.isOpen) return
        val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        val descente = _ui.value.rxRestHz ?: t0.downlinkLowHz ?: return
        val montee = t0.uplinkLowHz ?: return
        // Sur un satellite dont la montée et la descente partagent la bande,
        // aucune lecture ne peut les départager : on ne prétend rien.
        if (kotlin.math.abs(descente - montee) < 4_000_000L) return

        val fRx = runCatching { ft817.rx.readFrequency() }.getOrNull() ?: return
        val fTx = runCatching { ft817.tx.readFrequency() }.getOrNull() ?: return
        fun pres(f: Long, cible: Long) = kotlin.math.abs(f - cible) <= 2_000_000L

        val enversRx = pres(fRx, montee) && !pres(fRx, descente)
        val enversTx = pres(fTx, descente) && !pres(fTx, montee)
        if (!enversRx || !enversTx) return

        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        if (cleRx.isNullOrBlank() || cleTx.isNullOrBlank()) return
        // On pose les deux rôles **sans reconnecter**, puis on rouvre une
        // seule fois, ici, où l'on sait que c'est fini.
        poseRoleFt817(cleTx, "RX")
        poseRoleFt817(cleRx, "TX")
        _ui.value = _ui.value.copy(catStatus = t("ft817_roles_swapped"))
        ft817.close()
        ft817.open(_ui.value.ft817RxSerial, _ui.value.ft817TxSerial, _ui.value.ft817Baud)
        // Les liaisons ont été fermées et rouvertes sous le sondage : il
        // interrogeait un port mort. Le redémarrer fait partie de la
        // réouverture, au même titre que l'ouverture elle-même.
        surveilleEmission()
    }

    fun detecteFt817Roles() {
        viewModelScope.launch { detecteFt817RolesEtAttend() }
    }

    private suspend fun detecteFt817RolesEtAttend() {
        run {
            _ui.value = _ui.value.copy(catStatus = t("ft817_probing"))
            ft817.requestPermissions()
            delay(1200)
            val adaptateurs = ft817.listDevices().filter { it.hasPermission }
            if (adaptateurs.isEmpty()) {
                _ui.value = _ui.value.copy(catStatus = t("ft817_no_adapters"))
                return
            }
            val baud = _ui.value.ft817Baud
            val sondes = adaptateurs.map { d ->
                fr.f4ioz.satcombo.cat.IdentiteUsb.Sonde(
                    d.cle, runCatching { ft817.sonde(d.cle, baud) }.getOrNull())
            }
            val t0 = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val a = fr.f4ioz.satcombo.cat.IdentiteUsb.attribue(
                sondes,
                descenteHz = _ui.value.rxRestHz ?: t0?.downlinkLowHz,
                monteeHz = t0?.uplinkLowHz)

            a.rx?.let { setFt817Role(it, "RX") }
            a.tx?.let { setFt817Role(it, "TX") }

            val lues = sondes.joinToString("  ") {
                if (it.freqHz == null) "—" else "%.4f".format(it.freqHz / 1_000_000.0)
            }
            _ui.value = _ui.value.copy(
                catStatus = if (a.certaine) tf("ft817_probe_ok", lues)
                else tf("ft817_probe_unsure", lues),
                usbDevices = adaptateurs)
        }
    }

    /** Assign an adapter (by its key) to the RX or TX rig. */
    /**
     * Écrit l'attribution, sans toucher à la liaison.
     *
     * La pose et la réouverture étaient un seul geste, et c'est ce qui a
     * éteint le liseré. Attribuer les deux rôles demande **deux** appels ; il
     * partait donc deux reconnexions concurrentes, chacune enchaînant
     * fermeture, attente et ouverture — et l'appelant en lançait souvent une
     * troisième derrière. Les liaisons finissaient ouvertes, puisque la
     * dernière l'emportait, et le CAT paraissait sain.
     *
     * Mais le sondage d'émission, lui, ne survivait pas : il est démarré par
     * l'ouverture et sa boucle s'arrête dès que `catConnected` retombe. Une
     * reconnexion partie plus tôt et terminée plus tard éteignait donc le
     * sondage démarré par la précédente, sans que rien ne le redémarre. Le
     * liseré ne s'allumait plus, et tout le reste marchait — ce qui rendait le
     * défaut incompréhensible depuis l'écran.
     */
    private fun poseRoleFt817(serial: String, role: String) {
        if (role == "RX") {
            settings.ft817RxSerial = serial
            if (settings.ft817TxSerial == serial) settings.ft817TxSerial = ""
        } else {
            settings.ft817TxSerial = serial
            if (settings.ft817RxSerial == serial) settings.ft817RxSerial = ""
        }
        _ui.value = _ui.value.copy(
            ft817RxSerial = settings.ft817RxSerial, ft817TxSerial = settings.ft817TxSerial)
    }

    fun setFt817Role(serial: String, role: String) {
        poseRoleFt817(serial, role)
        rouvreSiOuvert()
    }

    /**
     * Rouvre la liaison quand l'assignation change sous elle.
     *
     * C'est le défaut qui a coûté un passage à Olivier : activer le CAT
     * **puis** détecter les postes ne changeait rien, parce que les ports
     * étaient déjà ouverts sur l'ancienne assignation — souvent aucune, ou une
     * assignation périmée. Rien ne reliait le nouveau choix à la liaison en
     * cours ; il fallait couper le CAT et le rallumer, ce qu'aucun écran ne
     * disait.
     *
     * Une assignation qui change pendant que la liaison est ouverte est une
     * contradiction : on la résout tout de suite plutôt que d'attendre que
     * l'opérateur la découvre au milieu d'un passage.
     */
    private fun rouvreSiOuvert() {
        if (!_ui.value.catEnabled) return
        // **Une réouverture à la fois.**
        //
        // Deux reconnexions concurrentes s'entrelacent : la fermeture de
        // l'une tombe au milieu de l'ouverture de l'autre, et le sondage
        // d'émission démarré par la première est arrêté par la seconde. On
        // annule celle qui court plutôt que d'en superposer une de plus.
        rouvreJob?.cancel()
        rouvreJob = viewModelScope.launch {
            runCatching {
                disconnectCat()
                delay(200)
                ouvreCat()
            }
        }
    }

    /**
     * Le raccourci : tout ce qu'il faut pour trafiquer, en un seul appui.
     *
     * L'enchaînement correct comptait quatre gestes dans le bon ordre —
     * rafraîchir la liste, accorder les permissions, détecter les rôles,
     * connecter — et l'ordre importait sans que rien ne le dise. Un opérateur
     * qui a trois minutes avant l'AOS n'a pas à connaître cet ordre.
     */
    fun prepareFt817() {
        viewModelScope.launch {
            runCatching {
                // 1. Lister, sans lire les fréquences : sans autorisation, la
                //    lecture échouerait et laisserait les lignes en suspens.
                _ui.value = _ui.value.copy(usbDevices = ft817.listDevices())

                // 2. Demander les autorisations et **attendre la réponse**.
                //
                //    C'est ici que la préparation échouait une fois sur deux.
                //    Le système ouvre une boîte de dialogue que l'opérateur
                //    doit toucher ; l'ancienne suite lui accordait quatre
                //    cents millisecondes, puis passait à l'étape suivante que
                //    la réponse soit venue ou non. Un doigt un peu lent, et
                //    tout le reste travaillait sans autorisation.
                //
                //    Une durée ne remplace pas une condition. On attend donc
                //    que les adaptateurs soient autorisés, jusqu'à dix
                //    secondes — le temps qu'il faut pour lire et toucher.
                ft817.requestPermissions()
                val autorises = attendAutorisationsUsb(10_000)
                if (autorises.isEmpty()) {
                    _ui.value = _ui.value.copy(catStatus = t("ft817_no_adapters"))
                    return@runCatching
                }

                // 3. Attribuer les rôles en interrogeant les postes.
                detecteFt817RolesEtAttend()

                // 4. Connecter, et **attendre que ce soit fait**.
                //
                //    `setCatEnabled` et `rouvreSiOuvert` lançaient chacun leur
                //    coroutine et rendaient la main aussitôt : l'étape 5
                //    sondait les adaptateurs pendant que la connexion les
                //    ouvrait. Deux ouvertures du même périphérique USB, et
                //    celle qui perd ne dit rien.
                if (!_ui.value.catEnabled) _ui.value = _ui.value.copy(catEnabled = true)
                else { disconnectCat(); delay(200) }
                ouvreCat()

                // 5. Vérifier par la liaison qui vient de s'ouvrir, et non en
                //    rouvrant les ports derrière elle.
                verifieFt817Ouvert()
            }.onFailure {
                _ui.value = _ui.value.copy(
                    catStatus = "préparation interrompue : " +
                        (it.message ?: it.javaClass.simpleName))
            }
        }
    }

    /**
     * Attend que les adaptateurs USB soient autorisés, sans dépasser [maxMs].
     *
     * Rend la liste des adaptateurs autorisés — vide si le délai s'épuise, ce
     * qui veut dire que l'opérateur a refusé ou n'a rien touché.
     */
    private suspend fun attendAutorisationsUsb(maxMs: Long): List<fr.f4ioz.satcombo.cat.UsbSerialInfo> {
        val fin = System.currentTimeMillis() + maxMs
        var vus = ft817.listDevices()
        while (System.currentTimeMillis() < fin) {
            val ok = vus.filter { it.hasPermission }
            if (ok.isNotEmpty() && ok.size == vus.size) {
                _ui.value = _ui.value.copy(usbDevices = vus)
                return ok
            }
            delay(250)
            vus = ft817.listDevices()
            _ui.value = _ui.value.copy(usbDevices = vus)
        }
        return vus.filter { it.hasPermission }
    }

    /**
     * Dit si la préparation a abouti, et sur quoi.
     *
     * L'ancienne suite se terminait en silence : elle avait fait ses cinq
     * gestes, et c'était tout. Pour savoir si la liaison vivait, il fallait
     * quitter les réglages et regarder ailleurs. Une préparation doit se
     * conclure par un verdict.
     */
    private suspend fun verifieFt817Ouvert() {
        if (!_ui.value.catConnected) {
            _ui.value = _ui.value.copy(catStatus = t("open_failed_usb"))
            return
        }
        val rx = runCatching { if (ft817.rx.isOpen) ft817.rx.readFrequency() else null }.getOrNull()
        val tx = runCatching { if (ft817.tx.isOpen) ft817.tx.readFrequency() else null }.getOrNull()
        fun mhz(hz: Long?) = if (hz == null) "—" else "%.4f".format(hz / 1_000_000.0)
        // **Les lignes d'adaptateurs se remplissent aussi.**
        //
        // Elles restaient sur « interrogation… » après une préparation
        // réussie, et il fallait appuyer sur ↻ pour les voir — alors que les
        // fréquences venaient d'être lues, deux lignes plus haut. Une valeur
        // connue qu'on n'affiche pas oblige l'opérateur à redemander ce que
        // l'application sait déjà, et lui laisse croire que rien n'a marché.
        //
        // On garnit depuis les liaisons ouvertes, sans rouvrir les ports :
        // c'est ce que faisait `litFrequencesUsb`, et c'est le conflit
        // d'ouverture corrigé en 19.18.
        val cleRx = _ui.value.ft817RxSerial
        val cleTx = _ui.value.ft817TxSerial
        val garnis = _ui.value.usbDevices.map { d ->
            when (d.cle) {
                cleRx -> d.copy(freqLueHz = rx, sonde = true)
                cleTx -> d.copy(freqLueHz = tx, sonde = true)
                else -> d
            }
        }
        _ui.value = _ui.value.copy(
            usbDevices = garnis,
            catUi = _ui.value.catUi.copy(
                veilleRxHz = rx, veilleTxHz = tx, veilleVivante = rx != null || tx != null),
            catStatus = if (rx == null && tx == null) t("ft817_no_reply")
                        else "RX " + mhz(rx) + "  ·  TX " + mhz(tx))
    }

    /** Swap the RX and TX assignments. */
    fun swapFt817Roles() {
        val rx = settings.ft817RxSerial
        settings.ft817RxSerial = settings.ft817TxSerial
        settings.ft817TxSerial = rx
        _ui.value = _ui.value.copy(
            ft817RxSerial = settings.ft817RxSerial, ft817TxSerial = settings.ft817TxSerial)
    }

    fun setFt817Baud(baud: Int) {
        settings.ft817Baud = baud
        _ui.value = _ui.value.copy(ft817Baud = baud)
    }

    fun setCatEnabled(on: Boolean) {
        _ui.value = _ui.value.copy(catEnabled = on)
        if (on) connectCat() else { cat.close(); _ui.value = _ui.value.copy(catConnected = false, catStatus = t("cat_disconnected")) }
    }

    fun setRigModel(model: String) {
        settings.rigModel = model
        // Default CI-V address per Icom model.
        val addr = when (model) {
            "IC910" -> 0x60
            "IC9100" -> 0x7C
            else -> 0xA2  // IC9700
        }
        settings.civAddress = addr
        cat.radioAddr = addr
        _ui.value = _ui.value.copy(rigModel = model, civAddress = addr)
    }

    fun setCivAddress(addr: Int) {
        settings.civAddress = addr
        cat.radioAddr = addr
        _ui.value = _ui.value.copy(civAddress = addr)
    }

    fun setCivBaud(baud: Int) {
        settings.civBaud = baud
        _ui.value = _ui.value.copy(civBaud = baud)
    }

    /** Lequel des adaptateurs branchés est le poste. */
    fun setCivUsbIndex(v: Int) {
        settings.civUsbIndex = v
        _ui.value = _ui.value.copy(civUsbIndex = settings.civUsbIndex)
    }

    /** Balayer les ports voisins, ou s'en tenir strictement à celui qui est choisi. */
    fun setCivUsbAuto(on: Boolean) {
        settings.civUsbAuto = on
        _ui.value = _ui.value.copy(civUsbAuto = on)
    }

    /** La liste des adaptateurs USB visibles, pour que le numéro se choisisse à vue. */
    fun refreshCatDevices() {
        _ui.value = _ui.value.copy(catDevices = runCatching { cat.availableDeviceNames() }
            .getOrDefault(emptyList()))
    }

    fun setCtcssAuto(on: Boolean) {
        settings.ctcssAuto = on
        _ui.value = _ui.value.copy(ctcssAuto = on)
        catArmedFor = null
    }

    /**
     * SO-50 arming: the transponder must be armed with a 74.4 Hz tone (starts a
     * 10-minute timer), then 67.0 Hz is used for the contact. This sends a brief
     * 74.4 Hz pulse on the uplink, then restores the working tone. The operator
     * should hold PTT briefly while this runs (we can't key the rig for them).
     */
    fun armSo50() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) {
                _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch
            }
            _ui.value = _ui.value.copy(catStatus = t("so50_arming"))
            runCatching {
                if (isPairRig) ft817.setCtcss(744)
                else {
                    cat.selectMainSub(true)       // SUB = uplink
                    cat.setToneFreq(744); cat.setToneOn(true)
                }
            }
            kotlinx.coroutines.delay(3000)        // window for the operator to key up
            // Restore the working tone (auto-detected or 67.0).
            val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val work = t?.let { effectiveCtcss(it) }?.takeIf { it > 0 } ?: 670
            runCatching {
                if (isPairRig) ft817.setCtcss(work)
                else {
                    cat.setToneFreq(work); cat.setToneOn(true)
                    cat.selectMainSub(false)
                }
            }
            _ui.value = _ui.value.copy(catStatus = tf("so50_armed", work/10.0))
        }
    }

    fun setCtcssTenthHz(tenth: Int) {
        settings.ctcssTenthHz = tenth
        _ui.value = _ui.value.copy(ctcssTenthHz = tenth)
        catArmedFor = null  // re-arm so the new tone is applied
    }

    /**
     * Suivre le Doppler en réception.
     *
     * Allumé, le poste est accordé par le téléphone dès que la molette se tait
     * deux secondes ; éteint, on retrouve l'ancien comportement, où seule
     * l'émission suit et où la réception reste entièrement à l'opérateur.
     */
    fun setCatRxDoppler(on: Boolean) {
        settings.catRxDoppler = on
        rxArbiter.reset()
        _ui.value = _ui.value.copy(catRxDoppler = on,
            catRxDriven = if (on) _ui.value.catRxDriven else false)
    }

    fun setCatTestSendAlways(on: Boolean) {
        _ui.value = _ui.value.copy(catTestSendAlways = on)
    }

    /**
     * Joue la séquence de début de passage contre un poste simulé.
     *
     * Aucune radio n'est nécessaire, et c'est le but : le poste en mémoire
     * refuse ce qu'un vrai refuse, et compte ses refus. Une séquence saine doit
     * donc en produire zéro — affirmation autrement plus forte que « ça n'a pas
     * planté », qui était tout ce que l'on pouvait dire jusqu'ici.
     */
    fun runCatBench() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(benchRunning = true, benchOk = false,
                benchReport = "", benchSteps = emptyList())
            val r = runCatching {
                if (isPairRig) fr.f4ioz.satcombo.cat.CatBench.runFt817Pair()
                else fr.f4ioz.satcombo.cat.CatBench.runIc9700()
            }.getOrNull()
            _ui.value = _ui.value.copy(
                benchRunning = false,
                benchOk = r?.ok == true,
                benchReport = r?.summary ?: t("send_failed"),
                benchSteps = r?.steps ?: emptyList())
        }
    }

    fun setCatJournal(on: Boolean) {
        fr.f4ioz.satcombo.cat.CatJournal.enabled = on
        if (!on) fr.f4ioz.satcombo.cat.CatJournal.clear()
        _ui.value = _ui.value.copy(catJournalOn = on)
    }

    fun clearCatJournal() { fr.f4ioz.satcombo.cat.CatJournal.clear() }

    fun testCat() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) { _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch }
            val result = if (isPairRig) ft817.testLink() else cat.testLink()
            _ui.value = _ui.value.copy(catStatus = result)
        }
    }

    /** Send a fixed test frequency now (downlink VFO), ignoring horizon. */
    fun catSendTestFreq() {
        viewModelScope.launch {
            if (!_ui.value.catConnected) { _ui.value = _ui.value.copy(catStatus = t("cat_connect_first")); return@launch }
            val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex)
            val hz = _ui.value.rxRestHz ?: t?.let { centreRx(it) } ?: 145_900_000L
            val ok = runCatching {
                if (isPairRig) ft817.rx.setFrequency(hz)
                else { cat.selectVfo(false); cat.setFrequency(hz) }
            }.isSuccess
            _ui.value = _ui.value.copy(catStatus = if (ok) tf("test_freq_sent", "${hz/1000}.${(hz%1000)}") else fr.f4ioz.satcombo.i18n.t("send_failed"))
        }
    }

    /**
     * Le poste simulé en cours, s'il y en a un.
     *
     * On le garde pour pouvoir le refermer proprement, et parce qu'un écran de
     * mise au point pourrait un jour montrer ce que « voit » la face avant.
     */
    private var civSim: fr.f4ioz.satcombo.cat.Ic9700Sim? = null
    private var ft817Sims: Pair<fr.f4ioz.satcombo.cat.Ft817Sim,
            fr.f4ioz.satcombo.cat.Ft817Sim>? = null

    fun setCatSimulated(on: Boolean) {
        settings.catSimulated = on
        _ui.value = _ui.value.copy(catSimulated = on)
        if (_ui.value.catConnected) disconnectCat()
    }

    fun setCatMonitor(on: Boolean) {
        settings.catMonitor = on
        fr.f4ioz.satcombo.cat.CatJournal.enabled = on
        if (!on) fr.f4ioz.satcombo.cat.CatJournal.clear()
        _ui.value = _ui.value.copy(catMonitor = on)
    }

    /**
     * Ouvre la liaison sur un poste en mémoire.
     *
     * Le mouchard s'empile par-dessus quand il est ouvert : on voit alors
     * exactement les mêmes trames que sur un vrai câble, ce qui fait du poste
     * simulé un outil d'apprentissage du protocole autant qu'un banc d'essai.
     */
    private fun connectSimulated() {
        if (isPairRig) {
            val r = fr.f4ioz.satcombo.cat.Ft817Sim()
            val x = fr.f4ioz.satcombo.cat.Ft817Sim()
            ft817Sims = r to x
            ft817.rx.attach(r)
            ft817.tx.attach(x)
            ft817.rx.pacingMs = 0; ft817.tx.pacingMs = 0
        } else {
            val sim = fr.f4ioz.satcombo.cat.Ic9700Sim(radioAddr = _ui.value.civAddress)
            civSim = sim
            cat.radioAddr = _ui.value.civAddress
            cat.attach(sim)
            cat.pacingMs = 0
        }
        _ui.value = _ui.value.copy(catConnected = true, catStatus = t("cat_sim_on"))
        surveilleEmission()
        startCatLoop()
    }

    private var sondeTxJob: Job? = null

    /** Relevés « en émission » consécutifs ; il en faut deux pour y croire. */
    private var confirmationsTx = 0

    private var veilleCatJob: Job? = null

    private var rouvreJob: Job? = null

    /**
     * Le témoin de liaison des réglages CAT.
     *
     * Il ne tourne que pendant que la section CAT est affichée, et s'arrête
     * dès qu'on en sort : le fil série appartient au Doppler, un témoin n'a
     * pas à le disputer pendant un passage.
     *
     * Il existe pour une raison d'usage, pas de diagnostic. Pour savoir si la
     * liaison suivait la molette, il fallait quitter les réglages, retrouver
     * une page de passage, regarder si le curseur bougeait, et revenir si non.
     * La question se pose à l'endroit où l'on branche : la réponse doit s'y
     * trouver aussi. Tourner le VFO fait bouger le nombre, et la démonstration
     * est faite sans changer d'écran.
     */
    fun veilleCat(actif: Boolean) {
        veilleCatJob?.cancel()
        if (!actif) {
            if (_ui.value.catUi.veilleVivante)
                _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(veilleVivante = false))
            return
        }
        veilleCatJob = viewModelScope.launch {
            while (_ui.value.catConnected) {
                val rx = runCatching {
                    if (isPairRig) { if (ft817.rx.isOpen) ft817.rx.readFrequency() else null }
                    else cat.readFrequency()
                }.getOrNull()
                val tx = runCatching {
                    if (isPairRig) { if (ft817.tx.isOpen) ft817.tx.readFrequency() else null }
                    else null
                }.getOrNull()
                // Les lignes par adaptateur se remplissent de la même lecture.
                //
                // Elles affichaient « interrogation… » jusqu'à ce qu'on presse
                // la flèche : la fréquence était pourtant lue deux fois par
                // seconde, mais elle n'allait qu'au témoin. Une donnée déjà
                // sous la main ne doit pas se redemander d'un geste.
                val cleRx = _ui.value.ft817RxSerial
                val cleTx = _ui.value.ft817TxSerial
                _ui.value = _ui.value.copy(
                    catUi = _ui.value.catUi.copy(
                        veilleRxHz = rx, veilleTxHz = tx,
                        veilleVivante = rx != null || tx != null),
                    usbDevices = _ui.value.usbDevices.map { d ->
                        when (d.cle) {
                            cleRx -> if (rx != null) d.copy(freqLueHz = rx, sonde = true) else d
                            cleTx -> if (tx != null) d.copy(freqLueHz = tx, sonde = true) else d
                            else -> d
                        }
                    })
                delay(500)
            }
            _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(veilleVivante = false))
        }
    }

    /**
     * Surveille l'état d'émission du poste.
     *
     * L'opérateur passe en émission au VOX : l'application ne commande rien,
     * elle **observe**. Deux fois par seconde suffit — assez pour que le
     * liseré apparaisse dès le premier mot, assez peu pour ne pas encombrer
     * la liaison série pendant que le Doppler travaille.
     */
    private fun surveilleEmission() {
        sondeTxJob?.cancel()
        confirmationsTx = 0
        // **La boucle patiente au lieu de mourir.**
        //
        // Sa condition d'entrée lisait `catConnected`, et le sondage était
        // lancé depuis un `.also` **pendant le calcul** de l'état qui allait
        // justement poser `catConnected` à vrai. Or `viewModelScope` répartit
        // sur `Main.immediate` : appelée depuis le fil principal, la coroutine
        // démarre sur-le-champ, en ligne, et lisait donc l'ancienne valeur —
        // fausse. La boucle s'arrêtait avant son premier tour.
        //
        // Cela dépendait du fil d'où venait l'appel, ce qui explique
        // l'intermittence : la connexion ordinaire marchait, la réouverture
        // qui suit un changement de rôle échouait. Le liseré cessait donc de
        // fonctionner exactement quand Olivier devait corriger l'attribution à
        // la main, et jamais autrement.
        //
        // L'ordre est corrigé chez les appelants, mais le remède véritable est
        // ici : une boucle qui **attend** que la liaison revienne au lieu de
        // rendre l'âme. Elle ne dépend plus de savoir qui la relance ni quand,
        // et elle survit à toute reconnexion — c'est une classe entière de
        // défauts qui disparaît, et non le seul d'aujourd'hui.
        sondeTxJob = viewModelScope.launch {
            while (true) {
                if (!_ui.value.catUi.liseret) {
                    if (_ui.value.catUi.enEmission)
                        _ui.value = _ui.value.copy(
                            catUi = _ui.value.catUi.copy(enEmission = false))
                    confirmationsTx = 0
                    delay(500)
                    continue
                }
                if (!_ui.value.catConnected) {
                    // Liaison absente : on n'affirme rien et on ne demande
                    // rien. Aucun octet ne part sur un fil fermé.
                    if (_ui.value.catUi.enEmission)
                        _ui.value = _ui.value.copy(
                            catUi = _ui.value.catUi.copy(enEmission = false))
                    confirmationsTx = 0
                    delay(500)
                    continue
                }
                // **On interroge le poste qui émet, ou personne.**
                //
                // Le repli vers le poste de réception paraissait prudent : à
                // défaut du bon, interroger celui qui répond. Mais un poste de
                // réception n'émet jamais — il répond donc « réception » avec
                // constance, et le liseré ne s'allume plus jamais. C'est le
                // pire des états : l'écran affirme quelque chose de faux au
                // lieu d'avouer qu'il ne sait pas. L'opérateur croit alors que
                // le liseré fonctionne, et se fie à son absence.
                //
                // Le poste d'émission fermé n'a rien d'exceptionnel — câble
                // débranché, rôles non attribués, deuxième adaptateur absent.
                // La réponse honnête est de le dire.
                val txOuvert = if (isPairRig) ft817.tx.isOpen else cat.isOpen
                val r = runCatching {
                    if (!txOuvert) null
                    else if (isPairRig) ft817.tx.isTransmitting()
                    else cat.isTransmitting()
                }
                val tx = r.getOrNull()
                // L'octet brut accompagne la conclusion : sans lui, « réception »
                // et « pas de réponse » se ressemblent à l'écran alors qu'ils
                // désignent deux défauts sans rapport.
                val brut = if (isPairRig) ft817.tx.dernierEtatTx else null
                val hex = brut?.let { " · 0x%02X".format(it) } ?: ""
                val diag = when {
                    !txOuvert -> "poste d'émission non ouvert"
                    r.isFailure -> "erreur " + (r.exceptionOrNull()?.javaClass?.simpleName ?: "")
                    tx == null -> "pas de réponse du poste" + hex
                    tx -> "émission" + hex
                    else -> "réception" + hex
                }
                // Sans lecture, le liseré s'éteint : on n'affirme rien.
                if (tx == null && _ui.value.catUi.enEmission) {
                    _ui.value = _ui.value.copy(
                        catUi = _ui.value.catUi.copy(enEmission = false))
                }
                if (diag != _ui.value.catUi.txDiag) {
                    _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(txDiag = diag))
                }
                // **Deux relevés pour allumer, un seul pour éteindre.**
                //
                // Un garde, et non un correctif : la cause du clignotement est
                // ailleurs, dans l'acquittement laissé sur le fil. Mais une
                // liaison série traverse un câble, un hub et un poste occupé à
                // servir son encodeur ; un octet égaré reste possible, et un
                // liseré qui s'allume à tort une demi-seconde apprend à
                // l'opérateur à ne plus le regarder.
                //
                // La dissymétrie est voulue. Allumer à tort ruine le signal ;
                // éteindre avec un demi-tour de retard ne coûte rien, et
                // surtout on ne veut jamais faire attendre l'extinction —
                // c'est l'allumage qui doit se mériter, pas l'inverse.
                // **Une absence de réponse n'est pas un démenti.**
                //
                // Le compteur était remis à zéro dès qu'une lecture manquait,
                // c'est-à-dire qu'un silence comptait comme un « réception ».
                // Or c'est pendant l'émission que le poste répond le moins
                // bien : il sert son encodeur, l'application lui écrit des
                // fréquences qu'il ignore, et une réponse sur deux se perd. Il
                // suffisait donc d'alterner « émission » et silence pour que
                // deux confirmations consécutives ne soient **jamais**
                // atteintes — et le liseré ne s'allumait plus du tout, alors
                // même que la lecture était juste une fois sur deux.
                //
                // Le garde de la 19.19 visait un octet égaré isolé ; il s'est
                // mis à interdire l'allumage. Un silence ne prouve rien : il
                // laisse le compteur où il est. Seul un « réception » franc le
                // remet à zéro.
                when (tx) {
                    true -> confirmationsTx++
                    false -> confirmationsTx = 0
                    null -> Unit
                }
                val allume = if (tx == null) _ui.value.catUi.enEmission
                             else confirmationsTx >= 2
                if (allume != _ui.value.catUi.enEmission) {
                    _ui.value = _ui.value.copy(
                        catUi = _ui.value.catUi.copy(enEmission = allume))
                }
                kotlinx.coroutines.delay(_ui.value.catUi.sondeMs.toLong())
            }
            if (_ui.value.catUi.enEmission)
                _ui.value = _ui.value.copy(catUi = _ui.value.catUi.copy(enEmission = false))
        }
    }

    fun connectCat() { viewModelScope.launch { ouvreCat() } }

    /**
     * L'ouverture, attendable.
     *
     * `connectCat` lançait sa propre coroutine et rendait la main aussitôt.
     * La préparation en un appui enchaînait donc « connecter » puis « lire les
     * fréquences » en pariant sur un délai de trois cents millisecondes — et
     * les deux se retrouvaient à ouvrir les mêmes adaptateurs USB en même
     * temps. D'où une préparation qui aboutit une fois sur deux, sans que rien
     * ne dise pourquoi.
     *
     * Séparer le corps de son lancement permet d'attendre la fin plutôt que de
     * l'estimer.
     */
    private suspend fun ouvreCat() {
        run {
            // Le mouchard doit être armé AVANT l'ouverture : c'est à ce moment
            // que le pilote décide de s'envelopper dedans ou non.
            fr.f4ioz.satcombo.cat.CatJournal.enabled = _ui.value.catMonitor
            if (_ui.value.catSimulated) { connectSimulated(); return@run }
            if (isPairRig) {
                // Dual FT-817: open both adapters by their remembered FTDI serials.
                if (_ui.value.ft817RxSerial.isBlank() && _ui.value.ft817TxSerial.isBlank()) {
                    _ui.value = _ui.value.copy(catConnected = false, catStatus = t("ft817_assign_first"))
                    return@run
                }
                ft817.requestPermissions()
                // Vérifier les rôles AVANT d'ouvrir, quand les câbles n'ont pas
                // de numéro de série.
                //
                // Deux PL2303 identiques ne se distinguent alors que par leur
                // emplacement dans l'arbre USB — et cet emplacement **change
                // d'un branchement à l'autre**. L'assignation enregistrée hier
                // désigne donc peut-être l'autre câble aujourd'hui : on écrit
                // la descente dans le poste d'émission, et l'on se retrouve en
                // UHF là où l'on attendait de la VHF.
                //
                // Le remède ne consiste pas à mieux deviner mais à ne plus se
                // fier à l'étiquette : on demande à chaque poste sur quelle
                // fréquence il est, et sa réponse dit son rôle.
                verifieRolesFt817SiSansNumero()

                val (rxOk, txOk) = if (isTxOnlyRig)
                    // Un seul câble : on n'ouvre que l'émission, et l'unique
                    // adaptateur présent fait l'affaire sans assignation.
                    ft817.open("", _ui.value.ft817TxSerial.ifBlank {
                        ft817.listDevices().firstOrNull { it.hasPermission }?.cle.orEmpty()
                    }, _ui.value.ft817Baud)
                else ft817.open(
                    _ui.value.ft817RxSerial, _ui.value.ft817TxSerial, _ui.value.ft817Baud)
                        .also { (r, t) -> if (r && t) verifieRolesOuverts() }
                val ok = rxOk || txOk
                _ui.value = _ui.value.copy(catConnected = ok,
                    catStatus = if (ok) tf("ft817_connected",
                        if (rxOk) "✓" else "✗", if (txOk) "✓" else "✗")
                    else t("open_failed_usb"))
                // Après la pose de `catConnected`, jamais pendant : la boucle
                // de sondage lit cet état à son premier tour.
                if (ok) { surveilleEmission(); startCatLoop() }
                return@run
            }
            cat.radioAddr = _ui.value.civAddress
            val refs = cat.availablePorts()
            _ui.value = _ui.value.copy(catDevices = refs.map { it.label })
            if (refs.isEmpty()) {
                _ui.value = _ui.value.copy(catConnected = false,
                    catStatus = t("no_usb_serial"),
                    catDiag = listOf(t("cat_err_no_device")))
                return@run
            }
            // Ce qui manquait ici, et qui explique l'essentiel de la peine :
            // l'ancienne suite demandait la permission sans attendre la réponse,
            // puis ouvrait le port dans la foulée — sur l'appareil 0, port 0,
            // quel que soit le choix de l'opérateur. Trois erreurs en deux
            // lignes. On attend la permission, on ouvre le port désigné, et on
            // vérifie que le poste répond avant de crier victoire.
            val trace = ArrayList<String>()
            val ordre =
                if (_ui.value.civUsbAuto) fr.f4ioz.satcombo.cat.CatScan.ordre(refs, _ui.value.civUsbIndex)
                else listOf(_ui.value.civUsbIndex.coerceIn(0, refs.size - 1))
            var gagnant = -1
            for (i in ordre) {
                trace.add(tf("cat_diag_try", refs[i].label))
                if (!cat.ensurePermission(i)) { trace.add(t("cat_diag_line_denied")); continue }
                if (!cat.open(i, _ui.value.civBaud)) {
                    trace.add(tf("cat_diag_line_open", cat.lastError)); continue
                }
                val hz = runCatching { cat.readFrequency() }.getOrNull()
                if (hz == null) {
                    // Le port s'ouvre toujours ; c'est le silence qui trahit.
                    trace.add(t("cat_diag_line_mute")); cat.close(); continue
                }
                trace.add(tf("cat_diag_line_ok",
                    String.format(java.util.Locale.US, "%.3f", hz / 1_000_000.0)))
                gagnant = i; break
            }
            val ok = gagnant >= 0
            if (ok && gagnant != _ui.value.civUsbIndex) {
                // Le port qui a répondu devient celui que l'on retient : la
                // fois suivante, la connexion est immédiate.
                settings.civUsbIndex = gagnant
                _ui.value = _ui.value.copy(civUsbIndex = settings.civUsbIndex)
            }
            _ui.value = _ui.value.copy(catConnected = ok, catDiag = trace,
                catStatus = if (ok) tf("connected_to", refs[gagnant].label)
                            else t("open_failed_usb"))
            if (ok) { surveilleEmission(); startCatLoop() }
        }
    }

    private var catLoopJob: kotlinx.coroutines.Job? = null

    /**
     * Dedicated CAT loop, faster than the 1 Hz tracking loop (OscarWatch-style).
     * Runs ~4x/second: reads the MAIN (downlink) every cycle so the display and
     * VFO-follow stay responsive; writes the uplink (SUB) only when needed, and
     * for linear, defers the uplink write briefly after the operator stops
     * moving the dial (so we don't fight the tuning).
     */
    private fun startCatLoop() {
        catLoopJob?.cancel()
        catLoopJob = viewModelScope.launch {
            while (_ui.value.catConnected) {
                runCatching { catTick() }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun disconnectCat() {
        catLoopJob?.cancel(); catLoopJob = null
        cat.close()
        ft817.close()
        cat.pacingMs = 40; ft817.rx.pacingMs = 25; ft817.tx.pacingMs = 25
        civSim = null; ft817Sims = null
        catArmedFor = null
        rxArbiter.reset(); fmArbiter.reset()
        toursDepuisLeMode = 0
        _ui.value = _ui.value.copy(catConnected = false, catStatus = t("cat_disconnected"),
            catRadioDownlinkHz = null, catRadioUplinkHz = null, catRxDriven = false,
            catRadioMode = null, catModeMismatch = false)
    }

    /** True if the chosen transmitter is FM (fixed channels) vs linear transponder. */
    private fun isFmMode(t: fr.f4ioz.satcombo.data.Transmitter): Boolean {
        if (t.isTransponder) return false
        val m = t.mode?.uppercase() ?: ""
        return m.contains("FM") || m.contains("NFM")
    }

    /** Satellite RF layout, OscarWatch-style. */
    private enum class SatLayout { CROSS_BAND, SAME_BAND, BEACON }

    private fun layoutFor(t: fr.f4ioz.satcombo.data.Transmitter): SatLayout {
        val dl = t.downlinkLowHz ?: centreRx(t) ?: 0L
        val ul = t.uplinkLowHz ?: 0L
        return when {
            ul <= 0L -> SatLayout.BEACON
            kotlin.math.abs(dl - ul) > 10_000_000L -> SatLayout.CROSS_BAND
            else -> SatLayout.SAME_BAND   // V/V or U/U, e.g. ISS FM
        }
    }

    /** Map a transmitter mode string to an IC-9700 CI-V mode byte. */
    private fun civModeFor(t: fr.f4ioz.satcombo.data.Transmitter, isUplink: Boolean): Int {
        val m = t.mode?.uppercase() ?: ""
        return when {
            m.contains("FM") -> 0x05
            // Linear in CW op-mode: use CW both sides.
            t.isTransponder && _ui.value.opMode == "CW" -> 0x03
            m.contains("CW") -> 0x03
            // Linear SSB: by convention downlink USB; uplink mirror is LSB if inverting.
            t.isTransponder && effectiveInvert(t) -> if (isUplink) 0x00 else 0x01  // LSB up / USB down
            m.contains("LSB") -> 0x00
            else -> 0x01  // USB default
        }
    }

    // ------------------------------------------------------------------
    // La frontière entre le ciel et le poste.
    //
    // Ces trois passages sont les seuls endroits de la boucle CAT où une
    // fréquence change de monde. Partout ailleurs — Doppler, inversion,
    // arbitrage de la molette, seuils d'écriture — tout est en fréquences de
    // satellite, et doit le rester : un `lastSentDl` mélangeant les deux
    // domaines rendrait le seuil de vingt hertz inopérant, et l'arbitre
    // prendrait notre propre consigne pour un geste de l'opérateur.
    //
    // Les regrouper ici a un second mérite : quand un jour il faudra ajouter
    // un troisième cas de poste, il n'y aura qu'un endroit à relire.
    // ------------------------------------------------------------------

    /**
     * Ce que le poste sait faire.
     *
     * Une fréquence hors de ses bandes n'est pas une erreur de calcul : c'est
     * une voie qui ne passe pas par lui. Sur une station QO-100 ordinaire, la
     * descente sort d'un LNB à 739 MHz et s'écoute à la clé ; le poste, lui, ne
     * fait plus qu'émettre en 144 ou 432. Lui envoyer 10 GHz ne provoquerait
     * qu'un NAK silencieux à chaque tour de boucle, dix fois par seconde.
     *
     * D'où cette garde, posée exactement là où la fréquence devient une trame.
     */
    private fun atteignableParLePoste(posteHz: Long): Boolean =
        fr.f4ioz.satcombo.cat.BandPlan.band(posteHz) != fr.f4ioz.satcombo.cat.BandPlan.Band.AUTRE

    /**
     * La descente arrive-t-elle jusqu'au poste ?
     *
     * Se demande avant de le relire : sans convertisseur vers le poste, ce
     * qu'on y lirait serait la fréquence d'émission, et l'arbitre y verrait un
     * geste de l'opérateur à chaque tour.
     */
    private fun descenteAuPoste(dlSat: Long): Boolean =
        !isTxOnlyRig && atteignableParLePoste(posteRx(dlSat))

    /** Écrit le couple descente/montée, chacun par son convertisseur. */
    private suspend fun ecrireCouple(dlSat: Long, ulSat: Long, sameBand: Boolean) {
        if (isTxOnlyRig) { ecrireMontee(ulSat, sameBand); return }
        val dl = posteRx(dlSat)
        if (!atteignableParLePoste(dl)) { ecrireMontee(ulSat, sameBand); return }
        val ul = posteTx(ulSat)
        if (!atteignableParLePoste(ul)) {
            // La montée passe par ailleurs : on ne garde que l'écoute.
            if (isPairRig) ft817.rx.setFrequency(dl)
            else { cat.readMainFrequency(); cat.setFrequency(dl) }
            return
        }
        if (isPairRig) ft817.setPair(dl, ul)
        else if (sameBand) cat.setSplitPair(dl, ul) else cat.setSatellitePair(dl, ul)
    }

    /** Écrit la seule montée — le cas où l'opérateur tient la réception. */
    private suspend fun ecrireMontee(ulSat: Long, sameBand: Boolean) {
        val ul = posteTx(ulSat)
        if (!atteignableParLePoste(ul)) return
        if (isPairRig) ft817.setUplink(ul)
        else if (sameBand) cat.setSplitUplink(ul) else cat.setUplink(ul)
    }

    /** Relit la descente et la ramène tout de suite dans le ciel. */
    private suspend fun lireDescente(sameBand: Boolean): Long? =
        runCatching {
            if (isPairRig) ft817.readDownlink()
            else if (sameBand) cat.readVfoAFrequency() else cat.readMainFrequency()
        }.getOrNull()?.let { satDepuisPoste(it) }

    // CAT session state (set up once when SAT mode armed for a transmitter).
    private var catArmedFor: Int? = null      // catnum currently armed
    private var catArmedTxDesc: String? = null
    private var lastSentDl = 0L
    private var lastSentUl = 0L

    /** Arm the radio for the current satellite according to its RF layout. */
    private suspend fun armCatForCurrent() {
        // Pause Doppler : l'armement écrit le mode, le split et la tonalité —
        // c'est déjà toucher au poste. On sort avant le test « déjà armé » pour
        // que rien ne soit noté comme fait : l'armement complet aura lieu au
        // relâchement.
        if (_ui.value.dopplerHold) return
        val sat = _ui.value.selected ?: return
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        if (catArmedFor == sat.catalogNumber && catArmedTxDesc == t.description) return
        val layout = layoutFor(t)
        if (isPairRig) {
            // Two dedicated rigs: no band/split gymnastics — just modes + tone.
            runCatching {
                ft817.setModes(
                    downlink = ft817ModeFor(t, isUplink = false),
                    uplink = ft817ModeFor(t, isUplink = true))
                val tone = effectiveCtcss(t)
                ft817.setCtcss(if (isFmMode(t) && tone > 0) tone else 0)
            }
            catArmedFor = sat.catalogNumber
            catArmedTxDesc = t.description
            catLayout = layout
            lastSentDl = 0L; lastSentUl = 0L
            rxArbiter.reset(); fmArbiter.reset()
            return
        }
        runCatching {
            when (layout) {
                SatLayout.CROSS_BAND -> {
                    cat.setSatelliteMode(true)
                    cat.setSplitOn(false)
                    // SUB = uplink: mode + CTCSS while selected.
                    cat.selectMainSub(true)
                    cat.setMode(civModeFor(t, isUplink = true))
                    val tone = effectiveCtcss(t)
                    if (isFmMode(t) && tone > 0) {
                        cat.setToneFreq(tone); cat.setToneOn(true)
                    } else cat.setToneOn(false)
                    // MAIN = downlink: mode; finish on MAIN.
                    cat.selectMainSub(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
                SatLayout.SAME_BAND -> {
                    // ISS V/V etc.: sat mode OFF, split ON, RX=VFO A, TX=VFO B.
                    cat.armSameBandSplit()
                    cat.selectVfo(true)
                    cat.setMode(civModeFor(t, isUplink = true))
                    val toneSB = effectiveCtcss(t)
                    if (isFmMode(t) && toneSB > 0) {
                        cat.setToneFreq(toneSB); cat.setToneOn(true)
                    } else cat.setToneOn(false)
                    cat.selectVfo(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
                SatLayout.BEACON -> {
                    // Receive-only: sat mode off, tune downlink on MAIN only.
                    cat.setSatelliteMode(false)
                    cat.setSplitOn(false)
                    cat.setToneOn(false)
                    cat.selectMainSub(false)
                    cat.setMode(civModeFor(t, isUplink = false))
                }
            }
        }
        catArmedFor = sat.catalogNumber
        catArmedTxDesc = t.description
        catLayout = layout
        lastSentDl = 0L; lastSentUl = 0L
        rxArbiter.reset(); fmArbiter.reset()
        // Nouveau satellite, donc nouvelles bandes : ce que le pilote croyait
        // savoir du poste ne vaut plus rien, et c'est précisément le moment où
        // l'ordre des deux écritures va se décider.
        cat.forgetBands()
    }
    private var catLayout: SatLayout = SatLayout.CROSS_BAND

    /**
     * One CAT cycle (~100 ms), OscarWatch-style.
     *  - FM cross-band (V/U): both legs Doppler-tuned (MAIN downlink, SUB uplink).
     *  - FM same-band (V/V, ISS): split A/B; RX on VFO A, TX on VFO B.
     *  - Linear: read the operator's RX dial; pause Doppler while it moves; resume
     *    after 8 stable samples (~800 ms); defer the uplink write 2.5 s after the
     *    dial last changed, so we never fight live tuning.
     *  - Beacon: receive-only, Doppler on downlink only.
     */
    private suspend fun catTick() {
        if (!_ui.value.catConnected) return
        val pos = _ui.value.livePosition
        val belowHorizon = pos == null || pos.elevationDeg < 0
        if (belowHorizon && !_ui.value.catTestSendAlways) {
            val dlNow = runCatching {
                if (isPairRig) ft817.readDownlink() else cat.readMainFrequency()
            }.getOrNull()?.let { satDepuisPoste(it) }
            // Sous l'horizon, personne ne pilote : le compte à rebours des
            // deux secondes repartira de zéro à l'acquisition.
            rxArbiter.reset(); fmArbiter.reset()
            if (dlNow != null) _ui.value = _ui.value.copy(catRadioDownlinkHz = dlNow,
                catRxDriven = false)
            return
        }
        val rr = pos?.rangeRateKmS ?: 0.0
        val t = activeTransmitters().getOrNull(_ui.value.selectedTxIndex) ?: return
        armCatForCurrent()
        val shift = _ui.value.calibShiftHz
        val txShift = _ui.value.txShiftHz

        when (catLayout) {
            SatLayout.BEACON -> {
                val dlRest = _ui.value.rxRestHz ?: centreRx(t) ?: return
                val dl = Doppler.downlink(dlRest, rr) + shift
                if (_ui.value.dopplerHold) {
                    // En pause, l'écran doit montrer le poste tel qu'il est, et
                    // non la consigne qu'on ne lui envoie pas : afficher la
                    // seconde ferait croire à un suivi qui n'a pas lieu.
                    // Relue par `lireDescente`, donc déjà ramenée dans le
                    // ciel : l'écran n'affiche jamais la fréquence intermédiaire
                    // du LNB, qui ne veut rien dire pour l'opérateur.
                    val lu = lireDescente(sameBand = false)
                    _ui.value = _ui.value.copy(catRadioDownlinkHz = lu ?: dl,
                        catRadioUplinkHz = null, catRxDriven = false)
                } else {
                    if (kotlin.math.abs(dl - lastSentDl) >= dopplerThreshold(t)) {
                        lastSentDl = dl
                        runCatching {
                            val dlPoste = posteRx(dl)
                            if (isPairRig) ft817.rx.setFrequency(dlPoste)
                            else { cat.readMainFrequency(); cat.setFrequency(dlPoste) }
                        }
                    }
                    _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = null)
                }
            }

            SatLayout.SAME_BAND -> {
                // ISS-type V/V. FM: fixed both sides + Doppler via split A/B.
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = true)
                else interactiveLinear(t, rr, shift, txShift, sameBand = true)
            }

            SatLayout.CROSS_BAND -> {
                if (isFmMode(t)) interactiveFm(t, rr, shift, txShift, sameBand = false)
                else interactiveLinear(t, rr, shift, txShift, sameBand = false)
            }
        }

        surveillerLeMode(t)
    }

    /** Depuis combien de tours de boucle on n'a pas demandé son mode au poste. */
    private var toursDepuisLeMode = 0

    /**
     * « USB LSB… à contrôler + afficher. »
     *
     * Le mode est posé une fois, à l'armement, et plus personne ne le regarde :
     * si l'opérateur passe le poste en LSB d'un coup de bouton, ou si le poste
     * revient de lui-même à ce qu'il avait en mémoire de bande, l'écran
     * continue d'annoncer USB et le correspondant devient inaudible sans que
     * rien ne l'explique. On relit donc le mode, on l'affiche tel que le poste
     * le donne, et on le remet quand il a bougé.
     *
     * Toutes les deux secondes, soit un tour de boucle sur vingt : le mode ne
     * change pas dix fois par seconde, et le bus CI-V a déjà fort à faire avec
     * le Doppler.
     */
    private suspend fun surveillerLeMode(t: fr.f4ioz.satcombo.data.Transmitter) {
        if (isPairRig) return
        if (++toursDepuisLeMode < 20) return
        toursDepuisLeMode = 0
        val lu = runCatching { cat.readMode() }.getOrNull() ?: return
        val attendu = civModeFor(t, isUplink = false)
        val ecart = lu != attendu
        _ui.value = _ui.value.copy(
            catRadioMode = fr.f4ioz.satcombo.cat.CatDecode.civModeName(lu),
            catModeMismatch = ecart)
        // La remise en mode est une écriture comme une autre : en pause Doppler
        // on se contente de dire à l'écran que le poste n'est plus au mode
        // attendu, et l'opérateur en fait ce qu'il veut.
        if (ecart && !_ui.value.dopplerHold) runCatching { cat.setMode(attendu) }
    }

    /** Doppler threshold... (kept below). */
    private fun dopplerThreshold(t: fr.f4ioz.satcombo.data.Transmitter): Long =
        if (isFmMode(t)) 200L else 20L

    /** FT-817 mode string for a transmitter leg (same policy as civModeFor). */
    private fun ft817ModeFor(t: fr.f4ioz.satcombo.data.Transmitter, isUplink: Boolean): String {
        val m = (t.mode ?: "").uppercase()
        return when {
            m.contains("FM") -> "FM"
            m.contains("CW") && !isUplink -> "CW"
            t.isTransponder && effectiveInvert(t) -> if (isUplink) "LSB" else "USB"
            m.contains("LSB") -> "LSB"
            else -> "USB"
        }
    }

    /**
     * Extract a CTCSS tone (tenths of Hz) from a transmitter description such as
     * "Mode V/U FM - Voice Repeater CTCSS 67.0". Returns 0 if none found.
     */
    private fun autoCtcssTenthHz(t: fr.f4ioz.satcombo.data.Transmitter): Int {
        val d = t.description
        val m = Regex("""(?:CTCSS|TONE|PL)\s*:?\s*(\d{2,3}(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
            .find(d) ?: return 0
        val hz = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0.0.toInt()
        return Math.round(hz * 10).toInt()
    }

    /** Effective CTCSS: explicit user setting wins; else auto-detected from name. */
    private fun effectiveCtcss(t: fr.f4ioz.satcombo.data.Transmitter): Int {
        if (_ui.value.ctcssTenthHz > 0) return _ui.value.ctcssTenthHz
        return if (_ui.value.ctcssAuto) autoCtcssTenthHz(t) else 0
    }

    // Qui tient le VFO de réception : l'opérateur, ou nous.
    private val rxArbiter = fr.f4ioz.satcombo.cat.RxArbiter()

    /**
     * Le même arbitrage en FM, mais avec une main beaucoup moins fine.
     *
     * Vingt hertz suffisent à reconnaître un geste sur un transpondeur linéaire,
     * où l'on cherche une station à la bande latérale près. En FM cela ne
     * marcherait pas : le poste arrondit ce qu'on lui écrit à son pas d'accord,
     * et cet arrondi-là, relu au tour suivant, passerait pour un geste de
     * l'opérateur — le logiciel rendrait la main toutes les cent millisecondes
     * sans que personne n'ait touché à rien. Un vrai changement de canal FM
     * vaut au moins cinq kilohertz ; un kilohertz et demi est donc large pour
     * l'un et hors d'atteinte pour l'autre.
     */
    private val fmArbiter = fr.f4ioz.satcombo.cat.RxArbiter(moveHz = 1_500L)

    /**
     * L'arbitre de la molette d'émission.
     *
     * Le même objet que pour la réception, et c'est délibéré : le problème est
     * identique au mot près — le poste ne dit pas *qui* a tourné la molette, et
     * ce qu'on relit après avoir écrit ressemble trait pour trait à un geste de
     * l'opérateur. Sa mémoire des dernières consignes est exactement ce qu'il
     * faut. Un second arbitre écrit à part aurait fini par diverger du premier.
     */
    private val txArbiter = fr.f4ioz.satcombo.cat.RxArbiter()

    /**
     * Applique le délai de reprise rangé, dès la création des arbitres.
     *
     * **Le défaut qu'il répare.** `regle()` n'était appelé que depuis le
     * sélecteur de réglage. Le choix de l'opérateur était donc bien enregistré,
     * mais les arbitres repartaient à leurs deux secondes d'usine à chaque
     * lancement — et il croyait, à juste titre, que le réglage ne servait à
     * rien. Une préférence qui ne survit pas au redémarrage est pire qu'une
     * préférence absente : elle fait douter de ce qu'on a sous les yeux.
     */
    init {
        // Les identifiants du partage sont posés dès le départ : sans cela le
        // QR code du Wi-Fi resterait vide jusqu'à la première saisie, même
        // quand ils sont déjà rangés.
        fr.f4ioz.satcombo.demo.ServeurDemo.configureWifi(
            settings.demoSsid, settings.demoMotDePasse)
        val v = settings.catHoldMs.toLong()
        rxArbiter.regle(v)
        fmArbiter.regle(v)
        txArbiter.regle(v)
    }

    /**
     * Un geste sur la molette d'émission a été vu et n'a pas encore été absorbé.
     *
     * Sans ce drapeau, l'absorption se déclencherait à chaque tour de boucle dès
     * que l'arbitre a la main — c'est-à-dire en permanence — et le moindre
     * arrondi du poste finirait par dériver dans le décalage.
     */
    @Volatile private var mainSurMoletteTx = false

    /**
     * FM : le Doppler tient les deux voies, et lâche la molette quand
     * l'opérateur y touche.
     *
     * « Quelques fois j'ajuste via le VFO, puis reprendre le doppler. » En FM
     * l'application écrivait jusqu'ici les deux fréquences à chaque tour, sans
     * jamais regarder le poste : retoucher la réception à la main était donc
     * impossible, la consigne suivante l'effaçait cent millisecondes plus tard.
     *
     * Désormais on relit la descente. Un écart qui ne s'explique par aucune de
     * nos consignes est un geste : on se tait, et le canal choisi devient le
     * nouveau repos — corrigé du Doppler de l'instant, sans quoi la correction
     * s'appliquerait deux fois. Deux secondes de silence plus tard, le suivi
     * reprend à partir de ce repos-là.
     *
     * La montée, elle, ne bouge pas de canal : sur un relais satellite c'est
     * une fréquence d'entrée fixe, et seul son Doppler la fait varier.
     */
    private suspend fun interactiveFm(
        t: fr.f4ioz.satcombo.data.Transmitter, rr: Double, shift: Long, txShift: Long,
        sameBand: Boolean
    ) {
        val ulRest = t.uplinkLowHz ?: return
        val dlObserved = lireDescente(sameBand)

        if (dlObserved != null) {
            val geste = fmArbiter.observe(dlObserved, System.currentTimeMillis())
            if (geste) {
                lastSentDl = 0L
                // Le canal choisi à la main est une fréquence de poste ; le
                // repos, lui, est une fréquence de satellite. Ôter le Doppler de
                // l'instant est ce qui fait la différence entre les deux.
                _ui.value = _ui.value.copy(
                    rxRestHz = Doppler.restFromDownlink(dlObserved - shift, rr))
            }
        }

        val rest = _ui.value.rxRestHz ?: centreRx(t) ?: return
        val dl = Doppler.downlink(rest, rr) + shift
        val ul = Doppler.uplink(ulRest, rr) + txShift

        // Sans relecture possible, on retombe sur le comportement d'avant :
        // écrire, toujours. Un poste muet ne doit pas priver de Doppler.
        // En pause Doppler on ne pilote pas, quoi qu'en dise l'arbitre : le
        // poste garde ce que l'opérateur y a laissé.
        val pilote = (dlObserved == null || fmArbiter.driven) && !_ui.value.dopplerHold
        if (pilote) {
            if (kotlin.math.abs(dl - lastSentDl) >= 200 || kotlin.math.abs(ul - lastSentUl) >= 200) {
                lastSentDl = dl; lastSentUl = ul
                fmArbiter.commanded(dl)
                runCatching { ecrireCouple(dl, ul, sameBand) }
            }
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = ul,
                catRxDriven = true)
        } else {
            // Sans relecture — poste muet et pause en cours — on affiche la
            // consigne calculée plutôt que rien : le suivi doit rester lisible.
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dlObserved ?: dl,
                catRadioUplinkHz = ul, catRxDriven = false)
        }
    }

    /**
     * Transpondeur linéaire : l'opérateur mène, puis le logiciel prend le
     * relais.
     *
     * Tant que la molette bouge, on la lit et on en déduit la fréquence de
     * repos : c'est l'opérateur qui choisit où l'on écoute, et l'on ne touche à
     * rien. Deux secondes de silence plus tard, ce choix devient une fréquence
     * de satellite, que le poste ne saurait pas tenir tout seul : le téléphone
     * accorde alors la réception **et** l'émission, tous deux dérivés du même
     * repos figé.
     *
     * Le repos figé est le cœur de la correction. Le recalculer à chaque
     * lecture, comme on le faisait jusqu'ici, revenait à demander au poste où
     * il en était pour lui répondre qu'il avait raison : la réception ne
     * bougeait jamais, et l'émission dérivait du Doppler qu'on venait de lui
     * réinjecter par erreur.
     */
    private suspend fun interactiveLinear(
        t: fr.f4ioz.satcombo.data.Transmitter, rr: Double, shift: Long, txShift: Long,
        sameBand: Boolean
    ) {
        // La descente ne revient au poste que s'il sait la recevoir. Sur une
        // station QO-100 elle s'écoute à la clé : il n'y a alors rien à relire,
        // rien à arbitrer, et c'est le repos choisi ailleurs qui mène l'émission.
        val auPoste = descenteAuPoste(_ui.value.rxRestHz ?: centreRx(t) ?: return)
        val dlObserved = if (auPoste) lireDescente(sameBand) else null
        if (auPoste && dlObserved == null) return
        val now = System.currentTimeMillis()
        val geste = dlObserved != null && rxArbiter.observe(dlObserved, now)
        if (geste) lastSentDl = 0L
        val pilote = (!auPoste || rxArbiter.driven) && _ui.value.catRxDoppler &&
            !_ui.value.dopplerHold

        // Tant que nous ne pilotons pas, le repos se relit du poste — décalage
        // d'étalonnage et décalage de mode (voix/CW) ôtés, puisqu'ils sont
        // remis à l'écriture.
        val lu = if (dlObserved != null)
            Doppler.restFromDownlink(dlObserved - shift - activeRxOffset(), rr)
        else _ui.value.rxRestHz ?: centreRx(t) ?: return
        val lo = t.downlinkLowHz ?: lu
        val hi = t.downlinkHighHz ?: lu
        if (!pilote) {
            val clamped = lu.coerceIn(minOf(lo, hi), maxOf(lo, hi))
            if (_ui.value.rxRestHz == null || kotlin.math.abs((_ui.value.rxRestHz ?: 0) - clamped) >= 20) {
                _ui.value = _ui.value.copy(rxRestHz = clamped)
            }
        }
        val rest = _ui.value.rxRestHz ?: lu

        val ulRest = Doppler.transponderUplinkRest(
            rest, t.downlinkLowHz ?: rest, t.downlinkHighHz ?: rest,
            t.uplinkLowHz!!, t.uplinkHighHz ?: t.uplinkLowHz!!, effectiveInvert(t))
        val ul = Doppler.uplink(ulRest, rr) + txShift
        val dl = Doppler.downlink(rest, rr) + shift + activeRxOffset()

        // --- la molette d'émission comme commande de décalage ---
        //
        // `ul` vaut `Doppler.uplink(ulRest, rr) + txShift`. Si l'opérateur a
        // tourné sa molette jusqu'à `ulLu`, le décalage qu'il vient d'exprimer
        // vaut donc `ulLu - (ul - txShift)`. Rien à inventer : une soustraction.
        //
        // Réservé au duplex à deux postes, et seulement quand nous pilotons —
        // sinon nous prendrions pour un geste la dérive d'un poste que nous
        // n'avons pas encore commandé.
        if (_ui.value.catUi.txVfoShift && isPairRig && !sameBand && pilote) {
            val ulLu = runCatching { ft817.readUplink() }.getOrNull()
                ?.let { satDepuisPosteTx(it) }
            if (ulLu != null) {
                // Le calcul vit désormais dans `MoletteTx`, hors de cette
                // boucle : il a été faux deux fois de suite ici, invérifiable
                // parce que mêlé à des lectures série et à des écritures.
                // Isolé, il se met en défaut au banc — et l'essai de régression
                // rejoue exactement la dérive Doppler qui faisait osciller le
                // décalage entre deux valeurs.
                if (txArbiter.observe(ulLu, now)) mainSurMoletteTx = true
                val d = fr.f4ioz.satcombo.domain.MoletteTx.decide(
                    shiftHz = txShift,
                    referenceHz = lastSentUl,
                    lueHz = ulLu,
                    gesteVu = mainSurMoletteTx,
                    moletteTranquille = txArbiter.driven)
                if (d.absorbe) {
                    setTxShift(d.shiftHz)
                    lastSentUl = d.referenceHz
                }
                if (d.gesteConsomme) mainSurMoletteTx = false
            }
        }

        if (pilote) {
            // Vingt hertz : en dessous, l'écart ne s'entend pas et l'écriture
            // ne ferait que charger le bus CI-V.
            if (kotlin.math.abs(dl - lastSentDl) >= 20 || kotlin.math.abs(ul - lastSentUl) >= 20) {
                lastSentDl = dl; lastSentUl = ul
                // Se reconnaître soi-même à la relecture : sans cela, notre
                // propre consigne passerait pour un geste de l'opérateur et
                // nous rendrions la main à chaque tour de boucle.
                rxArbiter.commanded(dl)
                // Notre propre montée ne doit pas nous revenir comme un geste.
                txArbiter.commanded(ul)
                runCatching { ecrireCouple(dl, ul, sameBand) }
            }
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dl, catRadioUplinkHz = ul,
                catRxDriven = true)
        } else {
            // La montée suit la molette de réception — mais pas sur tous les
            // postes, et c'est la correction apportée ici.
            //
            // L'ancienne règle disait : « ce délai protège la molette de
            // RÉCEPTION ; il n'a aucune raison de retenir l'ÉMISSION, que
            // l'opérateur ne touche pas ». Cette dernière phrase est fausse sur
            // un poste à double VFO commandé par un seul bouton : sur un
            // IC-9700 en mode satellite, écrire la montée déplace la réception
            // en retour, et l'opérateur se retrouve à se battre contre nous.
            //
            // Filmé sur RS-44 et FO-29 : la somme des deux VFO restait
            // rigoureusement constante pendant la bagarre, preuve que c'était
            // le poste qui tenait le couple, pas nous.
            //
            // La décision est rendue par `SuiviMontee`, qui ne dépend de rien
            // et s'éprouve à la table.
            if (fr.f4ioz.satcombo.domain.SuiviMontee.doitEcrire(
                    rigModel = _ui.value.rigModel,
                    operateurTourne = !rxArbiter.driven,
                    txSuitVite = _ui.value.catUi.txSuitVite,
                    maintienDoppler = _ui.value.dopplerHold,
                    ecartHz = kotlin.math.abs(ul - lastSentUl))) {
                lastSentUl = ul
                txArbiter.commanded(ul)
                runCatching { ecrireMontee(ul, sameBand) }
            }
            _ui.value = _ui.value.copy(catRadioDownlinkHz = dlObserved, catRadioUplinkHz = ul,
                catRxDriven = false)
        }
    }

    fun toggleSelectionMode() {
        val on = !_ui.value.selectionMode
        _ui.value = _ui.value.copy(selectionMode = on,
            selectedPassKeys = if (on) _ui.value.selectedPassKeys else emptySet())
    }

    fun togglePassSelected(p: fr.f4ioz.satcombo.data.SatPass) {
        val k = passKey(p)
        val s = _ui.value.selectedPassKeys.toMutableSet()
        if (!s.add(k)) s.remove(k)
        _ui.value = _ui.value.copy(selectedPassKeys = s)
    }

    fun selectAllVisible(passes: List<fr.f4ioz.satcombo.data.SatPass>) {
        _ui.value = _ui.value.copy(selectedPassKeys = passes.map { passKey(it) }.toSet())
    }

    fun clearPassSelection() {
        _ui.value = _ui.value.copy(selectedPassKeys = emptySet())
    }

    /** Build a PDF from the selected passes and hand it to onReady (file uri). */
    fun exportSelectedPdf(onReady: (android.net.Uri) -> Unit) {
        val keys = _ui.value.selectedPassKeys
        val all = (_ui.value.favoritePasses + _ui.value.passes)
        val chosen = all.filter { passKey(it) in keys }
            .distinctBy { passKey(it) }
            .sortedBy { it.aosEpochMs }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val file = PassPdf.build(getApplication(), chosen, _ui.value.observer)
                PassPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /**
     * Build a rich PDF from the SELECTED passes: one sheet per satellite that
     * has selected passes, showing its polar plot (from the earliest selected
     * pass), radio info, and the list of selected passes. Hands uri to onReady.
     */
    fun exportSelectedSheets(onReady: (android.net.Uri) -> Unit) {
        val keys = _ui.value.selectedPassKeys
        val all = (_ui.value.favoritePasses + _ui.value.passes).distinctBy { passKey(it) }
        val chosen = all.filter { passKey(it) in keys }.sortedBy { it.aosEpochMs }
        if (chosen.isEmpty()) return
        buildSheets(chosen, onReady)
    }

    /**
     * Build the pass PDF: one block per pass (its own polar plot), 6 per A4 page.
     * Resolves each satellite's radio context (chosen transmitter + config) once.
     */
    private fun buildSheets(passes: List<SatPass>, onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val cats = passes.map { it.catalogNumber }.toSet()
                val satByCat = _ui.value.satellites.associateBy { it.catalogNumber }
                val cfgByCat = HashMap<Int, SatConfig>()
                val txByCat = HashMap<Int, fr.f4ioz.satcombo.data.Transmitter?>()
                for (cat in cats) {
                    val cfg = satConfigStore.load(cat)
                    cfgByCat[cat] = cfg
                    val txs = runCatching { txRepo.forSatellite(cat) }.getOrDefault(emptyList())
                    txByCat[cat] = txs.firstOrNull { it.description == cfg.txDescription }
                        ?: txs.firstOrNull { it.alive } ?: txs.firstOrNull()
                }
                val sheets = passes.mapNotNull { p ->
                    val sat = satByCat[p.catalogNumber] ?: return@mapNotNull null
                    fr.f4ioz.satcombo.data.SatSheetPdf.PassSheet(
                        pass = p, sat = sat,
                        transmitter = txByCat[p.catalogNumber],
                        config = cfgByCat[p.catalogNumber])
                }
                val obs = _ui.value.observer ?: locationProvider.defaultObserver
                val file = fr.f4ioz.satcombo.data.SatSheetPdf.build(
                    getApplication(), sheets, obs, _ui.value.useUtc)
                fr.f4ioz.satcombo.data.SatSheetPdf.uri(getApplication(), file)
            }
            onReady(uri)
        }
    }

    /**
     * Export a sheet for every upcoming pass (48h) of all followed satellites.
     */
    fun exportSatSheets(onReady: (android.net.Uri) -> Unit) {
        val favs = _ui.value.satellites.filter { it.catalogNumber in _ui.value.favorites }
        if (favs.isEmpty()) return
        viewModelScope.launch {
            val passes = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val obs = _ui.value.observer ?: locationProvider.defaultObserver
                favs.flatMap { sat ->
                    runCatching {
                        predictor.upcomingPasses(sat, obs, fromMs = now - 20 * 60_000L,
                            hours = 48, minElDeg = settings.minElevDeg.toDouble())
                            .filter { it.losEpochMs > now }
                    }.getOrDefault(emptyList())
                }.sortedBy { it.aosEpochMs }
            }
            buildSheets(passes, onReady)
        }
    }

    /** Open a satellite from a notification tap; waits for data if still loading. */
    fun openFromNotification(catnum: Int, aos: Long) {
        viewModelScope.launch {
            var tries = 0
            while (_ui.value.satellites.none { it.catalogNumber == catnum } && tries < 50) {
                kotlinx.coroutines.delay(100); tries++
            }
            if (_ui.value.satellites.any { it.catalogNumber == catnum }) {
                closeSettings()
                selectByCatnum(catnum, if (aos > 0) aos else null)
            }
        }
    }

    fun selectByCatnum(catnum: Int, focusPassAos: Long? = null) {
        _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }?.let { select(it, focusPassAos) }
    }

    fun backToList() {
        trackingFor = null
        _ui.value = _ui.value.copy(selected = null, passes = emptyList(),
            passTrack = emptyList(), livePosition = null, trail = emptyList(),
            transmitters = emptyList(), transmittersLoading = false, groundTrack = emptyList(),
            logEditTimeMs = null, uiLocked = false)
    }

    /**
     * Re-download the freshest TLE for one satellite by catalog number (Celestrak
     * CATNR query) and replace it in the list. Used when a sked is near so the
     * common-window calc uses up-to-date elements.
     */
    /** If an announced sked for this sat is near and its TLE is aging, refresh it. */
    private fun maybeRefreshTleForSkeds(sat: TleEntry) {
        if (!_ui.value.skedsEnabled) return
        val soon = _ui.value.skeds.any {
            it.satNorad == sat.catalogNumber &&
                it.aosMs - System.currentTimeMillis() in 0..(48 * 3600_000L)
        }
        val epochAgeDays = sat.epochMs?.let {
            (System.currentTimeMillis() - it) / 86_400_000.0 } ?: 0.0
        if (soon && epochAgeDays > 2) refreshTleFor(sat.catalogNumber)
    }

    fun refreshTleFor(catnum: Int) {
        viewModelScope.launch {
            val fresh = runCatching { repo.fetchByCatnr(catnum) }.getOrNull() ?: return@launch
            val updated = _ui.value.satellites.map {
                if (it.catalogNumber == catnum) fresh.copy(
                    uplinkHz = it.uplinkHz, downlinkHz = it.downlinkHz, mode = it.mode) else it
            }
            // Si les éléments n'ont pas changé, il n'y a rien à recalculer.
            //
            // `select()` vide les transpondeurs et rallume l'indicateur de
            // chargement : appelé à chaque rafraîchissement, il faisait
            // clignoter l'écran du satellite ouvert et rechargeait le
            // catalogue pour rien. Or un TLE ne bouge que quelques fois par
            // jour.
            val ancien = _ui.value.satellites.firstOrNull { it.catalogNumber == catnum }
            val identique = ancien != null &&
                ancien.line1 == fresh.line1 && ancien.line2 == fresh.line2
            _ui.value = _ui.value.copy(satellites = updated)
            if (!identique) {
                _ui.value.selected?.let {
                    if (it.catalogNumber == catnum) select(fresh, _ui.value.focusedPassAos)
                }
            }
        }
    }

    fun select(sat: TleEntry, focusPassAos: Long? = null) {
        val obs = _ui.value.observer ?: locationProvider.defaultObserver
        // Tout ce qui appartenait au satellite précédent s'en va avec lui. Le
        // repos accordé à la main, surtout : le garder, c'était afficher les
        // fréquences de l'ancien satellite sous le nom du nouveau.
        _ui.value = _ui.value.copy(selected = sat, loading = true, trail = emptyList(),
            passTrack = emptyList(), transmitters = emptyList(), transmittersLoading = true,
            satStatus = null, focusedPassAos = focusPassAos, rxFromAgendaHz = null,
            logEditTimeMs = null, invertOverride = null,
            rxRestHz = null, selectedTxIndex = 0,
            catRadioDownlinkHz = null, catRadioUplinkHz = null)
        viewModelScope.launch {
            val tx = txRepo.forSatellite(sat.catalogNumber)
            val status = txRepo.statusOf(sat.catalogNumber)
            if (_ui.value.selected?.catalogNumber == sat.catalogNumber) {
                val active = tx.filter { it.alive && (it.downlinkLowHz != null || it.uplinkLowHz != null) }
                val cfg = satConfigStore.load(sat.catalogNumber)
                // À défaut de choix enregistré, on prend le premier émetteur
                // qui ait **une montée et une descente** — c'est-à-dire un
                // transpondeur, avec lequel on peut trafiquer.
                //
                // L'ancien repli sur l'indice zéro tombait, sur FO-29, sur la
                // balise CW : une descente seule, sans montée. De quoi écouter,
                // pas de quoi faire un contact, et rien à écrire dans le poste
                // d'émission.
                var idx = active.indexOfFirst { it.description == cfg.txDescription }
                if (idx < 0) idx = active.indexOfFirst {
                    it.downlinkLowHz != null && it.uplinkLowHz != null
                }
                if (idx < 0) idx = 0
                var rx = active.getOrNull(idx)?.let { centreRx(it) }
                // Une fréquence annoncée dans l’agenda passe devant le
                // catalogue pendant toute la durée du créneau : c’est le sens
                // même de l’annonce. On cherche d’abord l’émetteur dont la
                // bande la contient — sinon le VFO afficherait une fréquence
                // sous une étiquette de transpondeur qui n’a rien à voir.
                val evFreq = agendaFreqFor(sat.name,
                    focusPassAos ?: System.currentTimeMillis())
                if (evFreq != null) {
                    val hz = evFreq.freqHz
                    val inBand = active.indexOfFirst {
                        val lo = it.downlinkLowHz
                        val hi = it.downlinkHighHz ?: lo
                        lo != null && hi != null && hz >= minOf(lo, hi) && hz <= maxOf(lo, hi)
                    }
                    if (inBand >= 0) idx = inBand
                    rx = hz
                }
                _ui.value = _ui.value.copy(transmitters = tx, transmittersLoading = false,
                    selectedTxIndex = idx, rxRestHz = rx, calibShiftHz = cfg.calibShiftHz, txShiftHz = cfg.txShiftHz,
                    catUi = _ui.value.catUi.copy(
                        refCalibShiftHz = cfg.refCalibShiftHz, refTxShiftHz = cfg.refTxShiftHz,
                        arriveeCalibShiftHz = cfg.calibShiftHz,
                        arriveeTxShiftHz = cfg.txShiftHz),
                    rxOffsetVoiceHz = cfg.rxOffsetVoiceHz, rxOffsetCwHz = cfg.rxOffsetCwHz,
                    rxFromAgendaHz = evFreq?.freqHz,
                    satStatus = status)
            }
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val back = maxOf(_ui.value.pastPassHours * 3_600_000L, 20 * 60_000L)
            val from = now - back
            // Extend the window so a focused pass (e.g. chosen via a date filter
            // days ahead) is included, not just the next 72 h.
            val focus = focusPassAos
            val hours = if (focus != null)
                (((focus - now) / 3600_000L) + 6).toInt().coerceIn(72, 24 * 90)
            else 72
            val passes = withContext(Dispatchers.Default) {
                predictor.upcomingPasses(sat, obs, fromMs = from, hours = hours, minElDeg = settings.minElevDeg.toDouble())
            }.filter { it.losEpochMs > now - back }
            val gt = withContext(Dispatchers.Default) {
                predictor.groundTrack(sat, System.currentTimeMillis())
            }
            _ui.value = _ui.value.copy(passes = passes, loading = false, groundTrack = gt)
            refreshPassTrack(sat, obs)
            trackLive(sat, obs)
            maybeRefreshTleForSkeds(sat)
        }
    }

    /** Compute the polar-plot arc for the current or next pass. */
    private var trackedPassAos: Long = 0
    private suspend fun refreshPassTrack(sat: TleEntry, obs: Observer) {
        val now = System.currentTimeMillis()
        val focus = _ui.value.focusedPassAos
        val pass = (focus?.let { f -> _ui.value.passes.minByOrNull { kotlin.math.abs(it.aosEpochMs - f) } }
            ?: _ui.value.passes.firstOrNull { it.losEpochMs > now }) ?: run {
            _ui.value = _ui.value.copy(passTrack = emptyList()); return
        }
        if (pass.aosEpochMs == trackedPassAos && _ui.value.passTrack.isNotEmpty()) return
        trackedPassAos = pass.aosEpochMs
        val track = withContext(Dispatchers.Default) {
            predictor.passTrack(sat, obs, pass.aosEpochMs, pass.losEpochMs)
        }
        _ui.value = _ui.value.copy(passTrack = track)
    }

    // ===================== rotor d'azimut et d'élévation =====================

    /**
     * L'état mécanique du mât, tel que l'application le croit.
     *
     * Ce n'est pas la même chose que la consigne : entre les deux il y a une
     * minute de rotation, et c'est précisément cet écart qui rend le
     * recouvrement utile. Quand le contrôleur rend sa position, elle fait
     * autorité ; quand il ne rend rien — câble arraché, contrôleur muet —, on
     * continue avec la dernière consigne, faute de mieux, et le bandeau le dit.
     */
    private var rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)

    /**
     * La dernière position **vraiment lue**, et l'instant où elle l'a été.
     *
     * Elles ne servent qu'à l'affichage, et seulement pour tenir quelques
     * secondes quand le contrôleur saute une réponse — voir [RotorTenue]. Rien
     * d'autre ne s'en sert : une position tenue n'a pas à décider d'un
     * pointage.
     */
    private var rotorLu: fr.f4ioz.satcombo.rotor.RotorPos? = null
    private var rotorLuMs: Long = 0L

    /**
     * La dernière consigne envoyée, qui n'est pas la position du mât.
     *
     * C'est près d'elle, et non près de la position lue, que l'azimut suivant
     * se déroule. La différence est tout sauf théorique : pendant la minute où
     * le mât monte vers 540°, le contrôleur répond 380, puis 400, puis 420 ; se
     * dérouler près de ces valeurs-là ferait redescendre la consigne vers la
     * branche d'où l'on vient, et le mât ferait demi-tour au milieu du passage
     * — précisément ce que le plan avait choisi d'éviter.
     */
    private var rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)

    /** Le tour de mât retenu pour le passage en cours, choisi à l'acquisition. */
    private var rotorPlan: fr.f4ioz.satcombo.rotor.RotorMath.Plan? = null
    private var rotorPlanKey: String? = null
    private var rotorLoopJob: kotlinx.coroutines.Job? = null
    private var rotorWasFlipped = false
    private var rotorSimLink: fr.f4ioz.satcombo.rotor.Gs232Simulator? = null

    /**
     * Le même pilote que [rotorDriver], quand il parle GS-232.
     *
     * Il faut la vraie sorte pour lire les trames brutes : l'interface commune
     * ne connaît que « la position » et « c'est parti », ce qui suffit pour
     * suivre un satellite et ne suffit pas du tout pour comprendre pourquoi
     * rien ne bouge.
     */
    private var rotorSerial: fr.f4ioz.satcombo.rotor.Gs232Rotor? = null

    // ==================================================================
    // QO-100 — le satellite qui ne bouge pas
    //
    // Cet écran ne passe par aucune des boucles habituelles, et c'est
    // délibéré. `catTick()` part de `livePosition`, qui vient d'une
    // propagation SGP4 ; un géostationnaire n'a pas de position propagée
    // utilisable, et le prédicteur de passages ne rendrait jamais rien. Plutôt
    // que de piquer des exceptions dans ces boucles — chacune serait une
    // occasion de casser les autres satellites — on écrit ici, à la main, au
    // moment où l'opérateur bouge quelque chose. Il n'y a rien à rafraîchir
    // dix fois par seconde : sans Doppler, la fréquence ne bouge que quand on
    // la bouge.
    //
    // Ce qui est réutilisé tel quel, en revanche : [ecrireCouple], donc les
    // convertisseurs, la garde d'atteignabilité et le mode satellite du
    // poste ; et le décalage d'étalonnage par NORAD, qui existe déjà.
    // ==================================================================

    /** Le transpondeur couramment choisi, jamais nul. */
    private fun qo100Tp(): Qo100.Transpondeur =
        Qo100.TRANSPONDEURS.firstOrNull { it.cle == _ui.value.qo100.transpondeur } ?: Qo100.NB

    fun openQo100() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.QO100)) return
        _ui.value = _ui.value.copy(screen = Screen.QO100)
        // Le calage déjà mesuré pour ce satellite, et le pointage depuis le
        // QTH : deux choses qui ne changent pas pendant qu'on opère, donc
        // relues une fois à l'ouverture plutôt qu'à chaque tour.
        val calage = satConfigStore.load(Qo100.NORAD).calibShiftHz
        _ui.value = _ui.value.qo { copy(calageHz = calage) }
        qo100Recalcule()
        viewModelScope.launch {
            val obs = runCatching { resolveObserver() }.getOrNull() ?: return@launch
            val p = Qo100.pointage(obs.latDeg, obs.lonDeg)
            _ui.value = _ui.value.qo {
                copy(azDeg = p.azDeg, elDeg = p.elDeg, skewDeg = p.skewDeg)
            }
            qo100Soleil(obs.latDeg, obs.lonDeg)
        }
        qo100Panorama(true)
    }

    /** Le fil qui suit la balise. Un seul, et il meurt avec l'écran. */
    private var jobBalise: kotlinx.coroutines.Job? = null

    /**
     * Allume ou éteint le panorama, et avec lui le témoin de balise.
     *
     * Les deux vont ensemble et n'ont qu'un seul client : cet écran. Une FFT
     * de seize mille points trois fois par seconde n'a rien à faire dans le
     * dos de l'opérateur pendant qu'il écoute une radiosonde — d'où
     * l'interrupteur, plutôt qu'un calcul permanent dans [SdrHub].
     */
    private fun qo100Panorama(on: Boolean) {
        runCatching { fr.f4ioz.satcombo.sdr.SdrHub.setPanorama(on) }
        jobBalise?.cancel()
        jobBalise = null
        if (!on) {
            if (_ui.value.qo100.balise != null) {
                _ui.value = _ui.value.qo { copy(balise = null) }
            }
            return
        }
        jobBalise = viewModelScope.launch {
            fr.f4ioz.satcombo.sdr.SdrHub.panorama.collect { pan ->
                val m = if (pan.isEmpty()) null
                else withContext(Dispatchers.Default) { qo100MesureBalise(pan) }
                // On ne réécrit l'état que quand la mesure a bougé : sans ce
                // filet, trois recompositions par seconde de tout l'écran pour
                // apprendre que rien n'a changé.
                if (m != _ui.value.qo100.balise) {
                    _ui.value = _ui.value.qo { copy(balise = m) }
                }
            }
        }
    }

    /**
     * Où tombe la balise médiane dans le panorama, ramené à l'échelle de la
     * réglette.
     *
     * Tout le raisonnement tient dans le choix du repère. Le panorama est
     * ancré sur la PLL de la clé — pas sur l'accord fin, qui est en aval de la
     * prise. On remonte donc de la fréquence de la clé vers le ciel par le
     * convertisseur, puis on retire le calage déjà appliqué : ce qui reste est
     * exprimé dans les mêmes fréquences que la réglette et que le plan de
     * bande, c'est-à-dire les fréquences nominales.
     *
     * L'écart rendu est alors, exactement, ce qu'il reste à ajouter au calage.
     * C'est ce qui permet au bouton de la carte d'écrire
     * `calage + écart` sans autre calcul, et de converger en un coup.
     */
    private fun qo100MesureBalise(
        pan: FloatArray
    ): fr.f4ioz.satcombo.domain.MesureBalise.Mesure? {
        val axe = qo100AxeCiel() ?: return null
        return fr.f4ioz.satcombo.domain.MesureBalise.mesurer(pan, axe.first, axe.second)
    }

    /**
     * L'axe du panorama : le centre du ciel, et l'étendue signée.
     *
     * Rendu au singulier pour que la mesure de balise et le dessin de la
     * cascade parlent du même axe. Deux calculs séparés finiraient par diverger
     * d'un signe ou d'un calage, et la raie ne tomberait plus sur son trait —
     * c'est-à-dire que l'écran mentirait précisément là où on lui demande de
     * dire la vérité.
     *
     * `null` quand la clé ne tourne pas : il n'y a pas d'axe sans porteuse.
     */
    fun qo100AxeCiel(): Pair<Double, Double>? {
        val st = fr.f4ioz.satcombo.sdr.SdrHub.state.value
        if (!st.running) return null
        val q = _ui.value.qo100
        // La PLL, sans la part de Doppler encaissée en logiciel — nulle sur un
        // géostationnaire, mais on ne s'appuie pas sur une valeur nulle.
        val pllHz = st.centerHz - st.dopplerFineHz
        val centreCiel = cleVersSat(pllHz).toDouble() - q.calageHz
        val conv = _ui.value.convRx
        val inverse = _ui.value.convRxCle && conv.inverseur &&
            conv.couvre(q.descenteHz + q.calageHz)
        val etendue = if (inverse) -fr.f4ioz.satcombo.sdr.SdrHub.PANORAMA_SPAN_HZ
        else fr.f4ioz.satcombo.sdr.SdrHub.PANORAMA_SPAN_HZ
        return centreCiel to etendue
    }

    /**
     * Écrire d'un coup le calage que la balise vient de dicter.
     *
     * C'est la même opération que la saisie manuelle de la carte de calage, à
     * ceci près qu'on ne recopie pas un chiffre lu sur une cascade : on prend
     * celui que la mesure vient de rendre. Sans mesure, on ne fait rien —
     * surtout pas remettre à zéro.
     */
    fun qo100CalerSurLaMesure() {
        val m = _ui.value.qo100.balise ?: return
        setQo100Calage(_ui.value.qo100.calageHz + m.ecartArrondiHz)
    }

    /**
     * Les repères solaires, calculés hors du fil principal.
     *
     * Le balayage des transits parcourt une année minute par minute, soit un
     * demi-million de positions solaires. C'est rapide — quelques dizaines de
     * millisecondes — et c'est exactement le genre de calcul qui n'a rien à
     * faire sur le fil d'affichage : sur un téléphone lent, quelques dizaines
     * de millisecondes sont deux images perdues, et il n'y a aucune raison de
     * les perdre pour une information qui ne change pas de la journée.
     *
     * On ne recalcule pas non plus à chaque tour : le prochain passage en
     * azimut est valable jusqu'à demain, et les transits jusqu'à l'équinoxe
     * suivant. Une fois à l'ouverture de l'écran suffit.
     */
    private suspend fun qo100Soleil(latDeg: Double, lonDeg: Double) {
        val maintenant = System.currentTimeMillis()
        val calcul = withContext(Dispatchers.Default) {
            runCatching {
                fr.f4ioz.satcombo.domain.SoleilQo100.prochainPassageEnAzimut(
                    latDeg, lonDeg, maintenant) to
                    fr.f4ioz.satcombo.domain.SoleilQo100.prochainsTransits(
                        latDeg, lonDeg, maintenant, maximum = 4)
            }.getOrNull()
        } ?: return
        _ui.value = _ui.value.qo {
            copy(soleilAzimutMs = calcul.first, soleilTransits = calcul.second)
        }
    }

    /**
     * Quitter l'écran ne défait rien.
     *
     * Le poste reste où on l'a mis, comme il resterait si on avait tourné la
     * molette : on ne va pas déranger une station en plein QSO parce que
     * quelqu'un a regardé la liste des passages.
     */
    fun closeQo100() {
        qo100Panorama(false)
        _ui.value = _ui.value.copy(screen = Screen.PASSES)
    }

    fun setQo100Transpondeur(cle: String) {
        val tp = Qo100.TRANSPONDEURS.firstOrNull { it.cle == cle } ?: return
        _ui.value = _ui.value.qo {
            copy(transpondeur = tp.cle, descenteHz = tp.brideDescente(descenteHz))
        }
        qo100Recalcule()
        qo100Pousser()
    }

    /**
     * Se poser sur une descente, bridée au transpondeur.
     *
     * La bride n'est pas du confort : au-delà des bords, la montée
     * correspondante sort du transpondeur et la porteuse part chez le voisin —
     * un satellite géostationnaire n'a pas d'horizon pour rattraper l'erreur.
     */
    fun setQo100Descente(hz: Long) {
        // Sans bride, on balaie tout QO-100 — mais jamais au-delà du plan de
        // bande lui-même : au-dehors il n'y a plus de satellite, et l'écran
        // n'aurait plus rien de vrai à montrer.
        val borne = if (settings.qo100SansBride)
            hz.coerceIn(Qo100.REGLETTE_BAS_HZ, Qo100.REGLETTE_HAUT_HZ)
        else qo100Tp().brideDescente(hz)
        _ui.value = _ui.value.qo { copy(descenteHz = borne) }
        qo100Recalcule()
        qo100Pousser()
    }

    /** Balayer tout QO-100, ou rester dans le transpondeur choisi. */
    fun setQo100SansBride(on: Boolean) {
        settings.qo100SansBride = on
        _ui.value = _ui.value.qo { copy(sansBride = on) }
    }

    /** Le pas de molette de l'écran, en hertz signés. */
    fun qo100Pas(deltaHz: Long) = setQo100Descente(_ui.value.qo100.descenteHz + deltaHz)

    /** Se poser sur la balise médiane : le point de repère de tout le monde. */
    fun qo100AllerBalise() = setQo100Descente(Qo100.BALISE_MEDIANE_HZ)

    /** Se poser sur une mémoire ou un repère. */
    fun qo100Aller(hz: Long) = setQo100Descente(hz)

    /**
     * Range la fréquence courante dans les mémoires.
     *
     * Le nom est facultatif : sans lui, la mémoire prend ses kilohertz, ce qui
     * suffit à la reconnaître dans une liste courte. Demander un nom
     * obligatoire ferait renoncer à poser la mémoire au moment où elle sert —
     * en plein QSO, quand on n'a pas une main pour taper.
     */
    fun qo100PoseMemoire(nom: String) {
        val posees = fr.f4ioz.satcombo.domain.MemoiresQo100.pose(
            _ui.value.qo100.memoires, _ui.value.qo100.descenteHz, nom)
        settings.qo100Memoires = posees
        _ui.value = _ui.value.qo { copy(memoires = posees) }
    }

    fun qo100RetireMemoire(hz: Long) {
        val posees = fr.f4ioz.satcombo.domain.MemoiresQo100.retire(
            _ui.value.qo100.memoires, hz)
        settings.qo100Memoires = posees
        _ui.value = _ui.value.qo { copy(memoires = posees) }
    }

    // ---------------------------------------------- les chaînes de conversion

    /**
     * Change de chaîne : station fixe, station portable, ce qu'on veut.
     *
     * Chaque site a ses convertisseurs et **chaque oscillateur a son erreur
     * propre, mesurée**. Retaper la valeur à chaque changement de site, c'est
     * se tromper un jour — et trois cents kilohertz d'erreur sur QO-100, c'est
     * ne rien entendre du tout.
     */
    /**
     * Range l'écart mesuré d'un appareil.
     *
     * La référence est ignorée : sans point fixe, une mesure ne peut pas dire
     * ce qui revient au LNB et ce qui revient au récepteur.
     */
    fun setMaterielPpm(nom: String, ppm: Double) {
        val liste = fr.f4ioz.satcombo.domain.MaterielRx.range(
            _ui.value.qo100.materiels, nom, ppm)
        settings.materielsRx = fr.f4ioz.satcombo.domain.MaterielRx.ecrit(liste)
        _ui.value = _ui.value.qo { copy(materiels = liste) }
        qo100Recalcule()
    }

    fun setMaterielPoste(nom: String) {
        settings.materielPoste = nom
        _ui.value = _ui.value.qo { copy(materielPoste = nom) }
        qo100Recalcule()
    }

    fun setMaterielCle(nom: String) {
        settings.materielCle = nom
        _ui.value = _ui.value.qo { copy(materielCle = nom) }
        qo100Recalcule()
    }

    fun setQo100Chaine(nom: String) {
        settings.qo100Chaine = nom
        _ui.value = _ui.value.qo { copy(chaine = nom) }
        qo100AppliqueChaine()
    }

    /**
     * Enregistre un oscillateur mesuré dans la chaîne en service.
     *
     * [cielHz] est la fréquence lue sur une référence — un WebSDR sur GPSDO —
     * et [posteHz] celle affichée par le poste sur le **même signal**. L'écart
     * est l'oscillateur : l'opérateur n'a rien à calculer, il recopie deux
     * nombres qu'il a sous les yeux.
     *
     * Rend le message à afficher, vide si tout va bien. On refuse ce qui ne
     * peut pas être un oscillateur — deux champs inversés, une virgule
     * déplacée — mais **jamais un simple écart au nominal** : c'est justement
     * ce qu'on cherche à mesurer.
     */
    /**
     * Rend le succès **et** le texte, plutôt que le texte seul.
     *
     * Une chaîne vide signifiait « réussi » et une chaîne pleine « refusé ».
     * L'écran ne pouvait donc pas colorer un succès autrement qu'un échec, et
     * surtout il n'avait rien à montrer quand tout allait bien.
     */
    fun qo100Mesure(descente: Boolean, cielHz: Long, posteHz: Long): Pair<Boolean, String> {
        if (cielHz <= 0L || posteHz <= 0L) return false to t("qo100_mesure_vide")
        val ol = fr.f4ioz.satcombo.domain.ChaineQo100.olMesure(cielHz, posteHz)
        val bon = if (descente)
            fr.f4ioz.satcombo.domain.ChaineQo100.descenteCredible(ol)
        else fr.f4ioz.satcombo.domain.ChaineQo100.monteeCredible(ol)
        if (!bon) return false to tf("qo100_mesure_refus", ol / 1_000_000.0)
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = ol)
                    else actuelle.copy(monteeOlHz = ol)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
        // **Une réussite se dit.**
        //
        // Elle rendait une chaîne vide, donc l'écran n'affichait rien : le
        // même écran qu'avant d'appuyer. Rien ne distinguait « c'est fait » de
        // « le bouton n'a pas répondu », et l'opérateur concluait à un défaut
        // alors que sa mesure était prise et rangée.
        //
        // On rend donc l'oscillateur trouvé, et son écart au nominal — qui est
        // l'information intéressante : 27 kHz, c'est le quartz du LNB, et
        // c'est reproductible.
        val nominal = if (descente) 10_345_000_000L else 1_968_000_000L
        return true to tf("qo100_mesure_ok", ol / 1_000_000.0, (ol - nominal) / 1_000.0)
    }

    /** Efface un oscillateur : la chaîne redevient directe de ce côté. */
    fun qo100EffaceOl(descente: Boolean) {
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = 0L)
                    else actuelle.copy(monteeOlHz = 0L)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
    }

    /**
     * Pose un oscillateur nominal, choisi dans un préréglage.
     *
     * C'est le geste de départ : on désigne le matériel — « descente vers
     * 144 », « LNB nu » — et l'appareil en déduit l'oscillateur du catalogue.
     * La mesure vient après et le corrige ; mais il faut bien partir de
     * quelque part, et taper dix chiffres de mémoire n'est pas un départ.
     *
     * `olHz` à zéro retire le convertisseur : c'est le cas « prise directe »,
     * où le poste travaille déjà sur la fréquence du ciel.
     */
    fun qo100PoseOl(descente: Boolean, olHz: Long) {
        val q = _ui.value.qo100
        val actuelle = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        val neuve = if (descente) actuelle.copy(descenteOlHz = olHz)
                    else actuelle.copy(monteeOlHz = olHz)
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(q.chaines, neuve)
        settings.qo100Chaines = liste
        _ui.value = _ui.value.qo { copy(chaines = liste) }
        qo100AppliqueChaine()
    }

    /** Ajoute une chaîne vide sous ce nom, et s'y place. */
    fun qo100AjouteChaine(nom: String) {
        if (nom.isBlank()) return
        val liste = fr.f4ioz.satcombo.domain.ChaineQo100.range(
            _ui.value.qo100.chaines,
            fr.f4ioz.satcombo.domain.ChaineQo100.Chaine(nom = nom.trim()))
        settings.qo100Chaines = liste
        settings.qo100Chaine = nom.trim()
        _ui.value = _ui.value.qo { copy(chaines = liste, chaine = nom.trim()) }
        qo100AppliqueChaine()
    }

    /**
     * Porte les oscillateurs de la chaîne en service dans les convertisseurs.
     *
     * Une seule source de vérité : la chaîne. Les convertisseurs restent le
     * mécanisme, mais ils ne se règlent plus à la main pour QO-100 — deux
     * endroits où poser le même oscillateur finiraient par diverger.
     */
    private fun qo100AppliqueChaine() {
        val q = _ui.value.qo100
        val c = fr.f4ioz.satcombo.domain.ChaineQo100.choisie(q.chaines, q.chaine)
        // **La chaîne décide, y compris quand elle ne veut aucun
        // convertisseur.**
        //
        // Le `if` ne posait la valeur que dans un sens : choisir « prise
        // directe » laissait l'ancien oscillateur actif dans les réglages, et
        // la chaîne disait une chose pendant que la chaîne réelle en faisait
        // une autre. Une source unique de vérité n'en est une que si elle
        // écrit aussi les cas vides.
        // **Des bornes à la mesure de QO-100, non de toute la bande Ku.**
        //
        // Elles couvraient 10 400 à 10 800 MHz — quatre cents mégahertz pour
        // un transpondeur qui en fait un demi. Plus les bornes sont larges,
        // plus la fenêtre intermédiaire l'est aussi, et plus il devient facile
        // qu'une fréquence étrangère y ressemble. Les resserrer sur les seules
        // descentes de QO-100 réduit d'autant les occasions de confusion.
        settings.convRxActif = c.descenteActive
        settings.convRxOlHz = c.descenteOlHz
        settings.convRxInverseur = false
        settings.convRxBasHz = 10_489_000_000L
        settings.convRxHautHz = 10_500_000_000L
        settings.convTxActif = c.monteeActive
        settings.convTxOlHz = c.monteeOlHz
        settings.convTxInverseur = false
        settings.convTxBasHz = 2_390_000_000L
        settings.convTxHautHz = 2_450_000_000L
        // L'état affiché doit suivre la même écriture, sinon l'écran des
        // convertisseurs montrerait la valeur d'avant.
        _ui.value = _ui.value.copy(
            convRx = fr.f4ioz.satcombo.domain.Convertisseur(
                actif = c.descenteActive, olHz = c.descenteOlHz,
                inverseur = false,
                basHz = 10_489_000_000L, hautHz = 10_500_000_000L),
            convTx = fr.f4ioz.satcombo.domain.Convertisseur(
                actif = c.monteeActive, olHz = c.monteeOlHz,
                inverseur = false,
                basHz = 2_390_000_000L, hautHz = 2_450_000_000L))
        qo100Pousser()
    }

    fun setQo100AuPoste(on: Boolean) {
        _ui.value = _ui.value.qo { copy(auPoste = on) }
        if (on) qo100Pousser()
    }

    fun setQo100ALaCle(on: Boolean) {
        _ui.value = _ui.value.qo { copy(aLaCle = on) }
        if (on) qo100Pousser()
    }

    /**
     * Le calage sur la balise.
     *
     * L'opérateur écoute la balise BPSK médiane et note où elle tombe
     * réellement. L'écart avec 10 489,750 est la dérive de l'oscillateur du
     * convertisseur de descente — quelques dizaines de kilohertz à la mise
     * sous tension d'un LNB ordinaire, puis moins d'un après une demi-heure.
     *
     * Il se range là où tous les autres décalages d'étalonnage se rangent,
     * sous le NORAD du satellite, et sert ensuite partout sans mécanisme
     * nouveau.
     *
     * @param entenduHz où la balise a été entendue, en fréquence du ciel.
     */
    fun qo100CalerSurLaBalise(entenduHz: Long) {
        val ecart = entenduHz - Qo100.BALISE_MEDIANE_HZ
        setQo100Calage(_ui.value.qo100.calageHz + ecart)
    }

    /** Le décalage d'étalonnage, réglé directement. */
    fun setQo100Calage(hz: Long) {
        satConfigStore.saveShift(Qo100.NORAD, hz)
        _ui.value = _ui.value.qo { copy(calageHz = hz) }
        qo100Recalcule()
        qo100Pousser()
    }

    /** Remet le calage à zéro : ce qu'on fait après avoir changé de LNB. */
    fun qo100AnnulerCalage() = setQo100Calage(0L)

    /**
     * Recalcule ce qui se déduit — et rien d'autre.
     *
     * Les fréquences du poste et de la clé ne servent qu'à être affichées :
     * elles disent à l'opérateur ce qu'il devrait lire sur la face avant. Les
     * écritures réelles repassent par [ecrireCouple], qui refait la conversion
     * pour son compte. Recopier une valeur déjà convertie serait la seule
     * façon de la convertir deux fois.
     */
    private fun qo100Recalcule() {
        val q = _ui.value.qo100
        val dlSat = q.descenteHz + q.calageHz
        val ulSat = Qo100.monteeDepuisDescente(q.descenteHz)
        val rx = posteRx(dlSat)
        val tx = posteTx(ulSat)
        val cle = cleRx(dlSat)
        _ui.value = _ui.value.qo {
            copy(
                posteRxHz = rx, posteTxHz = tx, cleRxHz = cle,
                posteAtteignable = atteignableParLePoste(rx) && atteignableParLePoste(tx),
                cleAtteignable = cle in 24_000_000L..1_766_000_000L,
                surBalise = kotlin.math.abs(descenteHz - Qo100.BALISE_MEDIANE_HZ) < 100L)
        }
    }

    /**
     * Pousse la fréquence courante vers ce qui est coché, et vers cela seul.
     *
     * « Les deux, selon les essais » : les deux cases sont réellement
     * indépendantes. Un montage où le poste émet en 432 pendant que l'écoute
     * se fait à la clé sur un tout autre convertisseur est un montage courant
     * sur ce satellite, et il ne doit rien coûter de plus qu'une case cochée.
     */
    private fun qo100Pousser() {
        val q = _ui.value.qo100
        val dlSat = q.descenteHz + q.calageHz
        val ulSat = Qo100.monteeDepuisDescente(q.descenteHz)
        // **Balayer n'est pas émettre.**
        //
        // Débridé, on traverse les balises et les segments où l'émission est
        // proscrite, pour écouter ce qui s'y passe. Y pousser une fréquence
        // d'émission préparerait le poste à transmettre sur une balise — celle
        // qui sert de référence à toute la bande — et il suffirait alors d'un
        // appui sur l'alternat.
        //
        // La montée n'est donc écrite que là où le plan l'autorise, et l'écran
        // le dit : sans cela l'opérateur croirait son poste prêt.
        val peutEmettre = Qo100.emissionAutorisee(q.descenteHz)
        if (q.auPoste && !peutEmettre) {
            _ui.value = _ui.value.qo { copy(statut = t("qo100_ecoute_seule")) }
        }
        if (q.auPoste && peutEmettre) viewModelScope.launch {
            // sameBand = false : 145 en réception et 432 en émission, c'est le
            // mode satellite du poste, pas un simple split.
            runCatching { ecrireCouple(dlSat, ulSat, sameBand = false) }
                .onFailure { e ->
                    _ui.value = _ui.value.qo { copy(statut = e.message ?: "CAT ?") }
                }
        }
        if (q.aLaCle) runCatching {
            fr.f4ioz.satcombo.sdr.SdrHub.setCenter(cleRx(dlSat), cleRx(dlSat))
        }
    }

    fun openRotor() {
        if (!hasExtension(fr.f4ioz.satcombo.data.Extensions.ROTOR)) return
        refreshRotorDevices()
        _ui.value = _ui.value.copy(screen = Screen.ROTOR)
    }

    /** Quitter l'écran n'arrête pas le mât : un passage dure dix minutes. */
    fun closeRotor() { _ui.value = _ui.value.copy(screen = Screen.PASSES) }

    /**
     * Le même filet que pour le CAT, et pour la même raison.
     *
     * À une différence près, qui pèse : ici la panne coupe aussi la poursuite.
     * Un pilote qui a levé une exception est un pilote dont on ne sait plus ce
     * qu'il a envoyé, et laisser une boucle continuer à pousser des consignes
     * vers un lien dans cet état est la meilleure façon de faire tourner un mât
     * sans savoir vers où.
     */
    private inline fun rotorGuard(where: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            val what = e::class.java.simpleName + (e.message?.let { ": " + it } ?: "")
            _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = tf("rotor_error", what)) }
            android.util.Log.w("SatMe/ROTOR", "echec " + where, e)
        }
    }

    fun setRotorEnabled(v: Boolean) {
        settings.rotorEnabled = v
        _ui.value = _ui.value.rot { copy(rotorEnabled = v) }
    }

    fun setRotorLink(v: String) {
        settings.rotorLink = v
        _ui.value = _ui.value.rot { copy(rotorLink = settings.rotorLink) }
    }

    fun setRotorUsbIndex(v: Int) {
        settings.rotorUsbIndex = v
        _ui.value = _ui.value.rot { copy(rotorUsbIndex = settings.rotorUsbIndex) }
    }

    fun setRotorBaud(v: Int) {
        settings.rotorBaud = v
        _ui.value = _ui.value.rot { copy(rotorBaud = settings.rotorBaud) }
    }

    fun setRotorHost(v: String) {
        settings.rotorHost = v
        _ui.value = _ui.value.rot { copy(rotorHost = settings.rotorHost) }
    }

    fun setRotorPort(v: Int) {
        settings.rotorPort = v
        _ui.value = _ui.value.rot { copy(rotorPort = settings.rotorPort) }
    }

    fun setRotorMaxAz(v: Int) {
        settings.rotorMaxAz = v
        _ui.value = _ui.value.rot { copy(rotorMaxAz = settings.rotorMaxAz) }
    }

    /**
     * Changer la butée jette le plan en cours.
     *
     * Le plan dit de combien de tours il faut décaler le passage ; il a été
     * calculé pour une butée donnée et ne veut plus rien dire pour une autre.
     * Le garder « en attendant le prochain passage » ferait pointer le mât à
     * cent quatre-vingts degrés de la vérité, ce qui est exactement l'erreur
     * que tout ce travail cherche à rendre impossible.
     */
    fun setRotorAzStop(v: String) {
        settings.rotorAzStop = v
        rotorPlan = null; rotorPlanKey = null
        _ui.value = _ui.value.rot { copy(rotorAzStop = settings.rotorAzStop, rotorCoverage = null) }
    }

    fun setRotorAzFromStop(v: Boolean) {
        settings.rotorAzFromStop = v
        _ui.value = _ui.value.rot { copy(rotorAzFromStop = settings.rotorAzFromStop) }
    }

    fun setRotorAzOnly(v: Boolean) {
        settings.rotorAzOnly = v
        _ui.value = _ui.value.rot { copy(rotorAzOnly = settings.rotorAzOnly,
            rotorAimEl = if (v) null else _ui.value.rotorAimEl) }
    }

    fun setRotorMaxError(v: Int) {
        settings.rotorMaxError = v
        rotorPlan = null; rotorPlanKey = null
        _ui.value = _ui.value.rot { copy(rotorMaxError = settings.rotorMaxError, rotorCoverage = null) }
    }

    fun setRotorMaxEl(v: Int) {
        settings.rotorMaxEl = v
        _ui.value = _ui.value.rot { copy(rotorMaxEl = settings.rotorMaxEl) }
    }

    fun setRotorDeadband(v: Int) {
        settings.rotorDeadband = v
        _ui.value = _ui.value.rot { copy(rotorDeadband = settings.rotorDeadband) }
    }

    fun setRotorFlip(v: Boolean) {
        settings.rotorFlip = v
        rotorWasFlipped = false
        _ui.value = _ui.value.rot { copy(rotorFlip = v) }
    }

    fun setRotorParkAz(v: Int) {
        settings.rotorParkAz = v
        _ui.value = _ui.value.rot { copy(rotorParkAz = settings.rotorParkAz) }
    }

    fun setRotorParkEl(v: Int) {
        settings.rotorParkEl = v
        _ui.value = _ui.value.rot { copy(rotorParkEl = settings.rotorParkEl) }
    }

    fun setRotorMinEl(v: Int) {
        settings.rotorMinEl = v
        _ui.value = _ui.value.rot { copy(rotorMinEl = settings.rotorMinEl) }
    }

    /** Combien de minutes avant le lever le mât part attendre le satellite. */
    fun setRotorPreAos(v: Int) {
        settings.rotorPreAos = v
        _ui.value = _ui.value.rot { copy(rotorPreAos = settings.rotorPreAos) }
    }

    fun setRotorSim(v: Boolean) {
        settings.rotorSim = v
        _ui.value = _ui.value.rot { copy(rotorSim = v) }
    }

    /** La liste des adaptateurs USB visibles, pour que le numéro veuille dire quelque chose. */
    fun refreshRotorDevices() {
        rotorGuard("rotor_devices") {
            _ui.value = _ui.value.rot { copy(
                rotorDevices = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()).listDevices()) }
        }
    }

    fun rotorRequestPermissions() {
        rotorGuard("rotor_perm") {
            fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication()).requestPermissions()
            refreshRotorDevices()
        }
    }

    fun connectRotor() {
        viewModelScope.launch { rotorGuard("rotor_connect") { connectRotorInner() } }
    }

    private suspend fun connectRotorInner() {
        disconnectRotor()
        val u = _ui.value
        if (u.rotorSim) {
            // Un mât qui n'existe pas : tout se voit à l'écran, rien ne bouge.
            val sim = fr.f4ioz.satcombo.rotor.Gs232Simulator(
                azMaxDeg = u.rotorMaxAz.toDouble(),
                elMaxDeg = if (u.rotorFlip) 180.0 else u.rotorMaxEl.toDouble())
            val d = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication())
            d.pacingMs = 0L
            d.attach(sim)
            rotorSimLink = sim
            rotorDriver = d
            rotorSerial = d
            rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(0.0, 0.0)
            rotorWasFlipped = false
            _ui.value = _ui.value.rot { copy(rotorConnected = true, rotorStatus = t("rotor_sim_on")) }
            startRotorLoop()
            return
        }
        if (u.rotorLink == "ROTCTLD") {
            val d = fr.f4ioz.satcombo.rotor.RotctldRotor()
            val ok = d.open(u.rotorHost, u.rotorPort)
            rotorDriver = if (ok) d else null
            _ui.value = _ui.value.rot { copy(rotorConnected = ok,
                rotorStatus = if (ok) tf("rotor_connected", u.rotorHost + ":" + u.rotorPort)
                else tf("rotor_error", u.rotorHost + ":" + u.rotorPort)) }
            if (ok) { rotorWasFlipped = false; startRotorLoop() }
            return
        }
        val d = fr.f4ioz.satcombo.rotor.Gs232Rotor(getApplication())
        val refs = d.availablePorts()
        if (refs.isEmpty()) {
            _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = t("rotor_none"),
                rotorDiag = listOf(t("cat_err_no_device"))) }
            return
        }
        // La même suite que pour le poste, et pour les mêmes trois fautes :
        // on attendait pas la permission, on ouvrait le port 0 du premier
        // appareil quel que soit le choix, et on ne levait ni DTR ni RTS. Un
        // émulateur GS-232 sur Arduino se comporte comme n'importe quel pont
        // série : il ne pardonne aucune des trois.
        val trace = ArrayList<String>()
        val ordre = if (u.rotorAutoPort)
            fr.f4ioz.satcombo.cat.CatScan.ordre(refs, u.rotorUsbIndex)
        else listOf(u.rotorUsbIndex.coerceIn(0, refs.size - 1))
        var gagnant = -1
        // Pourquoi le dernier port essayé n'a pas convenu. Sans cela l'écran
        // affichait « autorise l'accès USB » quel que soit l'échec — y compris
        // quand le port s'était ouvert et que le contrôleur avait répondu :
        // le message envoyait chercher une permission déjà accordée.
        var echec = ""
        for (i in ordre) {
            trace.add(tf("cat_diag_try", refs[i].label))
            if (!d.ensurePermission(i)) {
                trace.add(t("cat_diag_line_denied")); echec = t("rotor_fail_denied"); continue
            }
            if (!d.open(i, u.rotorBaud)) {
                trace.add(tf("cat_diag_line_open", d.lastError))
                echec = tf("rotor_fail_open", d.lastError); continue
            }
            // Le port s'ouvre toujours. C'est la réponse à `C2` qui départage
            // le contrôleur du rotor d'une clé SDR ou d'un poste — et il faut
            // la demander plusieurs fois : lever DTR redémarre une carte
            // Arduino, et la première question part pendant l'amorçage.
            trace.add(t("rotor_diag_settle"))
            val pos = runCatching { d.probePosition() }.getOrNull()
            if (pos == null) {
                val muet = d.lastReply.isBlank()
                trace.add(if (muet) tf("rotor_diag_mute_tries", d.lastTries.toString())
                          else tf("rotor_diag_garbled", d.lastReply))
                echec = if (muet) t("rotor_fail_mute") else tf("rotor_fail_garbled", d.lastReply)
                d.close(); continue
            }
            trace.add(tf("rotor_diag_ok",
                String.format(java.util.Locale.US, "%.0f", pos.azDeg),
                String.format(java.util.Locale.US, "%.0f", pos.elDeg)))
            gagnant = i; break
        }
        val ok = gagnant >= 0
        if (ok && gagnant != u.rotorUsbIndex) {
            // Le port qui a répondu devient celui que l'on retient.
            settings.rotorUsbIndex = gagnant
        }
        rotorDriver = if (ok) d else null
        rotorSerial = if (ok) d else null
        _ui.value = _ui.value.rot { copy(rotorConnected = ok,
            rotorDevices = refs.map { it.label }, rotorDiag = trace,
            rotorUsbIndex = if (ok) gagnant else u.rotorUsbIndex,
            rotorLastSent = d.lastSent, rotorLastReply = d.lastReply,
            rotorStatus = if (ok) tf("rotor_connected", refs[gagnant].label)
            else if (echec.isNotBlank()) echec
            else t("open_failed_usb")) }
        if (ok) { rotorWasFlipped = false; startRotorLoop() }
    }

    fun setRotorAutoPort(v: Boolean) { _ui.value = _ui.value.rot { copy(rotorAutoPort = v) } }

    fun setRotorManualAz(v: Int) {
        _ui.value = _ui.value.rot { copy(rotorManualAz = v.coerceIn(0, _ui.value.rotorMaxAz)) }
    }

    fun setRotorManualEl(v: Int) {
        _ui.value = _ui.value.rot { copy(rotorManualEl = v.coerceIn(0, if (_ui.value.rotorFlip) 180 else _ui.value.rotorMaxEl)) }
    }

    /**
     * Envoie la consigne saisie à la main, sans satellite ni poursuite.
     *
     * « Est-il possible de forcer le rotor à tourner vers des angles… pour
     * tester sans satellite. » Oui, et c'est même la seule façon honnête
     * d'essayer une chaîne complète : un passage arrive quand il veut, et il
     * n'attend pas qu'on ait fini de débrouiller un câble.
     *
     * La consigne coupe la poursuite avant de partir. Deux volontés qui
     * commandent le même mât à une seconde d'intervalle ne se partagent pas le
     * travail : elles se contredisent, et le mât vibre entre les deux.
     *
     * L'angle est saisi en azimut **vrai**, comme tout ce qui est affiché ; la
     * conversion vers l'origine du contrôleur se fait au dernier moment, sur la
     * trame qui part — exactement comme pour le garage et pour la poursuite.
     */
    fun rotorGotoManual() {
        viewModelScope.launch {
            rotorGuard("rotor_goto") {
                setRotorEnabled(false)
                val u = _ui.value
                val limits = rotorLimits(u)
                val aim = fr.f4ioz.satcombo.rotor.RotorMath.manual(
                    u.rotorManualAz.toDouble(), u.rotorManualEl.toDouble(), limits)
                if (aim == null) {
                    _ui.value = _ui.value.rot { copy(rotorOutOfRange = true,
                        rotorStatus = t("rotor_manual_refused")) }
                    return@rotorGuard
                }
                launch {
                    val cmdAz = fr.f4ioz.satcombo.rotor.RotorMath.commandAz(
                        aim.azDeg, limits, u.rotorAzFromStop)
                    val parti = rotorDriver?.moveTo(cmdAz, aim.elDeg) ?: false
                    rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
                    rotorCmd = rotorAt
                    rotorWasFlipped = false
                    _ui.value = _ui.value.rot { copy(
                        rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg,
                        rotorOutOfRange = false,
                        rotorLastSent = rotorSerial?.lastSent ?: "",
                        rotorStatus = if (parti)
                            tf("rotor_manual_sent",
                                String.format(java.util.Locale.US, "%.0f", cmdAz),
                                String.format(java.util.Locale.US, "%.0f", aim.elDeg))
                        else t("rotor_send_failed")) }
                }
            }
        }
    }

    /**
     * Un pas de plus ou de moins, à partir de là où l'on croit être.
     *
     * Le point de départ est la position **lue** quand le contrôleur en donne
     * une, et la consigne précédente sinon. C'est ce qui rend le bouton utile
     * pour dégrossir un réglage de fin de course : on avance de dix degrés, on
     * regarde l'antenne, on recommence.
     */
    fun rotorJog(dAz: Int, dEl: Int) {
        val u = _ui.value
        val baseAz = u.rotorActualAz ?: u.rotorManualAz.toDouble()
        val baseEl = u.rotorActualEl ?: u.rotorManualEl.toDouble()
        setRotorManualAz((baseAz.toInt() + dAz))
        setRotorManualEl((baseEl.toInt() + dEl))
        rotorGotoManual()
    }

    /**
     * Une interrogation isolée, hors de la boucle.
     *
     * Utile quand rien n'est encore branché comme il faut : le bouton demande
     * la position, et l'écran montre la réponse telle qu'elle arrive — ou son
     * absence, qui est aussi une réponse.
     */
    fun rotorReadNow() {
        viewModelScope.launch {
            rotorGuard("rotor_read") {
                launch {
                    val d = rotorDriver
                    val pos = runCatching { d?.readPosition() }.getOrNull()
                    val ser = rotorSerial
                    _ui.value = _ui.value.rot { copy(
                        rotorLastSent = ser?.lastSent ?: "",
                        rotorLastReply = ser?.lastReply ?: "",
                        rotorStatus = if (pos == null) t("rotor_diag_mute")
                        else tf("rotor_diag_ok",
                            String.format(java.util.Locale.US, "%.0f", pos.azDeg),
                            String.format(java.util.Locale.US, "%.0f", pos.elDeg))) }
                }
            }
        }
    }

    fun disconnectRotor() {
        rotorLoopJob?.cancel(); rotorLoopJob = null
        runCatching { rotorDriver?.close() }
        rotorDriver = null
        rotorSimLink = null
        rotorSerial = null
        // Un mât débranché n'a plus de position à tenir : on oublie la dernière
        // lue, sans quoi elle survivrait quatre secondes à la déconnexion.
        rotorLu = null; rotorLuMs = 0L
        _ui.value = _ui.value.rot { copy(rotorConnected = false, rotorStatus = t("rotor_offline"),
            rotorTargetAz = null, rotorTargetEl = null,
            rotorActualAz = null, rotorActualEl = null, rotorOutOfRange = false,
            rotorAimAz = null, rotorAimEl = null) }
    }

    /**
     * L'arrêt immédiat.
     *
     * Il coupe aussi la poursuite, et ce n'est pas un détail : un arrêt suivi
     * d'une consigne une seconde plus tard n'est pas un arrêt, c'est une pause.
     * Le bouton doit faire ce que son nom dit.
     */
    fun rotorStopNow() {
        viewModelScope.launch {
            rotorGuard("rotor_stop") {
                setRotorEnabled(false)
                launch { rotorDriver?.stop() }
            }
        }
    }

    fun rotorParkNow() {
        viewModelScope.launch {
            rotorGuard("rotor_park") {
                setRotorEnabled(false)
                val u = _ui.value
                val aim = fr.f4ioz.satcombo.rotor.RotorMath.park(
                    u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), rotorLimits(u))
                if (aim == null) {
                    _ui.value = _ui.value.rot { copy(rotorOutOfRange = true) }
                } else {
                    launch {
                        rotorDriver?.moveTo(
                            fr.f4ioz.satcombo.rotor.RotorMath.commandAz(
                                aim.azDeg, rotorLimits(u), u.rotorAzFromStop),
                            aim.elDeg)
                        rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
                        rotorCmd = rotorAt
                        rotorWasFlipped = false
                        _ui.value = _ui.value.rot { copy(rotorTargetAz = aim.azDeg,
                            rotorTargetEl = aim.elDeg, rotorOutOfRange = false) }
                    }
                }
            }
        }
    }

    private fun rotorLimits(u: UiState) = fr.f4ioz.satcombo.rotor.RotorMath.Limits(
        azMaxDeg = u.rotorMaxAz.toDouble(),
        elMaxDeg = if (u.rotorFlip) 180.0 else u.rotorMaxEl.toDouble(),
        deadbandDeg = u.rotorDeadband.toDouble(),
        azStopDeg = if (u.rotorAzStop == "SOUTH") 180.0 else 0.0)

    /**
     * Le plan du passage : choisi une fois, tenu jusqu'au bout.
     *
     * Il ne se recalcule qu'au changement de passage, de butée, de course ou
     * d'écart toléré. Le recalculer à chaque seconde donnerait un mât qui
     * change d'avis en plein passage, et deux minutes de rotation pour arriver
     * à un endroit d'où le satellite sera déjà parti.
     */
    private fun ensureRotorPlan(
        u: UiState,
        limits: fr.f4ioz.satcombo.rotor.RotorMath.Limits
    ): fr.f4ioz.satcombo.rotor.RotorMath.Plan? {
        val track = u.passTrack
        if (track.isEmpty()) {
            rotorPlan = null; rotorPlanKey = null
            return null
        }
        val key = "${track.size}|${track.first()}|${track.last()}|" +
            "${limits.azStopDeg}|${limits.azMaxDeg}|${u.rotorMaxError}"
        if (key == rotorPlanKey) return rotorPlan
        val p = fr.f4ioz.satcombo.rotor.RotorMath.plan(
            track, limits, toleranceDeg = u.rotorMaxError.toDouble())
        rotorPlanKey = key
        rotorPlan = p
        if (p != null) {
            rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(
                fr.f4ioz.satcombo.rotor.RotorMath.clampAz(p.startAzDeg, limits), rotorCmd.elDeg)
            rotorWasFlipped = false
        }
        _ui.value = _ui.value.rot { copy(rotorCoverage = p?.coverage) }
        return p
    }

    /**
     * La boucle de poursuite du mât : une consigne par seconde, pas plus.
     *
     * Un rotor n'a rien à gagner à être commandé plus vite que cela — il tourne
     * à six degrés par seconde, et chaque départ use un relais. La cadence est
     * celle de la position calculée, et c'est bien ainsi.
     */
    private fun startRotorLoop() {
        rotorLoopJob?.cancel()
        rotorLoopJob = viewModelScope.launch {
            while (_ui.value.rotorConnected) {
                runCatching { rotorTick() }
                delay(1000)
            }
        }
    }

    private suspend fun rotorTick() {
        val d = rotorDriver ?: return
        // Le mât simulé n'avance que si on le fait avancer.
        rotorSimLink?.advance(1000)
        val u = _ui.value
        val limits = rotorLimits(u)
        // Une question, et une seconde si la première est restée sans réponse.
        // Un émulateur occupé à faire tourner deux moteurs saute une réponse de
        // temps en temps ; redemander trois cents millisecondes plus tard coûte
        // deux octets sur le fil et récupère la quasi-totalité de ces trous.
        var brut = runCatching { d.readPosition() }.getOrNull()
        if (brut == null) {
            delay(300)
            brut = runCatching { d.readPosition() }.getOrNull()
        }
        // Ce que le contrôleur rend est dans son origine à lui ; tout ce qui
        // suit est en azimut vrai, et le restera jusqu'à la trame suivante.
        val frais = brut?.let {
            fr.f4ioz.satcombo.rotor.RotorPos(
                fr.f4ioz.satcombo.rotor.RotorMath.trueAz(it.azDeg, limits, u.rotorAzFromStop),
                it.elDeg)
        }
        val maintenant = System.currentTimeMillis()
        if (frais != null) { rotorLu = frais; rotorLuMs = maintenant }
        // Et si le contrôleur se tait quand même, on garde quelques secondes ce
        // qu'il a dit en dernier plutôt que de faire clignoter la boussole entre
        // le mât et le satellite. Voir RotorTenue : la tenue sert l'écran, elle
        // ne sert jamais la consigne.
        val lu = fr.f4ioz.satcombo.rotor.RotorTenue.montrer(
            frais, rotorLu, rotorLuMs, maintenant)
        if (frais != null) rotorAt = frais
        // Le mât branché tient l'antenne : c'est lui, et non le téléphone, que
        // la boussole doit montrer. Rien d'autre ne change — mêmes couleurs,
        // même trace, même écart — seule la source de la visée est remplacée.
        val visee = lu?.let { fr.f4ioz.satcombo.rotor.RotorMath.antennaAim(it) }
        _ui.value = u.rot { copy(rotorActualAz = lu?.azDeg, rotorActualEl = lu?.elDeg,
            rotorAimAz = visee?.azDeg,
            rotorAimEl = if (u.rotorAzOnly) null else visee?.elDeg,
            rotorLastSent = rotorSerial?.lastSent ?: u.rotorLastSent,
            rotorLastReply = rotorSerial?.lastReply ?: u.rotorLastReply) }
        if (!u.rotorEnabled) return
        val pos = u.livePosition
        if (pos == null) {
            _ui.value = _ui.value.rot { copy(rotorTargetAz = null, rotorTargetEl = null,
                rotorStatus = t("rotor_no_target")) }
            return
        }
        val plan = ensureRotorPlan(u, limits)
        // Sous l'élévation minimale, le satellite est derrière la colline : le
        // mât rentre au garage plutôt que de suivre un point qu'on n'entend pas.
        val garage = pos.elevationDeg < u.rotorMinEl.toDouble()
        // …sauf dans les dernières minutes avant l'acquisition : il vaut mieux
        // aller attendre le satellite là où il se lèvera que rentrer au garage
        // pour en repartir aussitôt. Le point d'attente est le départ du tour de
        // mât déjà retenu pour ce passage, butée comprise — le mât n'aura donc
        // plus rien à dérouler quand le satellite se montrera.
        val avance = garage && plan != null &&
            fr.f4ioz.satcombo.rotor.RotorMath.prePositionDue(
                System.currentTimeMillis(), trackedPassAos.takeIf { it > 0L }, u.rotorPreAos)
        if (avance != u.rotorPrePositioning) {
            _ui.value = _ui.value.rot { copy(rotorPrePositioning = avance) }
        }
        val aim = if (avance)
            fr.f4ioz.satcombo.rotor.RotorMath.park(
                fr.f4ioz.satcombo.rotor.RotorMath.clampAz(plan!!.startAzDeg, limits),
                maxOf(0.0, u.rotorMinEl.toDouble()), limits)
        else if (garage)
            fr.f4ioz.satcombo.rotor.RotorMath.park(
                u.rotorParkAz.toDouble(), u.rotorParkEl.toDouble(), limits)
        else fr.f4ioz.satcombo.rotor.RotorMath.follow(
            pos.azimuthDeg, pos.elevationDeg, rotorCmd, limits, rotorWasFlipped)
        if (aim == null) {
            // Un garage impossible reste impossible : on ne borne pas une
            // position que l'opérateur a choisie lui-même, on le lui dit.
            _ui.value = _ui.value.rot { copy(rotorOutOfRange = true,
                rotorTargetAz = null, rotorTargetEl = null) }
            return
        }
        // Derrière la butée, le mât ne déroule pas : il attend au plus près et
        // l'écart est annoncé en degrés. Un mât qui pointe ailleurs sans le dire
        // est pire qu'un mât qui n'a pas bougé.
        _ui.value = _ui.value.rot { copy(rotorOutOfRange = false, rotorErrorDeg = aim.errorDeg,
            rotorTargetAz = aim.azDeg, rotorTargetEl = aim.elDeg, rotorFlipped = aim.flipped) }
        if (!garage) rotorCmd = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
        if (!fr.f4ioz.satcombo.rotor.RotorMath.needsMove(aim, rotorAt, u.rotorDeadband.toDouble()))
            return
        rotorWasFlipped = aim.flipped
        val cmd = fr.f4ioz.satcombo.rotor.RotorMath.commandAz(aim.azDeg, limits, u.rotorAzFromStop)
        // « lu » peut être une position tenue, vieille de trois secondes ; ce
        // n'est pas une lecture. Faute d'avoir vraiment lu le mât, on suppose
        // qu'il est allé où on le lui a dit — comme avant la tenue.
        if (d.moveTo(cmd, aim.elDeg) && frais == null)
            rotorAt = fr.f4ioz.satcombo.rotor.RotorPos(aim.azDeg, aim.elDeg)
    }

    // ===================== rotor d'azimut et d'élévation =====================

    private var trackingFor: Int? = null
    private fun trackLive(sat: TleEntry, obs: Observer) {
        trackingFor = sat.catalogNumber
        viewModelScope.launch {
            while (trackingFor == sat.catalogNumber) {
                val pos = withContext(Dispatchers.Default) {
                    runCatching { predictor.positionAt(sat, obs, System.currentTimeMillis()) }.getOrNull()
                }
                if (_ui.value.selected?.catalogNumber == sat.catalogNumber) {
                    val trail = _ui.value.trail.toMutableList()
                    if (pos != null && pos.elevationDeg >= 0) {
                        trail.add(pos.azimuthDeg to pos.elevationDeg)
                        if (trail.size > 60) trail.removeAt(0)
                    }
                    _ui.value = _ui.value.copy(livePosition = pos, trail = trail)
                    refreshPassTrack(sat, obs) // switch arc when the shown pass ends
                }
                delay(1000)
            }
        }
    }
}
