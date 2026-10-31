# TellusMe — la station terrestre de l'écosystème SatMe

*[English version](tellusme.md)*

**TellusMe** est à la station terrestre ce que SatMe-PC est à la station satellite : un petit programme sur le PC (Linux, Windows, Raspberry Pi) avec sa page dans le navigateur, bâti sur le moteur même de SatMe. Le déca d'abord ; la VHF/UHF terrestre ensuite.

## État : ébauche, réception seule

C'est une première **ébauche**. Elle **ne peut pas émettre**, par construction :

- aucun bouton d'émission, aucun PTT, aucun réglage de puissance nulle part ;
- sur l'IC-7000, une garde devant la ligne série ne laisse sortir que les commandes CI-V dont un récepteur a besoin (lire et régler fréquence et mode, lire le S-mètre) ; tout le reste — PTT (`1C`), messages CW (`17`), niveaux, menus — est refusé avant d'atteindre le fil. Le port s'ouvre RTS et DTR **bas** (sur bien des interfaces, ils passent le poste en émission) ;
- sur le Brick2 (openHPSDR Protocol 1), chaque datagramme est vérifié avant de partir : bit MOX toujours à 0, charge d'émission toujours nulle, seul le flux de réception est démarré ; le niveau d'excitation n'est jamais écrit.

## Ce qu'elle fait

- **Poste** : Icom IC-7000 en CI-V (adresse 70h, 19 200 ou 9 600 bauds, reconnexion d'elle-même quand le poste se tait), le **SDR Brick2** par le réseau, ou aucun (un VFO manuel, pour le carnet).
- **VFO** : fréquence, pas d'accord, molette, saisie, boutons de bande de 160 m à 6 m (plus 2 m et 70 cm avec l'IC-7000), boutons de mode, S-mètre.
- **Plan de bande** : le plan HF IARU Région 1, simplifié (CW, numérique, phonie/tous modes, balises), en couleur sous le VFO et sous le spectre. Affichage seulement — la réglementation de votre pays fait foi.
- **Brick2** : spectre et chute d'eau (48, 96 ou 192 kHz), clic pour accorder, son BLU/CW/AM/FM dans le navigateur.
- **Propagation** : flux solaire, indices K et A et conditions par bande, de HamQSL, lus au plus une fois par heure.
- **Cluster DX** : telnet, en lecture seule (votre indicatif est envoyé une fois, à la connexion, rien d'autre), spots filtrés sur la bande en cours, un clic sur un spot pour l'accorder.
- **Carnet** : le moteur de carnet de SatMe, dans le dossier de TellusMe, export ADIF et Contrôle ADIF.

TellusMe garde tout dans son propre dossier (`~/.tellusme`, ou `%APPDATA%\TellusMe` sous Windows, ou `$TELLUSME_HOME`), jamais dans celui de SatMe.

## La lancer (depuis les sources)

```
./gradlew :tellus:installDist
tellus/build/install/tellusme/bin/tellusme            # http://localhost:8092/
tellus/build/install/tellusme/bin/tellusme --simulation --port 8093
```

`--simulation` fait tourner un IC-7000 et un Brick2 en mémoire, un cluster DX sur la boucle locale et une réponse de propagation enregistrée : rien n'atteint un vrai poste, un port série ni le réseau. `--reseau` rend la page joignable depuis le réseau local (sinon : ce PC seul).

Le Brick2 est cherché à l'adresse donnée dans Réglages ; sans adresse, **Chercher le Brick2** envoie une recherche en diffusion sur le réseau local (seulement quand vous cliquez). Pendant que TellusMe écoute, la radio n'est plus disponible pour les autres logiciels openHPSDR (Thetis, piHPSDR).

## La suite

L'émission avec ses garde-fous, le FT8 en déca, d'autres postes, la VHF/UHF terrestre, un carnet partagé avec SatMe.
