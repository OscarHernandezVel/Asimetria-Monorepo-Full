package co.edu.ucc.asimetria.tareas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Exclusión mutua entre instancias con <i>advisory locks</i> de PostgreSQL.
 *
 * <p>En AWS corren varias tareas ECS idénticas; cada tarea programada (sincronizar cotizaciones,
 * cierre diario…) debe ejecutarse <b>una sola vez</b> por ciclo. {@code pg_try_advisory_lock} no
 * bloquea: la instancia que no obtiene el candado simplemente omite el ciclo. Si una instancia muere,
 * PostgreSQL libera el candado al cerrar su conexión (sin candados huérfanos).
 */
@Component
public class BloqueoDistribuido {

    private static final Logger log = LoggerFactory.getLogger(BloqueoDistribuido.class);

    private final DataSource datos;

    public BloqueoDistribuido(DataSource datos) {
        this.datos = datos;
    }

    /** @return {@code true} si esta instancia ejecutó la tarea */
    public boolean ejecutar(long clave, Runnable tarea) {
        try (Connection c = datos.getConnection()) {
            if (!intentar(c, "SELECT pg_try_advisory_lock(?)", clave)) {
                return false;
            }
            try {
                tarea.run();
                return true;
            } finally {
                intentar(c, "SELECT pg_advisory_unlock(?)", clave);
            }
        } catch (SQLException e) {
            log.warn("No se pudo obtener el bloqueo {}: {}", clave, e.getMessage());
            return false;
        }
    }

    private static boolean intentar(Connection c, String sql, long clave) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
