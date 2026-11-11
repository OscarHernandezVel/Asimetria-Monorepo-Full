package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Notificación emitida por una alerta, con su rastro de entrega (X-Message-Id de SendGrid). */
@Entity
@Table(name = "notificaciones")
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "regla_id")
    private Long reglaId;

    @Column(nullable = false)
    private String texto;

    @Column(nullable = false)
    private String canal;

    @Column(name = "estado_envio")
    private String estadoEnvio;

    @Column(name = "proveedor_id")
    private String proveedorId;

    @Column(nullable = false)
    private Instant fecha = Instant.now();

    protected Notificacion() {
    }

    public Notificacion(Long usuarioId, Long reglaId, String texto, String canal, String estadoEnvio, String proveedorId) {
        this.usuarioId = usuarioId;
        this.reglaId = reglaId;
        this.texto = texto;
        this.canal = canal;
        this.estadoEnvio = estadoEnvio;
        this.proveedorId = proveedorId;
    }

    public String getTexto() { return texto; }
    public String getCanal() { return canal; }
    public String getEstadoEnvio() { return estadoEnvio; }
    public String getProveedorId() { return proveedorId; }
    public Instant getFecha() { return fecha; }
}
