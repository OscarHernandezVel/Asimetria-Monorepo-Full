package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** Registro de cada backtesting ejecutado (HU-05). */
@Entity
@Table(name = "simulaciones_portafolio")
public class SimulacionPortafolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    @Column(name = "monto_inicial", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoInicial;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio;

    @Column(name = "fecha_fin", nullable = false)
    private LocalDate fechaFin;

    @Column(name = "valor_final", nullable = false, precision = 12, scale = 2)
    private BigDecimal valorFinal;

    @Column(name = "fecha_ejecucion", nullable = false)
    private Instant fechaEjecucion = Instant.now();

    protected SimulacionPortafolio() {
    }

    public SimulacionPortafolio(Long usuarioId, Empresa empresa, double monto, LocalDate inicio, LocalDate fin, double valorFinal) {
        this.usuarioId = usuarioId;
        this.empresa = empresa;
        this.montoInicial = BigDecimal.valueOf(monto).setScale(2, RoundingMode.HALF_UP);
        this.fechaInicio = inicio;
        this.fechaFin = fin;
        this.valorFinal = BigDecimal.valueOf(valorFinal).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getId() { return id; }
    public Empresa getEmpresa() { return empresa; }
    public double getMontoInicial() { return montoInicial.doubleValue(); }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public double getValorFinal() { return valorFinal.doubleValue(); }
    public Instant getFechaEjecucion() { return fechaEjecucion; }
}
