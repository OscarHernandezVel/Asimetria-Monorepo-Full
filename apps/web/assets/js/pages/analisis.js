// Análisis NASDAQ 100 vs activo (HU-03, HU-09). Todo el cálculo llega de GET /metrics/{t}/analisis.
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, esc, num, pct, tono, fecha, lineChart, scatter, userStore, toast, param } = UI;

  async function iniciar() {
    const empresas = (await Market.empresas()).filter((e) => !e.es_indice);
    const t = empresas.some((e) => e.ticker === param('t')) ? param('t') : empresas[0].ticker;
    const e = Market.info(t);
    $('#sel-activo').innerHTML = empresas.map((x) => '<option value="' + esc(x.ticker) + '"' + (x.ticker === t ? ' selected' : '') + '>' + esc(x.nombre) + '</option>').join('');
    $('#sel-activo').addEventListener('change', (ev) => { location.search = '?t=' + encodeURIComponent(ev.target.value); });

    document.title = 'NASDAQ 100 vs ' + e.nombre + ' — Asimetría';
    $('#titulo').textContent = 'NASDAQ 100 vs. ' + e.nombre;
    $('#bc-actual').textContent = 'NASDAQ 100 vs ' + e.nombre;
    $('#av-activo').textContent = e.corto;
    $('#av-activo').style.setProperty('--c', e.color);
    $('#leg-activo').textContent = e.nombre;
    $('#th-activo').textContent = e.nombre;
    $('#lbl-ret').textContent = 'Retorno ' + e.nombre.split(' ')[0];
    $('#lnk-backtest').href = 'portafolio.html?t=' + encodeURIComponent(t);
    $('#lnk-alerta').href = 'alertas.html?t=' + encodeURIComponent(t);
    $('#beta-sub').textContent = 'Sensibilidad de ' + e.nombre + ' frente al NASDAQ 100';

    const a = await Market.analisis(t, 90), m = a.metricas;
    lineChart($('#chart-rend'), [{ values: a.base100_indice, color: 'var(--primary)' }, { values: a.base100_activo, color: 'var(--pink)' }], { base: 100 });
    const pinta = (id, v) => { $(id).textContent = pct(v, 1); $(id).className = 'value ' + tono(v); };
    pinta('#st-ret', a.retorno); pinta('#st-ix', a.retorno_indice); pinta('#st-alfa', a.alfa); pinta('#st-dd', a.max_drawdown);

    // Beta o mensaje de datos insuficientes (HU-03 CA-4).
    if (m.error) {
      $('#aviso-datos').hidden = false;
      $('#aviso-datos').textContent = m.error + ': ' + e.nombre + ' solo tiene ' + a.ventana + ' sesiones de historial.';
      $('#beta-texto').textContent = 'Se necesitan al menos 20 sesiones alcistas y 20 bajistas del índice.';
    } else {
      $('#beta-valor').textContent = num(m.beta);
      $('#beta-up').textContent = num(m.beta_alcista);
      $('#beta-down').textContent = num(m.beta_bajista);
      $('#corr').textContent = num(m.correlacion);
      $('#beta-texto').textContent = 'Por cada 1% de movimiento del NASDAQ 100, ' + e.nombre + ' se mueve en promedio ' + num(m.beta) + '% en la misma dirección — ' +
        (m.beta_alcista > m.beta_bajista ? 'con mayor fuerza al alza que a la baja (sube más de lo que cae).' : 'con mayor fuerza a la baja que al alza.');
    }
    scatter($('#scatter'), a.dispersion, m.beta_alcista ?? 1, m.beta_bajista ?? 1);

    const d = a.desglose;
    const fila = (etq, x) => '<tr><td>' + etq + '</td><td class="mono">' + pct(x.indice, 1) + '</td><td class="mono ' + tono(x.activo) + '">' + pct(x.activo, 2) +
      '</td><td class="mono">' + num(x.ratio, 2) + '×</td></tr>';
    $('#desglose').innerHTML =
      fila('<span class="chip chip-up">Día alcista</span> <span class="muted small">' + fecha(d.alcista.fecha) + '</span>', d.alcista) +
      fila('<span class="chip chip-down">Día bajista</span> <span class="muted small">' + fecha(d.bajista.fecha) + '</span>', d.bajista) +
      '<tr><td>Promedio ' + a.ventana + ' días</td><td class="mono">' + pct(d.promedio.indice) + '/día</td><td class="mono ' + tono(d.promedio.activo) + '">' +
      pct(d.promedio.activo) + '/día</td><td class="mono">' + num(d.promedio.ratio, 1) + '×</td></tr>';

    // Preferencias de la tarjeta "Configurar alerta de asimetría" (preferencia de interfaz, por usuario).
    const prefKey = 'prefAsimetria.' + t;
    const pref = userStore.get(prefKey, { desviacion: true, resumen: true });
    $('#sw-desv').checked = pref.desviacion;
    $('#sw-resumen').checked = pref.resumen;
    $('#cfg-texto').textContent = 'Recibe una notificación cuando ' + e.nombre + ' se desvíe del NASDAQ 100 más allá del umbral definido.';
    ['#sw-desv', '#sw-resumen'].forEach((id) => $(id).addEventListener('change', () => {
      userStore.set(prefKey, { desviacion: $('#sw-desv').checked, resumen: $('#sw-resumen').checked });
      toast('Preferencias guardadas.');
    }));
  }

  iniciar().catch((ex) => {
    $('#aviso-datos').hidden = false;
    $('#aviso-datos').textContent = 'No fue posible cargar el análisis: ' + ex.message;
  });
})();
