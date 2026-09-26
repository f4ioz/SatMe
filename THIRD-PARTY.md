# Composants tiers

SatMe est distribué sous **GNU General Public License version 2** (voir
`LICENSE`). Le code propre au projet est licencié **GPL-2.0-ou-ultérieure** :
cela ne change rien aujourd'hui, mais laisse la porte ouverte à la version 3
si la bibliothèque de prédiction venait à changer. Ce choix n'en est pas tout à fait un : il découle de la
bibliothèque de prédiction orbitale, détaillée ci-dessous.

---

## Pourquoi la GPL

`predict4java`, qui calcule les passages, est un portage Java du cœur de
**PREDICT**, écrit par John A. Magliacane (KD2BD), lui-même fondé sur la
traduction en C de Neoklis Kyriazis (5B4AZ) publiée sous GNU GPL en 2002. Les
modèles SGP4/SDP4 sous-jacents sont du Dr T. S. Kelso, versés au domaine
public.

L'artefact employé — `com.github.davidmoten:predict4java:1.3.1` — déclare la
**GPL version 2** dans son descripteur Maven. La GPL étant à réciprocité,
l'œuvre combinée qu'est SatMe se distribue sous la même licence.

**Un point à connaître si la licence devait changer un jour.** Le dépôt amont
`uk.me.g4dpz/predict4java` a été relicencié en MIT en 2026. Passer à cet
artefact rendrait le choix de licence libre. Mais ce code descend d'une lignée
GPL, et un relicenciement aval ne purge pas nécessairement les droits des
auteurs antérieurs. Rester en GPL est la position défendable ; elle est aussi
celle de la tradition du logiciel radioamateur.

---

## Bibliothèques employées

| Composant | Licence | Usage dans SatMe |
|---|---|---|
| `com.github.davidmoten:predict4java` | GPL-2.0 | Prédiction des passages, SGP4/SDP4, Doppler |
| LAME (`libandroidlame.so`, embarqué) | LGPL-2.1 | Encodage MP3 des enregistrements |
| `com.naman14.androidlame` (enveloppe Java, dans `src/main/java`) | LGPL-2.1 | Interface Java vers LAME |
| `com.google.zxing:core` | Apache-2.0 | Génération des QR codes |
| `com.squareup.okhttp3:okhttp` | Apache-2.0 | Requêtes réseau |
| `com.github.mik3y:usb-serial-for-android` | MIT | Ponts série USB (CAT, rotor) |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | Interface et cycle de vie |
| `com.google.android.gms:play-services-location` | Propriétaire Google | Position GPS |
| `com.google.android.play:app-update-ktx` | Propriétaire Google | Détection de mise à jour |
| JUnit, Robolectric (essais seulement) | EPL-1.0, Apache-2.0 | Bancs d'essai |
| `ft8_lib` de Kārlis Goba (tables du code correcteur, recopiées) | MIT | Décodage FT8 et FT4 |

**Sur LAME et la LGPL.** La bibliothèque native est embarquée en tant que
`.so` distinct, non fusionné au reste de l'application. C'est ce qui permet à
un utilisateur de la remplacer par une version modifiée, condition posée par
la LGPL. Cette séparation doit être préservée.

**Sur les tables LDPC.** Le fichier `domain/LdpcTables.kt` reprend les tables
du code correcteur (174, 91) de `ft8_lib`, sous licence MIT. Il porte sa propre
mention de provenance et **n'est pas couvert par le copyright de SatMe** : la
licence MIT est compatible avec la GPL, mais son auteur doit rester nommé.

**Sur les composants Google.** Ils ne sont pas libres. Une distribution qui
voudrait s'en passer — F-Droid, par exemple — devrait remplacer la
localisation par le fournisseur de la plateforme et retirer la détection de
mise à jour.

---

## Algorithmes et données

**FT8 et FT4** sont implémentés d'après l'article *QEX* de K9AN, G4WJS et K1JT,
du domaine public. **Aucun code de WSJT-X n'est employé** : WSJT-X est sous GPL
et n'apparaît ni dans le code, ni dans les crédits de l'application.

**Les éléments orbitaux** proviennent de Celestrak et du bulletin AMSAT, aux
conditions de leurs éditeurs respectifs.

**Les statuts de satellites** viennent de l'AMSAT Live OSCAR Status et de
SatNOGS.

**Les parcs POTA** proviennent de `pota.app` (liste des parcs) et de
`pota-map.fr` (contours des zones). Ce sont des points d'accès publics, sans
clé ni authentification. SatMe les interroge, ne les redistribue pas, et ne
contrôle ni leur disponibilité ni leurs conditions d'usage : quiconque
reprend ce code s'en remet aux règles de ces services.

---

## Ce que la publication rend visible

Deux mécanismes de l'application reposaient sur la discrétion de la source, et
cessent d'en bénéficier :

Le **trousseau d'extensions** (`data/Extensions.kt`) contient ses mots-clés en
clair. Toute fonction qu'il protège devient accessible à qui lit le fichier.

La fonction **`estAuteur()`** teste si l'indicatif contient « F4IOZ ». Elle se
contourne en changeant son indicatif.

Ce sont des ralentisseurs, pas des serrures, et ils l'étaient déjà. Mais mieux
vaut le savoir avant de publier que de le découvrir après.

---

*Dernière vérification des licences : 24 septembre 2026.*
