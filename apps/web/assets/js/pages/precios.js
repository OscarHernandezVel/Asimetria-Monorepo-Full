// Precios y pago. Modo demostración: los datos de tarjeta solo se validan en el navegador y se descartan;
// nunca llegan a la API. En producción se tokenizan directamente con la pasarela (PCI DSS).
(function () {
  'use strict';
  const { $, $$, esc, usd, session, api, toast } = UI;

  // Validación Luhn (solo formato; el cobro real lo valida la pasarela PCI DSS, nunca este servidor).
  function luhn(numero) {
    const d = String(numero).replace(/\s+/g, '');
    if (!/^\d{13,19}$/.test(d)) return false;
    let suma = 0;
    for (let i = 0; i < d.length; i++) {
      let x = +d[d.length - 1 - i];
      if (i % 2) { x *= 2; if (x > 9) x -= 9; }
      suma += x;
    }
    return suma % 10 === 0;
  }
  const IVA = 0.19;
  const PLANES = [
    { id: 'CONSERVADOR', nombre: 'Conservador', precio: 9, desc: 'Alertas básicas y watchlist de hasta 10 activos.', incl: ['Watchlist de 10 activos', 'Alertas por variación', 'Tabla comparativa de métricas'] },
    { id: 'MODERADO', nombre: 'Moderado', precio: 24, desc: 'Backtesting, Beta personalizado y alertas ilimitadas.', incl: ['Todo lo del plan Conservador', 'Backtesting ilimitado', 'Beta alcista y bajista', 'Alertas ilimitadas'] },
    { id: 'AGRESIVO', nombre: 'Agresivo', precio: 49, desc: 'Datos en tiempo real vía WebSocket y API dedicada.', incl: ['Todo lo del plan Moderado', 'Tiempo real vía WebSocket', 'API dedicada', 'Soporte prioritario'] }
  ];

  $('#planes').innerHTML = PLANES.map((p) => '<article class="card plan' + (p.id === 'MODERADO' ? ' featured' : '') + '">' +
    (p.id === 'MODERADO' ? '<span class="chip chip-info" style="align-self:flex-start">Más elegido</span>' : '') +
    '<h2 style="font-size:20px">' + p.nombre + '</h2><p class="price">$' + p.precio + '<small>/mes</small></p><p class="muted small">' + esc(p.desc) + '</p>' +
    '<ul>' + p.incl.map((i) => '<li>' + esc(i) + '</li>').join('') + '</ul>' +
    '<button class="btn ' + (p.id === 'MODERADO' ? 'btn-primary' : '') + '" type="button" data-plan="' + p.id + '">Elegir ' + p.nombre + '</button></article>').join('');

  $('#pago-planes').innerHTML = PLANES.map((p) => '<label class="plan-opt"><span class="spread"><span class="row" style="gap:10px"><input type="radio" name="plan" value="' + p.id + '"> ' + p.nombre +
    '</span><span>$' + p.precio + '/mes</span></span><p>' + esc(p.desc) + '</p></label>').join('');

  const f = $('#form-pago'), dlg = $('#dlg-pago');
  function totales() {
    const p = PLANES.find((x) => x.id === f.plan.value);
    const iva = Math.round(p.precio * IVA * 100) / 100;
    $('#tot-plan').textContent = 'Plan ' + p.nombre;
    $('#tot-sub').textContent = usd(p.precio);
    $('#tot-iva').textContent = usd(iva);
    $('#tot-total').textContent = usd(p.precio + iva);
    $('#btn-pagar').textContent = 'Pagar ' + usd(p.precio + iva);
  }
  function metodo() {
    const m = f.metodo.value;
    $('#panel-tarjeta').hidden = m !== 'tarjeta';
    $('#panel-otro').hidden = m === 'tarjeta';
    $('#panel-otro').textContent = m === 'paypal' ? 'Serás redirigido a PayPal para autorizar el pago.' : 'Recibirás los datos bancarios por correo para completar la transferencia (PSE / ACH).';
  }
  $$('[data-plan]').forEach((b) => b.addEventListener('click', () => {
    f.reset(); $('#pago-error').textContent = '';
    f.plan.value = b.dataset.plan; totales(); metodo();
    dlg.showModal();
  }));
  f.addEventListener('change', () => { totales(); metodo(); });
  $('#btn-cerrar').addEventListener('click', () => dlg.close());

  // Formato mientras se escribe: grupos de 4 y MM / AA.
  $('#cc-num').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, '').slice(0, 19).replace(/(\d{4})(?=\d)/g, '$1 '); });
  $('#cc-exp').addEventListener('input', (e) => { const d = e.target.value.replace(/\D/g, '').slice(0, 4); e.target.value = d.length > 2 ? d.slice(0, 2) + ' / ' + d.slice(2) : d; });
  $('#cc-cvc').addEventListener('input', (e) => { e.target.value = e.target.value.replace(/\D/g, ''); });

  function validarTarjeta() {
    if (!luhn(f.numero.value)) return [f.numero, 'El número de tarjeta no es válido.'];
    const [mm, aa] = f.exp.value.split('/').map((x) => parseInt(x, 10));
    const fin = new Date(2000 + aa, mm); // primer día del mes siguiente al vencimiento
    if (!(mm >= 1 && mm <= 12) || !(fin > new Date())) return [f.exp, 'La fecha de vencimiento no es válida o ya pasó.'];
    if (!/^\d{3,4}$/.test(f.cvc.value)) return [f.cvc, 'El CVC debe tener 3 o 4 dígitos.'];
    if (f.titular.value.trim().length < 3) return [f.titular, 'Ingresa el nombre como aparece en la tarjeta.'];
    return null;
  }

  f.addEventListener('submit', async (e) => {
    e.preventDefault();
    $$('[aria-invalid]', f).forEach((i) => i.removeAttribute('aria-invalid'));
    const error = f.metodo.value === 'tarjeta' ? validarTarjeta() : null;
    if (error) { error[0].setAttribute('aria-invalid', 'true'); error[0].focus(); $('#pago-error').textContent = error[1]; return; }
    const plan = f.plan.value;
    f.reset(); // descarta los datos sensibles inmediatamente
    dlg.close();
    if (session.get()) {
      try {
        session.update(await api('/usuarios/me/plan', { method: 'PUT', body: { plan } })); // solo el plan: ningún dato de tarjeta sale del navegador
        toast('Modo demostración: no se realizó ningún cobro. Plan ' + plan.toLowerCase() + ' activado en tu cuenta.');
      } catch (ex) { toast(ex.message); }
    } else {
      toast('Modo demostración: no se realizó ningún cobro. Inicia sesión para activar el plan en tu cuenta.');
    }
  });

  if (session.get()) $('#acciones-cuenta').innerHTML = '<a class="btn btn-primary" href="mercados.html">Ir a mi panel</a>';
})();
