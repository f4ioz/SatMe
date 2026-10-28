/*
 * F4IOZ site — the shell: the phone's drawer (Échap, the veil, aria-expanded;
 * without JavaScript the menu is simply unfolded), the light or dark theme
 * (kept in this browser), the UTC clocks of the bar and the rail.
 */
(function () {
  const root = document.documentElement;
  const btn = document.querySelector('.shell-menu');
  const rail = document.getElementById('site-rail');
  const scrim = document.querySelector('.rail-scrim');
  function ouvre(o) {
    root.classList.toggle('nav-open', o);
    if (btn) { btn.setAttribute('aria-expanded', o ? 'true' : 'false'); btn.setAttribute('aria-label', o ? 'Fermer le menu' : 'Ouvrir le menu'); }
    if (scrim) scrim.hidden = !o;
    if (o && rail) { const a = rail.querySelector('a'); if (a) a.focus(); }
  }
  if (btn) btn.addEventListener('click', () => ouvre(!root.classList.contains('nav-open')));
  if (scrim) scrim.addEventListener('click', () => { ouvre(false); if (btn) btn.focus(); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && root.classList.contains('nav-open')) { ouvre(false); if (btn) btn.focus(); } });

  const theme = document.getElementById('theme-toggle');
  function icone() { const i = theme && theme.querySelector('.theme-icon'); if (i) i.textContent = root.getAttribute('data-theme') === 'light' ? '☾' : '☀'; }
  if (theme) theme.addEventListener('click', () => {
    const t = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    root.setAttribute('data-theme', t);
    try { localStorage.setItem('theme', t); } catch (e) {}
    icone();
  });
  icone();

  // A form that asks first (the admin page's « Supprimer »).
  document.addEventListener('submit', e => { const m = e.target.getAttribute && e.target.getAttribute('data-confirmer'); if (m && !confirm(m)) e.preventDefault(); });

  const p2 = n => (n < 10 ? '0' : '') + n;
  // The station's own time (its time zone, given by the page), whatever the visitor's.
  const locales = [];
  document.querySelectorAll('[data-heure-locale]').forEach(c => { try { locales.push([c, new Intl.DateTimeFormat('fr-FR', { timeZone: c.getAttribute('data-heure-locale'), hour: '2-digit', minute: '2-digit' })]); } catch (e) {} });
  function tic() {
    const d = new Date(), hm = p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()), hms = hm + ':' + p2(d.getUTCSeconds());
    document.querySelectorAll('[data-utc-clock]').forEach(c => { c.textContent = c.getAttribute('data-utc-clock') === 'nu' ? hms : (c.closest('.rail') ? hms : hm) + ' UTC'; });
    locales.forEach(l => { l[0].textContent = l[1].format(d); });
  }
  tic(); setInterval(tic, 1000);
})();
