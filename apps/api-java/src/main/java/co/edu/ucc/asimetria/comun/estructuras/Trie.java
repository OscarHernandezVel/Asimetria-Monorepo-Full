package co.edu.ucc.asimetria.comun.estructuras;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Árbol de prefijos (trie) para el buscador de activos («Buscar activo, ticker o sector…»).
 *
 * <p>Se indexan el ticker y cada palabra del nombre y del sector. Buscar un prefijo de longitud m
 * cuesta O(m) hasta su nodo más O(r) para recolectar r resultados, sin recorrer el catálogo.
 * La normalización quita tildes y mayúsculas: «tecnologia» encuentra «Tecnología».
 */
public final class Trie<V> {

    private static final class Nodo<V> {
        private final Map<Character, Nodo<V>> hijos = new HashMap<>();
        private final Set<V> valores = new LinkedHashSet<>();
    }

    private final Nodo<V> raiz = new Nodo<>();

    public static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim();
    }

    public synchronized void insertar(String clave, V valor) {
        Nodo<V> nodo = raiz;
        for (char c : normalizar(clave).toCharArray()) {
            nodo = nodo.hijos.computeIfAbsent(c, x -> new Nodo<>());
        }
        nodo.valores.add(valor);
    }

    /** Indexa cada palabra del texto (separadas por espacios o signos). */
    public void insertarPalabras(String texto, V valor) {
        for (String palabra : normalizar(texto).split("[^\\p{L}\\p{N}^]+")) {
            if (!palabra.isBlank()) {
                insertar(palabra, valor);
            }
        }
    }

    /** Valores cuyo término empieza por {@code prefijo}, sin duplicados, hasta {@code limite}. */
    public synchronized Set<V> buscarPrefijo(String prefijo, int limite) {
        Set<V> resultado = new LinkedHashSet<>();
        String p = normalizar(prefijo);
        if (p.isEmpty() || limite <= 0) {
            return resultado;
        }
        Nodo<V> nodo = raiz;
        for (char c : p.toCharArray()) {
            nodo = nodo.hijos.get(c);
            if (nodo == null) {
                return resultado;
            }
        }
        // Recorrido en anchura: primero las coincidencias más cortas (más cercanas al prefijo).
        Deque<Nodo<V>> cola = new ArrayDeque<>();
        cola.add(nodo);
        while (!cola.isEmpty() && resultado.size() < limite) {
            Nodo<V> actual = cola.poll();
            for (V v : actual.valores) {
                if (resultado.size() >= limite) {
                    break;
                }
                resultado.add(v);
            }
            cola.addAll(actual.hijos.values());
        }
        return resultado;
    }

    public synchronized void vaciar() {
        raiz.hijos.clear();
        raiz.valores.clear();
    }
}
