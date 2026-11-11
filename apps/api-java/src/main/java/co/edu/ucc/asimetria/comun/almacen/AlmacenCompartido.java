package co.edu.ucc.asimetria.comun.almacen;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Estado compartido entre todas las instancias de la API (cotizaciones en vivo, contador de
 * intentos de login, cuota de Alpha Vantage, estado del circuit breaker, banderas de prueba).
 *
 * <p>Patrón Strategy: {@link AlmacenRedis} en producción (ElastiCache) y {@link AlmacenMemoria}
 * en desarrollo y pruebas. Los servicios dependen solo de esta interfaz.
 */
public interface AlmacenCompartido {

    Optional<String> obtener(String clave);

    /** {@code ttl} nulo = sin expiración. */
    void guardar(String clave, String valor, Duration ttl);

    /** Incrementa un contador; el TTL se fija solo al crearlo (ventana fija). */
    long incrementar(String clave, Duration ttlInicial);

    void eliminar(String clave);

    /** Lista acotada: añade al final y conserva solo los últimos {@code maximo} (ring buffer). */
    void agregarAcotado(String clave, String valor, int maximo);

    /** Elementos de la lista acotada en orden cronológico. */
    List<String> listar(String clave);

    boolean disponible();

    String tipo();
}
