package co.edu.ucc.asimetria.auditoria;

import co.edu.ucc.asimetria.persistencia.Auditoria;
import co.edu.ucc.asimetria.persistencia.AuditoriaRepositorio;
import co.edu.ucc.asimetria.persistencia.LlamadaExterna;
import co.edu.ucc.asimetria.persistencia.LlamadaExternaRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Trazabilidad (RNF-04): eventos críticos de usuario y llamadas a servicios externos.
 *
 * <p>Doble destino: tabla consultable desde la app y log JSON estructurado (CloudWatch Logs en
 * AWS). Cada registro va en su propia transacción ({@code REQUIRES_NEW}): un intento de login
 * fallido queda auditado aunque la operación principal se revierta.
 */
@Service
public class AuditoriaService {

    public static final Duration RETENCION = Duration.ofDays(90);
    private static final Logger log = LoggerFactory.getLogger("asimetria.auditoria");

    private final AuditoriaRepositorio auditoria;
    private final LlamadaExternaRepositorio llamadas;
    private final Clock reloj;

    public AuditoriaService(AuditoriaRepositorio auditoria, LlamadaExternaRepositorio llamadas, Clock reloj) {
        this.auditoria = auditoria;
        this.llamadas = llamadas;
        this.reloj = reloj;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(String evento, String estado, Usuario usuario, String email, String ip, String detalle) {
        Instant ahora = reloj.instant();
        String correo = usuario != null ? usuario.getEmail() : email;
        String det = recortar(detalle, 255);
        log.atInfo().setMessage("auditoria")
                .addKeyValue("evento", evento).addKeyValue("estado", estado)
                .addKeyValue("usuario_id", usuario != null ? usuario.getId() : null)
                .addKeyValue("email", correo).addKeyValue("ip", ip).addKeyValue("detalle", det).log();
        auditoria.save(new Auditoria(ahora, usuario != null ? usuario.getId() : null, correo, ip, evento, estado, det));
    }

    public void exito(String evento, Usuario usuario, String ip, String detalle) {
        registrar(evento, "EXITO", usuario, null, ip, detalle);
    }

    public void error(String evento, Usuario usuario, String email, String ip, String detalle) {
        registrar(evento, "ERROR", usuario, email, ip, detalle);
    }

    /** Traza de una llamada externa. Nunca lanza: la trazabilidad no debe romper el flujo de negocio. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarLlamada(String proveedor, String operacion, boolean exito, Integer httpStatus, long latenciaMs, String detalle) {
        String det = recortar(detalle, 255);
        log.atInfo().setMessage("llamada_externa")
                .addKeyValue("proveedor", proveedor).addKeyValue("operacion", operacion).addKeyValue("exito", exito)
                .addKeyValue("http_status", httpStatus).addKeyValue("latencia_ms", latenciaMs).addKeyValue("detalle", det).log();
        try {
            llamadas.save(new LlamadaExterna(reloj.instant(), proveedor, recortar(operacion, 40), exito, httpStatus, (int) latenciaMs, det));
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar la traza de la llamada externa: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Auditoria> delUsuario(Long usuarioId) {
        return auditoria.findTop100ByUsuarioIdOrderByIdDesc(usuarioId);
    }

    /** Retención de 90 días para auditoría y trazas (tarea diaria). */
    @Transactional
    public int purgar() {
        Instant limite = reloj.instant().minus(RETENCION);
        return auditoria.purgarAnteriores(limite) + llamadas.purgarAnteriores(limite);
    }

    private static String recortar(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
