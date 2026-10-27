// Portafolio: backtesting en el servidor (POST /simulaciones, HU-05) + portafolio teórico (HU-08).
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, esc, num, pct, usd, tono, lineChart, toast, param, cartera, fecha, api } = UI;
  const f = $('#form-bt');
  let lista = [];
  const opciones = (sel) => Market.todas().filter((e) => !e.es_indice)
    .map((e) => '<option value="' + esc(e.ticker) + '"' + (e.ticker === sel ? ' selected' : '') + '>' + esc(e.nombre) + ' (' + esc(e.ticker) + ')</option>').join('');

  // ---- Backtesting ----
  async function simular() {
    const err = $('#bt-error'); err.textContent = '';
    f.monto.removeAttribute('aria-invalid');
    try {
      const r = await api('/simulaciones', { method: 'POST',
        body: { ticker: f.ticker.value, monto: Number(f.monto.value), fecha_inicio: f.inicio.value, fecha_fin: f.fin.value } });
      mostrar(r);
    } catch (ex) { // HU-05 CA-4: el servidor valida monto y periodo
      err.textContent = ex.message;
      if (/monto/i.test(ex.message)) { f.monto.setAttribute('aria-invalid', 'true'); f.monto.focus(); }
    }
  }
  function mostrar(r) {
    const s = r.serie;
    $('#res-sub').textContent = Market.info(r.ticker).nombre + ' · ' + fecha(r.fecha_inicio) + ' → ' + fecha(r.fecha_fin) + ' · ' + (s.length - 1) + ' sesiones';
    $('#r-final').textContent = usd(r.valor_final);
    $('#r-ret').textContent = pct(r.retorno); $('#r-ret').className = 'value ' + tono(r.retorno);
    $('#r-ix').textContent = usd(r.valor_indice);
    const dif = r.valor_final - r.valor_indice;
    $('#r-dif').textContent = (dif >= 0 ? '+' : '−') + usd(Math.abs(dif)); $('#r-dif').className = 'value ' + tono(dif);
    lineChart($('#r-chart'), [{ values: s.map((x) => x.indice), color: 'var(--primary)' }, { values: s.map((x) => x.portafolio), color: 'var(--pink)' }], { base: r.monto });
    $('#r-tabla').innerHTML = s.map((x) => '<tr><td>' + fecha(x.fecha) + '</td><td class="mono">' + usd(x.portafolio) + '</td><td class="mono">' + usd(x.indice) + '</td></tr>').join('');
    pintarHistorial();
  }
  f.addEventListener('submit', (ev) => { ev.preventDefault(); simular(); });

  // Casos del documento (1.000 USD en el activo destacado): sesión con el índice ≈ +2 % y ≈ −2 %.
  document.querySelectorAll('[data-caso]').forEach((b) => b.addEventListener('click', async () => {
    try {
      const d = await Market.destacado();
      const a = await Market.analisis(d.ticker);
      const dia = a.desglose[b.dataset.caso].fecha, i = a.fechas.indexOf(dia);
      f.ticker.value = d.ticker; f.monto.value = 1000; f.inicio.value = a.fechas[i - 1]; f.fin.value = dia;
      simular();
    } catch (ex) { toast(ex.message); }
  }));

  async function pintarHistorial() {
    const h = await api('/simulaciones').catch(() => []);
    $('#hist-body').innerHTML = h.length ? h.slice(0, 5).map((x) => '<tr><td>' + new Date(x.fecha_ejecucion).toLocaleString('es-CO') + '</td><td>' + esc(x.ticker) + '</td><td>' +
      fecha(x.fecha_inicio) + ' → ' + fecha(x.fecha_fin) + '</td><td class="mono">' + usd(x.monto) + '</td><td class="mono ' + tono(x.valor_final - x.monto) + '">' + usd(x.valor_final) + '</td></tr>').join('')
      : '<tr><td colspan="5" class="empty">Aún no has ejecutado simulaciones.</td></tr>';
  }

  // ---- Portafolio teórico (CRUD sobre /watchlist) ----
  function pintarPortafolio() {
    const total = lista.reduce((s, x) => s + x.cantidad * (Market.cotizacion(x.ticker).precio || 0), 0);
    $('#port-body').innerHTML = lista.length ? lista.map((it) => {
      const e = Market.info(it.ticker), precio = Market.cotizacion(it.ticker).precio, valor = it.cantidad * (precio || 0);
      return '<tr><td><div class="asset"><span class="avatar" style="--c:' + e.color + '" aria-hidden="true">' + esc(e.corto) + '</span><div><strong>' + esc(it.nombre) + '</strong><span>' + esc(it.ticker) + '</span></div></div></td>' +
        '<td class="mono">' + usd(precio) + '</td>' +
        '<td><label class="sr-only" for="q-' + esc(it.ticker) + '">Cantidad de ' + esc(it.nombre) + '</label><input class="input qty" id="q-' + esc(it.ticker) + '" type="number" min="0" step="1" value="' + it.cantidad + '" data-qty="' + esc(it.ticker) + '"></td>' +
        '<td class="mono">' + usd(valor) + '</td><td class="mono">' + (total ? num((valor / total) * 100, 1) + '%' : '—') + '</td>' +
        '<td><button class="btn btn-sm btn-danger" type="button" data-quitar="' + esc(it.ticker) + '">Quitar</button></td></tr>';
    }).join('') : '<tr><td colspan="6" class="empty">Tu lista está vacía. Agrega una empresa para empezar.</td></tr>';
    $('#port-total').textContent = usd(total);
  }
  async function recargar() { lista = await cartera.lista(); pintarPortafolio(); }
  $('#port-body').addEventListener('change', async (e) => {
    const i = e.target.closest('[data-qty]'); if (!i) return;
    const q = Number(i.value);
    if (!Number.isInteger(q) || q < 0) { i.setAttribute('aria-invalid', 'true'); toast('La cantidad debe ser un número entero mayor o igual a 0.'); return; }
    try { await cartera.actualizar(i.dataset.qty, { cantidad: q }); await recargar(); } catch (ex) { toast(ex.message); }
  });
  $('#port-body').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-quitar]'); if (!b) return;
    const nombre = Market.info(b.dataset.quitar).nombre;
    if (!confirm('¿Quitar ' + nombre + ' de tu lista de seguimiento?')) return;
    try { await cartera.quitar(b.dataset.quitar); toast(nombre + ' se quitó de tu lista.'); await recargar(); } catch (ex) { toast(ex.message); }
  });
  $('#form-add').addEventListener('submit', async (e) => {
    e.preventDefault();
    const t = $('#add-activo').value, nombre = Market.info(t).nombre;
    try { toast((await cartera.agregar(t)) ? nombre + ' se agregó a tu lista.' : nombre + ' ya está en tu lista de seguimiento.'); await recargar(); } // HU-08 CA-4
    catch (ex) { toast(ex.message); }
  });

  (async function iniciar() {
    try {
      await Promise.all([Market.empresas(), Market.cotizaciones()]);
      const tSel = Market.todas().some((e) => e.ticker === param('t')) ? param('t') : null;
      f.ticker.innerHTML = opciones(tSel || Market.todas().find((e) => !e.es_indice).ticker);
      $('#add-activo').innerHTML = opciones();
      const hoy = new Date(), haceUnAnio = new Date(hoy - 365 * 864e5);
      f.inicio.value = haceUnAnio.toISOString().slice(0, 10); f.fin.value = hoy.toISOString().slice(0, 10);
      f.inicio.max = f.fin.max = f.fin.value;
      await Promise.all([recargar(), pintarHistorial()]);
    } catch (ex) { $('#bt-error').textContent = ex.message; }
  })();
})();
