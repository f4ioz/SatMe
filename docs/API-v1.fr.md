# API du poste de commande SatMe — version 1

*[English version](API-v1.md)*

SatMe ouvre un petit serveur HTTP sur le téléphone. La page web du poste de
commande n'est qu'un client parmi d'autres : tout programme capable d'une
requête HTTP peut faire la même chose, sur Windows, Linux, macOS ou ailleurs.

Ce document fige le contrat de la **version 1**.

---

## 1. Principes

**Deux portes, deux jetons.** `/d/<jeton>/` est la porte du public : lecture
seule, jeton fait pour être montré en QR code. `/c/<jetonCommande>/` est le
poste de commande : il écrit dans le carnet, donc son jeton ne quitte jamais le
téléphone, et un code à six chiffres s'y ajoute.

**Tout est en clair, sur le réseau local.** C'est proportionné à un point
d'accès de démonstration ou à un réseau domestique. Pour un accès depuis
internet, il faut un tunnel — WireGuard par exemple — et surtout pas une
redirection de port.

**La version ne bouge que si un client casse.** Ajouter une route ou un champ
ne change pas le numéro : un programme écrit pour la version 1 continue de
fonctionner. Retirer un champ ou en changer le sens, si.

---

## 2. S'y connecter

L'opérateur active le poste de commande dans **Réglages → Partage et écoute →
Partage**. L'application affiche alors une adresse de la forme :

```
http://192.168.1.42:8080/c/nkq7wz3d5f/
```

et un code à six chiffres. Cette adresse est la **base** de toutes les requêtes
qui suivent.

### Savoir à qui l'on parle

```
GET <base>/api
→ {"api":1,"satme":"20.53"}
```

Sans authentification. À appeler en premier : un client doit pouvoir vérifier
la version avant de présenter un code.

### Ouvrir une session

```
GET <base>/entrer?code=123456
→ {"ok":true,"cle":"a1b2c3…","api":1}
→ {"ok":false}          (code refusé, réponse retardée d'une seconde)
```

La clé rendue vaut **trois heures**. Elle accompagne toutes les requêtes
suivantes sous le paramètre `cle`. Une clé expirée ou inconnue donne un
**403** avec `{"ok":false,"raison":"session"}` — le client doit alors
redemander le code.

Si le pont vers l'application n'est pas monté — cela arrive le temps du
démarrage — les routes répondent `{"ok":false,"raison":"pasPret"}`.

---

## 3. Lire

### État courant

```
GET <base>/etat?cle=…
```

```json
{
  "station": "F4IOZ", "grille": "JN18FS",
  "sat": "ISS",
  "az": 226.4, "el": -59.0,
  "rx": 437801100, "tx": 145989000,
  "antaz": 231.0, "antel": 2.0,
  "aos": 1790254285126, "los": 1790254885126,
  "elmax": 41.0, "now": 1790254200000,
  "ptt": false, "son": true,
  "heure": "09:21:31 UTC",
  "trace": [[118.0, 1.2], [126.0, 8.4]],
  "qso": [{"h": "09:18", "c": "F5RRS", "l": "JN36EB"}],
  "img": 3
}
```

Les fréquences sont en **hertz**, corrigées du Doppler. `aos` et `los` sont des
millisecondes depuis 1970 : à comparer à `now`, l'heure du téléphone, pour
obtenir un compte à rebours juste même si l'horloge du PC dérive. `antaz` et
`antel` valent `null` quand aucune boussole n'est branchée. `trace` est la
trajectoire du passage, en paires azimut/élévation.

### Satellites suivis

```
GET <base>/sats?cle=…
→ ["ISS","SO-50","JO-97"]
```

### Propositions d'indicatif

```
GET <base>/propose?cle=…&q=F5R
→ [{"c":"F5RRS","l":"JN36EB","n":"Jean Dupont","q":4}]
```

Trois au maximum, classées par fréquence de contact, récence, et bonus si
l'indicatif a déjà été travaillé sur le satellite en cours. Rien n'est proposé
sous deux caractères. `q` est le nombre de contacts déjà faits.

### Fiche QRZ

```
GET <base>/qrz?cle=…&call=F5RRS
→ {"ok":true,"c":"F5RRS","l":"JN36EB","n":"Jean Dupont",
   "f":"Jean","v":"Quimper","p":"France","e":""}
```

`f` est le prénom seul, `v` la ville, `p` le pays, `e` l'erreur éventuelle.
Demande un abonnement XML QRZ configuré dans l'application. **Le quota est
celui de l'abonnement** : un client qui interroge à chaque frappe l'épuisera.
SatMe met les réponses en cache, mais le client doit tout de même espacer ses
demandes.

---

## 4. Agir

### Enregistrer un contact

```
GET <base>/qso?cle=…&call=F5RRS&loc=JN36EB&rse=59&rsr=59
→ {"ok":true}
```

