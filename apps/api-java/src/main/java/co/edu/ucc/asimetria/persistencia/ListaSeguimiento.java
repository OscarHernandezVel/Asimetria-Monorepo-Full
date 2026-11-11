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

import java.time.Instant;

/** Ítem de la watchlist / portafolio teórico de un usuario (HU-08). */
@Entity
@Table(name = "listas_seguimiento")
public class ListaSeguimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "empresa_id", nullable = false)
    private Empresa empresa;

    @Column(nullable = false)
    private int cantidad;

    @Column(nullable = false)
    private boolean favorito;

    @Column(name = "fecha_agregado", nullable = false)
    private Instant fechaAgregado = Instant.now();

    protected ListaSeguimiento() {
    }

    public ListaSeguimiento(Long usuarioId, Empresa empresa, int cantidad, boolean favorito) {
        this.usuarioId = usuarioId;
        this.empresa = empresa;
        this.cantidad = cantidad;
        this.favorito = favorito;
    }

    public Long getId() { return id; }
    public Long getUsuarioId() { return usuarioId; }
    public Empresa getEmpresa() { return empresa; }
    public int getCantidad() { return cantidad; }
    public boolean isFavorito() { return favorito; }
    public Instant getFechaAgregado() { return fechaAgregado; }

    public void setCantidad(int cantidad) { this.cantidad = cantidad; }
    public void setFavorito(boolean favorito) { this.favorito = favorito; }
}
