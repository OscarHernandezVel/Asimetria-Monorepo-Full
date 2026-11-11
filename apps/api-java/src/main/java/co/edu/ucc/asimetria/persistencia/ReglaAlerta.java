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
import java.time.Instant;

/**
 * Regla de alerta (HU-06). Ciclo de vida: ACTIVA → DISPARADA (con reintento de envío si falla el
 * correo, {@code pendienteEnvio}) ; el usuario puede pasarla a INACTIVA y reactivarla.
 */
@Entity
@Table(name = "reglas_alertas")
public class ReglaAlerta {

    public static final String ACTIVA = "ACTIVA";
    public static final String DISPARADA = "DISPARADA";
    public static final String INACTIVA = "INACTIVA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    @Column(name = "tipo_condicion", nullable = false)
    private String tipoCondicion;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal umbral;

    @Column(nullable = false)
    private String canal;

    @Column(nullable = false)
    private String estado = ACTIVA;

    @Column(name = "pendiente_envio", nullable = false)
    private boolean pendienteEnvio;

    @Column(name = "fecha_creacion", nullable = false)
    private Instant fechaCreacion = Instant.now();

    protected ReglaAlerta() {
    }

    public ReglaAlerta(Long usuarioId, Empresa empresa, String tipoCondicion, BigDecimal umbral, String canal) {
        this.usuarioId = usuarioId;
        this.empresa = empresa;
        this.tipoCondicion = tipoCondicion;
        this.umbral = umbral;
        this.canal = canal;
    }

    public void marcarDisparada(boolean envioPendiente) {
        this.estado = DISPARADA;
        this.pendienteEnvio = envioPendiente;
    }

    public void cambiarEstado(String nuevo) {
        this.estado = nuevo;
        this.pendienteEnvio = false;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public Empresa getEmpresa() { return empresa; }
    public String getTipoCondicion() { return tipoCondicion; }
    public double getUmbral() { return umbral.doubleValue(); }
    public String getCanal() { return canal; }
    public String getEstado() { return estado; }
    public boolean isPendienteEnvio() { return pendienteEnvio; }
    public Instant getFechaCreacion() { return fechaCreacion; }
}
