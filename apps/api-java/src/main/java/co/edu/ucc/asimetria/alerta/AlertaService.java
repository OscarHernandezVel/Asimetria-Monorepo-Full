package co.edu.ucc.asimetria.alerta;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.dominio.alerta.TipoCondicion;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.Notificacion;
import co.edu.ucc.asimetria.persistencia.NotificacionRepositorio;
import co.edu.ucc.asimetria.persistencia.ReglaAlerta;
import co.edu.ucc.asimetria.persistencia.ReglaAlertaRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** CRUD de reglas de alerta (RF-06, HU-06 CA-1) y consulta de notificaciones. */
@Service
public class AlertaService {

    public record AlertaVista(Long id, String ticker, String nombre, String tipo, double umbral, String canal,
                              String estado, boolean pendienteEnvio, Instant fechaCreacion) {
        static AlertaVista de(ReglaAlerta r) {
            Empresa e = r.getEmpresa();
            return new AlertaVista(r.getId(), e.getTicker(), e.getNombreEmpresa(), r.getTipoCondicion(), r.getUmbral(),
                    r.getCanal(), r.getEstado(), r.isPendienteEnvio(), r.getFechaCreacion());
        }
    }

    public record NotificacionVista(String texto, String canal, String estadoEnvio, String proveedorId, Instant fecha) {
        static NotificacionVista de(Notificacion n) {
            return new NotificacionVista(n.getTexto(), n.getCanal(), n.getEstadoEnvio(), n.getProveedorId(), n.getFecha());
        }
    }

    private static final Set<String> CANALES = Set.of("CORREO", "PUSH");

    private final ReglaAlertaRepositorio reglas;
    private final EmpresaRepositorio empresas;
    private final NotificacionRepositorio notificaciones;
    private final AuditoriaService auditoria;

    public AlertaService(ReglaAlertaRepositorio reglas, EmpresaRepositorio empresas, NotificacionRepositorio notificaciones,
                         AuditoriaService auditoria) {
        this.reglas = reglas;
        this.empresas = empresas;
        this.notificaciones = notificaciones;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public List<AlertaVista> listar(Usuario u) {
        return reglas.listar(u.getId()).stream().map(AlertaVista::de).toList();
    }

    @Transactional
    public AlertaVista crear(Usuario u, String ticker, String tipo, double umbral, String canal, String ip) {
        Empresa e = empresas.findByTickerAndActivaTrue(ticker.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
        try {
            TipoCondicion.valueOf(tipo);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.invalido("Tipo de condición no válido");
        }
        if (!(umbral > 0) || umbral >= 1e8) {
            throw ApiException.invalido("El umbral debe ser un número mayor que 0");
        }
        String c = canal == null ? "CORREO" : canal;
        if (!CANALES.contains(c)) {
            throw ApiException.invalido("Canal no válido");
        }
        ReglaAlerta r = reglas.save(new ReglaAlerta(u.getId(), e, tipo, BigDecimal.valueOf(umbral), c));
        auditoria.exito("ALERTA_CREAR", u, ip, e.getTicker() + " " + tipo + " " + umbral);
        return AlertaVista.de(r);
    }

    @Transactional
    public AlertaVista cambiarEstado(Usuario u, long id, String estado, String ip) {
        if (!ReglaAlerta.ACTIVA.equals(estado) && !ReglaAlerta.INACTIVA.equals(estado)) {
            throw ApiException.invalido("Estado no válido");
        }
        ReglaAlerta r = propia(u, id);
        r.cambiarEstado(estado);
        auditoria.exito("ALERTA_ESTADO", u, ip, id + " → " + estado);
        return AlertaVista.de(reglas.save(r));
    }

    @Transactional
    public void eliminar(Usuario u, long id, String ip) {
        reglas.delete(propia(u, id));
        auditoria.exito("ALERTA_ELIMINAR", u, ip, String.valueOf(id));
    }

    @Transactional(readOnly = true)
    public List<NotificacionVista> notificaciones(Usuario u) {
        return notificaciones.findTop20ByUsuarioIdOrderByIdDesc(u.getId()).stream().map(NotificacionVista::de).toList();
    }

    /** Una regla ajena «no existe» para quien la pide (404, no 403): no se revela su existencia. */
    private ReglaAlerta propia(Usuario u, long id) {
        ReglaAlerta r = reglas.findById(id).orElse(null);
        if (r == null || !r.getUsuarioId().equals(u.getId())) {
            throw ApiException.noEncontrado("Alerta no encontrada");
        }
        return r;
    }
}
