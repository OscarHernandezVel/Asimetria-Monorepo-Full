package co.edu.ucc.asimetria.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Cuerpos de petición con validación declarativa (Bean Validation). Los errores salen como 422 con
 * {@code {"detail": ...}}. Las reglas de negocio (monto &gt; 0, fechas) se validan en los servicios
 * para dar el mensaje exacto de cada criterio de aceptación.
 */
public final class Solicitudes {

    private Solicitudes() {
    }

    public record Registro(@NotBlank(message = "Ingresa tu nombre completo") @Size(max = 100) String nombre,
                           @NotBlank @Email(message = "Ingresa un correo electrónico válido") @Size(max = 255) String email,
                           @NotNull @Size(min = 8, max = 72, message = "La contraseña debe tener mínimo 8 caracteres, con letras y números") String password,
                           @Pattern(regexp = "CONSERVADOR|MODERADO|AGRESIVO") String perfil) {
    }

    public record Login(@NotBlank @Email String email, @NotNull @Size(max = 200) String password) {
    }

    public record Quiz(@NotNull @Size(min = 5, max = 5, message = "Responde todas las preguntas del quiz") List<Integer> respuestas) {
    }

    public record Plan(@NotBlank String plan) {
    }

    public record Watch(@NotBlank @Size(max = 10) String ticker) {
    }

    public record CambioWatch(@Min(value = 0, message = "La cantidad debe ser mayor o igual a 0") @Max(10_000_000) Integer cantidad,
                              Boolean favorito) {
    }

    public record Simulacion(@NotBlank @Size(max = 10) String ticker, @NotNull Double monto,
                             @NotNull LocalDate fechaInicio, @NotNull LocalDate fechaFin) {
    }

    public record Alerta(@NotBlank @Size(max = 10) String ticker, @NotBlank String tipo, @NotNull Double umbral, String canal) {
    }

    public record CambioAlerta(@NotBlank String estado) {
    }

    public record FallosDemo(boolean proveedorCaido, boolean correoCaido) {
    }
}
