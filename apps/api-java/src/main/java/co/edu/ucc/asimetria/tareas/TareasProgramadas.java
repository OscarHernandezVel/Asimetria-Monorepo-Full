package co.edu.ucc.asimetria.tareas;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.mercado.IngestaService;
import co.edu.ucc.asimetria.usuario.UsuarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * Tareas en segundo plano (sustituyen al worker y al beat de Celery). Cada una corre bajo un
 * {@link BloqueoDistribuido}: con N instancias en ECS se ejecuta una sola vez por ciclo.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "asimetria", name = "tareas-habilitadas", havingValue = "true", matchIfMissing = true)
public class TareasProgramadas {

    private static final Logger log = LoggerFactory.getLogger(TareasProgramadas.class);
    static final long CARGA = 4201, COTIZACIONES = 4202, CIERRE = 4203, PURGA = 4204;

    private final BloqueoDistribuido bloqueo;
    private final IngestaService ingesta;
    private final CotizacionService cotizaciones;
    private final AuditoriaService auditoria;
    private final UsuarioService usuarios;
    private final PropiedadesAsimetria props;
    private final Clock reloj;

    public TareasProgramadas(BloqueoDistribuido bloqueo, IngestaService ingesta, CotizacionService cotizaciones,
                             AuditoriaService auditoria, UsuarioService usuarios, PropiedadesAsimetria props, Clock reloj) {
        this.bloqueo = bloqueo;
        this.ingesta = ingesta;
        this.cotizaciones = cotizaciones;
        this.auditoria = auditoria;
        this.usuarios = usuarios;
        this.props = props;
        this.reloj = reloj;
    }

    /** HU-02 CA-1: al arrancar, la primera instancia carga catálogo e historia si la BD está vacía. */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        bloqueo.ejecutar(CARGA, () -> {
            ingesta.cargaInicial();
            if (props.modoDemo()) {
                usuarios.crearDemo();
            }
        });
    }

    @Scheduled(fixedDelayString = "${asimetria.intervalo-cotizaciones}", initialDelayString = "${asimetria.intervalo-cotizaciones}",
            timeUnit = TimeUnit.SECONDS)
    public void sincronizarCotizaciones() {
        bloqueo.ejecutar(COTIZACIONES, cotizaciones::sincronizar);
    }

    /** HU-02 CA-2: cierre de NYSE/NASDAQ ≈ 21:00 UTC; lunes a viernes a las 21:30 UTC. */
    @Scheduled(cron = "0 30 21 * * MON-FRI", zone = "UTC")
    public void cierreDiario() {
        bloqueo.ejecutar(CIERRE, () -> log.info("Cierre diario: {} precios nuevos", ingesta.cierreDiario(LocalDate.now(reloj))));
    }

    /** RNF-04: retención de 90 días. */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void purgarAuditoria() {
        bloqueo.ejecutar(PURGA, () -> log.info("Purga de auditoría: {} registros", auditoria.purgar()));
    }
}
