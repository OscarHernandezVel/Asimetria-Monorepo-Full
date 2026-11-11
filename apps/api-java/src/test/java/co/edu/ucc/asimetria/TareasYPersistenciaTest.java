package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.mercado.IngestaService;
import co.edu.ucc.asimetria.persistencia.Auditoria;
import co.edu.ucc.asimetria.persistencia.AuditoriaRepositorio;
import co.edu.ucc.asimetria.tareas.BloqueoDistribuido;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Bloqueo distribuido, cierre diario sin duplicados (HU-02 CA-2/CA-3) y retención de auditoría (RNF-04). */
class TareasYPersistenciaTest extends PruebaIntegracion {

    @Autowired
    private BloqueoDistribuido bloqueo;
    @Autowired
    private DataSource datos;
    @Autowired
    private IngestaService ingesta;
    @Autowired
    private co.edu.ucc.asimetria.mercado.CotizacionService cotizaciones;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditoriaService auditoria;
    @Autowired
    private AuditoriaRepositorio auditorias;
    @Autowired
    private co.edu.ucc.asimetria.mercado.MetricasService metricas;

    @Test
    void elBloqueoSoloLoObtieneUnaInstancia() throws Exception {
        AtomicBoolean otraLoObtuvo = new AtomicBoolean(true);
        boolean ejecuto = bloqueo.ejecutar(9001, () -> {
            // Mientras esta "instancia" lo tiene, otra conexión (otra tarea ECS) no puede tomarlo
            try (Connection c = datos.getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(9001)")) {
                rs.next();
                otraLoObtuvo.set(rs.getBoolean(1));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(ejecuto).isTrue();
        assertThat(otraLoObtuvo).isFalse();
        assertThat(bloqueo.ejecutar(9001, () -> { })).isTrue();   // se liberó al terminar
    }

    @Test
    void cierreDiarioGuardaUnaSolaVezYOmiteFinesDeSemana() {
        cotizaciones.sincronizar();
        LocalDate lunes = LocalDate.of(2031, 1, 6);
        int primero = ingesta.cierreDiario(lunes);
        int segundo = ingesta.cierreDiario(lunes);
        int sabado = ingesta.cierreDiario(LocalDate.of(2031, 1, 4));
        Integer fuenteVivo = jdbc.queryForObject("SELECT count(*) FROM precios_historicos WHERE time >= '2031-01-06' AND fuente = 'COTIZACION_VIVO'", Integer.class);
        jdbc.update("DELETE FROM precios_historicos WHERE time >= '2031-01-01'");   // no contaminar otras pruebas
        metricas.recalcular(null);
        assertThat(primero).isEqualTo(12);
        assertThat(segundo).isZero();          // ON CONFLICT DO NOTHING
        assertThat(sabado).isZero();
        assertThat(fuenteVivo).isEqualTo(12);  // procedencia registrada
        assertThat(ingesta.cargaInicial()).isFalse();   // idempotente: la BD ya tiene datos
    }

    @Test
    void retencionDeNoventaDias() {
        auditorias.save(new Auditoria(Instant.now().minus(91, ChronoUnit.DAYS), null, null, null, "VIEJO", "EXITO", null));
        auditoria.registrarLlamada("PRUEBA", "op", true, 200, 5, "ok");
        assertThat(auditoria.purgar()).isGreaterThanOrEqualTo(1);
        assertThat(auditorias.findAll()).noneMatch(a -> "VIEJO".equals(a.getEvento()));
    }
}
