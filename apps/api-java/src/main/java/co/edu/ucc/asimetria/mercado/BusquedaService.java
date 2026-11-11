package co.edu.ucc.asimetria.mercado;

import co.edu.ucc.asimetria.comun.estructuras.Trie;
import co.edu.ucc.asimetria.mercado.Vistas.CatalogoActualizado;
import co.edu.ucc.asimetria.mercado.Vistas.EmpresaVista;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Buscador de activos por prefijo de ticker, nombre o sector, sobre un {@link Trie} en memoria.
 * El índice se reconstruye al cambiar el catálogo (Observer: {@link CatalogoActualizado}).
 */
@Service
public class BusquedaService {

    private final EmpresaRepositorio empresas;
    private final Trie<EmpresaVista> indice = new Trie<>();
    private volatile boolean construido;

    public BusquedaService(EmpresaRepositorio empresas) {
        this.empresas = empresas;
    }

    @EventListener(CatalogoActualizado.class)
    public synchronized void reconstruir() {
        indice.vaciar();
        for (Empresa e : empresas.findByActivaTrueOrderById()) {
            EmpresaVista v = new EmpresaVista(e.getTicker(), e.getNombreEmpresa(), e.getSector(), e.isEsIndice());
            indice.insertar(e.getTicker(), v);
            indice.insertarPalabras(e.getNombreEmpresa(), v);
            if (e.getSector() != null) {
                indice.insertarPalabras(e.getSector(), v);
            }
        }
        construido = true;
    }

    public List<EmpresaVista> buscar(String prefijo, int limite) {
        if (!construido) {
            reconstruir();
        }
        return List.copyOf(indice.buscarPrefijo(prefijo, limite));
    }
}
