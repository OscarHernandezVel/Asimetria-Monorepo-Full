// Landing: tarjeta del activo con mayor asimetría favorable (GET /api/v1/destacado, público).
(function () {
  'use strict';
  const { $, api, lineChart, num, pct, tono, session, toast } = UI;

  Market.destacado().then((d) => {
    $('#hero-activo').firstChild.textContent = d.nombre + ' ';
    $('#hero-activo span').textContent = d.ticker;
    $('#hero-leyenda-activo').textContent = d.nombre;
    lineChart($('#hero-chart'), [{ values: d.base100_indice, color: 'var(--primary)' }, { values: d.base100_activo, color: 'var(--pink)' }]);
    $('#hero-beta').textContent = num(d.beta);
    $('#hero-ret').textContent = pct(d.retorno_30d, 1);
    $('#hero-ret').className = 'value ' + tono(d.retorno_30d);
    $('#hero-vol').textContent = d.nivel_volatilidad;
    $('#hero-asimetria').textContent = 'Asimetría ' + num(d.ratio_asimetria) + '×';
  }).catch(() => { $('#hero-asimetria').textContent = 'Datos no disponibles temporalmente'; });

  // "Ver demo en vivo": sesión de demostración emitida por la API (solo con MODO_DEMO=1).
  $('#btn-demo').addEventListener('click', async () => {
    try {
      session.start(await api('/auth/demo', { method: 'POST', publico: true }));
      location.href = 'mercados.html';
    } catch (e) { toast(e.message); }
  });

  if (session.get()) $('#acciones-cuenta').innerHTML = '<a class="btn btn-primary" href="mercados.html">Ir a mi panel</a>';
})();
