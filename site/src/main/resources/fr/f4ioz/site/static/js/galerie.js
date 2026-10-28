/*
 * F4IOZ site — the galleries' viewer. A thumbnail of a gallery
 * ([data-galerie] a[data-plein]) opens its picture over the page, in a
 * <dialog> (focus kept inside, Échap closes, the thumbnail gets the focus
 * back): its words (satellite, UTC time, mode), its page, the previous and
 * the next of the page by the arrows, Home and End, the buttons, or a swipe.
 * Without a script each thumbnail is a link to the picture's page.
 */
(function () {
  'use strict';
  const liens = Array.from(document.querySelectorAll('[data-galerie] a[data-plein]'));
  if (!liens.length || typeof HTMLDialogElement === 'undefined') return;
  const ico = d => '<svg class="ico" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="' + d + '"/></svg>';
  const dlg = document.createElement('dialog');
  dlg.className = 'visionneuse';
  dlg.setAttribute('aria-label', 'Visionneuse d’images');
  dlg.innerHTML = '<div class="vi-scene"><img class="vi-img" alt=""><span class="vi-att" hidden>Chargement…</span></div>' +
    '<button type="button" class="vi-btn vi-prec" aria-label="Image précédente">' + ico('M15 5l-7 7 7 7') + '</button>' +
    '<button type="button" class="vi-btn vi-suiv" aria-label="Image suivante">' + ico('M9 5l7 7-7 7') + '</button>' +
    '<div class="vi-barre"><p class="vi-legende"></p><span class="vi-compte num"></span><a class="vi-fiche">Fiche de l’image</a>' +
    '<button type="button" class="vi-btn vi-ferme" aria-label="Fermer">' + ico('M6 6l12 12M18 6L6 18') + '</button></div>';
  document.body.appendChild(dlg);
  const img = dlg.querySelector('.vi-img'), att = dlg.querySelector('.vi-att'), leg = dlg.querySelector('.vi-legende'), compte = dlg.querySelector('.vi-compte'), fiche = dlg.querySelector('.vi-fiche');
  let i = 0, origine = null;
  const precharge = k => { const a = liens[(k + liens.length) % liens.length]; if (a) { const im = new Image(); im.src = a.dataset.plein; } };
  function montre(k) {
    i = (k + liens.length) % liens.length;
    const a = liens[i], vignette = a.querySelector('img');
    // The thumbnail at once (already loaded), the picture itself when it comes.
    img.src = vignette ? vignette.currentSrc || vignette.src : '';
    img.alt = vignette ? vignette.alt : '';
    att.hidden = false;
    const plein = new Image();
    plein.onload = () => { if (liens[i] === a) { img.src = a.dataset.plein; att.hidden = true; } };
    plein.onerror = () => { att.hidden = true; };
    plein.src = a.dataset.plein;
    leg.textContent = a.dataset.legende || img.alt;
    compte.textContent = (i + 1) + ' / ' + liens.length;
    fiche.href = a.getAttribute('href');
    fiche.hidden = a.getAttribute('href') === a.dataset.plein;
    dlg.querySelector('.vi-prec').hidden = dlg.querySelector('.vi-suiv').hidden = liens.length < 2;
    precharge(i + 1); precharge(i - 1);
  }
  liens.forEach((a, k) => a.addEventListener('click', e => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault(); origine = a; montre(k); dlg.showModal(); dlg.querySelector('.vi-ferme').focus();
    document.documentElement.classList.add('vi-ouverte');
  }));
  dlg.addEventListener('close', () => { document.documentElement.classList.remove('vi-ouverte'); img.removeAttribute('src'); if (origine) origine.focus(); });
  dlg.querySelector('.vi-prec').addEventListener('click', () => montre(i - 1));
  dlg.querySelector('.vi-suiv').addEventListener('click', () => montre(i + 1));
  dlg.querySelector('.vi-ferme').addEventListener('click', () => dlg.close());
  // A click on the backdrop (outside the picture and the buttons) closes.
  dlg.addEventListener('click', e => { if (e.target === dlg || e.target.classList.contains('vi-scene')) dlg.close(); });
  dlg.addEventListener('keydown', e => {
    if (e.key === 'ArrowRight') { e.preventDefault(); montre(i + 1); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); montre(i - 1); }
    else if (e.key === 'Home') { e.preventDefault(); montre(0); }
    else if (e.key === 'End') { e.preventDefault(); montre(liens.length - 1); }
  });
  // A swipe: left for the next, right for the previous.
  let x0 = null, y0 = 0;
  dlg.addEventListener('pointerdown', e => { if (e.pointerType !== 'mouse') { x0 = e.clientX; y0 = e.clientY; } });
  dlg.addEventListener('pointerup', e => {
    if (x0 == null) return;
    const dx = e.clientX - x0, dy = e.clientY - y0; x0 = null;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.4) montre(i + (dx < 0 ? 1 : -1));
  });
})();
