/* F4IOZ site — the Orbites tab opened as SatMe-PC opens it; `?theme=vitrine&tableau=1` straight to a dashboard. */
(function () {
  const p = new URLSearchParams(location.search);
  if (p.get('theme') && typeof obTheme === 'function') obTheme(p.get('theme'));
  Orbites.montre();
  if (p.get('tableau') === '1') setTimeout(() => obTableau(true), 400);
})();
