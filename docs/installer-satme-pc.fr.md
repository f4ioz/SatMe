# Installer SatMe-PC sous Linux

*[English version](installer-satme-pc.md) · Sous Windows : [tout en un](installer-satme-pc-windows.fr.md) · Ce que fait SatMe-PC : [SatMe-PC](station-pc.fr.md)*

SatMe-PC, c'est toute la station dans un navigateur. Un seul paquet, `satme-pc.tar`, pour Debian, Ubuntu, Linux Mint, Raspberry Pi OS… Rien à compiler.

## Le plus simple : l'installation guidée

```
curl -fsSL https://raw.githubusercontent.com/f4ioz/SatMe/main/installe-satme.sh -o installe-satme.sh
bash installe-satme.sh
```

Il regarde ce qui est branché (adaptateurs du poste ou du rotor, clé RTL-SDR, MiniTiouner) et coche ce qui va avec ; vous choisissez, il installe, puis lance SatMe-PC et ouvre la page. Le relancer pour mettre à jour. `bash installe-satme.sh --oui` prend ce qu'il a détecté sans rien demander.

Les étapes ci-dessous font la même chose à la main.

## 1. Java

SatMe-PC demande Java 17 ou plus récent :

```
sudo apt install default-jre
java -version
```

## 2. Télécharger et lancer

```
cd ~
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/satme-pc.tar
curl -LO https://github.com/f4ioz/SatMe/releases/latest/download/SHA256SUMS
sha256sum --ignore-missing -c SHA256SUMS
tar xf satme-pc.tar
~/satme/bin/satme
```

Puis ouvrir **http://localhost:8090/** dans le navigateur. Le premier lancement télécharge les éléments orbitaux (depuis `gp.f4ioz.fr`) : quelques secondes.

`sha256sum` doit dire `satme-pc.tar: OK` ; sinon, télécharger à nouveau.

| Option | Effet |
|---|---|
| `--port 8090` | le port de la page |
| `--reseau` | la page ouverte aux autres machines du réseau ; sans elle, ce PC seulement |
| `--dossier DIR` | où sont gardés réglages, carnet, journal et enregistrements (`~/.satme` d'origine) |
| `--version` | la version installée |

## 3. Ce que demande chaque fonction

N'installer que ce qui sert.

| Pour | Installer |
|---|---|
| **Poste (CAT) ou rotor** sur un port série/USB | être dans le groupe `dialout` : `sudo usermod -aG dialout $USER`, puis se déconnecter et se reconnecter |
| **Vidéos, GIF, enregistrements du téléphone**, images DATV | `sudo apt install ffmpeg` |
| **Clé RTL-SDR** (SDR, radiosondes, METEOR, panorama QO-100) | `sudo apt install rtl-sdr` (SatMe-PC lance `rtl_tcp` lui-même) |
| **DATV avec un MiniTiouner** branché sur ce PC | [LongMynd](#datv-sur-ce-pc) et sa règle udev, plus bas — ou un [SatMe-Node](installer-satme-node.fr.md) près de l'antenne |
| **Le téléphone rejoint par QR code** | le pare-feu doit laisser passer le port de la page + 1 : `sudo ufw allow from 192.168.0.0/16 to any port 8091 proto tcp` (seulement si `ufw` est actif) |

Rien ne s'ouvre tout seul : un port série ne s'ouvre que choisi et « Connecter » cliqué, RTS/DTR restent bas, et aucun ordre d'émission n'est jamais envoyé. Commencer avec le poste et le rotor simulés.

### DATV sur ce PC

```
sudo apt install build-essential libusb-1.0-0-dev libasound2-dev
cd ~ && curl -L https://github.com/BritishAmateurTelevisionClub/longmynd/archive/refs/heads/master.tar.gz | tar xz
make -C longmynd-master && mkdir -p ~/longmynd && cp longmynd-master/longmynd ~/longmynd/
echo 'SUBSYSTEMS=="usb", ATTRS{idVendor}=="0403", ATTRS{idProduct}=="6010", ATTRS{product}=="MiniTiouner*", MODE:="0666"' | sudo tee /etc/udev/rules.d/60-satme-minitiouner.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
```

Débrancher puis rebrancher le MiniTiouner. SatMe-PC trouve LongMynd dans `~/longmynd/` (ou dans le `PATH`). Branché, le MiniTiouner apparaît aussi comme deux ports série (`/dev/ttyUSB…`) : ne jamais les choisir comme port du poste ou du rotor.

## 4. Le lancer avec la session (facultatif)

```
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/satme-pc.service <<'EOF'
[Unit]
Description=SatMe-PC

[Service]
ExecStart=%h/satme/bin/satme
Restart=on-failure

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now satme-pc
```

## 5. Mettre à jour, désinstaller

- **Mettre à jour** : les mêmes commandes qu'à l'étape 2 (arrêter SatMe-PC d'abord). Réglages, carnet, journal et enregistrements restent dans `~/.satme`.
- **Désinstaller** : `rm -rf ~/satme` (et `~/.satme` pour effacer aussi vos données).

## Le compiler soi-même

```
git clone https://github.com/f4ioz/SatMe.git && cd SatMe
./gradlew :pc:installDist
pc/build/install/satme/bin/satme
```

Il faut un JDK 17 ou plus récent et le SDK Android (le projet contient aussi l'application du téléphone).
