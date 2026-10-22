# Installer SatMe-Node sur un Raspberry Pi

*[English version](installer-satme-node.md)*

**SatMe-Node** met les récepteurs et l'émetteur de QO-100 à côté de l'antenne, sur un Raspberry Pi, et les pilote par le réseau depuis [SatMe-PC](station-pc.fr.md) ou le téléphone :

- **DATV** : le MiniTiouner (LongMynd) — ou le démodulateur DVB-S2 de SatMe avec un Pluto ou un bladeRF ;
- **SDR** : un bladeRF ou un Pluto, dont la bande étroite arrive à SatMe-PC comme une carte branchée ici (cockpit QO-100, cascade, FT8, balise, enregistrement) ;
- **émission** (Pluto ou bladeRF), DATV et SSTV, décochée au départ ;
- **sa santé** : température, charge, mémoire, microSD, réseau, USB, services, journal.

## Quel Raspberry Pi

| Ce que fait le node | Pi |
|---|---|
| MiniTiouner seul | **Pi 3** (B ou B+) suffit |
| MiniTiouner + bladeRF (SDR, émission) | **Pi 4 ou Pi 5**, le bladeRF sur un port **USB 3** (bleu) |
| MiniTiouner + Pluto | Pi 4 ou Pi 5 conseillé |

## Ce qu'il faut

- Le Raspberry Pi, son **alimentation officielle** (3 A pour un Pi 4, 5 A pour un Pi 5 : le bladeRF consomme, un bloc faible fait brider le Pi), une carte microSD de 16 Go ou plus.
- L'**Ethernet** : le flux SDR fait environ 34 Mbit/s au débit plein (1,058 MS/s) ; en Wi-Fi, choisir 529 ou 265 kS/s.
- Le MiniTiouner (Pro TS2 compris) et son alimentation s'il en a une ; le bladeRF ou le Pluto.
- Avec un LNB double sortie (Twin) : **sortie 1 → MiniTiouner**, qui alimente le LNB en 18 V (horizontale, DATV) ; **sortie 2 → bladeRF** par un **injecteur 13 V avec bloc DC** (verticale, bande étroite) — le bladeRF ne doit jamais recevoir la tension du LNB.

## 1. Préparer la carte

Avec **Raspberry Pi Imager** (raspberrypi.com/software) :

- Modèle : votre Pi. Système : **Raspberry Pi OS Lite (64-bit)**.
- Réglages (la roue dentée) : nom d'hôte **`satme-node`**, un utilisateur et son mot de passe, **SSH activé**, le Wi-Fi seulement sans câble.

Mettre la carte dans le Pi, brancher le réseau, l'alimenter, attendre une minute.

## 2. Installer

Depuis un PC du même réseau, se connecter au Pi :

```
ssh <utilisateur>@satme-node.local
```

Puis, **dans le dossier personnel** (là où `ssh` arrive), l'installation guidée (elle voit un Raspberry Pi sans bureau et propose SatMe-Node) :

```
curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
bash installe-satme.sh
```

Ou à la main :

```
cd ~
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
tar xf satme-pc.tar
sudo ./satme/noeud/installe-noeud.sh
```

Le script n'est pas dans le dossier personnel mais dans **`satme/noeud/`**, créé par `tar` (il est déjà exécutable). « commande introuvable » : vérifier avec `ls satme/noeud/` que l'archive est bien décompressée.

Une dizaine de minutes sur un Pi 3, un peu plus sur un Pi 4 (LongMynd se compile). Le script :

- installe **Java** (le premier qu'offre le système : 21 sur Raspberry Pi OS récent, Debian 13 « trixie ») ;
- compile **LongMynd** (BATC) et lui ouvre le MiniTiouner (udev) ;
- installe l'outil du **bladeRF** (`bladeRF-cli`), son accès USB et son **image FPGA** (xA4 et xA9) ;
- copie SatMe dans `/opt/satme` et démarre le service **satme-noeud** (port 8090), son utilisateur dans les groupes `plugdev` (bladeRF) et `video` (température du Pi).

Il finit par l'**adresse** du node et son **code à six chiffres** : les noter. Le relancer ne casse rien : il reprend tout.

Brancher ensuite le MiniTiouner et le bladeRF (USB 3) sur le Pi, ou les débrancher et les rebrancher. Le **Pluto** n'a besoin de rien : branché en USB, il apparaît comme une carte réseau (192.168.2.1).

## 3. L'associer

- **SatMe-PC** : onglet DATV → MiniTiouner **Changer** → l'adresse du node (`192.168.1.30` ou `satme-node.local`) et son code → **Associer**. Puis :
  - onglet **SDR** → récepteur **« SDR du SatMe-Node »** : la bande étroite du bladeRF ou du Pluto du node ;
  - **Station › SatMe-Node** : sa santé, ses cartes et leur rôle ; **Détails…** pour ses actions.
- **Le téléphone** : écran QO-100 → **DATV** → l'adresse du node (son IP : les téléphones ne trouvent pas toujours les noms en `.local`) et son code → **Associer**.

Chacun garde la clé que le node lui a donnée : le node, le PC ou le téléphone peuvent redémarrer sans tout refaire. Revoir le code, sur le Pi : `journalctl -u satme-noeud | grep Code`.

## Vérifier

- `http://satme-node.local:8090/` dans un navigateur : ce qu'est le node et ce que font ses cartes (jamais son code).
- Sur le Pi : `systemctl status satme-noeud`, `journalctl -u satme-noeud -f`.
- `bladeRF-cli -p` : le bladeRF est-il vu ? La supervision signale un bladeRF en **USB 2** (port noir) : le mettre sur un port bleu.
- « MiniTiouner introuvable » : est-il branché ? « pas d'accès » : le débrancher et le rebrancher (la règle udev s'applique au branchement).
- Température élevée ou « bridé » dans la supervision : un dissipateur ou un ventilateur, une meilleure alimentation.

## Mettre à jour

Sur le Pi, les mêmes commandes qu'à l'étape 2 (version publiée la plus récente). Réglages, code et clés restent dans `/var/lib/satme-noeud`. **Relancer SatMe sur le node** depuis SatMe-PC (Station › SatMe-Node › Détails…) redémarre seulement le service, jamais le Pi.

## Sécurité

- Sans sa clé, le node ne dit que ce qu'il est ; ce qui le change est un POST.
- Son **alimentation LNB reste coupée** tant qu'on ne la choisit pas depuis SatMe-PC ou le téléphone (13 V ou 18 V) : jamais quand autre chose alimente le LNB.
- **L'émission du node** demande deux cases, décochées au départ : « Émettre par le SatMe-Node » dans SatMe-PC, et **« émission autorisée »**, qui ne se coche que sur le node lui-même (sa page ouverte sur le Pi, ou par un tunnel SSH). Le node a ses bandes, sa durée et son plafond de puissance ; il coupe l'émission si SatMe-PC se tait 1,5 s ou se déconnecte. Une même carte ne reçoit et n'émet jamais à la fois.
- Fait pour le réseau de la maison : ne pas ouvrir son port sur Internet (rien n'est chiffré).
