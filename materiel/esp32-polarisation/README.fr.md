# Boîtier de polarisation ESP32 · firmware

*[English](README.md)*

Le firmware du boîtier qui commande les commutateurs de polarisation à distance **WiMo** (UHF 18082 / 18083, VHF 18080 / 18081) depuis **SatMe-PC**, par USB. Le boîtier met +12 V sur **une seule** broche de la prise 6 pôles du WiMo (1 circulaire droite, 2 circulaire gauche, 5 horizontale, 4 masse) ; sans tension, l'antenne est en **verticale**.

## Le montage

ESP32 DevKit (module WROOM) → TBD62083A (ou ULN2803A) → arbre de 3 relais 12 V, KA → KB → KC : le +12 V ne peut arriver que sur une broche, quoi que fasse le logiciel.

| Broche ESP32 | Rôle |
|---|---|
| GPIO23, 22, 21 | sorties KA, KB, KC (UHF) → IN1, IN2, IN3 |
| GPIO19, 18, 17 | sorties KA′, KB′, KC′ (VHF, plus tard) → IN4, IN5, IN6 |
| GPIO32, 33, 25, 26, 27 | sélecteur, circuit B : V, H, D, G, AUTO (à la masse dans sa position, pull-up internes) |
| GPIO13 | SEND du poste par 1 kΩ (à la masse en émission, pull-up interne) |

| Polarisation | Broche WiMo sous 12 V | KA | KB | KC |
|---|---|---|---|---|
| V (verticale) | aucune | 0 | – | – |
| H (horizontale) | 5 | 1 | 0 | – |
| D (circulaire droite) | 1 | 1 | 1 | 0 |
| G (circulaire gauche) | 2 | 1 | 1 | 1 |

Le **sélecteur rotatif** (2 circuits × 5 positions) garde le dernier mot : sur V, H, D ou G, son circuit A alimente directement la bonne broche et l'arbre n'est pas alimenté ; sur **AUTO**, il alimente l'arbre et c'est SatMe qui choisit. VHF : un relais H/V seul se câble sur KA′ (repos = V).

GPIO16 et 17 servent à la PSRAM sur un module **WROVER** : prendre un DevKit WROOM, ou déplacer KC′.

## Les règles du firmware

- Au démarrage, tous les relais au repos (aucune tension : V).
- Hors AUTO (ou entre deux positions du sélecteur) : toutes les sorties relâchées, toute commande refusée (`ERR MANUEL`).
- SEND actif : aucune commutation (`ERR TX`).
- Un changement passe par « aucune tension » : KA relâché d'abord, KB et KC posés derrière, KA en dernier.
- Anti-rebond : 50 ms pour le sélecteur, 20 ms pour SEND.

## Le dialogue USB

115200 bauds, 8N1, une ligne par commande (fin de ligne `\n` ou `\r\n`), majuscules ou minuscules.

| Envoyé | Réponse |
|---|---|
| `POL U H` (ou `V`, `D`, `G`) | `OK POL U H`, ou `ERR MANUEL …`, `ERR TX …`, `ERR SYNTAXE …` |
| `POL V H` | pareil pour la VHF |
| `ETAT?` | `ETAT SEL=AUTO U=D V=V TX=0 FW=1.0` |
| `ID?` | `ID SATME-POL ESP32 FW=1.0` |

`SEL` vaut `AUTO`, `V`, `H`, `D`, `G` ou `?` (entre deux positions). `U` est la polarisation **réelle** de l'antenne UHF : celle du sélecteur hors AUTO, celle des relais en AUTO. `V` est celle des relais VHF. Une ligne `ETAT …` part aussi d'elle-même au démarrage et à chaque changement (sélecteur, SEND, polarisation).

Pour essayer à la main : `screen /dev/ttyUSB0 115200` (ou le moniteur série de l'IDE Arduino), puis `ETAT?`.

## Compiler et charger

IDE Arduino 2 ou `arduino-cli`, avec le paquet **esp32** d'Espressif, carte « ESP32 Dev Module » :

```
arduino-cli core install esp32:esp32
arduino-cli compile --fqbn esp32:esp32:esp32 esp32-polarisation
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 esp32-polarisation
```

## Dans SatMe-PC

**Réglages › Poste et rotor › Polarisation d'antenne** : choisir le port du boîtier, **Tester la liaison**, dire ce qui est équipé (UHF, VHF). Le panneau **Polarisation** du Suivi montre l'état réel et commande V / H / D / G en AUTO. L'**ESP32 virtuel** suit exactement ces règles : on peut tout essayer avant de souder.

Sur Linux, l'utilisateur doit être dans le groupe `dialout`. À l'ouverture du port, Linux lève brièvement RTS/DTR, ce qui peut redémarrer le DevKit : il repart avec tous les relais au repos, et SatMe redemande l'état une seconde et demie plus tard.
