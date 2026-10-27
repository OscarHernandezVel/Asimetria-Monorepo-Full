-- RNF-02: consultas de agregación sobre 1.000.000 de filas en < 500 ms.
-- Crea una hypertable temporal con la misma estructura que precios_historicos, la llena con 1M de filas
-- (800 empresas × 1.250 sesiones), mide con EXPLAIN ANALYZE y la elimina. No toca los datos reales.
--   docker compose exec -T db psql -U asimetria -d asimetria < packages/db/benchmark_rnf02.sql
\timing on
BEGIN;
CREATE TABLE bench_precios (LIKE precios_historicos INCLUDING DEFAULTS, PRIMARY KEY (time, empresa_id));
SELECT create_hypertable('bench_precios', 'time', chunk_time_interval => INTERVAL '30 days');
CREATE INDEX ON bench_precios (empresa_id, time DESC);

INSERT INTO bench_precios (time, empresa_id, precio_cierre, volumen)
SELECT now() - make_interval(days => d), e, 100 + e * 0.1 + sin(d / 20.0) * 5, 1000000
FROM generate_series(1, 800) e, generate_series(1, 1250) d;
ANALYZE bench_precios;
SELECT count(*) AS filas FROM bench_precios;

-- 1) Dashboard de un activo: promedio mensual de 5 años (time_bucket + índice compuesto).
--    Se ejecuta 3 veces: la primera es en frío; RNF-02 compara la caliente contra la tabla real.
EXPLAIN (ANALYZE, COSTS OFF, SUMMARY ON)
SELECT time_bucket('30 days', time) AS mes, avg(precio_cierre), max(precio_cierre), min(precio_cierre)
FROM bench_precios WHERE empresa_id = 421 GROUP BY mes ORDER BY mes;
EXPLAIN (ANALYZE, COSTS OFF, SUMMARY ON)
SELECT time_bucket('30 days', time) AS mes, avg(precio_cierre), max(precio_cierre), min(precio_cierre)
FROM bench_precios WHERE empresa_id = 421 GROUP BY mes ORDER BY mes;
EXPLAIN (ANALYZE, COSTS OFF, SUMMARY ON)
SELECT time_bucket('30 days', time) AS mes, avg(precio_cierre), max(precio_cierre), min(precio_cierre)
FROM bench_precios WHERE empresa_id = 421 GROUP BY mes ORDER BY mes;

-- 2) Tabla comparativa: volatilidad y retorno medio del último año para TODAS las empresas (~290k filas)
EXPLAIN (ANALYZE, COSTS OFF, SUMMARY ON)
SELECT empresa_id, avg(precio_cierre), stddev(precio_cierre)
FROM bench_precios WHERE time > now() - INTERVAL '1 year' GROUP BY empresa_id;

-- 3) Peor caso: agregación sobre el millón completo
EXPLAIN (ANALYZE, COSTS OFF, SUMMARY ON)
SELECT empresa_id, avg(precio_cierre) FROM bench_precios GROUP BY empresa_id;
ROLLBACK;
