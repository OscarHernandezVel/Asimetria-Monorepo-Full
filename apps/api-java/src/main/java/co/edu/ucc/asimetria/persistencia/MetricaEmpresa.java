package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Métricas precalculadas por el recálculo diario (HU-03, HU-10). */
@Entity
@Table(name = "metricas_empresa")
public class MetricaEmpresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empresa_id", nullable = false, unique = true)
    private Long empresaId;

    @Column(name = "beta_alcista", precision = 8, scale = 4)
    private BigDecimal betaAlcista;

    @Column(name = "beta_bajista", precision = 8, scale = 4)
    private BigDecimal betaBajista;

    @Column(precision = 8, scale = 4)
    private BigDecimal beta;

    @Column(precision = 8, scale = 4)
    private BigDecimal correlacion;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal volatilidad;

    @Column(name = "retorno_esperado", precision = 8, scale = 4)
    private BigDecimal retornoEsperado;

    @Column(name = "rendimiento_dividendos", precision = 8, scale = 4)
    private BigDecimal rendimientoDividendos;

    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant fechaActualizacion = Instant.now();

    protected MetricaEmpresa() {
    }

    public MetricaEmpresa(Long empresaId) {
        this.empresaId = empresaId;
    }

    /** Actualiza todas las métricas a la vez (las nulas quedan nulas: datos insuficientes). */
    public void actualizar(Double betaAlcista, Double betaBajista, Double beta, Double correlacion, double volatilidad,
                           Double retornoEsperado, Double dividendos, Instant ahora) {
        this.betaAlcista = dec(betaAlcista);
        this.betaBajista = dec(betaBajista);
        this.beta = dec(beta);
        this.correlacion = dec(correlacion);
        this.volatilidad = dec(volatilidad);
        this.retornoEsperado = dec(retornoEsperado);
        if (dividendos != null) {
            this.rendimientoDividendos = dec(dividendos);
        }
        this.fechaActualizacion = ahora;
    }

    private static BigDecimal dec(Double v) {
        return v == null || v.isNaN() || v.isInfinite() ? null : BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static Double num(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    public Long getEmpresaId() { return empresaId; }
    public Double getBetaAlcista() { return num(betaAlcista); }
    public Double getBetaBajista() { return num(betaBajista); }
    public Double getBeta() { return num(beta); }
    public Double getCorrelacion() { return num(correlacion); }
    public double getVolatilidad() { return volatilidad.doubleValue(); }
    public Double getRetornoEsperado() { return num(retornoEsperado); }
    public Double getRendimientoDividendos() { return num(rendimientoDividendos); }
    public Instant getFechaActualizacion() { return fechaActualizacion; }
}
