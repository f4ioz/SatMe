# SatMe-PC — démonstration / demo

*English below.*

## Français

Une station SatMe-PC **fictive** à installer sur une VM Linux ou un Raspberry Pi pour montrer le logiciel :
indicatif inventé (série Q, réservée par l'UIT aux codes Q : il n'appartient à personne), carnet, journal des
passages et images SSTV inventés, **matériel virtuel** (IC-9700 et rotor simulés, générateur SDR, avions et
navires d'essai, mire SSTV comme carte son). **Rien de réel n'est ouvert, rien n'émet** ; un bandeau
« DÉMO — données fictives » est sur chaque page.

**Installer** (Debian, Ubuntu, Raspberry Pi OS 64 bits ; x86_64 ou arm64) :

```
tar xzf satme-demo.tar.gz
cd satme-demo
./installe-demo.sh --sec          # essai à sec : ce qui serait fait
sudo ./installe-demo.sh           # Java si besoin, /opt/satme-demo, service satme-demo, port 8090
```

Puis `http://<adresse-de-la-machine>:8090/` depuis une autre machine : le mot de passe est affiché à la fin de
l'installation (et dans `sudo journalctl -u satme-demo | grep 'mot de passe'`). Depuis la machine elle-même,
pas de mot de passe.

- **Remettre à neuf** : le bouton du bandeau, ou `sudo -u satme-demo touch /var/lib/satme-demo/demo/.satme-demo-reset && sudo systemctl restart satme-demo`.
- Les passages, le carnet et les images suivent la date : refaits quand ils ont plus de deux jours.
- **Hors ligne** : les éléments orbitaux sont dans le programme ; avec Internet, ils sont mis à jour.
- **Désinstaller** : `sudo ./installe-demo.sh --desinstalle` ; autre port : `--port 8095`.
- Sans service : `satme/bin/satme --demo` (données dans `~/.satme-demo`, jamais dans `~/.satme`).

## English

A **made-up** SatMe-PC station to install on a Linux VM or a Raspberry Pi to show the software: invented
callsign (Q series, kept by the ITU for the Q code: nobody's), invented log, pass journal and SSTV pictures,
**virtual hardware** (simulated IC-9700 and rotator, SDR test generator, trial aircraft and ships, an SSTV test
card as the sound card). **Nothing real is opened, nothing transmits**; a « DÉMO — données fictives » band is on
every page.

**Install** (Debian, Ubuntu, Raspberry Pi OS 64-bit; x86_64 or arm64):

```
tar xzf satme-demo.tar.gz
cd satme-demo
./installe-demo.sh --sec          # dry run: what would be done
sudo ./installe-demo.sh           # Java if needed, /opt/satme-demo, satme-demo service, port 8090
```

Then `http://<machine-address>:8090/` from another machine: the password is shown at the end of the install
(and by `sudo journalctl -u satme-demo | grep 'mot de passe'`). From the machine itself, no password.

- **Make it new again**: the band's button, or `sudo -u satme-demo touch /var/lib/satme-demo/demo/.satme-demo-reset && sudo systemctl restart satme-demo`.
- The passes, the log and the pictures follow the date: made again when older than two days.
- **Offline**: the orbital elements are carried in the program; with the Internet they are refreshed.
- **Uninstall**: `sudo ./installe-demo.sh --desinstalle`; another port: `--port 8095`.
- Without the service: `satme/bin/satme --demo` (data in `~/.satme-demo`, never in `~/.satme`).
