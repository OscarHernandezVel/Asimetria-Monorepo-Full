// Configuración: cuenta, quiz de perfil (HU-07 CA-3), inyección de fallos (demo) y auditoría (RNF-04).
(function () {
  'use strict';
  if (!UI.ok) return;
  const { $, $$, esc, api, session, toast, perfilTexto } = UI;

  function pintarCuenta(u) {
    $('#c-nombre').textContent = u.nombre;
    $('#c-email').textContent = u.email;
    $('#c-perfil').textContent = perfilTexto(u.perfil_riesgo);
    $('#c-plan').textContent = u.plan ? 'Plan ' + perfilTexto(u.plan) : 'Gratuito';
    $('#c-exp').textContent = new Date(session.get().exp).toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit' }) + ' (JWT de 120 min)';
    const lateral = $('#perfil-lateral');
    if (lateral) lateral.textContent = 'Perfil ' + perfilTexto(u.perfil_riesgo).toLowerCase();
  }

  // ---- Quiz (el servidor clasifica y guarda el perfil) ----
  const PREGUNTAS = [
    ['¿Cuál es tu horizonte de inversión?', ['Menos de 1 año', 'Entre 1 y 5 años', 'Más de 5 años']],
    ['Si tu inversión cae 20 % en un mes, ¿qué haces?', ['Vendo para evitar más pérdidas', 'Espero a que se recupere', 'Compro más aprovechando el precio']],
    ['¿Cuál es tu objetivo principal?', ['Preservar mi capital', 'Equilibrio entre riesgo y retorno', 'Máximo crecimiento']],
    ['¿Qué experiencia tienes invirtiendo en bolsa?', ['Ninguna', 'Algo de experiencia', 'Amplia experiencia']],
    ['¿Qué porcentaje de tus ingresos destinarías a invertir?', ['Menos del 10 %', 'Entre 10 % y 30 %', 'Más del 30 %']]
  ];
  $('#preguntas').innerHTML = PREGUNTAS.map(([q, ops], i) => '<fieldset><legend>' + (i + 1) + '. ' + esc(q) + '</legend>' +
    ops.map((o, j) => '<label><input type="radio" name="q' + i + '" value="' + (j + 1) + '"> ' + esc(o) + '</label>').join('') + '</fieldset>').join('');

  $('#form-quiz').addEventListener('submit', async (e) => {
    e.preventDefault();
    const respuestas = PREGUNTAS.map((_, i) => Number(e.target['q' + i].value) || 0);
    if (respuestas.includes(0)) { $('#quiz-error').textContent = 'Responde todas las preguntas del quiz'; return; }
    try {
      const u = await api('/usuarios/me/quiz', { method: 'POST', body: { respuestas } });
      session.update(u);
      $('#quiz-error').textContent = '';
      toast('Tu perfil es ' + perfilTexto(u.perfil_riesgo) + '. Las recomendaciones ya se ajustaron.');
      pintarCuenta(u); pintarAuditoria();
    } catch (ex) { $('#quiz-error').textContent = ex.message; }
  });
  if (location.hash === '#quiz') $('#quiz').focus();

  // ---- Inyección de fallos (solo MODO_DEMO; si el servidor responde 404 la sección queda oculta) ----
  async function cargarFallos() {
    try {
      const f = await api('/demo/fallos');
      $$('[data-sim]').forEach((sw) => { sw.checked = f[sw.dataset.sim]; });
      $('#sec-fallos').hidden = false;
    } catch (ex) { /* producción: sin inyección de fallos */ }
  }
  $$('[data-sim]').forEach((sw) => sw.addEventListener('change', async () => {
    const cuerpo = Object.fromEntries($$('[data-sim]').map((x) => [x.dataset.sim, x.checked]));
    try {
      await api('/demo/fallos', { method: 'PUT', body: cuerpo });
      toast((sw.checked ? 'Activada' : 'Desactivada') + ' la simulación: ' + sw.parentElement.textContent.trim());
    } catch (ex) { toast(ex.message); sw.checked = !sw.checked; }
  }));

  // ---- Auditoría ----
  let registros = [];
  async function pintarAuditoria() {
    registros = await api('/auditoria').catch(() => []);
    $('#audit-body').innerHTML = registros.length ? registros.map((r) => '<tr><td class="mono small">' + esc(r.ts.replace('T', ' ').slice(0, 19)) + '</td><td class="mono small">' + esc(r.ip || '—') +
      '</td><td>' + esc(r.evento) + '</td><td><span class="chip ' + (r.estado === 'EXITO' ? 'chip-up' : 'chip-down') + '">' + esc(r.estado) + '</span></td><td class="small muted">' + esc(r.detalle) + '</td></tr>').join('')
      : '<tr><td colspan="5" class="empty">Sin eventos registrados.</td></tr>';
  }
  $('#btn-export').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(registros, null, 2)], { type: 'application/json' });
    const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: 'auditoria-asimetria.json' });
    a.click(); URL.revokeObjectURL(a.href);
  });

  $('#btn-salir').addEventListener('click', async () => {
    await api('/auth/logout', { method: 'POST' }).catch(() => {}); // queda auditado en el servidor
    session.end(); // el JWT es sin estado: al descartarlo la sesión termina en este navegador
    location.href = 'index.html';
  });

  api('/usuarios/me').then((u) => { session.update(u); pintarCuenta(u); }).catch((ex) => toast(ex.message));
  cargarFallos();
  pintarAuditoria();
})();
