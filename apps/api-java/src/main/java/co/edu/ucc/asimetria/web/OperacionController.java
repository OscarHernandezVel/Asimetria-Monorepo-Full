package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.alerta.notificacion.Canales;
import co.edu.ucc.asimetria.alerta.notificacion.CanalNotificacion.ErrorNotificacion;
import co.edu.ucc.asimetria.alerta.notificacion.ClienteSendGrid;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.mercado.proveedor.ErrorProveedor;
import co.edu.ucc.asimetria.mercado.proveedor.FabricaProveedores;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorAlphaVantage;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorResiliente;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorSimulado;
import co.edu.ucc.asimetria.persistencia.LlamadaExterna;
import co.edu.ucc.asimetria.persistencia.LlamadaExternaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operación: salud (RNF-05), trazabilidad de integraciones e inyección de fallos (RNF-06). */
@RestController
@Tag(name = "Operación")
public class OperacionController {

    private final JdbcTemplate jdbc;
    private final AlmacenCompartido almacen;
    private final PropiedadesAsimetria props;
    private final LlamadaExternaRepositorio llamadas;
    private final PreciosRepositorio precios;
    private final ProveedorResiliente proveedor;
    private final ClienteSendGrid sendgrid;
    private final Clock reloj;

    public OperacionController(JdbcTemplate jdbc, AlmacenCompartido almacen, PropiedadesAsimetria props,
                               LlamadaExternaRepositorio llamadas, PreciosRepositorio precios, ProveedorResiliente proveedor,
                               ClienteSendGrid sendgrid, Clock reloj) {
        this.jdbc = jdbc;
        this.almacen = almacen;
        this.props = props;
        this.llamadas = llamadas;
        this.precios = precios;
        this.proveedor = proveedor;
        this.sendgrid = sendgrid;
        this.reloj = reloj;
    }

