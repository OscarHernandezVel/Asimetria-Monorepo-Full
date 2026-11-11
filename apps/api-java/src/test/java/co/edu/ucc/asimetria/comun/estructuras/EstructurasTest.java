package co.edu.ucc.asimetria.comun.estructuras;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstructurasTest {

    @Test
    void bufferCircularSobrescribeElMasAntiguoYConservaElOrden() {
        BufferCircular<Integer> b = new BufferCircular<>(3);
        assertThat(b.ultimo()).isEmpty();
        for (int i = 1; i <= 5; i++) {
            b.agregar(i);
        }
        assertThat(b.comoLista()).containsExactly(3, 4, 5);
        assertThat(b.ultimo()).contains(5);
        assertThat(b.tamano()).isEqualTo(3);
        assertThat(b.capacidad()).isEqualTo(3);
        assertThatThrownBy(() -> new BufferCircular<>(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topKDevuelveLosMayoresOrdenados() {
        List<Integer> datos = List.of(5, -9, 3, 12, 7, -1, 8);
        assertThat(TopK.de(datos, 3, Comparator.naturalOrder())).containsExactly(12, 8, 7);
        // «Mayores movimientos»: por valor absoluto
        assertThat(TopK.de(datos, 2, Comparator.comparingInt(Math::abs))).containsExactly(12, -9);
        assertThat(TopK.de(List.<Integer>of(), 3, Comparator.naturalOrder())).isEmpty();
        assertThat(TopK.de(List.of(1, 2), 10, Comparator.naturalOrder())).containsExactly(2, 1);
        assertThatThrownBy(() -> new TopK<Integer>(0, Comparator.naturalOrder())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trieBuscaPorPrefijoSinTildesNiMayusculas() {
        Trie<String> t = new Trie<>();
        t.insertar("RTC", "RTC");
        t.insertarPalabras("RiTech S.A.S Tecnología", "RTC");
        t.insertarPalabras("Quantum Finance Fintech", "QFN");
        t.insertarPalabras("Global Exchange Servicios financieros", "GLX");
        assertThat(t.buscarPrefijo("ri", 10)).containsExactly("RTC");
        assertThat(t.buscarPrefijo("TECNOLOGIA", 10)).containsExactly("RTC");
        assertThat(t.buscarPrefijo("fin", 10)).containsExactlyInAnyOrder("QFN", "GLX");
        assertThat(t.buscarPrefijo("fin", 1)).hasSize(1);
        assertThat(t.buscarPrefijo("zzz", 10)).isEmpty();
        assertThat(t.buscarPrefijo("  ", 10)).isEmpty();
        t.vaciar();
        assertThat(t.buscarPrefijo("ri", 10)).isEmpty();
    }

    @Test
    void cacheLruExpulsaElMenosUsado() {
        CacheLRU<String, Integer> c = new CacheLRU<>(2);
        c.poner("a", 1);
        c.poner("b", 2);
        assertThat(c.obtener("a")).isEqualTo(1);   // "a" pasa a ser el más reciente
        c.poner("c", 3);                           // expulsa "b"
        assertThat(c.obtener("b")).isNull();
        assertThat(c.obtenerOCrear("a", k -> 99)).isEqualTo(1);
        assertThat(c.obtenerOCrear("d", k -> 4)).isEqualTo(4);
        assertThat(c.tamano()).isEqualTo(2);
        assertThatThrownBy(() -> new CacheLRU<>(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenBucketPermiteRafagaYRecargaConElTiempo() {
        AtomicLong reloj = new AtomicLong(0);
        TokenBucket b = new TokenBucket(2, 1, reloj::get);  // ráfaga 2, 1 ficha/s
        assertThat(b.intentarConsumir()).isTrue();
        assertThat(b.intentarConsumir()).isTrue();
        assertThat(b.intentarConsumir()).isFalse();
        reloj.addAndGet(1_000_000_000L);                    // +1 s → 1 ficha
        assertThat(b.intentarConsumir()).isTrue();
        assertThat(b.intentarConsumir()).isFalse();
        assertThatThrownBy(() -> new TokenBucket(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serieTemporalRangoUltimasYAlineacion() {
        SerieTemporal activo = new SerieTemporal();
        SerieTemporal indice = new SerieTemporal();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 10; i++) {
            indice.agregar(d.plusDays(i), 100 + i);
            if (i >= 4) {
                activo.agregar(d.plusDays(i), 50 + i);   // el activo empieza a cotizar después
            }
        }
        assertThat(indice.rango(d.plusDays(2), d.plusDays(4)).valores()).containsExactly(102, 103, 104);
        assertThat(indice.rango(d.plusDays(4), d.plusDays(2)).vacia()).isTrue();
        assertThat(indice.ultimas(2).valores()).containsExactly(108, 109);
        SerieTemporal.Alineadas a = activo.alinear(indice);
        assertThat(a.tamano()).isEqualTo(6);
        assertThat(a.fechas().get(0)).isEqualTo(d.plusDays(4));
        assertThat(a.a()[0]).isEqualTo(54);
        assertThat(a.b()[0]).isEqualTo(104);
        assertThat(new SerieTemporal().alinear(indice).tamano()).isZero();
    }
}
