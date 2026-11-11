package co.edu.ucc.asimetria.comun.almacen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Implementación sobre Redis (Amazon ElastiCache en AWS).
 *
 * <p>Degradación controlada (RNF-06): si Redis falla, se omite 10 s (mini circuit breaker) y las
 * operaciones devuelven "vacío"; la API sigue respondiendo desde PostgreSQL en vez de esperar el
 * timeout en cada llamada. El contador de login queda en 0 mientras tanto (fail-open documentado).
 */
public final class AlmacenRedis implements AlmacenCompartido {

    private static final Logger log = LoggerFactory.getLogger(AlmacenRedis.class);
    private static final Duration PAUSA_TRAS_FALLO = Duration.ofSeconds(10);

    private final StringRedisTemplate redis;
    private final Clock reloj;
    private volatile Instant omitirHasta = Instant.EPOCH;

    public AlmacenRedis(StringRedisTemplate redis, Clock reloj) {
        this.redis = redis;
        this.reloj = reloj;
    }

    private <T> T intentar(String operacion, Supplier<T> accion, T siFalla) {
        if (reloj.instant().isBefore(omitirHasta)) {
            return siFalla;
        }
        try {
            return accion.get();
        } catch (RuntimeException e) {
            omitirHasta = reloj.instant().plus(PAUSA_TRAS_FALLO);
            log.warn("Redis no disponible en {}; se omite {} s: {}", operacion, PAUSA_TRAS_FALLO.toSeconds(), e.getMessage());
            return siFalla;
        }
    }

    @Override
    public Optional<String> obtener(String clave) {
        return intentar("obtener", () -> Optional.ofNullable(redis.opsForValue().get(clave)), Optional.empty());
    }

    @Override
    public void guardar(String clave, String valor, Duration ttl) {
        intentar("guardar", () -> {
            if (ttl == null) {
                redis.opsForValue().set(clave, valor);
            } else {
                redis.opsForValue().set(clave, valor, ttl);
            }
            return null;
        }, null);
    }

    @Override
    public long incrementar(String clave, Duration ttlInicial) {
        return intentar("incrementar", () -> {
            Long n = redis.opsForValue().increment(clave);
            if (n != null && n == 1 && ttlInicial != null) {
                redis.expire(clave, ttlInicial);
            }
            return n == null ? 0L : n;
        }, 0L);
    }

    @Override
    public void eliminar(String clave) {
        intentar("eliminar", () -> redis.delete(clave), false);
    }

    @Override
    public void agregarAcotado(String clave, String valor, int maximo) {
        intentar("agregarAcotado", () -> {
            redis.opsForList().rightPush(clave, valor);
            redis.opsForList().trim(clave, -maximo, -1); // conserva los últimos N: lista circular
            return null;
        }, null);
    }

    @Override
    public List<String> listar(String clave) {
        return intentar("listar", () -> {
            List<String> l = redis.opsForList().range(clave, 0, -1);
            return l == null ? List.<String>of() : l;
        }, List.of());
    }

    @Override
    public boolean disponible() {
        return intentar("ping", () -> "PONG".equalsIgnoreCase(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping())), false);
    }

    @Override
    public String tipo() {
        return "redis";
    }
}
