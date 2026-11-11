package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Optimización de {@code precios_historicos} según el motor disponible (RNF-02, portabilidad):
 *
 * <ul>
 *   <li><b>TimescaleDB disponible</b> (Docker, Timescale Cloud): hypertable con chunks de 30 días y
 *       compresión columnar para datos de más de 90 días.</li>
 *   <li><b>PostgreSQL estándar</b> (Amazon RDS no ofrece TimescaleDB): índice BRIN sobre {@code time}.
 *       Para series que solo crecen en orden temporal, BRIN ocupa ~1000× menos que un B-Tree y
 *       acota los barridos por rango de fechas.</li>
 * </ul>
 * Una sola migración, el mismo código en todos los entornos.
 */
public class V2__SeriesTemporales extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        try (Statement st = c.createStatement()) {
            if (timescaleDisponible(c)) {
                st.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
                st.execute("SELECT create_hypertable('precios_historicos', 'time', chunk_time_interval => INTERVAL '30 days', "
                        + "if_not_exists => TRUE, migrate_data => TRUE)");
                st.execute("ALTER TABLE precios_historicos SET (timescaledb.compress, "
                        + "timescaledb.compress_segmentby = 'empresa_id', timescaledb.compress_orderby = 'time DESC')");
                st.execute("SELECT add_compression_policy('precios_historicos', INTERVAL '90 days', if_not_exists => TRUE)");
            } else {
                st.execute("CREATE INDEX IF NOT EXISTS brin_precios_time ON precios_historicos USING BRIN (time)");
            }
        }
    }

    static boolean timescaleDisponible(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb'")) {
            return rs.next();
        }
    }
}