    /** Sonda del balanceador (ALB) y del monitor externo: 200 si la BD responde; la caché puede degradarse. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> salud() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        boolean cache = almacen.disponible();
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("estado", db && cache ? "ok" : "degradado");
        cuerpo.put("db", db);
        cuerpo.put("cache", cache);
        cuerpo.put("cache_tipo", almacen.tipo());
        return ResponseEntity.status(db ? 200 : 503).body(cuerpo);
    }

    private void exigirHerramientas() {
        if (!props.herramientasPrueba()) {
            throw ApiException.noEncontrado("No encontrado");
        }
    }

    @GetMapping("/api/v1/demo/fallos")
    public Solicitudes.FallosDemo verFallos(Usuario u) {
        exigirHerramientas();
        return new Solicitudes.FallosDemo(bandera(ProveedorSimulado.BANDERA_CAIDO), bandera(Canales.BANDERA_CORREO_CAIDO));
    }

    @PutMapping("/api/v1/demo/fallos")
    @Operation(summary = "Inyección de fallos (solo HERRAMIENTAS_PRUEBA=1; afecta a todos los usuarios)")
    public Solicitudes.FallosDemo fijarFallos(Usuario u, @RequestBody Solicitudes.FallosDemo f) {
        exigirHerramientas();
        almacen.guardar(ProveedorSimulado.BANDERA_CAIDO, String.valueOf(f.proveedorCaido()), null);
        almacen.guardar(Canales.BANDERA_CORREO_CAIDO, String.valueOf(f.correoCaido()), null);
        return f;
    }

    private boolean bandera(String clave) {
        return almacen.obtener(clave).map(Boolean::parseBoolean).orElse(false);
    }

    @GetMapping("/api/v1/integraciones")
    @Operation(summary = "Estado de las integraciones (sin claves), cuota, latencias, trazas y origen de los precios")
    public Map<String, Object> integraciones(Usuario u) {
        Instant desde = reloj.instant().minus(Duration.ofHours(24));
        Map<String, Object> av = new LinkedHashMap<>();
        av.put("proveedor", ProveedorAlphaVantage.NOMBRE);
        av.put("configurado", props.alphavantage().configurado());
        av.put("en_uso_para_precios", ProveedorAlphaVantage.NOMBRE.equals(proveedor.nombre()));
        av.put("cuota_usada_hoy", almacen.obtener("cuota:alphavantage:" + java.time.LocalDate.now(reloj)).map(Long::parseLong).orElse(0L));
        av.put("cuota_limite_diaria", props.alphavantage().limiteDiario());
        av.put("circuito", proveedor.circuito().estado().name());
        av.putAll(resumen(ProveedorAlphaVantage.NOMBRE, desde));
        Map<String, Object> sg = new LinkedHashMap<>();
        sg.put("proveedor", "SENDGRID");
        sg.put("configurado", props.sendgrid().configurado());
        sg.put("remitente", props.sendgrid().configurado() ? props.sendgrid().remitente().replaceAll("^.*@", "") : null);
        sg.putAll(resumen("SENDGRID", desde));
        List<Map<String, Object>> recientes = llamadas.findTop30ByOrderByIdDesc().stream().map(OperacionController::traza).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proveedores", List.of(av, sg));
        out.put("recientes", recientes);
        out.put("origen_precios", precios.contarPorFuente().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fuente", c.fuente());
            m.put("filas", c.filas());
            m.put("ultima_ingesta", c.ultimaIngesta() == null ? null : c.ultimaIngesta().toString());
            return m;
        }).toList());
        return out;
    }

    private Map<String, Object> resumen(String proveedorNombre, Instant desde) {
        List<LlamadaExterna> filas = llamadas.findByProveedorAndTsAfterOrderByTs(proveedorNombre, desde);
        List<Integer> lat = filas.stream().filter(f -> f.getHttpStatus() != null).map(LlamadaExterna::getLatenciaMs).sorted().toList();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("llamadas_24h", filas.size());
        r.put("exitosas_24h", filas.stream().filter(LlamadaExterna::isExito).count());
        r.put("latencia_p95_ms", lat.isEmpty() ? null : lat.get(Math.max(0, (int) Math.ceil(lat.size() * 0.95) - 1)));
        r.put("ultima", filas.isEmpty() ? null : traza(filas.get(filas.size() - 1)));
        return r;
    }

    private static Map<String, Object> traza(LlamadaExterna l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", l.getTs().toString());
        m.put("proveedor", l.getProveedor());
        m.put("operacion", l.getOperacion());
        m.put("exito", l.isExito());
        m.put("http_status", l.getHttpStatus());
        m.put("latencia_ms", l.getLatenciaMs());
        m.put("detalle", l.getDetalle());
        return m;
    }

    @PostMapping("/api/v1/integraciones/probar")
    @Operation(summary = "Verifica las claves reales: 1 consulta a Alpha Vantage y SendGrid en sandbox (no envía correos)")
    public Map<String, Map<String, Object>> probar(Usuario u) {
        exigirHerramientas();
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();
        if (!props.alphavantage().configurado()) {
            r.put("ALPHAVANTAGE", Map.of("ok", false, "detalle", "No configurado (ALPHAVANTAGE_API_KEY vacío)"));
        } else {
            try {
                Double qqq = FabricaProveedores.alphaVantage(props, almacen, reloj).cotizaciones(Map.of("^NDX", 0.0)).get("^NDX");
                r.put("ALPHAVANTAGE", Map.of("ok", true, "detalle", "QQQ (proxy NASDAQ 100) = " + qqq + " USD"));
            } catch (RuntimeException e) {   // ErrorProveedor, red o JSON inesperado
                r.put("ALPHAVANTAGE", Map.of("ok", false, "detalle", String.valueOf(e.getMessage())));
            }
        }
        if (!sendgrid.configurado()) {
            r.put("SENDGRID", Map.of("ok", false, "detalle", "No configurado (SENDGRID_API_KEY vacío)"));
        } else {
            try {
                sendgrid.enviar(props.sendgrid().remitente(), "Prueba de integración Asimetría", "Prueba sandbox", true);
                r.put("SENDGRID", Map.of("ok", true, "detalle", "Clave y remitente aceptados (sandbox: no se envió correo)"));
            } catch (ErrorNotificacion e) {
                r.put("SENDGRID", Map.of("ok", false, "detalle", e.getMessage()));
            }
        }
        return r;
    }
}
