# Assistant vocal, Home Assistant et commandes à distance

*[English version](assistant-domotique.md)*

SatMe-PC répond à quelques questions et fait quelques gestes **sans
émission** par trois chemins, chacun un module facultatif, désactivé
d'origine (*Réglages › Modules*), réglé dans *Réglages › Assistant et
domotique* :

| Chemin | Module | Ce qui sort du PC |
|---|---|---|
| **Assistant vocal** : le micro de ce PC, « SatMe, prochain passage de l'ISS » | Assistant vocal | rien : reconnaissance et voix sur ce PC |
| **Home Assistant** par MQTT, puis Alexa ou Google Home par Home Assistant | Home Assistant | l'état de la station vers **votre** broker MQTT |
| **Commandes à distance** par **votre site** (une skill Alexa à vous) | Publication web | l'état vers votre site ; les commandes sont relevées par SatMe-PC |

> ### Jamais d'émission
> Rien de ce qui émet ne peut être demandé par la voix, Home Assistant,
> Alexa ou un site : ni PTT, ni CAT en émission, ni émission DATV, SSTV,
> FT8/FT4 ou par l'ADALM-PLUTO, ni mouvement du rotor (seul l'auto-pilote,
> réglé à l'écran, le bouge comme d'habitude). Une telle demande reçoit la
> réponse : « Pour la sécurité, l'émission et les mouvements d'antenne se
> commandent seulement à l'écran de la station. » La liste des actions est
> fermée (huit, ci-dessous) et vérifiée par SatMe-PC lui-même, quoi que
> l'autre côté envoie.

## Ce qu'on peut demander

Les questions (réponse courte, dite et affichée) :

| Intention | Exemples |
|---|---|
| `prochain_passage` | « prochain passage de l'ISS », « quand passe SO-50 », « AO quatre-vingt-onze », « prochain passage » (vos favoris ★) |
| `visibles` | « quels satellites sont visibles », « qu'est-ce qui est au-dessus en ce moment » |
| `prochain_aos` | « prochain AOS », « c'est quand le prochain satellite » |
| `autopilote_etat` | « où en est l'auto-pilote » (prochaine campagne, passage en cours) |
| `derniere_sstv` | « dernière image SSTV » (satellite, heure, mode) |
| `datv_etat` | « état de la DATV » (verrouillage, fréquence, MER, station lue) |
| `balise` | « la balise QO-100 » (niveau au-dessus du bruit, écart ; la balise large bande par le récepteur DATV) |
| `rotor` | « où pointe l'antenne » (azimut, élévation) |
| `dernier_qso` | « dernier QSO » |
| `aide` | « aide » |

Les actions (toutes de réception ou de son) :

