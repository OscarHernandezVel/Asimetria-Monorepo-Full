// Autenticación (HU-07) contra la API: el hash bcrypt, el bloqueo por intentos y la auditoría ocurren en el servidor.
(function () {
  'use strict';
  const { $, $$, api, session, toast, param } = UI;

  // ---- Pestañas accesibles (WAI-ARIA tabs) ----
  const tabs = $$('[role="tab"]');
  function activar(nombre) {
    tabs.forEach((t) => {
      const sel = t.id === 'tab-' + nombre;
      t.setAttribute('aria-selected', sel);
      t.tabIndex = sel ? 0 : -1;
      $('#' + t.getAttribute('aria-controls')).hidden = !sel;
    });
  }
  tabs.forEach((t, i) => {
    t.addEventListener('click', () => activar(t.id.slice(4)));
    t.addEventListener('keydown', (e) => {
      if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
      const otro = tabs[(i + 1) % tabs.length];
      activar(otro.id.slice(4)); otro.focus();
    });
  });
  $$('[data-ir]').forEach((b) => b.addEventListener('click', () => activar(b.dataset.ir)));
  if (param('modo') === 'registro') activar('registro');

  // Redirección segura: solo páginas locales de la app (evita open redirect).
  function destino(porDefecto) {
    const next = param('next');
    return next && /^[a-z]+\.html(\?[\w=&.%^-]*)?$/.test(next) ? next : porDefecto;
  }
  const ocupado = (form, si) => { form.querySelector('[type=submit]').disabled = si; };

  // ---- Inicio de sesión (CA-2, CA-4) ----
  $('#form-login').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target, err = $('#login-error');
    if (!f.email.value.trim() || !f.password.value) { err.textContent = 'Ingresa tu correo y contraseña.'; return; }
    ocupado(f, true);
    try {
      session.start(await api('/auth/login', { method: 'POST', publico: true, body: { email: f.email.value.trim(), password: f.password.value } }));
      location.href = destino('mercados.html');
    } catch (ex) {
      err.textContent = ex.status === 422 ? 'Credenciales inválidas' : ex.message; // mensaje genérico (CA-4)
      ocupado(f, false);
    }
  });

  // ---- Registro (CA-1, CA-5) ----
  $('#form-registro').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target, err = $('#reg-error');
    [f.nombre, f.email, f.password].forEach((i) => i.removeAttribute('aria-invalid'));
    const fallo = (input, msg) => { input?.setAttribute('aria-invalid', 'true'); input?.focus(); err.textContent = msg; };
    const pass = f.password.value;
    // Validación previa para responder al instante; la API vuelve a validar todo (nunca se confía en el cliente).
    if (!f.nombre.value.trim()) return fallo(f.nombre, 'Ingresa tu nombre completo.');
    if (!f.email.checkValidity() || !f.email.value.trim()) return fallo(f.email, 'Ingresa un correo electrónico válido.');
    if (pass.length < 8 || !/[a-z]/i.test(pass) || !/\d/.test(pass)) return fallo(f.password, 'La contraseña debe tener mínimo 8 caracteres, con letras y números.');
    if (!f.terminos.checked) return fallo(f.terminos, 'Debes aceptar los términos y la política de privacidad.');
    const perfil = f.perfil.value || null;
    ocupado(f, true);
    try {
      session.start(await api('/auth/registro', { method: 'POST', publico: true,
        body: { nombre: f.nombre.value.trim(), email: f.email.value.trim(), password: pass, perfil } }));
      location.href = perfil ? destino('mercados.html') : 'configuracion.html#quiz';
    } catch (ex) {
      fallo(ex.status === 409 ? f.email : null, ex.message);
      ocupado(f, false);
    }
  });

  $('#btn-demo').addEventListener('click', async () => {
    try {
      session.start(await api('/auth/demo', { method: 'POST', publico: true }));
      location.href = destino('mercados.html');
    } catch (ex) { toast(ex.message); }
  });

  $$('[data-oauth]').forEach((b) => b.addEventListener('click', () =>
    toast('Inicio con ' + b.dataset.oauth + ' (OAuth2) aún no está habilitado. Usa tu correo y contraseña.')));
})();
