-- El evaluador de alertas corre tras cada actualización de cotizaciones (cada 5 s) y solo le
-- interesan las reglas activas o con envío pendiente: índice parcial, pequeño y siempre en memoria.
CREATE INDEX IF NOT EXISTS ix_reglas_evaluables ON reglas_alertas (empresa_id)
  WHERE estado = 'ACTIVA' OR pendiente_envio;

-- Historiales por usuario, del más reciente al más antiguo.
CREATE INDEX IF NOT EXISTS ix_simulaciones_usuario ON simulaciones_portafolio (usuario_id, id DESC);
CREATE INDEX IF NOT EXISTS ix_reglas_usuario ON reglas_alertas (usuario_id, id DESC);
