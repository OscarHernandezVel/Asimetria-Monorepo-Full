package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.dominio.mercado.Barra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>Decorator</b> de resiliencia (RNF-06, HU-01 CA-3/CA-4): reintentos con espera exponencial
 * (150 → 300 → 600 ms) y {@link CircuitBreaker}. Si el circuito está abierto no se llama al
 * proveedor y los servicios usan los últimos datos almacenados.
 */
public final class ProveedorResiliente implements ProveedorMercado {

    private static final Logger log = LoggerFactory.getLogger(ProveedorResiliente.class);

    /** Permite a las pruebas reintentar sin esperar de verdad. */
    @FunctionalInterface
    public interface Pausa {
        void dormir(Duration d) throws InterruptedException;
    }

    private final ProveedorMercado base;
    private final CircuitBreaker circuito;
    private final int intentos;
    private final Duration esperaBase;
    private final Pausa pausa;

    public ProveedorResiliente(ProveedorMercado base, CircuitBreaker circuito, int intentos, Duration esperaBase, Pausa pausa) {
        this.base = base;
        this.circuito = circuito;
        this.intentos = intentos;
        this.esperaBase = esperaBase;
        this.pausa = pausa;
    }

    public CircuitBreaker circuito() {
        return circuito;
    }

    private <T> T conResiliencia(String operacion, Supplier<T> llamada) {
        if (!circuito.permiteLlamar()) {
            throw new ErrorProveedor.CircuitoAbierto("Circuito abierto: se usan los últimos datos almacenados");
        }
        for (int i = 0; i < intentos; i++) {
            try {
                T r = llamada.get();
                circuito.registrarExito();
                return r;
            } catch (ErrorProveedor.LimiteExcedido e) {
                circuito.registrarFallo();
                throw e;   // reintentar no ayuda si la cuota se agotó
            } catch (RuntimeException e) {
                log.atWarn().setMessage("fallo del proveedor").addKeyValue("evento", "PROVEEDOR_ERROR")
                        .addKeyValue("operacion", operacion).addKeyValue("intento", i + 1).addKeyValue("detalle", e.getMessage()).log();
                if (i < intentos - 1) {
                    esperar(esperaBase.multipliedBy(1L << i));
                }
            }
        }
        circuito.registrarFallo();
        throw new ErrorProveedor("Proveedor no disponible tras " + intentos + " intentos");
    }

    private void esperar(Duration d) {
        try {
            pausa.dormir(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErrorProveedor("Interrumpido durante el reintento");
        }
    }

    @Override
    public String nombre() {
        return base.nombre();
    }

    @Override
    public List<EntradaCatalogo> catalogo() {
        return base.catalogo();
    }

    @Override
    public List<Barra> historico(String ticker, int dias) {
        return conResiliencia("historico", () -> base.historico(ticker, dias));
    }

    @Override
    public Map<String, Double> cotizaciones(Map<String, Double> ultimos) {
        return conResiliencia("cotizaciones", () -> base.cotizaciones(ultimos));
    }

    @Override
    public Double dividendos(String ticker) {
        return conResiliencia("dividendos", () -> base.dividendos(ticker));
    }
}
