// ui.js — utilidades compartidas: sesión (JWT), cliente de la API, barra lateral, formatos,
// notificaciones y gráficos SVG sin librerías externas.
(function () {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  // Escapa texto antes de insertarlo como HTML (ISO 25010 Seguridad → Integridad: evita XSS).
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---- Formatos (en-US como en los mockups: 18,942.6 · $212.40) ----
  const nf = (d) => new Intl.NumberFormat('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
  const num = (x, d = 2) => (x == null || !Number.isFinite(x) ? '—' : nf(d).format(x).replace('-', '−'));
  const pct = (x, d = 2) => (x == null || !Number.isFinite(x) ? '—' : (x > 0 ? '+' : '') + num(x, d) + '%');
  const usd = (x, d = 2) => (x == null ? '—' : '$' + num(x, d));
  const tono = (x) => (x > 0 ? 'up' : x < 0 ? 'down' : '');
  const fecha = (iso) => new Date(String(iso).slice(0, 10) + 'T12:00:00').toLocaleDateString('es-CO', { day: '2-digit', month: 'short', year: 'numeric' });

  // ---- Preferencias locales (solo UI; los datos del usuario viven en el servidor) ----
  const store = {
    get(k, def) { try { const v = localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch (e) { return def; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* sin almacenamiento: no es crítico */ } }
  };

  // ---- Sesión: JWT HS256 de 120 min emitido por la API (RNF-03). sessionStorage se borra al cerrar la pestaña. ----
  const CLAVE = 'asimetria.sesion';
  const session = {
    get() {
      try {
        const s = JSON.parse(sessionStorage.getItem(CLAVE));
        if (s && s.exp > Date.now()) return s;
      } catch (e) { /* sesión corrupta → inexistente */ }
      return null;
    },
    start(t) {
      const s = { token: t.access_token, exp: Date.parse(t.expira), usuario: t.usuario };
      sessionStorage.setItem(CLAVE, JSON.stringify(s));
      return s;
    },
    update(usuario) {
      const s = session.get();
      if (s) { s.usuario = usuario; sessionStorage.setItem(CLAVE, JSON.stringify(s)); }
    },
    end() { sessionStorage.removeItem(CLAVE); }
  };
  const userStore = {
    get: (k, def) => store.get('asimetria.u.' + (session.get()?.usuario.id ?? 'anon') + '.' + k, def),
    set: (k, v) => store.set('asimetria.u.' + (session.get()?.usuario.id ?? 'anon') + '.' + k, v)
  };

  const paginaActual = () => location.pathname.split('/').pop() + location.search;
  const irALogin = () => location.replace('auth.html?next=' + encodeURIComponent(paginaActual()));

  // ---- Cliente de la API REST /api/v1 ----
  class ErrorApi extends Error {
    constructor(msg, status) { super(msg); this.status = status; }
  }
  async function api(ruta, { method = 'GET', body, publico = false } = {}) {
    const headers = { Accept: 'application/json' };
    const s = session.get();
    if (s && !publico) headers.Authorization = 'Bearer ' + s.token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let r;
    try {
      r = await fetch('/api/v1' + ruta, { method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(10000) }); // nunca dejar la vista "cargando" indefinidamente
    } catch (e) {
      throw new ErrorApi('No fue posible conectar con el servidor. Inténtalo de nuevo.', 0);
    }
    if (r.status === 204) return null;
    if (r.status >= 502) throw new ErrorApi('El servidor no está disponible en este momento.', r.status); // proxy sin API
    if (r.status === 429) throw new ErrorApi('Demasiadas solicitudes seguidas. Espera unos segundos e inténtalo de nuevo.', 429);
    const datos = await r.json().catch(() => ({}));
    if (r.ok) return datos;
    if (r.status === 401 && document.body.hasAttribute('data-protected')) { session.end(); irALogin(); }
    const d = datos.detail;
    const msg = Array.isArray(d) ? d.map((x) => String(x.msg).replace(/^Value error, /, '')).join('. ') : d || 'Error ' + r.status;
    throw new ErrorApi(msg, r.status);
  }

  // ---- Notificaciones accesibles (aria-live) ----
  function toast(msg) {
    let zona = $('.toasts');
    if (!zona) {
      zona = document.createElement('div');
      zona.className = 'toasts'; zona.setAttribute('role', 'status'); zona.setAttribute('aria-live', 'polite');
      document.body.append(zona);
    }
    const t = document.createElement('div');
    t.className = 'toast'; t.textContent = msg;
    zona.append(t);
    setTimeout(() => t.remove(), 5000);
  }

  // Polling que se pausa con la pestaña oculta (Eficiencia → Utilización de recursos).
  function poll(fn, ms) {
    fn();
    document.addEventListener('visibilitychange', () => { if (!document.hidden) fn(); }); // al volver, datos al instante
    return setInterval(() => { if (!document.hidden) fn(); }, ms);
  }

  const perfilTexto = (p) => ({ CONSERVADOR: 'Conservador', MODERADO: 'Moderado', AGRESIVO: 'Agresivo' }[p] || 'Sin perfil');
  const iniciales = (n) => String(n || '?').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  const param = (k) => new URLSearchParams(location.search).get(k);

  // ---- Iconos (SVG en línea, decorativos) ----
  const ICONOS = {
    home: '<path d="M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z"/>',
    trend: '<path d="m3 17 6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
    bars: '<path d="M5 20V10M12 20V4M19 20v-7"/>',
    wallet: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 10h18"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10 21h4"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1"/><rect x="13" y="4" width="7" height="7" rx="1"/><rect x="4" y="13" width="7" height="7" rx="1"/><rect x="13" y="13" width="7" height="7" rx="1"/>'
  };
  const icon = (n) => '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + ICONOS[n] + '</svg>';

  // ---- Barra lateral única para todas las vistas internas ----
  const NAV = [
    ['index.html', 'Inicio', 'home'], ['mercados.html', 'Mercados', 'trend'], ['estrategias.html', 'Estrategias', 'bars'],
    ['portafolio.html', 'Portafolio', 'wallet'], ['alertas.html', 'Alertas', 'bell']
  ];
  function renderSidebar() {
    const aside = $('[data-sidebar]');
    if (!aside) return;
    const actual = document.body.dataset.nav;
    const u = session.get()?.usuario || {};
    const li = ([href, txt, ic]) => '<li><a href="' + href + '"' + (href === actual ? ' aria-current="page"' : '') + '>' + icon(ic) + txt + '</a></li>';
    aside.innerHTML =
      '<a class="brand" href="index.html"><span class="logo" aria-hidden="true">A</span>Asimetría</a>' +
      '<button class="btn btn-icon menu-toggle" type="button" aria-expanded="false" aria-controls="menu-app" aria-label="Abrir menú">' + icon('menu') + '</button>' +
      '<nav id="menu-app" aria-label="Aplicación"><ul class="side-nav">' + NAV.map(li).join('') + '</ul><hr class="side-sep">' +
      '<ul class="side-nav">' + li(['configuracion.html', 'Configuración', 'gear']) + '</ul></nav>' +
      '<a class="user-card" href="configuracion.html"><span class="avatar" aria-hidden="true">' + esc(iniciales(u.nombre)) + '</span>' +
      '<span><strong>' + esc(u.nombre) + '</strong><span id="perfil-lateral">Perfil ' + esc(perfilTexto(u.perfil_riesgo).toLowerCase()) + '</span></span></a>';
    const btn = $('.menu-toggle', aside);
    btn.addEventListener('click', () => {
      const abierto = aside.classList.toggle('open');
      btn.setAttribute('aria-expanded', abierto);
      btn.setAttribute('aria-label', abierto ? 'Cerrar menú' : 'Abrir menú');
    });
  }

  // ---- Vistas protegidas: sin sesión válida se va al login (la API respondería 401) ----
  function guard() {
    if (!document.body.hasAttribute('data-protected') || session.get()) return true;
    irALogin();
    return false;
  }

  // ---- Gráficos SVG (0 KB de librerías) ----
  const W = 600, H = 240;
  let uid = 0;
  function lineChart(svg, series, opts = {}) {
    const todos = series.flatMap((s) => s.values);
    if (!todos.length) { svg.innerHTML = ''; return; }
    let min = Math.min(...todos), max = Math.max(...todos);
    if (min === max) { min -= 1; max += 1; }
    const pad = 8;
    const y = (v) => pad + (1 - (v - min) / (max - min)) * (H - 2 * pad);
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('preserveAspectRatio', 'none');
    let out = '';
    if (opts.base != null && opts.base > min && opts.base < max) {
      out += '<line x1="0" x2="' + W + '" y1="' + y(opts.base) + '" y2="' + y(opts.base) + '" style="stroke:var(--border)" stroke-dasharray="4 4" vector-effect="non-scaling-stroke"/>';
    }
    for (const s of series) {
      const n = s.values.length;
      const pts = s.values.map((v, i) => ((i / Math.max(n - 1, 1)) * W).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
      if (s.area) {
        const id = 'grad' + ++uid;
        out += '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" style="stop-color:' + s.color + ';stop-opacity:.35"/><stop offset="1" style="stop-color:' + s.color + ';stop-opacity:0"/></linearGradient></defs>' +
          '<polygon points="0,' + H + ' ' + pts + ' ' + W + ',' + H + '" fill="url(#' + id + ')"/>';
      }
      out += '<polyline points="' + pts + '" fill="none" style="stroke:' + s.color + '" stroke-width="' + (s.width || 2) + '" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>';
    }
    svg.innerHTML = out;
  }

  // Dispersión de retornos diarios con las rectas de beta alcista (derecha) y bajista (izquierda).
  function scatter(svg, puntos, betaUp, betaDown) {
    const SW = 300, SH = 200;
    const m = Math.max(...puntos.flat().map(Math.abs)) || 1;
    const px = (v) => SW / 2 + (v / m) * (SW / 2 - 8), py = (v) => SH / 2 - (v / m) * (SH / 2 - 8);
    svg.setAttribute('viewBox', '0 0 ' + SW + ' ' + SH);
    let out = '<line x1="0" x2="' + SW + '" y1="' + SH / 2 + '" y2="' + SH / 2 + '" style="stroke:var(--border)"/><line y1="0" y2="' + SH + '" x1="' + SW / 2 + '" x2="' + SW / 2 + '" style="stroke:var(--border)"/>';
    puntos.forEach(([x, yv]) => { out += '<circle cx="' + px(x).toFixed(1) + '" cy="' + py(yv).toFixed(1) + '" r="2.4" style="fill:var(--primary)" opacity=".75"/>'; });
    const lim = m / Math.max(betaUp, betaDown, 1);
    out += '<line x1="' + px(-lim) + '" y1="' + py(-lim * betaDown) + '" x2="' + px(0) + '" y2="' + py(0) + '" style="stroke:var(--pink)" stroke-width="2"/>';
    out += '<line x1="' + px(0) + '" y1="' + py(0) + '" x2="' + px(lim) + '" y2="' + py(lim * betaUp) + '" style="stroke:var(--pink)" stroke-width="2"/>';
    svg.innerHTML = out;
  }

  function donut(el, partes) {
    let acc = 0;
    el.style.background = 'conic-gradient(' + partes.map((p) => p.color + ' ' + acc + '% ' + (acc += p.pct) + '%').join(',') + ')';
  }

  const ok = guard();
  if (ok) renderSidebar();

  window.UI = {
    ok, $, $$, esc, num, pct, usd, tono, fecha, store, session, userStore, api, ErrorApi, toast, poll,
    perfilTexto, iniciales, param, icon, lineChart, scatter, donut
  };
})();
