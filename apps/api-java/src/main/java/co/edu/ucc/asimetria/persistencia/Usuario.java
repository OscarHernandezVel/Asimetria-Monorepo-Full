package co.edu.ucc.asimetria.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String nombre;

    @Column(name = "perfil_riesgo")
    private String perfilRiesgo;

    private String plan;

    @Column(name = "fecha_registro", nullable = false)
    private Instant fechaRegistro = Instant.now();

    protected Usuario() {
    }

    public Usuario(String email, String passwordHash, String nombre, String perfilRiesgo) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nombre = nombre;
        this.perfilRiesgo = perfilRiesgo;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getNombre() { return nombre; }
    public String getPerfilRiesgo() { return perfilRiesgo; }
    public String getPlan() { return plan; }
    public Instant getFechaRegistro() { return fechaRegistro; }

    public void setPerfilRiesgo(String perfilRiesgo) { this.perfilRiesgo = perfilRiesgo; }
    public void setPlan(String plan) { this.plan = plan; }
}
