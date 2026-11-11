package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.dominio.mercado.Cotizacion;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Modelos de lectura del módulo de mercado. Son el contrato JSON de la API (snake_case global) y
 * son {@link Serializable} porque se guardan en la caché Redis (RNF-01).
 */
public final class Vistas {

    private Vistas() {
    }

    public record EmpresaVista(String ticker, String nombre, String sector, boolean esIndice) implements Serializable {
    }

    public record SerieVista(String ticker, List<LocalDate> fechas, List<Double> cierres) {
    }

    public record EstadoCotizaciones(boolean desactualizado, String motivo, String ultimaActualizacion) {
    }

    public record CotizacionesVista(EstadoCotizaciones estado, Map<String, Cotizacion> cotizaciones) {
    }

    /** Tick intradía guardado en el buffer circular. */
    public record Tick(double precio, String ts) {
    }

    public record MetricaVista(String ticker, String nombre, String sector, Double betaAlcista, Double betaBajista,
                               Double beta, Double ratioAsimetria, Double correlacion, double volatilidad,
                               String nivelVolatilidad, Double retornoEsperado, Double dividendos, String error,
                               List<Double> tendencia, String fechaActualizacion) implements Serializable {
    }

    /** Recomendación = métrica + puntaje del perfil (las propiedades de la métrica se aplanan en el JSON). */
    public record RecomendacionVista(@JsonUnwrapped MetricaVista metrica, double ratioRiesgoBeneficio,
                                     double puntaje) implements Serializable {
    }

    public record DiaDesglose(String fecha, double indice, double activo, Double ratio) implements Serializable {
    }

    public record PromedioDesglose(double indice, double activo, Double ratio) implements Serializable {
    }

    public record Desglose(DiaDesglose alcista, DiaDesglose bajista, PromedioDesglose promedio) implements Serializable {
    }

    public record AnalisisVista(String ticker, String nombre, int ventana, List<String> fechas,
                                List<Double> base100Activo, List<Double> base100Indice, double retorno,
                                double retornoIndice, double alfa, double maxDrawdown, List<double[]> dispersion,
                                Desglose desglose, MetricaVista metricas) implements Serializable {
    }

    public record DestacadoVista(String ticker, String nombre, Double beta, Double ratioAsimetria,
                                 String nivelVolatilidad, @JsonProperty("retorno_30d") double retorno30d,
                                 List<Double> base100Activo, List<Double> base100Indice) implements Serializable {
    }

    /** Eventos de dominio (patrón Observer vía {@code ApplicationEventPublisher}). */
    public record CotizacionesActualizadas(java.util.Set<String> tickers, java.time.Instant ts) {
    }

    public record CatalogoActualizado() {
    }
}
