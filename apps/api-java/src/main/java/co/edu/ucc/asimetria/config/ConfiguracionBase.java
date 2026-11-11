package co.edu.ucc.asimetria.config;

import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.comun.almacen.AlmacenMemoria;
import co.edu.ucc.asimetria.comun.almacen.AlmacenRedis;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration
public class ConfiguracionBase implements CachingConfigurer {

    /** Reloj inyectable: las pruebas fijan el tiempo sin esperar ni depender de la fecha real. */
    @Bean
    public Clock reloj() {
        return Clock.systemUTC();
    }

    /** Strategy: Redis (varias instancias en ECS) o memoria (desarrollo y pruebas). */
    @Bean
    public AlmacenCompartido almacenCompartido(PropiedadesAsimetria props, Clock reloj,
                                               ObjectProvider<StringRedisTemplate> redis) {
        return "redis".equalsIgnoreCase(props.almacen())
                ? new AlmacenRedis(redis.getObject(), reloj)
                : new AlmacenMemoria(reloj);
    }

    /**
     * Si la caché (Redis) falla, se registra y se continúa sin caché en vez de devolver 500
     * (RNF-06): la consulta se resuelve contra PostgreSQL.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
