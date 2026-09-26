# Consigne — élagage et traduction des commentaires de SatMe

À coller dans Claude Code, à la racine du dépôt. Sur Opus 5.5 de préférence :
les arbitrages sur ce qu'il faut couper y gagneront.

---

## La tâche

Les commentaires de SatMe sont en français et représentent environ 19 800
lignes sur 201 fichiers Kotlin. Il faut les passer en anglais **en les
élaguant**, pas en les traduisant mot à mot.

Trois fichiers du paquet `demo` sont déjà faits et servent de référence :
`PontCommande.kt`, `AnnonceReseau.kt`, `EcouteDeportee.kt`. `ServeurDemo.kt`
est fait à moitié. Lis-les avant de commencer.

---

## Ce qui reste, ce qui part

**Garder** ce qui explique *pourquoi* un choix a été fait, surtout quand le
choix est contre-intuitif ou qu'il vient d'un défaut constaté. Ces
commentaires-là ont été écrits parce que quelqu'un s'est trompé une fois, et
ils évitent qu'on recommence.

**Garder** les pièges : ordres d'appel, conventions non évidentes, contraintes
de matériel, raisons de ne pas faire autrement.

**Couper** ce qui paraphrase le code. `// on incrémente le compteur` au-dessus
d'un `n++` n'apprend rien.

**Couper** les justifications de décisions désormais acquises. Un commentaire
de dix lignes expliquant pourquoi on n'a pas pris une bibliothèque, alors que
la question ne se pose plus depuis deux ans, se réduit à une phrase.

**Couper** les récits d'atelier. « Trois versions ont cherché ce défaut » est
intéressant pour l'auteur, pas pour le lecteur — sauf si le piège peut se
reproduire, auquel cas c'est le piège qu'on garde, pas le récit.

**Raccourcir** sans pitié : un bloc de quinze lignes fait souvent quatre lignes
une fois débarrassé du superflu. Une réduction de moitié sur l'ensemble est un
bon objectif.

---

## Règles d'écriture

L'anglais doit être **direct et sobre**. Pas de tournures alambiquées, pas de
vocabulaire savant. Une phrase courte vaut mieux qu'une phrase élégante.

Les emphases `**…**` en tête de bloc sont conservées quand le bloc porte une
règle importante. Ailleurs, elles disparaissent.

Les termes radioamateurs restent en usage anglais : callsign, grid square,
locator, pass, uplink, downlink, transponder, Doppler.

---

## Ce qu'il ne faut pas toucher

**Le code lui-même.** Aucun nom de fonction, de variable ou de classe ne
change. La tâche porte sur les commentaires, rien d'autre.

**`app/src/main/java/com/naman14/`** — enveloppe Java de LAME, LGPL,
propriété de son auteur.

**`domain/LdpcTables.kt`** — tables MIT de Kārlis Goba, avec leur mention de
provenance.

**`i18n/Strings.kt`** — les textes de l'interface sont déjà bilingues. Seuls
les commentaires du fichier sont concernés, pas les chaînes.

---

## Méthode, et elle compte

**Un module à la fois**, dans cet ordre :

1. `demo` (finir `ServeurDemo.kt`, puis `PageCommande.kt`, `PageDemo.kt`)
2. `data` puis `domain` — le cœur
3. `ui`
4. `cat`, `sdr`, `sonde`, `sstv`, `rotor`, `ble`, `audio`, `apt`, `diag`
5. la racine, dont `MainViewModel.kt` seul

**Après chaque fichier**, vérifie qu'il ne reste rien de français :

```bash
python3 - "$FICHIER" <<'EOF'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read().split('\n')
r = [l for l in s if re.match(r'\s*(//|\*|/\*)', l)
     and re.search(r"\b(le|la|les|une|des|qui|que|pour|dans|est|sont|pas|plus|sur)\b", l)]
print(len(r), "lignes françaises restantes")
for l in r[:10]: print("  ", l.strip()[:80])
EOF
```

**Après chaque module**, compile :

```bash
./gradlew compileDebugKotlin
```

**Avant de livrer**, les essais complets :

```bash
./gradlew testDebugUnitTest
```

Mille cent quarante-cinq essais, zéro échec. Un seul échec veut dire qu'une
chaîne de caractères a été touchée par erreur : cherche là, pas ailleurs.

---

## Deux pièges rencontrés

**Un mot d'une autre langue peut se glisser dans une traduction longue** — un
mot russe est apparu au milieu d'un commentaire anglais, et la compilation ne
l'a pas vu : un commentaire accepte n'importe quoi. Contrôle après coup :

```bash
grep -rP '[\x{0400}-\x{04FF}\x{4E00}-\x{9FFF}]' app/src/main/java --include=*.kt
```

**Les remplacements par texte exact échouent sur l'indentation.** Fais échouer
le script plutôt que de l'écrire à moitié : un fichier traduit aux trois quarts
est pire qu'un fichier pas traduit.

---

## Le ton à viser

Ces commentaires ont une voix : ils expliquent, ils assument les erreurs, ils
ne se paient pas de mots. Garde-la. Un commentaire qui dit « this was wrong
three times, here is why » vaut mieux que « handles the edge case ».
