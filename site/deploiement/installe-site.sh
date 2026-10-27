#!/bin/sh
# Installe (ou met à jour) le site F4IOZ en Kotlin sur une Debian (LXC, VM) : à lancer en root depuis le
# dossier f4ioz-site-kt/ du paquet (f4ioz-site-kt.tar.gz). Ne touche ni à l'ancien site Python ni à nginx.
#
#   /opt/f4ioz-site-kt/f4ioz-site/   le programme (la version précédente gardée dans f4ioz-site.precedent/)
#   /etc/f4ioz-site-kt/site.conf     la configuration (créée depuis site.conf.exemple la première fois seulement)
#   /var/lib/f4ioz-site-kt/          les données (publications, caches) et le contenu (articles, albums)
#   f4ioz-site-kt.service            le service systemd, utilisateur f4ioz, 127.0.0.1:8001 par défaut
#
# Retour arrière : ./installe-site.sh --retour (remet la version précédente du programme et redémarre).
set -eu

ICI=$(cd "$(dirname "$0")" && pwd)
OPT=/opt/f4ioz-site-kt
ETC=/etc/f4ioz-site-kt
VAR=/var/lib/f4ioz-site-kt
SERVICE=f4ioz-site-kt.service

[ "$(id -u)" = 0 ] || { echo "À lancer en root (sudo $0)."; exit 1; }

if [ "${1:-}" = "--retour" ]; then
    [ -d "$OPT/f4ioz-site.precedent" ] || { echo "Pas de version précédente dans $OPT."; exit 1; }
    systemctl stop "$SERVICE" || true
    rm -rf "$OPT/f4ioz-site.annule"
    mv "$OPT/f4ioz-site" "$OPT/f4ioz-site.annule"
    mv "$OPT/f4ioz-site.precedent" "$OPT/f4ioz-site"
    systemctl start "$SERVICE"
    echo "Version précédente remise ; celle annulée est dans $OPT/f4ioz-site.annule."
    exit 0
fi

# Java 17 ou plus (21 de préférence) : sans interface graphique.
if ! command -v java >/dev/null 2>&1; then
    apt-get update
    apt-get install -y openjdk-21-jre-headless || apt-get install -y openjdk-17-jre-headless
fi
java -version 2>&1 | head -1

# L'utilisateur du service, sans connexion.
id f4ioz >/dev/null 2>&1 || useradd --system --home-dir "$VAR" --shell /usr/sbin/nologin f4ioz

# Le programme : la version en place gardée pour un retour arrière.
mkdir -p "$OPT"
if [ -d "$OPT/f4ioz-site" ]; then
    rm -rf "$OPT/f4ioz-site.precedent"
    cp -a "$OPT/f4ioz-site" "$OPT/f4ioz-site.precedent"
fi
rm -rf "$OPT/f4ioz-site.neuf"
cp -a "$ICI/f4ioz-site" "$OPT/f4ioz-site.neuf"
rm -rf "$OPT/f4ioz-site"
mv "$OPT/f4ioz-site.neuf" "$OPT/f4ioz-site"
chown -R root:root "$OPT"
chmod 755 "$OPT/f4ioz-site/bin/f4ioz-site"

# La configuration : jamais écrasée.
mkdir -p "$ETC"
if [ ! -f "$ETC/site.conf" ]; then
    cp "$ICI/site.conf.exemple" "$ETC/site.conf"
    echo "Configuration créée : $ETC/site.conf (à compléter : jeton, mot de passe, Wavelog)."
fi
chown root:f4ioz "$ETC" "$ETC/site.conf"
chmod 750 "$ETC"; chmod 640 "$ETC/site.conf"

# Les données et le contenu (l'exemple d'article seulement si le contenu est vide).
mkdir -p "$VAR/contenu/articles" "$VAR/contenu/pages" "$VAR/contenu/galeries" "$VAR/contenu/images"
if [ -z "$(ls -A "$VAR/contenu/articles")" ] && [ -d "$ICI/contenu-exemple/articles" ]; then
    cp -a "$ICI/contenu-exemple/articles/." "$VAR/contenu/articles/"
fi
chown -R f4ioz:f4ioz "$VAR"
chmod 750 "$VAR"

# Le service.
cp "$ICI/$SERVICE" "/etc/systemd/system/$SERVICE"
systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null
systemctl restart "$SERVICE"
sleep 3
systemctl --no-pager --lines=5 status "$SERVICE" || true
echo
echo "Site installé. Aperçu : ssh -L 8001:127.0.0.1:8001 <cette machine>, puis http://127.0.0.1:8001/"
echo "Jeton de publication : $OPT/f4ioz-site/bin/f4ioz-site jeton ; mot de passe admin : $OPT/f4ioz-site/bin/f4ioz-site mot-de-passe"
