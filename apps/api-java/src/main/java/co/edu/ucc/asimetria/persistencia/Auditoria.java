package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Evento crítico auditado (RNF-04): UTC, usuario, IP de origen, tipo y resultado. */
@Entity
@Table(name = "auditoria")
public class Auditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(name = "usuario_id")
    private Long usuarioId;

    private String email;
    private String ip;

    @Column(nullable = false)
    private String evento;

    @Column(nullable = false)
    private String estado;

    private String detalle;

    protected Auditoria() {
    }

    public Auditoria(Instant ts, Long usuarioId, String email, String ip, String evento, String estado, String detalle) {
        this.ts = ts;
        this.usuarioId = usuarioId;
        this.email = email;
        this.ip = ip;
        this.evento = evento;
        this.estado = estado;
        this.detalle = detalle;
    }

    public Instant getTs() { return ts; }
    public String getEmail() { return email; }
    public String getIp() { return ip; }
    public String getEvento() { return evento; }
    public String getEstado() { return estado; }
    public String getDetalle() { return detalle; }
}
