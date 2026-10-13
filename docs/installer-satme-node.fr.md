# Installer SatMe-Node sur un Raspberry Pi

*[English version](installer-satme-node.md)*

**SatMe-Node** met le MiniTiouner (bande large de QO-100, DATV) à côté de l'antenne, sur un Raspberry Pi, et le pilote par le réseau depuis [SatMe-PC](station-pc.fr.md) ou le téléphone. Le node ne fait que recevoir : LongMynd y tourne et envoie son état et le flux vidéo (0,3 à 2 Mbit/s). Rien n'y est décodé : un **Raspberry Pi 3** suffit.

## Ce qu'il faut

- Un Raspberry Pi 3 (B ou B+), 4 ou 5, son alimentation, une carte microSD de 8 Go ou plus.
- L'**Ethernet** de préférence (le Wi-Fi du Pi 3 est faible près d'une parabole).
- Le MiniTiouner (Pro TS2 compris), son alimentation s'il en a une, et le câble du LNB.

## 1. Préparer la carte

Avec **Raspberry Pi Imager** (raspberrypi.com/software) :

- Modèle : votre Pi. Système : **Raspberry Pi OS Lite (64-bit)**.
- Réglages (la roue dentée) : nom d'hôte **`satme-node`**, un utilisateur et son mot de passe, **SSH activé**, le Wi-Fi seulement sans câble.

Mettre la carte dans le Pi, brancher le réseau, l'alimenter, attendre une minute.

## 2. Installer

Depuis un PC du même réseau, l'installation guidée (elle voit un Raspberry Pi sans bureau et propose SatMe-Node) :

```
ssh <utilisateur>@satme-node.local
curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
bash installe-satme.sh
```

Ou à la main :

```
ssh <utilisateur>@satme-node.local
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
tar xf satme-pc.tar
sudo ./satme/noeud/installe-noeud.sh
```

Une dizaine de minutes sur un Pi 3. Le script installe Java, compile LongMynd (BATC), lui donne accès au MiniTiouner (udev), copie SatMe dans `/opt/satme` et démarre le service **satme-noeud** (port 8090). Il finit par l'**adresse** du node et son **code à six chiffres** : les noter.

Brancher ensuite le MiniTiouner sur le Pi (ou le débrancher et le rebrancher).

## 3. L'associer

- **SatMe-PC** : onglet DATV → MiniTiouner **Changer** → l'adresse du node (`192.168.1.30` ou `satme-node.local`) et son code → **Associer**.
- **Le téléphone** : écran QO-100 → **DATV** → l'adresse du node (son IP : les téléphones ne trouvent pas toujours les noms en `.local`) et son code → **Associer**.

Chacun garde la clé que le node lui a donnée : le node, le PC ou le téléphone peuvent redémarrer sans tout refaire. Revoir le code, sur le Pi : `journalctl -u satme-noeud | grep Code`.

## Vérifier

- `http://satme-node.local:8090/` dans un navigateur : ce qu'est le node et ce que fait son MiniTiouner (jamais son code).
- Sur le Pi : `systemctl status satme-noeud`, `journalctl -u satme-noeud -f`.
- « MiniTiouner introuvable » : est-il branché ? « pas d'accès » : le débrancher et le rebrancher (la règle udev s'applique au branchement).

## Mettre à jour

Sur le Pi, les trois mêmes commandes qu'à l'étape 2. Réglages, code et clés restent dans `/var/lib/satme-noeud`.

## Sécurité

- Sans sa clé, le node ne dit que ce qu'il est ; ce qui le change est un POST.
- Son **alimentation LNB reste coupée** tant qu'on ne la choisit pas depuis SatMe-PC ou le téléphone (13 V ou 18 V) : jamais quand autre chose alimente le LNB.
- Fait pour le réseau de la maison : ne pas ouvrir son port sur Internet (rien n'est chiffré).

## Raspberry Pi 4 ou 5

Même installation. Ils laissent la place à ce qui pourra venir ensuite sur le node — le rotor, le poste, la clé SDR au pied du mât —, le Pi 3 restant un node MiniTiouner.
