/* F4IOZ site — the Sked tab: « my » station chosen here (kept in this browser), the windows asked for at once when the address says so. */
(async function () {
  const m = g('site-moi'), ok = l => /^[A-R]{2}[0-9]{2}([A-X]{2})?$/.test(l);
  if (m) {
    m.value = E.locator;
    m.onchange = () => {
      const l = m.value.trim().toUpperCase();
      if (!ok(l)) { m.setCustomValidity('Locator de 4 ou 6 caractères (JN18FS)'); m.reportValidity(); return; }
      m.setCustomValidity(''); m.value = l; E.locator = l;
      try { localStorage.setItem('site-loc', l); } catch (x) {}
      if (v('k-loc')) calculeSked();
    };
  }
  await prepareSked();
  const p = new URLSearchParams(location.search);
  const sat = p.get('sat') || '25544';
  if ([...g('k-sat').options].some(o => o.value === sat)) g('k-sat').value = sat;
  if (p.get('loc') && ok(p.get('loc').toUpperCase())) { g('k-loc').value = p.get('loc').toUpperCase(); calculeSked(); }
  majVueSked();
})();
