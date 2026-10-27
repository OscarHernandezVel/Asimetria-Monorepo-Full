// Estrategias: recomendaciones del servidor (HU-04) + tabla comparativa de métricas (HU-10).
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, $$, esc, num, pct, tono, session, perfilTexto, cartera, toast } = UI;
  const POR_PAGINA = 5;
  const MARGEN = { CONSERVADOR: 20, MODERADO: 35 };
  const perfil = session.get().usuario.perfil_riesgo;
  let datos = [];
  const tabla = { col: 'volatilidad', dir: -1, pagina: 0, q: '' };

  $('#chip-perfil').textContent = 'Perfil ' + perfilTexto(perfil).toLowerCase() +
    (perfil ? ' · volatilidad ≤ ' + (MARGEN[perfil] ? MARGEN[perfil] + '%' : 'sin límite') : '');

  // ---- Recomendaciones (GET /recommendations/, cacheadas en Redis) ----
  let espera;
  function filtros() {
    const f = $('#form-filtros');
    return { vol_max: f.volMax.value, retorno_min: f.retornoMin.value, ratio_min: f.ratioMin.value };
  }
  async function pintarRecomendaciones() {
    const estado = $('#reco-estado'), lista = $('#reco-lista');
    let reco;
    try {
      reco = await Market.recomendaciones(filtros());
    } catch (ex) {
      lista.innerHTML = '';
      estado.innerHTML = ex.status === 409 // HU-04 CA-5
        ? '<div class="notice notice-warn">' + esc(ex.message) + ' <a class="btn btn-sm btn-primary" href="configuracion.html#quiz">Responder quiz</a></div>'
        : '<div class="notice notice-error" role="alert">' + esc(ex.message) + '</div>';
      return;
    }
    if (!reco.length) { lista.innerHTML = ''; estado.innerHTML = '<p class="empty">No se encontraron empresas con esos criterios.</p>'; return; } // CA-4
    estado.innerHTML = '<p class="small muted">' + reco.length + ' empresas ordenadas por retorno ajustado a riesgo y asimetría favorable (β↑/β↓).</p>';
    lista.innerHTML = reco.map((d, i) => {
      const e = Market.info(d.ticker);
      return '<article class="card reco"><div class="spread"><div class="asset"><span class="avatar" style="--c:' + e.color + '" aria-hidden="true">' + esc(e.corto) + '</span>' +
        '<div><strong>' + (i + 1) + '. ' + esc(d.nombre) + '</strong><span>' + esc(d.ticker) + ' · ' + esc(d.sector) + '</span></div></div>' +
        '<span class="chip ' + (d.ratio_asimetria > 1 ? 'chip-up' : 'chip-down') + '">Asimetría ' + num(d.ratio_asimetria) + '×</span></div>' +
        '<div class="metrics"><div class="stat"><span class="label">Retorno esp.</span><span class="value ' + tono(d.retorno_esperado) + '">' + pct(d.retorno_esperado, 1) + '</span></div>' +
        '<div class="stat"><span class="label">Volatilidad</span><span class="value">' + num(d.volatilidad, 1) + '%</span></div>' +
        '<div class="stat"><span class="label">β↑ / β↓</span><span class="value">' + num(d.beta_alcista) + ' / ' + num(d.beta_bajista) + '</span></div></div>' +
        '<div class="row"><a class="btn btn-sm" href="analisis.html?t=' + encodeURIComponent(d.ticker) + '">Ver análisis</a>' +
        '<button class="btn btn-sm" type="button" data-watch="' + esc(d.ticker) + '">+ Watchlist</button></div></article>';
    }).join('');
  }
  $('#form-filtros').addEventListener('input', () => {
    $('#o-vol').textContent = $('#f-vol').value + '%';
    clearTimeout(espera);
    espera = setTimeout(pintarRecomendaciones, 250); // debounce: una petición por pausa de escritura
  });
  $('#reco-lista').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-watch]'); if (!b) return;
    const nombre = Market.info(b.dataset.watch).nombre;
    try { toast((await cartera.agregar(b.dataset.watch)) ? nombre + ' se añadió a tu watchlist.' : nombre + ' ya está en tu lista de seguimiento.'); }
    catch (ex) { toast(ex.message); }
  });

  // ---- Tabla comparativa: ordenar, paginar, buscar (HU-10 CA-2) ----
  function pintarTabla() {
    const filas = datos
      .filter((d) => (d.nombre + ' ' + d.ticker).toLowerCase().includes(tabla.q))
      .sort((a, b) => {
        const x = a[tabla.col], y = b[tabla.col];
        if (x == null) return 1; if (y == null) return -1; // N/D siempre al final
        return (typeof x === 'string' ? x.localeCompare(y, 'es') : x - y) * tabla.dir;
      });
    const paginas = Math.max(1, Math.ceil(filas.length / POR_PAGINA));
    tabla.pagina = Math.min(tabla.pagina, paginas - 1);
    const vis = filas.slice(tabla.pagina * POR_PAGINA, (tabla.pagina + 1) * POR_PAGINA);
    const beta = (v, d) => (v == null ? '<span class="badge" title="' + esc(d.error) + '">N/D</span>' : num(v));
    $('#tabla-body').innerHTML = vis.length ? vis.map((d) => {
      const e = Market.info(d.ticker);
      return '<tr><td><div class="asset"><span class="avatar" style="--c:' + e.color + '" aria-hidden="true">' + esc(e.corto) + '</span><a href="analisis.html?t=' + encodeURIComponent(d.ticker) + '"><strong>' +
        esc(d.nombre) + '</strong><span>' + esc(d.ticker) + '</span></a></div></td>' +
        '<td class="mono ' + tono(d.variacion) + '">' + pct(d.variacion) + '</td>' +
        '<td class="mono">' + beta(d.beta_alcista, d) + '</td><td class="mono">' + beta(d.beta_bajista, d) + '</td>' +
        '<td class="mono">' + num(d.volatilidad, 1) + '% <span class="muted small">' + esc(d.nivel_volatilidad) + '</span></td>' +
        '<td class="mono">' + (d.dividendos == null ? '—' : num(d.dividendos, 1) + '%') + '</td>' +
        '<td class="mono ' + tono(d.retorno_esperado) + '">' + pct(d.retorno_esperado, 1) + '</td></tr>';
    }).join('') : '<tr><td colspan="7" class="empty">No se encontraron resultados</td></tr>'; // HU-10 CA-4
    $('#pag-info').textContent = 'Página ' + (tabla.pagina + 1) + ' de ' + paginas + ' · ' + filas.length + ' empresas';
    $('#pag-prev').disabled = tabla.pagina === 0;
    $('#pag-next').disabled = tabla.pagina >= paginas - 1;
  }
  $$('#tabla-metricas th button').forEach((b) => b.addEventListener('click', () => {
    const col = b.dataset.col;
    tabla.dir = tabla.col === col ? -tabla.dir : (col === 'nombre' ? 1 : -1);
    tabla.col = col;
    $$('#tabla-metricas th').forEach((th) => th.setAttribute('aria-sort', 'none'));
    b.parentElement.setAttribute('aria-sort', tabla.dir > 0 ? 'ascending' : 'descending');
    pintarTabla();
  }));
  $('#t-buscar').addEventListener('input', (e) => { tabla.q = e.target.value.trim().toLowerCase(); tabla.pagina = 0; pintarTabla(); });
  $('#pag-prev').addEventListener('click', () => { tabla.pagina--; pintarTabla(); });
  $('#pag-next').addEventListener('click', () => { tabla.pagina++; pintarTabla(); });

  // ---- Carga con manejo de fallo y reintento (HU-10 CA-5) ----
  async function cargar() {
    $('#tabla-estado').innerHTML = '<p class="small muted">Cargando métricas…</p>';
    try {
      await Market.empresas();
      const [ms] = await Promise.all([Market.metricas(true), Market.cotizaciones()]);
      datos = ms.map((m) => ({ ...m, variacion: Market.cotizacion(m.ticker).variacion }));
      $('#tabla-estado').innerHTML = '';
    } catch (err) {
      datos = [];
      $('#tabla-estado').innerHTML = '<div class="notice notice-error" role="alert">No fue posible cargar las métricas: ' + esc(err.message) +
        ' <button class="btn btn-sm" type="button" id="btn-reintentar">Reintentar</button></div>';
      $('#btn-reintentar').addEventListener('click', cargar);
    }
    pintarTabla();
    if (datos.length) pintarRecomendaciones();
    else $('#reco-estado').innerHTML = '<p class="empty">Recomendaciones no disponibles mientras el servicio de métricas no responde.</p>';
  }
  cargar();
})();
