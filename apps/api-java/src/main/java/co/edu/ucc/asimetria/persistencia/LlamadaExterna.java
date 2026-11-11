package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Traza de una llamada a un servicio externo (Alpha Vantage, SendGrid). Nunca contiene secretos. */
@Entity
@Table(name = "llamadas_externas")
public class LlamadaExterna {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false)
    private String proveedor;

    @Column(nullable = false)
    private String operacion;

    @Column(nullable = false)
    private boolean exito;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latencia_ms", nullable = false)
    private int latenciaMs;

    private String detalle;

    protected LlamadaExterna() {
    }

    public LlamadaExterna(Instant ts, String proveedor, String operacion, boolean exito, Integer httpStatus, int latenciaMs, String detalle) {
        this.ts = ts;
        this.proveedor = proveedor;
        this.operacion = operacion;
        this.exito = exito;
        this.httpStatus = httpStatus;
        this.latenciaMs = latenciaMs;
        this.detalle = detalle;
    }

    public Instant getTs() { return ts; }
    public String getProveedor() { return proveedor; }
    public String getOperacion() { return operacion; }
    public boolean isExito() { return exito; }
    public Integer getHttpStatus() { return httpStatus; }
    public int getLatenciaMs() { return latenciaMs; }
    public String getDetalle() { return detalle; }
}
