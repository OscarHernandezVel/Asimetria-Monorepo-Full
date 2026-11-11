package co.edu.ucc.asimetria.usuario;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.ListaSeguimiento;
import co.edu.ucc.asimetria.persistencia.ListaSeguimientoRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Watchlist y portafolio teórico (RF-08, HU-08). Todo se filtra por el usuario del token (CA-3). */
@Service
public class WatchlistService {

    public record WatchVista(String ticker, String nombre, String sector, int cantidad, boolean favorito, Instant fechaAgregado) {
        static WatchVista de(ListaSeguimiento l) {
            Empresa e = l.getEmpresa();
            return new WatchVista(e.getTicker(), e.getNombreEmpresa(), e.getSector(), l.getCantidad(), l.isFavorito(), l.getFechaAgregado());
        }
    }

    private final ListaSeguimientoRepositorio listas;
    private final EmpresaRepositorio empresas;
    private final AuditoriaService auditoria;

    public WatchlistService(ListaSeguimientoRepositorio listas, EmpresaRepositorio empresas, AuditoriaService auditoria) {
        this.listas = listas;
        this.empresas = empresas;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public List<WatchVista> listar(Usuario u) {
        return listas.listar(u.getId()).stream().map(WatchVista::de).toList();
    }

    @Transactional
    public WatchVista agregar(Usuario u, String ticker, String ip) {
        Empresa e = empresas.findByTickerAndActivaTrue(ticker.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
        if (listas.existsByUsuarioIdAndEmpresaId(u.getId(), e.getId())) {   // CA-4
            auditoria.error("WATCHLIST_AGREGAR", u, null, ip, e.getTicker() + " duplicado");
            throw ApiException.conflicto(e.getNombreEmpresa() + " ya está en tu lista de seguimiento.");
        }
        ListaSeguimiento item = listas.save(new ListaSeguimiento(u.getId(), e, 0, false));
        auditoria.exito("WATCHLIST_AGREGAR", u, ip, e.getTicker());
        return WatchVista.de(item);
    }

    @Transactional
    public WatchVista actualizar(Usuario u, String ticker, Integer cantidad, Boolean favorito, String ip) {
        ListaSeguimiento item = item(u, ticker);
        if (cantidad != null) {
            item.setCantidad(cantidad);
        }
        if (favorito != null) {
            item.setFavorito(favorito);
        }
        auditoria.exito("PORTAFOLIO_ACTUALIZAR", u, ip, item.getEmpresa().getTicker() + " cantidad=" + cantidad + " favorito=" + favorito);
        return WatchVista.de(listas.save(item));
    }

    @Transactional
    public void quitar(Usuario u, String ticker, String ip) {
        ListaSeguimiento item = item(u, ticker);
        listas.delete(item);
        auditoria.exito("WATCHLIST_ELIMINAR", u, ip, item.getEmpresa().getTicker());
    }

    private ListaSeguimiento item(Usuario u, String ticker) {
        return listas.buscar(u.getId(), ticker.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.noEncontrado("La empresa no está en tu lista"));
    }
}
