<?php
// SatMe-PC « Publication web » — a minimal receiver, API v1 (docs/publication-web.md).
// SPDX-License-Identifier: GPL-2.0-or-later — a short example: copy it, adapt it.
//
// 1. Set TOKEN and DIR below; put this file on your site (PHP 7.4 or later).
// 2. In SatMe-PC: Réglages › Services en ligne › Envoyer vers un site web,
//    address https://your-site.example/recevoir.php, the same token, « Tester ».
// It stores each item as DIR/<type>/<year>/<id>.<ext> + <id>.json and keeps DIR/index.json
// (newest first): the same layout as SatMe-PC's « Dossier » target, read by index.html.

const TOKEN = 'change-me';                      // the same as in SatMe-PC; never leave it empty on a public site
const DIR = __DIR__ . '/../satme-donnees';       // best outside the web root; else see the docs (.htaccess)
const MAX = 200 * 1024 * 1024;                   // bytes; PHP's upload_max_filesize and post_max_size must allow it
const TYPES = ['sstv-image' => ['png', 'jpg', 'gif'], 'planche' => ['png', 'jpg'], 'rapport' => ['pdf'],
               'qso' => ['adi'], 'journal-video' => ['mp4', 'webm']];

function reponse(int $code, array $o): void {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($o, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') reponse(405, ['ok' => false, 'erreur' => 'POST attendu']);
$auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
if (TOKEN === '' || !hash_equals('Bearer ' . TOKEN, $auth)) reponse(401, ['ok' => false, 'erreur' => 'jeton refusé']);
$type = $_SERVER['HTTP_X_SATME_TYPE'] ?? '';
if ($type === 'ping') reponse(200, ['ok' => true, 'nom' => 'Mon site']);
// Not taken here (the live data, withdrawals, any kind not listed): refused, SatMe-PC says so.
if (!isset(TYPES[$type])) reponse(403, ['ok' => false, 'erreur' => "type non pris par ce site : $type"]);

$f = $_FILES['fichier'] ?? null;
if (!$f || $f['error'] !== UPLOAD_ERR_OK)
    reponse($f && in_array($f['error'], [UPLOAD_ERR_INI_SIZE, UPLOAD_ERR_FORM_SIZE]) ? 413 : 400, ['ok' => false, 'erreur' => 'fichier manquant']);
if ($f['size'] > MAX) reponse(413, ['ok' => false, 'erreur' => 'trop gros']);
$meta = json_decode($_POST['meta'] ?? '', true);
if (!is_array($meta)) reponse(400, ['ok' => false, 'erreur' => 'meta illisible']);
$ext = strtolower(pathinfo($f['name'], PATHINFO_EXTENSION));
if (!in_array($ext, TYPES[$type], true)) reponse(415, ['ok' => false, 'erreur' => "extension refusée : $ext"]);

// X-SatMe-Id: the same delivery tried again is not stored twice.
$id = preg_replace('/[^A-Za-z0-9-]/', '', $_SERVER['HTTP_X_SATME_ID'] ?? '') ?: bin2hex(random_bytes(8));
$annee = substr((string)($meta['utc'] ?? ''), 0, 4);
if (!ctype_digit($annee)) $annee = gmdate('Y');
$rel = "$type/$annee/$id";
if (is_file(DIR . "/$rel.json")) reponse(200, ['ok' => true, 'id' => $id, 'deja' => true]);
if (!is_dir(DIR . "/$type/$annee") && !mkdir(DIR . "/$type/$annee", 0775, true)) reponse(500, ['ok' => false, 'erreur' => 'dossier']);
if (!move_uploaded_file($f['tmp_name'], DIR . "/$rel.$ext")) reponse(500, ['ok' => false, 'erreur' => 'écriture']);
$meta['chemin'] = "$rel.$ext";
$meta['ajoute'] = gmdate('Y-m-d\TH:i:s\Z');
$json = JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES;
file_put_contents(DIR . "/$rel.json", json_encode($meta, $json));

// index.json, newest first; a newer version of the same item (its « cle ») replaces the line.
$fp = fopen(DIR . '/index.json', 'c+');
flock($fp, LOCK_EX);
$liste = json_decode(stream_get_contents($fp) ?: '{}', true)['elements'] ?? [];
$cle = $meta['cle'] ?? '';
$liste = array_values(array_filter($liste, fn($e) => !($cle !== '' && ($e['cle'] ?? '') === $cle && ($e['type'] ?? '') === $type)));
array_unshift($liste, $meta);
ftruncate($fp, 0);
rewind($fp);
fwrite($fp, json_encode(['version' => 1, 'maj' => $meta['ajoute'], 'elements' => array_slice($liste, 0, 500)], $json));
flock($fp, LOCK_UN);
fclose($fp);
reponse(201, ['ok' => true, 'id' => $id]);