`loc`, `rse` et `rsr` sont facultatifs ; les RST valent 59 par défaut. Le
contact est rangé sous le satellite actuellement suivi : si aucun n'est
sélectionné, la réponse est `{"ok":false}`.

### Enregistrement du passage

```
GET <base>/rec?cle=…&on=1      (1 démarre, 0 arrête)
→ {"ok":true}
```

### Changer de satellite

```
GET <base>/sat?cle=…&nom=SO-50
→ {"ok":true}
→ {"ok":false}    (nom inconnu des satellites suivis)
```

Le nom doit correspondre à l'un de ceux rendus par `/sats`, casse indifférente.

### Planches SSTV

La page `<base>/planche` (servie sans code ; ses requêtes demandent `cle`)
met en planche les images SSTV reçues par le téléphone. Ses routes :

```
GET  <base>/planche/liste?cle=…            → {"modeles":[{"id","nom"}…],"indicatif","nom"}
GET  <base>/planche/modele?cle=…&id=…      → {"texte":"planche=1\n…","ratio","fond","recues","manquent":[…],
                                               "locator","dates","titre"}
POST <base>/planche/enregistre?cle=…&id=…  (corps : le texte du modèle)  → {"ok":true}
GET  <base>/planche/nouveau?cle=…&type=grille|tour                       → {"ok":true,"id":"…"}
POST <base>/planche/importe?cle=…&nom=…    (corps : un JPEG ou un PNG, 25 Mo au plus) → {"ok":true,"id","cases"}
GET  <base>/planche/remplit?cle=…&id=…&sat=ISS                           → {"ok":true}
GET  <base>/planche/galerie?cle=…          → [{"f","s","m","t","c","d"}…]   (fichier, satellite, mode, heure, complète, en direct)
GET  <base>/planche/vignette?cle=…&f=…&w=320                             → image/jpeg
GET  <base>/planche/apercu?cle=…&id=…&w=1400[&ind&nom&loc&dates]         → image/jpeg
GET  <base>/planche/exporte?cle=…&id=…[&ind&nom&loc&dates]               → image/png (pièce jointe)
```

Le modèle voyage sous la forme texte où le téléphone le garde (`planche=1`,
`case=x;y;w;h`, `texte=…`, `image=case;fichier`, positions en fractions de la
planche). Un modèle renvoyé garde son propre fond : le client ne peut pas le
faire pointer vers un autre fichier. `vignette` ne sert que les images de la
galerie. L'aperçu et la planche sont dessinés par le téléphone, avec le dessin
de son propre écran.

---

## 5. Ce que l'API ne fait pas

C'est délibéré, et la liste est aussi importante que le reste.

**Elle ne pilote pas le poste.** Ni fréquence, ni PTT, ni mode. L'émission
engage la responsabilité de l'opérateur devant sa licence, et elle ne doit pas
partir d'une requête HTTP sur un réseau local partagé.

**Elle ne modifie pas les réglages** de l'application (seulement le nom écrit
sur les planches SSTV), ni le carnet au-delà de l'ajout d'un contact. Rien ne s'efface depuis le réseau.

**Elle n'expose pas le flux audio du poste de commande.** Le son passe par la
page du public — `/d/<jeton>/son.pcm`, PCM 16 bits mono à 22 050 Hz — et
seulement pendant un enregistrement de passage.

La surface exposée tient dans deux fichiers, `demo/PontCommande.kt` et, pour
les planches SSTV, `demo/PlancheWeb.kt` : relisibles d'un coup. Une surface qu'on peut lire est une surface
qu'on peut défendre.

---

## 6. Écrire un client

Rien d'autre qu'un client HTTP n'est nécessaire. En Python :

```python
import requests

BASE = "http://192.168.1.42:8080/c/nkq7wz3d5f"

assert requests.get(f"{BASE}/api").json()["api"] == 1
cle = requests.get(f"{BASE}/entrer", params={"code": "123456"}).json()["cle"]

etat = requests.get(f"{BASE}/etat", params={"cle": cle}).json()
print(etat["sat"], etat["az"], etat["el"])

requests.get(f"{BASE}/qso", params={"cle": cle, "call": "F5RRS",
                                    "loc": "JN36EB"})
```

**Trois conseils tirés de l'écriture de la page web.**

Rafraîchir l'état **une fois par seconde** suffit : la télémétrie n'est
calculée qu'à cette cadence dans l'application, et interroger plus vite ne
donne rien de plus frais.

Traiter le **403** comme un retour à l'écran de code, pas comme une panne.

Ne jamais reprendre le curseur de l'opérateur pendant qu'il tape. Pendant un
passage, chaque geste rendu à la souris est un contact perdu.

---

*Version 1 du contrat, figée à SatMe 20.53. Les évolutions compatibles
s'ajouteront sans changer ce numéro.*
