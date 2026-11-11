package co.edu.ucc.asimetria.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** El .env de la versión Python usa INTERVALO_COTIZACIONES=5 (segundos): sin unidad no debe leerse como 5 ms. */
class PropiedadesTest {

    private static Duration intervalo(String valor) {
        Binder b = new Binder(new MapConfigurationPropertySource(Map.of("asimetria.intervalo-cotizaciones", valor)));
        return b.bind("asimetria", PropiedadesAsimetria.class).get().intervaloCotizaciones();
    }

    @Test
    void intervaloSinUnidadSonSegundosYConUnidadSeRespeta() {
        assertThat(intervalo("5")).isEqualTo(Duration.ofSeconds(5));
        assertThat(intervalo("5s")).isEqualTo(Duration.ofSeconds(5));
        assertThat(intervalo("1m")).isEqualTo(Duration.ofMinutes(1));
    }
}
