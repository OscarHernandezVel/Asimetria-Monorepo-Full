package co.edu.ucc.asimetria.comun.estructuras;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Buffer circular de capacidad fija (ring buffer).
 *
 * <p>Uso en el sistema: guarda los últimos N ticks intradía de cada ticker (RF-09). Insertar es
 * O(1) y, al llenarse, sobrescribe el dato más antiguo sin mover elementos ni reservar memoria:
 * el consumo queda acotado por {@code capacidad} aunque el proveedor envíe datos sin parar.
 *
 * <p>Es seguro entre hilos (los métodos son {@code synchronized}): el worker escribe mientras
 * las peticiones HTTP leen.
 */
public final class BufferCircular<T> {

    private final Object[] datos;
    private int inicio;   // índice del elemento más antiguo
    private int tamano;

    public BufferCircular(int capacidad) {
        if (capacidad <= 0) {
            throw new IllegalArgumentException("La capacidad debe ser mayor que 0");
        }
        this.datos = new Object[capacidad];
    }

    /** Añade al final; si está lleno, descarta el más antiguo. O(1). */
    public synchronized void agregar(T valor) {
        int fin = (inicio + tamano) % datos.length;
        datos[fin] = valor;
        if (tamano < datos.length) {
            tamano++;
        } else {
            inicio = (inicio + 1) % datos.length;
        }
    }

    /** Copia en orden cronológico (del más antiguo al más reciente). O(n). */
    @SuppressWarnings("unchecked")
    public synchronized List<T> comoLista() {
        List<T> lista = new ArrayList<>(tamano);
        for (int i = 0; i < tamano; i++) {
            lista.add((T) datos[(inicio + i) % datos.length]);
        }
        return lista;
    }

    /** Elemento más reciente. O(1). */
    @SuppressWarnings("unchecked")
    public synchronized Optional<T> ultimo() {
        return tamano == 0 ? Optional.empty() : Optional.of((T) datos[(inicio + tamano - 1) % datos.length]);
    }

    public synchronized int tamano() {
        return tamano;
    }

    public int capacidad() {
        return datos.length;
    }
}
