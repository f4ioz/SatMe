# SatMe

Application Android de poursuite de satellites radioamateurs : prévoir les
passages, viser l'antenne, corriger le Doppler, et noter le contact sans
lâcher le manche.

Écrite en Kotlin et Jetpack Compose par **F4IOZ**.

[Sur Google Play](https://play.google.com/store/apps/details?id=fr.f4ioz.satcombo)

---

## Ce que fait SatMe

**Passages** — prévisions jusqu'à quinze jours, calculées sur le téléphone et
disponibles hors ligne. AOS, LOS, élévation maximale, tracé polaire. Statut des
satellites d'après l'AMSAT et SatNOGS, alertes avant chaque passage suivi.

**Pointage** — boussole du téléphone, ou module Bluetooth WitMotion fixé sur
la flèche de l'antenne, avec calibrage guidé.

**Fréquences** — correction Doppler en réception et en émission, transpondeurs
linéaires et FM. Pilotage CAT de l'Icom IC-9700, d'une paire de Yaesu FT-817,
ou d'un FT-817 associé à une clé SDR.

**Carnet** — clavier d'indicatifs conçu pour une seule main, mémoire des
correspondants, dépôt et rapatriement Wavelog ou Cloudlog, carrés QRZ,
confirmations LoTW, export ADIF et fiches PDF.

**Partage** — une page web que le public ouvre sur son propre téléphone pour
suivre le passage en direct, sans rien installer. Écoute déportée entre deux
appareils SatMe sur le même réseau.

**Poste de commande** — une page servie par le téléphone, à ouvrir depuis un
PC du réseau : saisie au vrai clavier, cadran polaire, changement de
satellite, enregistrement. Son protocole est documenté dans
[`docs/API-v1.md`](docs/API-v1.md) et se prête à d'autres clients.

---

## Une note sur la langue

**Le code et ses commentaires sont en français.** Les noms suivent les
conventions d'Android et de Compose quand elles s'imposent — `onClick`,
`setState` — mais le reste est français, et les commentaires le sont à près de
quatre cinquièmes.

Ce n'est pas un oubli. Ces commentaires expliquent *pourquoi* un choix a été
fait plutôt que ce que fait le code, et cette explication perdrait à être
écrite dans une langue que l'auteur manie moins bien. Le public premier du
projet est par ailleurs francophone.

Une contribution en anglais reste la bienvenue : les deux langues peuvent
cohabiter, et mieux vaut un commentaire anglais exact qu'un commentaire
français approximatif.

## Compiler

```
JDK 21, Gradle 8.14.3, SDK Android 36

./gradlew testDebugUnitTest assembleDebug
```

La signature de publication attend un fichier `keystore.properties` à la
racine, jamais versionné :

```
storeFile=/chemin/vers/votre.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Les essais unitaires — environ mille cent cinquante — tournent sans appareil.
Ils sont la garantie que la lecture d'un défaut n'a pas cassé autre chose, et
un changement qui les fait tomber demande d'abord une explication.

---

## Licence

**GNU General Public License version 2**, et ce n'est pas un choix de style :
la bibliothèque de prédiction orbitale, héritée de PREDICT de KD2BD, est sous
GPL et impose sa licence à l'ensemble.

Le détail des composants tiers et de leurs licences se trouve dans
[`THIRD-PARTY.md`](THIRD-PARTY.md).

---

## Contribuer

Les rapports de défaut sont plus utiles avec ce qui suit : version de
l'application, modèle de téléphone, et — si c'est un problème de matériel — le
poste, le module ou la carte son concernés.

Deux choses à savoir avant de proposer du code.

**Les essais passent avant la correction.** Un défaut qui se reproduit dans un
essai ne revient pas ; un défaut corrigé sans essai revient toujours.

**Les commentaires expliquent pourquoi, pas quoi.** Le code dit déjà ce qu'il
fait. Ce qui se perd, c'est la raison d'un choix — et c'est ce qui coûte cher
à retrouver six mois plus tard.

---

## Remerciements

Dr T. S. Kelso pour les modèles SGP4/SDP4, John Magliacane KD2BD pour PREDICT,
Neoklis Kyriazis 5B4AZ pour sa traduction, David Johnson G4DPZ pour le portage
Java.

L'AMSAT et SatNOGS pour les statuts, Celestrak pour les éléments orbitaux.

Le radio-club **F6KMX** pour les essais sur le terrain — et pour avoir signalé
les défauts que l'atelier ne voit jamais.

73 de F4IOZ
