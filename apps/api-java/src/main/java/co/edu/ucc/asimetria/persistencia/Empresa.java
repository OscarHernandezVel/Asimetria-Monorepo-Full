package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Activo o índice del catálogo (el NASDAQ 100 es una fila con {@code esIndice = true}). */
@Entity
@Table(name = "empresas")
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ticker;

    @Column(name = "nombre_empresa", nullable = false)
    private String nombreEmpresa;

    private String sector;

    @Column(name = "es_indice", nullable = false)
    private boolean esIndice;

    @Column(nullable = false)
    private boolean activa = true;

    protected Empresa() {
    }

    public Empresa(String ticker, String nombreEmpresa, String sector, boolean esIndice) {
        this.ticker = ticker;
        this.nombreEmpresa = nombreEmpresa;
        this.sector = sector;
        this.esIndice = esIndice;
    }

    public Long getId() { return id; }
    public String getTicker() { return ticker; }
    public String getNombreEmpresa() { return nombreEmpresa; }
    public String getSector() { return sector; }
    public boolean isEsIndice() { return esIndice; }
    public boolean isActiva() { return activa; }
}
