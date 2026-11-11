package co.edu.ucc.asimetria.comun.almacen;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Operaciones sobre Redis y degradación controlada cuando Redis cae (RNF-06). */
class AlmacenRedisTest {

    @Test
    @SuppressWarnings("unchecked")
    void operacionesDelegadasEnRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valores = mock(ValueOperations.class);
        ListOperations<String, String> listas = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        when(redis.opsForList()).thenReturn(listas);
        when(valores.get("k")).thenReturn("v");
        when(valores.increment("n")).thenReturn(1L);
        when(listas.range("l", 0, -1)).thenReturn(List.of("a", "b"));
        AlmacenRedis a = new AlmacenRedis(redis, Clock.systemUTC());
        assertThat(a.obtener("k")).contains("v");
        a.guardar("k", "v", null);
        a.guardar("k", "v", Duration.ofSeconds(5));
        assertThat(a.incrementar("n", Duration.ofSeconds(60))).isEqualTo(1);
        verify(redis).expire("n", Duration.ofSeconds(60));   // TTL solo al crear el contador
        a.agregarAcotado("l", "x", 120);
        verify(listas).trim("l", -120, -1);                  // lista circular en Redis
        assertThat(a.listar("l")).containsExactly("a", "b");
        a.eliminar("k");
        assertThat(a.tipo()).isEqualTo("redis");
    }

    @Test
    void siRedisFallaSeOmiteDiezSegundosYSeDevuelveVacio() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("caído"));
        Instant[] ahora = {Instant.parse("2026-11-09T12:00:00Z")};
        Clock reloj = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return ahora[0]; }
        };
        AlmacenRedis a = new AlmacenRedis(redis, reloj);
        assertThat(a.obtener("k")).isEmpty();                  // primer fallo: abre la pausa
        assertThat(a.incrementar("n", null)).isZero();
        assertThat(a.listar("l")).isEmpty();
        assertThat(a.disponible()).isFalse();
        verify(redis, times(1)).opsForValue();                 // durante la pausa no se vuelve a intentar
        ahora[0] = ahora[0].plusSeconds(11);
        assertThat(a.obtener("k")).isEmpty();
        verify(redis, times(2)).opsForValue();                 // pasada la pausa, reintenta
    }
}
