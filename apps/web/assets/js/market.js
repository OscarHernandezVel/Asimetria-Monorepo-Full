// market.js — capa de datos del frontend: único lugar que conoce las rutas de la API /api/v1.
// Las vistas piden datos aquí y solo se encargan de pintar (ISO 25010 Mantenibilidad → Modificabilidad).
(function () {
  'use strict';
  const { api } = UI;

  const COLORES = ['var(--pink)', 'var(--primary)', 'var(--violet)', 'var(--red)', 'var(--green)'];
  let empresas = null, porTicker = {}, metricas = null, cot = {}, estado = null;
  const una = {}; // promesas en curso: evita peticiones duplicadas

  function cargarUna(k, fn) {
    if (!una[k]) una[k] = fn().catch((e) => { delete una[k]; throw e; });
    return una[k];
  }

  const Market = {
    INDICE: '^NDX',
    async empresas() {
      return cargarUna('empresas', async () => {
        empresas = await api('/mercado/empresas');
        empresas.forEach((e, i) => {
          porTicker[e.ticker] = { ...e, color: e.es_indice ? 'var(--primary)' : COLORES[(i - 1 + COLORES.length) % COLORES.length],
            corto: e.es_indice ? 'IX' : e.ticker.slice(0, 2) };
        });
        return empresas;
      });
    },
    todas: () => empresas || [],
    info: (t) => porTicker[t] || { ticker: t, nombre: t, corto: t.slice(0, 2), color: 'var(--primary)' },
    async metricas(forzar = false) {
      if (forzar) delete una.metricas;
      return cargarUna('metricas', async () => (metricas = await api('/metrics/')));
    },
    metrica: (t) => metricas?.find((m) => m.ticker === t),
    // GET /mercado/cotizaciones: nunca falla por el proveedor; `estado.desactualizado` avisa (RNF-06).
    async cotizaciones() {
      const r = await api('/mercado/cotizaciones');
      cot = r.cotizaciones; estado = r.estado;
      return r;
    },
    cotizacion: (t) => cot[t] || { precio: null, variacion: null },
    estado: () => estado,
    precios: (t, dias) => api('/mercado/precios/' + encodeURIComponent(t) + '?dias=' + dias),
    analisis: (t, ventana = 90) => api('/metrics/' + encodeURIComponent(t) + '/analisis?ventana=' + ventana),
    recomendaciones(f) {
      const q = new URLSearchParams(Object.entries(f).filter(([, v]) => v !== '' && v != null));
      return api('/recommendations/?' + q);
    },
    destacado: () => api('/destacado', { publico: true })
  };

  // ---- Cartera del usuario: watchlist + portafolio teórico (HU-08) ----
  UI.cartera = {
    lista: () => api('/watchlist'),
    // false si ya existía (HU-08 CA-4: el servidor responde 409).
    async agregar(ticker) {
      try { await api('/watchlist', { method: 'POST', body: { ticker } }); return true; } catch (e) { if (e.status === 409) return false; throw e; }
    },
    quitar: (ticker) => api('/watchlist/' + encodeURIComponent(ticker), { method: 'DELETE' }),
    actualizar: (ticker, cambios) => api('/watchlist/' + encodeURIComponent(ticker), { method: 'PATCH', body: cambios }),
    // Distribución por sector a precio actual (para la dona).
    distribucion(lista) {
      const sect = {};
      let total = 0;
      for (const it of lista) {
        const v = it.cantidad * (Market.cotizacion(it.ticker).precio || 0);
        sect[it.sector] = (sect[it.sector] || 0) + v; total += v;
      }
      const partes = Object.entries(sect).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).map(([s, v]) => ({ sector: s, pct: (v / total) * 100 }));
      return { total, partes };
    }
  };

  window.Market = Market;
})();
