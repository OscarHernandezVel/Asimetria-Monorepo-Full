package co.edu.ucc.asimetria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Punto de entrada del backend de Asimetría.
 *
 * <p>Capas (cada una solo conoce a la de abajo):
 * {@code web} (HTTP) → servicios ({@code mercado}, {@code usuario}, {@code alerta}) → {@code dominio}
 * (cálculo puro, sin E/S) y {@code persistencia}/proveedores externos. {@code comun} contiene
 * estructuras de datos y utilidades transversales.
 */
@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
public class AsimetriaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AsimetriaApplication.class, args);
    }
}
