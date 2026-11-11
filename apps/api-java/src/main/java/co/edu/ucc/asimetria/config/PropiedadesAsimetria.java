package co.edu.ucc.asimetria.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Configuración tipada del sistema (prefijo {@code asimetria}), validada al arrancar: si falta el
 * secreto JWT o es corto, la aplicación no inicia (falla rápido en vez de quedar insegura).
 * Todos los valores llegan por variables de entorno (12-factor; en AWS desde Secrets Manager).
 */
@Validated
@ConfigurationProperties(prefix = "asimetria")
public record PropiedadesAsimetria(
        @Valid @NotNull Jwt jwt,
        @Min(4) int bcryptCosto,
        @NotNull String proveedor,
        @Valid @NotNull AlphaVantage alphavantage,
        @Valid @NotNull SendGrid sendgrid,
        @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration intervaloCotizaciones,   // "5" (formato del .env de Python) = 5 s
        @NotNull String almacen,
        boolean modoDemo,
        boolean herramientasPrueba,
        boolean tareasHabilitadas,
        boolean confiarProxy,
        double probFalloSimulado,
        @Valid @NotNull LimiteTasa limiteTasa) {

    public record Jwt(@NotNull @Size(min = 32, message = "JWT_SECRET debe tener al menos 32 caracteres") String secreto,
                      @Min(1) int minutos) {
    }

    public record AlphaVantage(String clave, @Min(1) int limiteDiario) {
        public boolean configurado() {
            return clave != null && !clave.isBlank();
        }
    }

    public record SendGrid(String clave, String remitente) {
        public boolean configurado() {
            return clave != null && !clave.isBlank();
        }
    }

    /** Peticiones por segundo y ráfaga, general y para /auth (anti fuerza bruta). */
    public record LimiteTasa(boolean habilitado, double porSegundo, int rafaga, double authPorMinuto, int authRafaga) {
    }
}
