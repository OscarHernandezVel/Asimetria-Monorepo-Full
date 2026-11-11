package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;
import co.edu.ucc.asimetria.mercado.Vistas.CotizacionesActualizadas;
import co.edu.ucc.asimetria.mercado.Vistas.CotizacionesVista;
import co.edu.ucc.asimetria.mercado.Vistas.EstadoCotizaciones;
import co.edu.ucc.asimetria.mercado.Vistas.Tick;
import co.edu.ucc.asimetria.mercado.proveedor.ErrorProveedor;
import co.edu.ucc.asimetria.mercado.proveedor.ProveedorMercado;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio.UltimoCierre;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Cotizaciones en vivo (RF-01, RF-09, RNF-06).
 *
 * <p>{@link #sincronizar()} lo ejecuta una sola instancia (bloqueo distribuido) cada 5 s: pide los
 * precios al proveedor (con reintentos y circuit breaker) y los publica en el almacén compartido,
 * más un buffer circular de 120 ticks por activo. Después emite {@link CotizacionesActualizadas}
 * (Observer): el evaluador de alertas reacciona sin que este servicio lo conozca.
 *
 * <p>{@link #actuales()} lo lee cualquier instancia y <b>nunca falla por el proveedor</b>: si no hay
 * dato en vivo, usa el último cierre de la base de datos y marca el estado como desactualizado.
 */
@Service
public class CotizacionService {

    private static final String SEP = ";";
    private static final int TICKS = 120;
    private static final String CLAVE_ESTADO = "cot:estado";

    private final ProveedorMercado proveedor;
    private final PreciosRepositorio precios;
    private final AlmacenCompartido almacen;
    private final ApplicationEventPublisher eventos;
    private final Clock reloj;

    public CotizacionService(ProveedorMercado proveedor, PreciosRepositorio precios, AlmacenCompartido almacen,
                             ApplicationEventPublisher eventos, Clock reloj) {
        this.proveedor = proveedor;
        this.precios = precios;
        this.almacen = almacen;
        this.eventos = eventos;
        this.reloj = reloj;
    }

    public EstadoCotizaciones sincronizar() {
        Map<String, Double> ultimos = new LinkedHashMap<>();
        precios.ultimosCierres().forEach((t, u) -> ultimos.put(t, precioVivo(t).orElse(u.ultimo())));
        if (ultimos.isEmpty()) {
            return estado();
        }
        Instant ahora = reloj.instant();
        try {
            Map<String, Double> nuevos = proveedor.cotizaciones(ultimos);
            nuevos.forEach((t, p) -> {
                String valor = p + SEP + ahora.toEpochMilli();
                almacen.guardar("cot:" + t, valor, Duration.ofDays(1));
                almacen.agregarAcotado("ticks:" + t, valor, TICKS);
            });
            guardarEstado(false, "", ahora.toString());
            eventos.publishEvent(new CotizacionesActualizadas(nuevos.keySet(), ahora));
        } catch (ErrorProveedor e) {
            guardarEstado(true, e.getMessage(), estado().ultimaActualizacion());
        }
        return estado();
    }

    public CotizacionesVista actuales() {
        LocalDate hoy = LocalDate.now(reloj);
        Map<String, Cotizacion> cot = new TreeMap<>();
        precios.ultimosCierres().forEach((t, u) -> cot.put(t, cotizacion(t, u, hoy)));
        return new CotizacionesVista(estado(), cot);
    }

    /** Con dato en vivo: variación intradía frente al último cierre previo a hoy. Sin él: último cierre y su variación. */
    public static Cotizacion calcular(Optional<Double> vivo, UltimoCierre u, LocalDate hoy) {
        return vivo.map(p -> Cotizacion.de(p, u.fecha().equals(hoy) ? u.anterior() : u.ultimo()))
                .orElseGet(() -> Cotizacion.de(u.ultimo(), u.anterior()));
    }

    private Cotizacion cotizacion(String ticker, UltimoCierre u, LocalDate hoy) {
        return calcular(precioVivo(ticker), u, hoy);
    }

    public Optional<Double> precioVivo(String ticker) {
        return almacen.obtener("cot:" + ticker).map(v -> Double.parseDouble(v.split(SEP)[0]));
    }

    public List<Tick> intradia(String ticker) {
        return almacen.listar("ticks:" + ticker).stream().map(v -> {
            String[] p = v.split(SEP);
            return new Tick(Double.parseDouble(p[0]), Instant.ofEpochMilli(Long.parseLong(p[1])).toString());
        }).toList();
    }

    public EstadoCotizaciones estado() {
        return almacen.obtener(CLAVE_ESTADO).map(v -> {
            String[] p = v.split(SEP, -1);
            return new EstadoCotizaciones("1".equals(p[0]), p[1].isEmpty() ? null : p[1], p[2].isEmpty() ? null : p[2]);
        }).orElse(new EstadoCotizaciones(true, "sin datos en vivo aún", null));
    }

    private void guardarEstado(boolean desactualizado, String motivo, String ultima) {
        almacen.guardar(CLAVE_ESTADO, (desactualizado ? "1" : "0") + SEP + motivo.replace(SEP, ",") + SEP + (ultima == null ? "" : ultima), null);
    }
}
