package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/**
 * <b>Factory</b>: ensambla el proveedor según {@code asimetria.proveedor} y lo envuelve en los
 * decoradores. Cadena resultante:
 * <pre>
 *  alphavantage → Resiliente( Trazado( AlphaVantage ) )   cada intento queda trazado
 *  simulado     → Resiliente( Simulado )                  sin trazas (no es un servicio externo)
 * </pre>
 * Cambiar de proveedor es configuración, no código (Compatibilidad → reemplazabilidad).
 */
@Configuration
public class FabricaProveedores {

    @Bean
    public ProveedorResiliente proveedorMercado(PropiedadesAsimetria props, AlmacenCompartido almacen,
                                                AuditoriaService auditoria, Clock reloj) {
        ProveedorMercado base = switch (props.proveedor().toLowerCase()) {
            case "alphavantage" -> new ProveedorTrazado(alphaVantage(props, almacen, reloj), auditoria);
            case "simulado" -> new ProveedorSimulado(reloj, almacen, props.probFalloSimulado(), ProveedorSimulado.SEMILLA);
            default -> throw new IllegalStateException("PROVEEDOR desconocido: " + props.proveedor());
        };
        CircuitBreaker circuito = new CircuitBreaker(almacen, base.nombre().toLowerCase(), 3, Duration.ofSeconds(30), reloj);
        return new ProveedorResiliente(base, circuito, 3, Duration.ofMillis(150), d -> Thread.sleep(d.toMillis()));
    }

    /** Cliente para las pruebas manuales de integración (no forma parte de la cadena principal). */
    public static ProveedorAlphaVantage alphaVantage(PropiedadesAsimetria props, AlmacenCompartido almacen, Clock reloj) {
        return new ProveedorAlphaVantage(clienteHttp("https://www.alphavantage.co/query"), props.alphavantage().clave(),
                props.alphavantage().limiteDiario(), almacen, reloj);
    }

    /** Timeouts explícitos: un proveedor lento nunca bloquea un hilo indefinidamente. */
    public static RestClient clienteHttp(String base) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(base).requestFactory(f).build();
    }
}