| Intention | Exemples |
|---|---|
| `autopilote_on` / `autopilote_off` | « active l'auto-pilote », « coupe l'auto-pilote » |
| `sstv_on` / `sstv_off` | « démarre la réception SSTV », « arrête la SSTV » |
| `datv_on` / `datv_off` | « démarre la DATV » (les derniers réglages de l'onglet DATV), « arrête la DATV » |
| `son_muet` / `son_remet` | « coupe le son », « remets le son » (le son des récepteurs sur ce PC) |

Les phrases se comprennent en français et en anglais, sans tenir compte
des accents ni de la ponctuation, avec les nombres en lettres (« SO
cinquante », « AO quatre-vingt-onze ») et une lettre de travers. Une
question (« est-ce que la DATV est coupée ? ») n'est jamais prise pour un
ordre. Un module coupé (SSTV, DATV) le dit au lieu d'agir.

## Assistant vocal

1. *Réglages › Modules* : cocher **Assistant vocal**.
2. *Réglages › Assistant et domotique › Assistant vocal* : choisir le
   modèle (**tiny** 75 Mo, **base** 145 Mo — d'origine —, **small** 485 Mo)
   puis **Installer**. SatMe-PC utilise l'environnement Python de
   *Phonie → texte* (`stt/` dans le dossier des données) : il le crée s'il
   manque (Python 3.9 ou plus récent requis), y ajoute faster-whisper
   s'il n'y est pas (environ 120 Mo) et télécharge le modèle. C'est le seul
   moment où l'assistant va sur Internet.
3. Cocher **Écouter le micro** (ou se servir seulement du bouton 🎙).

**Le mot de réveil.** On dit « **SatMe** » (« Satmi », « Sat me » vont
aussi), puis la question dans la même phrase, ou juste après (8 s).
SatMe-PC découpe la voix au niveau (pas de son continu envoyé au moteur),
fait écrire chaque morceau par Whisper sur ce PC et ne garde que ceux qui
commencent par le mot de réveil. Les modèles tout faits d'openWakeWord
(« hey jarvis », « alexa »…) sont sous licence CC BY-NC-SA (pas
d'utilisation commerciale) : ils ne sont pas utilisés.

**Le bouton 🎙** (en bas à droite de la page, et sur la carte) écoute la
phrase suivante sans le mot de réveil, même quand « Écouter le micro » est
décoché : le micro s'ouvre alors 8 s seulement. Le champ **Demander** de la
carte essaie une phrase au clavier.

**La réponse** est dite par la voix de *Phonie → texte › Traduction
parlée* (Piper, sinon eSpeak NG, sinon speech-dispatcher) sur ce PC, le son
des récepteurs baissé pendant ce temps, et affichée dans une bulle en bas à
droite. Sans voix sur le PC, la page ouverte sur ce PC la dit avec la voix
du navigateur. Le micro n'écoute pas pendant que SatMe parle.

**Vie privée.** Le son du micro ne quitte pas le PC et n'est pas
enregistré ; seul le texte des 30 dernières commandes est gardé en mémoire
(jusqu'au redémarrage). La démonstration n'ouvre jamais le micro.

**Réglages.** Le micro (celui du système d'origine ; ne pas prendre la
carte son des décodeurs), la langue (celle de SatMe, français ou anglais),
la sensibilité (1 : seulement une voix forte ; 10 : un murmure) et
« Dire la réponse à voix haute ».

## Home Assistant

SatMe-PC se connecte à **votre broker MQTT** (souvent l'extension
*Mosquitto broker* de Home Assistant) et y publie ses entités avec la
**découverte MQTT** : elles apparaissent d'elles-mêmes dans Home Assistant,
sous un appareil « SatMe ». Rien n'est ouvert sur le PC.

1. *Réglages › Modules* : cocher **Home Assistant**.
2. *Réglages › Assistant et domotique › Home Assistant* : l'adresse du
   broker, le port (1883, 8883 en TLS), l'utilisateur et le mot de passe
   MQTT (gardé sur ce PC, jamais réaffiché ni écrit dans un journal),
   **Enregistrer**, puis cocher **Publier vers Home Assistant**.
3. Pour les interrupteurs, cocher **Actions** (décoché d'origine : l'état
   seulement).

| Entité | Type | Contenu |
|---|---|---|
| Prochain passage | capteur | la phrase (« Prochain passage de SO-50 à 13 h 25… ») |
| Prochain AOS | capteur `timestamp` | l'heure de l'AOS suivant |
| Satellites visibles | capteur | leur nombre (la liste dans `visibles_liste` de l'état) |
| Auto-pilote SSTV | capteur | son état |
| Dernière image SSTV, son heure | capteurs | satellite, heure, mode |
| DATV verrouillée | capteur binaire | ON / OFF |
| DATV MER, DATV station | capteurs | dB, indicatif lu ou service |
| Balise QO-100 | capteur | dB au-dessus du bruit |
| Rotor azimut, élévation | capteurs | degrés |
| Dernier QSO | capteur | indicatif, satellite, heure |
| Réponse de SatMe | capteur | la réponse à la dernière commande |
| Auto-pilote SSTV, Réception SSTV, Réception DATV, Son coupé | interrupteurs (avec **Actions**) | les actions sûres |
| Commande SatMe | texte (avec **Actions**) | une phrase, comprise comme à la voix |

Les sujets : `satme/etat` (tout l'état en JSON, retenu, toutes les 30 s et à
chaque commande), `satme/disponible` (`online` / `offline`, testament MQTT),
`satme/commande/<interrupteur>` (`ON` / `OFF`), `satme/commande/texte` (une
phrase), `satme/commande/intention` (un identifiant ci-dessus). La racine
`satme` et le préfixe `homeassistant` se changent sur la carte. Décocher
**Actions** retire les interrupteurs de Home Assistant.

### Alexa et Google Home par Home Assistant

Home Assistant présente ses entités aux assistants du commerce ; SatMe n'a
rien de plus à faire :

- **Home Assistant Cloud (Nabu Casa)** : *Paramètres › Assistants vocaux ›
  Alexa* (ou *Google Assistant*), activer, puis **exposer** les
  interrupteurs SatMe voulus. « Alexa, allume Réception SSTV », « Ok Google,
  éteins Auto-pilote SSTV ».
- **Sans abonnement** : l'intégration *Alexa Smart Home* (une skill à créer
  soi-même sur la console développeur d'Amazon et une fonction AWS Lambda)
  ou *Google Assistant* manuelle, décrites dans la documentation de Home
  Assistant ; on y expose les mêmes interrupteurs.
- **Pour les questions** : une *routine* Alexa ou un *script* Home Assistant
  qui lit un capteur (« Prochain passage ») et le fait dire par l'enceinte,
  ou *Assist* de Home Assistant avec le champ « Commande SatMe ».

Seules les quatre actions sûres sont des interrupteurs : il n'existe aucune
entité qui émette.

## Commandes à distance par votre site (skill Alexa)

Pour une skill Alexa **à vous**, hébergée sur votre site (pas de Home
Assistant) : votre site reçoit les requêtes d'Amazon, et SatMe-PC lui
envoie l'état et relève les commandes — le site ne se connecte jamais à la
station.

1. Régler et **tester** *Réglages › Services en ligne › Envoyer vers un
   site web* (module *Publication web*), avec une adresse web et un jeton.
2. *Réglages › Assistant et domotique › Commandes à distance* : cocher
   **Questions** (les réponses partent toutes les minutes) et, si voulu,
   **Actions** (les huit actions sûres).
3. Côté site : recevoir les types `commandes-etat`, `commandes-attente` et
   `commandes-resultat` (décrits dans
   [la documentation de l'API](publication-web.fr.md#commandes-à-distance-facultatif)),
   et un point d'entrée HTTPS pour la skill qui **vérifie la signature
   d'Amazon** (URL `SignatureCertChainUrl`, chaîne de certificats jusqu'à
   une racine de confiance, `echo-api.amazon.com` dans le certificat,
   `Signature-256`, horodatage à 150 s près, identifiant de la skill), répond
   aux questions avec l'état reçu et met les actions en attente pour
   SatMe-PC.
