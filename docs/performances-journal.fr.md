# Journal des passages — ce que coûte un export

*Mesures et estimations sur le téléphone de référence : **Xiaomi 11T Pro** (8 cœurs, 8 Go, Android 14), SatMe 20.82, 11 octobre 2026.*

Une vidéo du journal se fabrique sur le téléphone : chaque image (5 par seconde) est dessinée par le processeur, puis encodée en H.264 par la puce vidéo du téléphone ; le son du passage est encodé en AAC. Un GIF a 76 images en 480 × 640, sans son.

## Mesuré (passage de 10 minutes avec son, export accéléré)

| Export | Fabrication | Processeur | Mémoire en plus | Chauffe | Fichier |
|---|---|---|---|---|---|
| GIF | **2 à 4 s** | ¾ de cœur, un instant | ≈ 30 Mo | aucune | ≈ 3 Mo |
| Vidéo 480 px | **≈ 37 s** | 1 cœur, 2 à la fin | ≈ 95 Mo | aucune | ≈ 7 Mo |
| Vidéo 720 px | **≈ 45 s** | 1 à 2 cœurs | ≈ 100 Mo | +1 °C | ≈ 15 Mo |

« Mémoire en plus » : au-dessus de ce que l'appli occupe déjà, rendue à la fin. Sur 8 cœurs, il en reste toujours plus de la moitié de libre.

## Estimé (même téléphone)

Modèle tiré des mesures : ≈ 1 s de préparation par minute de passage (le son), puis 34 ms par image + 35 ms par mégapixel. Une vidéo accélérée dure ≈ 1/5 du passage (selon l'activité : contacts, images) ; en temps réel, autant que le passage.

| Passage | Export | Fabrication | Fichier |
|---|---|---|---|
| 5 min | vidéo 480 / 720 / 1080 px accélérée | 18 s / 22 s / 31 s | 4 / 8 / 19 Mo |
| 5 min | vidéo 480 / 720 / 1080 px temps réel | 1,3 / 1,6 / 2,4 min | 19 / 41 / 97 Mo |
| 10 min | vidéo 1080 px accélérée | ≈ 1 min | ≈ 39 Mo |
| 10 min | vidéo 480 / 720 / 1080 px temps réel | 2,6 / 3,2 / 4,7 min | 37 / 82 / 195 Mo |
| 15 min | vidéo 480 / 720 / 1080 px accélérée | 55 s / 67 s / 94 s | 11 / 25 / 58 Mo |
| 15 min | vidéo 480 / 720 / 1080 px temps réel | 3,9 / 4,9 / 7,1 min | 56 / 123 / 292 Mo |
| toute durée | GIF | quelques secondes | 2 à 4 Mo |

**Mémoire** : le son du passage est aujourd'hui chargé d'un bloc, en plusieurs copies : ≈ +10 Mo par minute de passage pendant une vidéo (≈ 50 Mo pour 5 min, ≈ 150 Mo pour 15 min), quelle que soit la résolution. Le GIF n'a pas de son : ≈ +30 Mo.

## Ce qu'on en retient

- Le **GIF** est quasi gratuit.
- La **vidéo 480 px** est le meilleur choix pour partager : deux fois plus légère que 720 px, à peine plus rapide à fabriquer (le coût vient surtout du nombre d'images et du son, pas de la taille).
- La **vidéo en temps réel** est la plus lourde : plusieurs minutes et plusieurs dizaines de mégaoctets.
- Sur un téléphone ancien (2 à 3 Go), un long passage en vidéo peut manquer de mémoire : c'est le son chargé d'un bloc qui pèse.
