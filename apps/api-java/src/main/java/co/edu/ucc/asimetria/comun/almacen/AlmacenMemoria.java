package co.edu.ucc.asimetria.comun.almacen;

import co.edu.ucc.asimetria.comun.estructuras.BufferCircular;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Implementación en memoria (una sola instancia: desarrollo y pruebas). */
public final class AlmacenMemoria implements AlmacenCompartido {

    private record Entrada(String valor, Instant expira) {
        boolean vigente(Instant ahora) {
            return expira == null || ahora.isBefore(expira);
        }
    }

    private final Map<String, Entrada> valores = new ConcurrentHashMap<>();
    private final Map<String, BufferCircular<String>> listas = new ConcurrentHashMap<>();
    private final Clock reloj;

    public AlmacenMemoria(Clock reloj) {
        this.reloj = reloj;
    }

    @Override
    public Optional<String> obtener(String clave) {
        Entrada e = valores.get(clave);
        if (e == null) {
            return Optional.empty();
        }
        if (!e.vigente(reloj.instant())) {
            valores.remove(clave, e);
            return Optional.empty();
        }
        return Optional.of(e.valor());
    }

    @Override
    public void guardar(String clave, String valor, Duration ttl) {
        valores.put(clave, new Entrada(valor, ttl == null ? null : reloj.instant().plus(ttl)));
    }

    @Override
    public long incrementar(String clave, Duration ttlInicial) {
        Instant ahora = reloj.instant();
        Entrada nueva = valores.compute(clave, (k, e) -> (e == null || !e.vigente(ahora))
                ? new Entrada("1", ttlInicial == null ? null : ahora.plus(ttlInicial))
                : new Entrada(String.valueOf(Long.parseLong(e.valor()) + 1), e.expira()));
        return Long.parseLong(nueva.valor());
    }

    @Override
    public void eliminar(String clave) {
        valores.remove(clave);
        listas.remove(clave);
    }

    @Override
    public void agregarAcotado(String clave, String valor, int maximo) {
        listas.computeIfAbsent(clave, k -> new BufferCircular<>(maximo)).agregar(valor);
    }

    @Override
    public List<String> listar(String clave) {
        BufferCircular<String> b = listas.get(clave);
        return b == null ? List.of() : b.comoLista();
    }

    @Override
    public boolean disponible() {
        return true;
    }

    @Override
    public String tipo() {
        return "memoria";
    }
}
