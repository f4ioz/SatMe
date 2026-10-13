# Installer SatMe-PC sous Windows

*[English version](installer-satme-pc-windows.md) · Sous Linux : [Installer SatMe-PC](installer-satme-pc.fr.md) · Ce que fait SatMe-PC : [SatMe-PC](station-pc.fr.md)*

Tout en un : Java, SatMe-PC, ffmpeg (vidéos, GIF, DATV) et rtl_tcp (clé RTL-SDR) dans un seul installateur. Windows 10 ou 11, 64 bits. Rien d'autre à installer.

## 1. Télécharger et installer

Sur la [page des téléchargements](https://github.com/f4ioz/SatMe/releases/latest) :

- **`satme-pc-windows.msi`** : l'installateur. Double-cliquer dessus. Il s'installe pour votre compte seulement, sans droits d'administrateur, avec un raccourci dans le menu Démarrer (dossier *SatMe*) et, si vous le voulez, sur le bureau.
- ou **`satme-pc-windows.zip`** : la même chose sans installation. Le décompresser où vous voulez, puis lancer `SatMe-PC\SatMe-PC.exe`.

**Windows peut avertir** (« Windows a protégé votre ordinateur ») : SatMe-PC n'est pas signé par un certificat payant. Cliquer sur **Informations complémentaires**, puis **Exécuter quand même**. Pour vérifier le fichier, comparer son empreinte avec `SHA256SUMS` :

```
certutil -hashfile satme-pc-windows.msi SHA256
```

## 2. Le lancer

Lancer **SatMe-PC** depuis le menu Démarrer. La page s'ouvre dans le navigateur (**http://localhost:8090/**) ; le premier lancement télécharge les éléments orbitaux (depuis `gp.f4ioz.fr`), quelques secondes.

SatMe-PC n'a pas de fenêtre à lui : il tourne en arrière-plan, avec **son icône près de l'horloge** (dans la flèche ^ si elle n'est pas visible). Un clic dessus rouvre la page ; un clic droit propose aussi le dossier des réglages et **Quitter SatMe-PC**. Le relancer alors qu'il tourne déjà ouvre simplement sa page.

Réglages, carnet, journal et enregistrements sont dans `%APPDATA%\SatMe` (ce que SatMe-PC écrit en marchant aussi : `satme-pc.log`).

## 3. Ce que demande chaque fonction

| Pour | À faire |
|---|---|
| **Poste (CAT) ou rotor** | brancher l'interface : elle apparaît comme un port `COM…` dans l'onglet Station. Il faut parfois le pilote de son fabricant (FTDI, CP210x, CH340) ; Windows le trouve souvent tout seul. |
| **Clé RTL-SDR** (SDR, radiosondes, METEOR, panorama QO-100) | une fois : installer le pilote **WinUSB** avec [Zadig](https://zadig.akeo.ie/) — brancher la clé, *Options → List All Devices*, choisir *Bulk-In, Interface (Interface 0)* (ou *RTL2838UHIDIR*), *WinUSB*, **Replace Driver**. |
| **Carte son** (le son du poste, SSTV, APRS, FT8) | rien : elle apparaît dans l'onglet Son. |
| **DATV avec un MiniTiouner** | LongMynd ne tourne que sous Linux : le MiniTiouner se branche sur un [SatMe-Node](installer-satme-node.fr.md) (un Raspberry Pi près de l'antenne), que SatMe-PC pilote par le réseau. VLC (facultatif) ouvre le flux dans sa propre fenêtre. |
| **Le téléphone rejoint par QR code**, ou la page ouverte aux autres machines | à la première fois, Windows demande si Java peut communiquer sur le réseau : accepter pour les **réseaux privés**. |

Rien ne s'ouvre tout seul : un port série ne s'ouvre que choisi et « Connecter » cliqué, RTS/DTR restent bas, et aucun ordre d'émission n'est jamais envoyé. Commencer avec le poste et le rotor simulés.

## 4. Options

Le raccourci lance `SatMe-PC.exe` sans option. Pour en ajouter (par exemple ouvrir la page aux autres machines du réseau), faire un raccourci vers `SatMe-PC.exe` avec, à la fin de la cible :

| Option | Effet |
|---|---|
| `--port 8090` | le port de la page |
| `--reseau` | la page ouverte aux autres machines du réseau ; sans elle, ce PC seulement |
| `--dossier DIR` | où sont gardés réglages, carnet, journal et enregistrements |

## 5. Mettre à jour, désinstaller

- **Mettre à jour** : quitter SatMe-PC (icône près de l'horloge → Quitter), puis installer le nouveau `.msi` : il remplace l'ancien. Vos données restent dans `%APPDATA%\SatMe`.
- **Désinstaller** : *Paramètres → Applications → SatMe-PC → Désinstaller*. Supprimer aussi `%APPDATA%\SatMe` pour effacer vos données.
