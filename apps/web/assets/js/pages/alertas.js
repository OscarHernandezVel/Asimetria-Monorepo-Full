// Alertas (HU-06): reglas en el servidor; el worker de Celery las evalúa cada 60 s y envía las notificaciones.
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, esc, num, pct, usd, api, toast, param, poll } = UI;
  const f = $('#form-alerta');

  f.addEventListener('change', (e) => {
    if (e.target.name !== 'tipo' && e.target.name !== 'ticker') return;
    const precio = f.tipo.value === 'PRECIO_LIMITE';
    $('#lbl-umbral').textContent = precio ? 'Avisar si el precio alcanza (USD)' : 'Avisar si varía más de (%)';
    $('#al-hint').textContent = precio ? 'Se dispara cuando el precio es mayor o igual al límite.' : 'Variación diaria absoluta (al alza o a la baja).';
    const actual = Market.cotizacion(f.ticker.value).precio;
    f.umbral.value = precio && actual ? Math.ceil(actual * 1.02) : 3;
  });

  // ---- Crear (CA-1) ----
  f.addEventListener('submit', async (e) => {
    e.preventDefault();
    const umbral = Number(f.umbral.value);
    if (!Number.isFinite(umbral) || umbral <= 0) {
      f.umbral.setAttribute('aria-invalid', 'true'); f.umbral.focus();
      $('#al-error').textContent = 'El umbral debe ser un número mayor que 0.';
      return;
    }
    f.umbral.removeAttribute('aria-invalid'); $('#al-error').textContent = '';
    try {
      const r = await api('/alertas', { method: 'POST', body: { ticker: f.ticker.value, tipo: f.tipo.value, umbral, canal: f.canal.value } });
      toast('Alerta creada para ' + r.nombre + '.');
      pintar();
    } catch (ex) { $('#al-error').textContent = ex.message; }
  });

  // ---- Listado, activar/desactivar, eliminar ----
  const condicion = (r) => (r.tipo === 'PRECIO_LIMITE' ? 'Precio ≥ ' + usd(r.umbral) : '|Var.| > ' + num(r.umbral) + '%');
  function chip(r) {
    const cls = { ACTIVA: 'chip-info', DISPARADA: 'chip-up', INACTIVA: '' }[r.estado];
    return '<span class="chip ' + cls + '">' + r.estado.charAt(0) + r.estado.slice(1).toLowerCase() + (r.pendiente_envio ? ' · reintentando envío' : '') + '</span>';
  }
  async function pintar() {
    try {
      const [rs, ns] = await Promise.all([api('/alertas'), api('/notificaciones'), Market.cotizaciones()]);
      $('#reglas-body').innerHTML = rs.length ? rs.map((r) => {
        const q = Market.cotizacion(r.ticker);
        return '<tr><td><strong>' + esc(r.nombre) + '</strong><br><span class="muted small">' + (r.canal === 'PUSH' ? 'Push' : 'Correo') + '</span></td><td>' + condicion(r) + '</td>' +
          '<td class="mono">' + (r.tipo === 'PRECIO_LIMITE' ? usd(q.precio) : pct(q.variacion)) + '</td><td>' + chip(r) + '</td>' +
          '<td class="row" style="flex-wrap:nowrap"><button class="btn btn-sm" type="button" data-toggle="' + r.id + '" data-estado="' + (r.estado === 'INACTIVA' ? 'ACTIVA' : 'INACTIVA') + '">' +
          (r.estado === 'INACTIVA' ? 'Activar' : 'Desactivar') + '</button>' +
          '<button class="btn btn-sm btn-danger" type="button" data-borrar="' + r.id + '" aria-label="Eliminar alerta de ' + esc(r.nombre) + '">Eliminar</button></td></tr>';
      }).join('') : '<tr><td colspan="5" class="empty">No tienes alertas. Crea la primera, por ejemplo “avisar si RiTech varía más de 3%”.</td></tr>';
      $('#notif-lista').innerHTML = ns.length ? ns.slice(0, 8).map((n) => '<li><div><strong>' + esc(n.texto) + '</strong><span>' + (n.canal === 'PUSH' ? 'Push' : 'Correo') + ' · ' +
        new Date(n.fecha).toLocaleString('es-CO') + '</span></div></li>').join('') : '<li><div><span>Aún no se han enviado notificaciones.</span></div></li>';
    } catch (ex) { $('#worker-estado').textContent = ex.message; }
  }
  $('#reglas-body').addEventListener('click', async (e) => {
    const t = e.target.closest('[data-toggle]'), b = e.target.closest('[data-borrar]');
    try {
      if (t) await api('/alertas/' + t.dataset.toggle, { method: 'PATCH', body: { estado: t.dataset.estado } });
      if (b) {
        if (!confirm('¿Eliminar esta alerta?')) return;
        await api('/alertas/' + b.dataset.borrar, { method: 'DELETE' });
        toast('Alerta eliminada.');
      }
      if (t || b) pintar();
    } catch (ex) { toast(ex.message); }
  });

  // "Evaluar ahora": misma lógica del worker, solo sobre mis reglas.
  $('#btn-evaluar').addEventListener('click', async () => {
    try {
      const r = await api('/alertas/evaluar', { method: 'POST' });
      toast(r.disparadas ? '🔔 ' + r.disparadas + ' alerta(s) disparada(s).' : r.pendientes ? 'El correo no está disponible: se reintentará.' : 'Ninguna regla superó su umbral.');
      $('#worker-estado').textContent = 'Última revisión: ' + new Date().toLocaleTimeString('es-CO') + '.';
      pintar();
    } catch (ex) { toast(ex.message); }
  });

  (async function iniciar() {
    try {
      const empresas = await Market.empresas();
      const sel = empresas.some((a) => a.ticker === param('t')) ? param('t') : empresas.find((a) => !a.es_indice).ticker;
      f.ticker.innerHTML = empresas.map((a) => '<option value="' + esc(a.ticker) + '"' + (a.ticker === sel ? ' selected' : '') + '>' + esc(a.nombre) + '</option>').join('');
      poll(pintar, 15000); // el worker evalúa en el servidor; aquí solo se refresca la vista
    } catch (ex) { $('#al-error').textContent = ex.message; }
  })();
})();
