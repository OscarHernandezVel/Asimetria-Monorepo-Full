package co.edu.ucc.asimetria.persistencia;

import co.edu.ucc.asimetria.comun.estructuras.SerieTemporal;
import co.edu.ucc.asimetria.dominio.mercado.Barra;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acceso a {@code precios_historicos} con JDBC directo: inserciones en lote y lecturas de series
 * completas son mucho más rápidas sin el ORM (RNF-01/RNF-02). Devuelve {@link SerieTemporal}.
 */
@Repository
public class PreciosRepositorio {

    /** Cierre NYSE/NASDAQ ≈ 21:00 UTC: cada sesión se guarda a esa hora. */
    private static final int HORA_CIERRE_UTC = 21;

    private final JdbcTemplate jdbc;

    public PreciosRepositorio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UltimoCierre(double ultimo, double anterior, LocalDate fecha) {
    }

    public record ConteoFuente(String fuente, long filas, Instant ultimaIngesta) {
    }

    /** Inserta sin duplicar: ON CONFLICT (time, empresa_id) DO NOTHING (HU-02 CA-3). Devuelve filas nuevas. */
    public int insertarLote(long empresaId, List<Barra> barras, String fuente) {
        if (barras.isEmpty()) {
            return 0;
        }
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        int[][] r = jdbc.batchUpdate("""
                INSERT INTO precios_historicos (time, empresa_id, precio_apertura, precio_maximo, precio_minimo,
                                                precio_cierre, volumen, fuente, fecha_ingesta)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (time, empresa_id) DO NOTHING""",
                barras, 500, (ps, b) -> {
                    ps.setObject(1, aMarcaTiempo(b.fecha()));
                    ps.setLong(2, empresaId);
                    ps.setDouble(3, b.apertura());
                    ps.setDouble(4, b.maximo());
                    ps.setDouble(5, b.minimo());
                    ps.setDouble(6, b.cierre());
                    ps.setLong(7, b.volumen());
                    ps.setString(8, fuente);
                    ps.setObject(9, ahora);
                });
        int total = 0;
        for (int[] lote : r) {
            for (int n : lote) {
                total += Math.max(n, 0);
            }
        }
        return total;
    }

    public SerieTemporal serie(long empresaId) {
        SerieTemporal s = new SerieTemporal();
        jdbc.query("SELECT time, precio_cierre FROM precios_historicos WHERE empresa_id = ? ORDER BY time",
                rs -> {
                    s.agregar(fecha(rs), rs.getDouble(2));
                }, empresaId);
        return s;
    }

    /** Últimas {@code n} sesiones (usa el índice empresa_id, time DESC). */
    public SerieTemporal ultimas(long empresaId, int n) {
        SerieTemporal s = new SerieTemporal();
        jdbc.query("SELECT time, precio_cierre FROM precios_historicos WHERE empresa_id = ? ORDER BY time DESC LIMIT ?",
                rs -> {
                    s.agregar(fecha(rs), rs.getDouble(2));
                }, empresaId, n);
        return s;
    }

    /** Último y penúltimo cierre de cada empresa activa: dos lecturas de índice por empresa (LATERAL). */
    public Map<String, UltimoCierre> ultimosCierres() {
        Map<String, double[]> precios = new HashMap<>();
        Map<String, LocalDate> fechas = new HashMap<>();
        jdbc.query("""
                SELECT e.ticker, p.time, p.precio_cierre
                FROM empresas e
                CROSS JOIN LATERAL (SELECT time, precio_cierre FROM precios_historicos
                                    WHERE empresa_id = e.id ORDER BY time DESC LIMIT 2) p
                WHERE e.activa ORDER BY e.ticker, p.time DESC""", rs -> {
            String t = rs.getString(1);
            double[] par = precios.computeIfAbsent(t, k -> new double[]{Double.NaN, Double.NaN});
            if (Double.isNaN(par[0])) {
                par[0] = rs.getDouble(3);
                fechas.put(t, fecha(rs));
            } else {
                par[1] = rs.getDouble(3);
            }
        });
        Map<String, UltimoCierre> out = new HashMap<>();
        precios.forEach((t, p) -> out.put(t, new UltimoCierre(p[0], Double.isNaN(p[1]) ? p[0] : p[1], fechas.get(t))));
        return out;
    }

    public List<ConteoFuente> contarPorFuente() {
        return jdbc.query("SELECT coalesce(fuente, 'SIN_REGISTRO'), count(*), max(fecha_ingesta) FROM precios_historicos GROUP BY fuente",
                (rs, i) -> new ConteoFuente(rs.getString(1), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class) == null ? null : rs.getObject(3, OffsetDateTime.class).toInstant()));
    }

    public static OffsetDateTime aMarcaTiempo(LocalDate fecha) {
        return fecha.atTime(HORA_CIERRE_UTC, 0).atOffset(ZoneOffset.UTC);
    }

    private static LocalDate fecha(ResultSet rs) throws SQLException {
        return rs.getObject("time", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }
}
