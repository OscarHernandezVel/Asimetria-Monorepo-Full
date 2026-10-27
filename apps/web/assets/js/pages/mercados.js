// Mercados (HU-09): índice en vivo, watchlist, mayores movimientos, distribución y asimetría detectada.
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, $$, esc, num, pct, usd, tono, lineChart, donut, poll, toast, icon, cartera } = UI;

  $('#btn-alertas').innerHTML = icon('bell');
  $('#btn-modulos').innerHTML = icon('grid');

  let rango = 21, filtro = '', lista = [], serieIndice = [];

  // ---- Índice ----
  async function cargarIndice() {
    serieIndice = (await Market.precios(Market.INDICE, rango)).cierres;
    pintarIndice();
  }
  function pintarIndice() {
    const q = Market.cotizacion(Market.INDICE);
    if (q.precio == null) return;
    $('#indice-precio').textContent = num(q.precio, 1);
    const v = $('#indice-var');
    v.textContent = (q.variacion >= 0 ? '▲ ' : '▼ ') + pct(q.variacion) + ' hoy';
    v.className = 'small ' + tono(q.variacion);
    const s = serieIndice.slice();
    if (s.length) s[s.length - 1] = q.precio; // el último punto refleja el precio en vivo (CA-3)
    lineChart($('#indice-chart'), [{ values: s, color: 'var(--primary)', area: true }]);
  }
  $$('[data-rango]').forEach((b) => b.addEventListener('click', () => {
    $$('[data-rango]').forEach((x) => x.setAttribute('aria-pressed', x === b));
    rango = +b.dataset.rango;
    cargarIndice().catch((e) => toast(e.message));
  }));

  // ---- Watchlist ----
  function filaWatch(it) {
    const e = Market.info(it.ticker), m = Market.metrica(it.ticker) || {}, q = Market.cotizacion(it.ticker);
    return '<tr data-buscar="' + esc((it.nombre + ' ' + it.ticker + ' ' + it.sector).toLowerCase()) + '">' +
      '<td><div class="asset"><span class="avatar" style="--c:' + e.color + '" aria-hidden="true">' + esc(e.corto) + '</span>' +
      '<a href="analisis.html?t=' + encodeURIComponent(it.ticker) + '"><strong>' + esc(it.nombre) + '</strong><span>' + esc(it.ticker) + ' · ' + esc(it.sector) + '</span></a></div></td>' +
      '<td class="mono">' + usd(q.precio) + '</td>' +
      '<td class="mono ' + tono(q.variacion) + '">' + pct(q.variacion) + '</td>' +
      '<td>' + (m.beta == null ? '<span class="badge" title="' + esc(m.error) + '">N/D</span>' : '<span class="badge">' + num(m.beta) + '</span>') + '</td>' +
      '<td>' + esc(m.nivel_volatilidad || '—') + '</td>' +
      '<td><svg class="spark" data-spark="' + esc(it.ticker) + '" aria-hidden="true"></svg></td>' +
      '<td><button class="star" type="button" data-fav="' + esc(it.ticker) + '" aria-pressed="' + it.favorito + '" aria-label="Marcar ' + esc(it.nombre) + ' como favorito">' + (it.favorito ? '★' : '☆') + '</button></td></tr>';
  }
  function pintarWatchlist() {
    $('#watch-body').innerHTML = lista.map(filaWatch).join('');
    $$('[data-spark]').forEach((svg) => {
      const p = Market.metrica(svg.dataset.spark)?.tendencia || [];
      lineChart(svg, [{ values: p, color: p.at(-1) >= p[0] ? 'var(--green)' : 'var(--red)', width: 1.5 }]);
    });
    aplicarFiltro();
  }
  function aplicarFiltro() {
    let visibles = 0;
    $$('#watch-body tr').forEach((tr) => { const ok = tr.dataset.buscar.includes(filtro); tr.hidden = !ok; visibles += ok; });
    $('#watch-empty').hidden = visibles > 0;
    $('#watch-empty').textContent = lista.length ? 'No se encontraron resultados.' : 'Tu watchlist está vacía. Usa “Añadir activo”.';
  }
  $('#buscar').addEventListener('input', (e) => { filtro = e.target.value.trim().toLowerCase(); aplicarFiltro(); });
  $('#watch-body').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-fav]'); if (!b) return;
    const it = lista.find((x) => x.ticker === b.dataset.fav);
    try {
      Object.assign(it, await cartera.actualizar(it.ticker, { favorito: !it.favorito }));
      b.setAttribute('aria-pressed', it.favorito); b.textContent = it.favorito ? '★' : '☆';
    } catch (ex) { toast(ex.message); }
  });

  // ---- Panel derecho ----
  function pintarRail() {
    const top = Market.todas().filter((e) => !e.es_indice).map((e) => ({ e, q: Market.cotizacion(e.ticker) }))
      .filter((x) => x.q.variacion != null).sort((a, b) => Math.abs(b.q.variacion) - Math.abs(a.q.variacion)).slice(0, 4);
    $('#movers').innerHTML = top.map(({ e, q }) => {
      const i = Market.info(e.ticker);
      return '<li><span class="avatar" style="--c:' + i.color + '" aria-hidden="true">' + esc(i.corto) + '</span><div><strong>' + esc(e.nombre) +
        '</strong><span>' + esc(e.sector) + '</span></div><b class="' + tono(q.variacion) + '">' + pct(q.variacion) + '</b></li>';
    }).join('');
    const d = cartera.distribucion(lista);
    const colores = ['var(--primary)', 'var(--pink)', 'var(--violet)'];
    const partes = d.partes.slice(0, 2).concat(d.partes.length > 2 ? [{ sector: 'Otros', pct: d.partes.slice(2).reduce((s, p) => s + p.pct, 0) }] : []);
    donut($('#donut'), partes.length ? partes.map((p, i) => ({ pct: p.pct, color: colores[i] })) : [{ pct: 100, color: 'var(--border)' }]);
    $('#donut-total').textContent = '$' + num(d.total / 1000, 1) + 'K';
    $('#donut-legend').innerHTML = partes.length ? partes.map((p, i) => '<li style="--c:' + colores[i] + '">' + esc(p.sector) + ' ' + num(p.pct, 0) + '%</li>').join('')
      : '<li style="--c:var(--border)">Asigna cantidades en Portafolio</li>';
  }

  // Texto de asimetría a partir del análisis del activo destacado (días de ±2 % del índice).
  async function pintarAsimetria() {
    const d = await Market.destacado();
    const a = await Market.analisis(d.ticker);
    const up = a.desglose.alcista, down = a.desglose.bajista;
    $('#asim-texto').innerHTML = '<strong>' + esc(a.nombre) + '</strong> subió ' + pct(up.activo, 1) + ' mientras NASDAQ 100 subió ' +
      pct(up.indice, 1) + ', y amortiguó su caída a ' + pct(down.activo, 1) + ' cuando el índice cayó ' + pct(down.indice, 1) + '.';
    $('#asim-link').href = 'analisis.html?t=' + encodeURIComponent(d.ticker);
  }

  // ---- Diálogo "Añadir activo" ----
  const dlg = $('#dlg-anadir');
  $('#btn-anadir').addEventListener('click', () => {
    $('#sel-activo').innerHTML = Market.todas().filter((e) => !e.es_indice)
      .map((e) => '<option value="' + esc(e.ticker) + '">' + esc(e.nombre) + ' (' + esc(e.ticker) + ')</option>').join('');
    $('#anadir-error').textContent = '';
    dlg.showModal();
  });
  $('#form-anadir').addEventListener('submit', async (e) => {
    if (e.submitter?.value !== 'ok') return;
    e.preventDefault();
    const t = $('#sel-activo').value, nombre = Market.info(t).nombre;
    try {
      if (!(await cartera.agregar(t))) { $('#anadir-error').textContent = nombre + ' ya está en tu lista de seguimiento.'; return; }
      dlg.close();
      toast(nombre + ' se añadió a tu watchlist.');
      lista = await cartera.lista();
      pintarWatchlist(); pintarRail();
    } catch (ex) { $('#anadir-error').textContent = ex.message; }
  });

  // ---- Actualización en vivo: polling cada 5 s a /mercado/cotizaciones (Celery actualiza la caché) ----
  async function refrescar() {
    try {
      const { estado } = await Market.cotizaciones();
      const aviso = $('#aviso-stale');
      aviso.hidden = !estado.desactualizado;
      if (estado.desactualizado) {
        const hora = estado.ultima_actualizacion ? new Date(estado.ultima_actualizacion).toLocaleTimeString('es-CO') : 'último cierre';
        aviso.textContent = 'Datos desactualizados: el proveedor no responde. Se muestra el último dato conocido (' + hora + ').';
      }
      pintarIndice(); pintarWatchlist(); pintarRail();
    } catch (ex) {
      const aviso = $('#aviso-stale');
      aviso.hidden = false;
      aviso.textContent = 'Datos desactualizados: ' + ex.message + ' Se muestra el último dato conocido.';
    }
  }
  setInterval(() => {
    const t = Market.estado()?.ultima_actualizacion;
    $('#estado-act').textContent = t ? 'última actualización hace ' + Math.max(0, Math.round((Date.now() - Date.parse(t)) / 1000)) + 's' : 'esperando datos en vivo…';
  }, 1000);

  (async function iniciar() {
    try {
      await Market.empresas();
      [lista] = await Promise.all([cartera.lista(), Market.metricas(), Market.cotizaciones()]);
      await cargarIndice();
      pintarWatchlist(); pintarRail();
      pintarAsimetria().catch(() => { $('#asim-texto').textContent = 'Análisis no disponible en este momento.'; });
      poll(refrescar, 5000);
    } catch (ex) {
      $('#aviso-stale').hidden = false;
      $('#aviso-stale').textContent = ex.message;
    }
  })();
})();
