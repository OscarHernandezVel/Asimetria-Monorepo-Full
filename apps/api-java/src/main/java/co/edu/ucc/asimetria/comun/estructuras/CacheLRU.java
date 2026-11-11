package co.edu.ucc.asimetria.comun.estructuras;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Caché LRU (Least Recently Used) acotada: {@link LinkedHashMap} en orden de acceso, que expulsa
 * la entrada menos usada al superar la capacidad. get/put en O(1).
 *
 * <p>Uso: el limitador de peticiones guarda un {@link TokenBucket} por IP. Acotar el mapa evita
 * que un atacante con miles de IPs distintas agote la memoria del servidor (RNF-03/RNF-05).
 */
public final class CacheLRU<K, V> {

    private final int capacidad;
    private final LinkedHashMap<K, V> mapa;

    public CacheLRU(int capacidad) {
        if (capacidad <= 0) {
            throw new IllegalArgumentException("La capacidad debe ser mayor que 0");
        }
        this.capacidad = capacidad;
        this.mapa = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> mayor) {
                return size() > CacheLRU.this.capacidad;
            }
        };
    }

    public synchronized V obtenerOCrear(K clave, Function<? super K, ? extends V> crear) {
        return mapa.computeIfAbsent(clave, crear);
    }

    public synchronized V obtener(K clave) {
        return mapa.get(clave);
    }

    public synchronized void poner(K clave, V valor) {
        mapa.put(clave, valor);
    }

    public synchronized int tamano() {
        return mapa.size();
    }
}
