package co.edu.ucc.asimetria.alerta;

import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion;
import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.Mensaje;
import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.ResultadoEnvio;
import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.dominio.alerta.CondicionAlerta;
import co.edu.ucc.asimetria.dominio.alerta.Condiciones;
import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;
import co.edu.ucc.asimetria.mercado.CotizacionService;
import co.edu.ucc.asimetria.mercado.Vistas.CotizacionesActualizadas;
import co.edu.ucc.asimetria.persistencia.Notificacion;
import co.edu.ucc.asimetria.persistencia.NotificacionRepositorio;
import co.edu.ucc.asimetria.persistencia.ReglaAlerta;
import co.edu.ucc.asimetria.persistencia.ReglaAlertaRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.persistencia.UsuarioRepositorio;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Evaluador de alertas (RF-06, HU-06 CA-2/CA-3/CA-4).
 *
 * <p><b>Observer</b>: escucha {@link CotizacionesActualizadas}; cada nueva cotización dispara la
 * revisión sin que el servicio de cotizaciones conozca a este. Usa la <b>Factory</b> de condiciones
 * (Strategy por tipo) y la <b>cadena</b> de canales para notificar.
 */
@Service
public class EvaluadorAlertas {

    public record Resultado(int disparadas, int pendientes) {
    }

    private final ReglaAlertaRepositorio reglas;
    private final UsuarioRepositorio usuarios;
    private final NotificacionRepositorio notificaciones;
    private final CotizacionService cotizaciones;
    private final CanalNotificacion canales;
    private final AuditoriaService auditoria;

    public EvaluadorAlertas(ReglaAlertaRepositorio reglas, UsuarioRepositorio usuarios, NotificacionRepositorio notificaciones,
                            CotizacionService cotizaciones, CanalNotificacion canales, AuditoriaService auditoria) {
        this.reglas = reglas;
        this.usuarios = usuarios;
        this.notificaciones = notificaciones;
        this.cotizaciones = cotizaciones;
        this.canales = canales;
        this.auditoria = auditoria;
    }

    /** Transaccional propio: la llamada interna a {@link #evaluar} no pasa por el proxy de Spring. */
    @EventListener
    @Transactional
    public void alActualizarCotizaciones(CotizacionesActualizadas evento) {
        evaluar(null);
    }

    /** @param usuarioId {@code null} = todas las reglas (worker); un id = solo las de ese usuario («Evaluar ahora»). */
    @Transactional
    public Resultado evaluar(Long usuarioId) {
        Map<String, Cotizacion> cot = cotizaciones.actuales().cotizaciones();
        int disparadas = 0, pendientes = 0;
        for (ReglaAlerta r : reglas.evaluables(usuarioId)) {
            Cotizacion c = cot.get(r.getEmpresa().getTicker());
            if (c == null) {
                continue;
            }
            CondicionAlerta condicion = Condiciones.de(r.getTipoCondicion());
            if (!r.isPendienteEnvio() && !condicion.seCumple(c, r.getUmbral())) {
                continue;   // CA-3: no se cumple → no se notifica
            }
            Usuario u = usuarios.findById(r.getUsuarioId()).orElse(null);
            if (u == null) {
                continue;
            }
            String texto = condicion.describir(r.getEmpresa().getNombreEmpresa(), c, r.getUmbral());
            try {
                ResultadoEnvio envio = canales.enviar(new Mensaje(u.getEmail(), "Alerta de Asimetría", texto, r.getCanal()));
                notificaciones.save(new Notificacion(u.getId(), r.getId(), texto, r.getCanal(), envio.estadoEnvio(), envio.proveedorId()));
                r.marcarDisparada(false);
                disparadas++;
                auditoria.exito("ALERTA_DISPARADA", u, null, texto);
            } catch (CanalNotificacion.ErrorNotificacion e) {
                r.marcarDisparada(true);   // CA-4: queda pendiente y se reintenta, la alerta no se pierde
                pendientes++;
                auditoria.error("ALERTA_ENVIO", u, null, null, e.getMessage());
            }
            reglas.save(r);
        }
        return new Resultado(disparadas, pendientes);
    }
}
