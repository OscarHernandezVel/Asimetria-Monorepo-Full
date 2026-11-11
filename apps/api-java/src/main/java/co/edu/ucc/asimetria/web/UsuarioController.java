package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.alerta.AlertaService;
import co.edu.ucc.asimetria.alerta.AlertaService.AlertaVista;
import co.edu.ucc.asimetria.alerta.AlertaService.NotificacionVista;
import co.edu.ucc.asimetria.alerta.EvaluadorAlertas;
import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.persistencia.Auditoria;
import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.usuario.SimulacionService;
import co.edu.ucc.asimetria.usuario.SimulacionService.HistorialVista;
import co.edu.ucc.asimetria.usuario.SimulacionService.SimulacionVista;
import co.edu.ucc.asimetria.usuario.WatchlistService;
import co.edu.ucc.asimetria.usuario.WatchlistService.WatchVista;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Recursos del usuario: watchlist (RF-08), simulaciones (RF-05), alertas (RF-06) y auditoría (RNF-04).
 * Toda consulta filtra por el usuario del token: nadie ve los datos de otro (HU-08 CA-3).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Usuario")
public class UsuarioController {

    public record AuditoriaVista(Instant ts, String email, String ip, String evento, String estado, String detalle) {
        static AuditoriaVista de(Auditoria a) {
            return new AuditoriaVista(a.getTs(), a.getEmail(), a.getIp(), a.getEvento(), a.getEstado(), a.getDetalle());
        }
    }

    private final WatchlistService watchlist;
    private final SimulacionService simulaciones;
    private final AlertaService alertas;
    private final EvaluadorAlertas evaluador;
    private final AuditoriaService auditoria;
    private final IpCliente ip;

    public UsuarioController(WatchlistService watchlist, SimulacionService simulaciones, AlertaService alertas,
                             EvaluadorAlertas evaluador, AuditoriaService auditoria, IpCliente ip) {
        this.watchlist = watchlist;
        this.simulaciones = simulaciones;
        this.alertas = alertas;
        this.evaluador = evaluador;
        this.auditoria = auditoria;
        this.ip = ip;
    }

    // ---------------------------------------------------------------- watchlist (HU-08)
    @GetMapping("/watchlist")
    public List<WatchVista> watchlist(Usuario u) {
        return watchlist.listar(u);
    }

    @PostMapping("/watchlist")
    @Operation(summary = "Agregar a la lista (409 si ya está: HU-08 CA-4)")
    public ResponseEntity<WatchVista> agregar(Usuario u, @Valid @RequestBody Solicitudes.Watch s, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlist.agregar(u, s.ticker(), ip.de(req)));
    }

    @PatchMapping("/watchlist/{ticker}")
    public WatchVista actualizar(Usuario u, @PathVariable String ticker, @Valid @RequestBody Solicitudes.CambioWatch s, HttpServletRequest req) {
        return watchlist.actualizar(u, ticker, s.cantidad(), s.favorito(), ip.de(req));
    }

    @DeleteMapping("/watchlist/{ticker}")
    public ResponseEntity<Void> quitar(Usuario u, @PathVariable String ticker, HttpServletRequest req) {
        watchlist.quitar(u, ticker, ip.de(req));
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- simulaciones (HU-05)
    @PostMapping("/simulaciones")
    @Operation(summary = "Backtesting comprar-y-mantener frente al NASDAQ 100")
    public ResponseEntity<SimulacionVista> simular(Usuario u, @Valid @RequestBody Solicitudes.Simulacion s, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(simulaciones.simular(u, s.ticker(), s.monto(), s.fechaInicio(), s.fechaFin(), ip.de(req)));
    }

    @GetMapping("/simulaciones")
    public List<HistorialVista> historial(Usuario u) {
        return simulaciones.historial(u);
    }

    // ---------------------------------------------------------------- alertas (HU-06)
    @GetMapping("/alertas")
    public List<AlertaVista> alertas(Usuario u) {
        return alertas.listar(u);
    }

    @PostMapping("/alertas")
    public ResponseEntity<AlertaVista> crearAlerta(Usuario u, @Valid @RequestBody Solicitudes.Alerta s, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(alertas.crear(u, s.ticker(), s.tipo(), s.umbral(), s.canal(), ip.de(req)));
    }

    @PatchMapping("/alertas/{id}")
    public AlertaVista cambiarAlerta(Usuario u, @PathVariable long id, @Valid @RequestBody Solicitudes.CambioAlerta s, HttpServletRequest req) {
        return alertas.cambiarEstado(u, id, s.estado(), ip.de(req));
    }

    @DeleteMapping("/alertas/{id}")
    public ResponseEntity<Void> eliminarAlerta(Usuario u, @PathVariable long id, HttpServletRequest req) {
        alertas.eliminar(u, id, ip.de(req));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/alertas/evaluar")
    @Operation(summary = "Ejecuta ahora la misma revisión que el worker, solo sobre mis reglas")
    public EvaluadorAlertas.Resultado evaluar(Usuario u) {
        return evaluador.evaluar(u.getId());
    }

    @GetMapping("/notificaciones")
    public List<NotificacionVista> notificaciones(Usuario u) {
        return alertas.notificaciones(u);
    }

    // ---------------------------------------------------------------- auditoría (RNF-04)
    @GetMapping("/auditoria")
    public List<AuditoriaVista> auditoria(Usuario u) {
        return auditoria.delUsuario(u.getId()).stream().map(AuditoriaVista::de).toList();
    }
}
