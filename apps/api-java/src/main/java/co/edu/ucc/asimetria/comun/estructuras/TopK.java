package co.edu.ucc.asimetria.comun.estructuras;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Selección de los K mayores elementos con un montículo mínimo (min-heap) de tamaño K.
 *
 * <p>Uso: «Mayores movimientos» del dashboard (los 4 activos con mayor |variación|) y el ranking
 * de recomendaciones (RF-04). Coste O(n log K) y memoria O(K), frente a O(n log n) de ordenar
 * todo: la cima del montículo es el menor de los K mejores, así que cada candidato se compara
 * en O(1) y solo entra si la supera.
 */
public final class TopK<T> {

    private final int k;
    private final Comparator<? super T> orden;
    private final PriorityQueue<T> monticulo;

    public TopK(int k, Comparator<? super T> orden) {
        if (k <= 0) {
            throw new IllegalArgumentException("k debe ser mayor que 0");
        }
        this.k = k;
        this.orden = orden;
        this.monticulo = new PriorityQueue<>(k, orden);
    }

    public void ofrecer(T candidato) {
        if (monticulo.size() < k) {
            monticulo.add(candidato);
        } else if (orden.compare(candidato, monticulo.peek()) > 0) {
            monticulo.poll();
            monticulo.add(candidato);
        }
    }

    public void ofrecerTodos(Collection<? extends T> candidatos) {
        candidatos.forEach(this::ofrecer);
    }

    /** Los K mejores, del mayor al menor. */
    public List<T> resultado() {
        List<T> lista = new ArrayList<>(monticulo);
        lista.sort(orden.reversed());
        return lista;
    }

    /** Atajo: los K mayores de una colección. */
    public static <T> List<T> de(Collection<? extends T> elementos, int k, Comparator<? super T> orden) {
        if (elementos.isEmpty()) {
            return List.of();
        }
        TopK<T> top = new TopK<>(Math.min(k, elementos.size()), orden);
        top.ofrecerTodos(elementos);
        return top.resultado();
    }
}
