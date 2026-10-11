# SatMe sur PC — la station dans un navigateur

*Première version, octobre 2026 (branche `moteur-commun`). L'application Android reste la référence ; le PC s'y ajoute.*

SatMe tourne sur un PC (Linux, Windows, Raspberry Pi) et se pilote depuis un navigateur : les prochains passages, le satellite suivi dans le ciel, les fréquences corrigées du Doppler, le poste (CAT) et le rotor. Le cœur est **le même code que sur le téléphone** : la boucle Doppler (`StationCat`) et le suivi du rotor (`StationRotor`) viennent du module commun `:moteur`.

## Lancer

Il faut Java 17 ou plus récent.

```
./gradlew :pc:installDist
pc/build/install/satme/bin/satme          # Linux, macOS
pc\build\install\satme\bin\satme.bat      # Windows
```

puis ouvrir **http://localhost:8090/**.

| Option | Effet |
|---|---|
| `--port 8090` | port de la page |
| `--reseau` | page ouverte aux autres machines du réseau (une station au pied du mât pilotée depuis la maison) ; sans elle, ce PC seulement |
| `--dossier DIR` | où ranger réglages et éléments (`~/.satme`, `%APPDATA%\SatMe` sous Windows) |

## Ce que fait cette version

- **Passages** des 24 heures à venir, pour les satellites du bulletin AMSAT ; un clic suit le satellite.
- **Satellite suivi** : azimut, élévation, distance, compte à rebours AOS/LOS, le passage tracé dans le ciel, la position du mât.
- **Fréquences** : émetteurs actifs de SatNOGS, RX et TX corrigées du Doppler ; dans un transpondeur linéaire, ±100 Hz à ±5 kHz.
- **Poste (CAT)** : Icom IC-9700, IC-9100, IC-910H (CI-V) et Kenwood TS-2000 (mode SAT), avec la boucle Doppler du téléphone ; un IC-9700 et un TS-2000 **simulés** pour essayer sans rien brancher.
- **Rotor** : GS-232 sur port série, rotctld par le réseau, ou simulé ; suivi, parking, attente au point de lever, aller à un azimut ; réglages (butée, azimut max, retournement, parking…).
- **Réglages du téléphone** : sa sauvegarde (Réglages → Sauvegarde, fichier `.json`) se reprend sur la page — indicatif, locator, rotor, poste. Les numéros de ports USB du téléphone ne sont **pas** repris, et le rotor arrive **sans suivre** : il ne bouge qu'une fois « Suivre le satellite » coché.

## Sécurité du matériel

- **Rien ne s'ouvre tout seul.** Un port série ne s'ouvre que lorsqu'on choisit ce port et qu'on clique « Connecter ». Aucune recherche automatique.
- **RTS et DTR bas** par défaut. Le CAT Icom les lève (ses ponts USB l'exigent) : **choisir le port du bon poste**. Le TS-2000 les garde bas, et un port qui renvoie nos propres mots (le port PTT d'un RigExpert) est refermé aussitôt.
- Sous Linux, le système lève RTS/DTR un instant à chaque ouverture de port, avant que SatMe puisse les baisser : c'est une raison de plus pour n'ouvrir que le port voulu.
- **Aucun ordre d'émission** n'est jamais envoyé (`TX`, `KY` refusés par le pilote TS-2000).
- Les éléments viennent d'**AMSAT** seulement ; CelesTrak n'est jamais interrogé depuis le PC.

## Avant le premier essai

- **Linux** : être dans le groupe `dialout` (`sudo usermod -aG dialout $USER`, puis se reconnecter).
- **Windows** : installer le pilote de l'adaptateur (CP210x, FTDI, CH340) ; le poste apparaît comme `COM3`, `COM4`…
- Commencer par le **poste simulé** et le **rotor simulé**, puis brancher le vrai matériel un élément à la fois.

## Pas encore sur PC

FT-817/818 et IC-705 en paire, TH-D72, APRS et KISS, enregistrement du son, SSTV et METEOR en direct, clé SDR, journal des passages, carnet de contacts. Le téléphone reste nécessaire pour cela ; les pages du PC qui accompagnent le téléphone (pupitre, journal, planche) restent servies par le téléphone.
