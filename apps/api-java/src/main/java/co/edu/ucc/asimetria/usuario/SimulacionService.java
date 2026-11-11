package co.edu.ucc.asimetria.usuario;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.comun.estructuras.SerieTemporal;
import co.edu.ucc.asimetria.dominio.calculo.ResultadoSimulacion;
import co.edu.ucc.asimetria.persistencia.Empresa;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.PreciosRepositorio;
import co.edu.ucc.asimetria.persistencia.SimulacionPortafolio;
import co.edu.ucc.asimetria.persistencia.SimulacionRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Backtesting (RF-05, HU-05). Recorta las series con {@link SerieTemporal#rango} (TreeMap.subMap,
 * O(log n)), las alinea por fecha y construye el resultado con el Builder del dominio.
 */
@Service
public class SimulacionService {

    public record SimulacionVista(Long id, String ticker, double monto, LocalDate fechaInicio, LocalDate fechaFin,
                                  double valorFinal, double valorIndice, double retorno, double retornoIndice,
                                  List<ResultadoSimulacion.Punto> serie) {
    }

    public record HistorialVista(Long id, String ticker, double monto, LocalDate fechaInicio, LocalDate fechaFin,
                                 double valorFinal, Instant fechaEjecucion) {
    }

    private final EmpresaRepositorio empresas;
    private final PreciosRepositorio precios;
    private final SimulacionRepositorio simulaciones;
    private final AuditoriaService auditoria;

    public SimulacionService(EmpresaRepositorio empresas, PreciosRepositorio precios, SimulacionRepositorio simulaciones,
                             AuditoriaService auditoria) {
        this.empresas = empresas;
        this.precios = precios;
        this.simulaciones = simulaciones;
        this.auditoria = auditoria;
    }

    @Transactional
    public SimulacionVista simular(Usuario u, String ticker, double monto, LocalDate inicio, LocalDate fin, String ip) {
        Empresa e = empresas.findByTickerAndActivaTrue(ticker.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.noEncontrado("Empresa no encontrada"));
        Empresa indice = empresas.findFirstByEsIndiceTrueAndActivaTrue().orElseThrow();
        ResultadoSimulacion r;
        try {
            if (!fin.isAfter(inicio)) {
                throw new IllegalArgumentException("La fecha final debe ser posterior a la inicial");
            }
            ResultadoSimulacion.Builder b = ResultadoSimulacion.builder(monto);   // CA-4: valida el monto
            SerieTemporal.Alineadas al = precios.serie(e.getId()).rango(inicio, fin).alinear(precios.serie(indice.getId()).rango(inicio, fin));
            for (int i = 0; i < al.tamano(); i++) {
                b.sesion(al.fechas().get(i), al.a()[i], al.b()[i]);
            }
            r = b.construir();                                                     // CA-4: valida el periodo
        } catch (IllegalArgumentException ex) {
            auditoria.error("SIMULACION", u, null, ip, ex.getMessage());
            throw ApiException.invalido(ex.getMessage());
        }
        LocalDate desde = r.serie().get(0).fecha(), hasta = r.serie().get(r.serie().size() - 1).fecha();
        SimulacionPortafolio s = simulaciones.save(new SimulacionPortafolio(u.getId(), e, monto, desde, hasta, r.valorFinal()));
        auditoria.exito("SIMULACION", u, ip, e.getTicker() + " " + monto + " → " + r.valorFinal());
        return new SimulacionVista(s.getId(), e.getTicker(), monto, desde, hasta, r.valorFinal(), r.valorIndice(),
                r.retorno(), r.retornoIndice(), r.serie());
    }

    @Transactional(readOnly = true)
    public List<HistorialVista> historial(Usuario u) {
        return simulaciones.recientes(u.getId(), Limit.of(20)).stream()
                .map(s -> new HistorialVista(s.getId(), s.getEmpresa().getTicker(), s.getMontoInicial(), s.getFechaInicio(),
                        s.getFechaFin(), s.getValorFinal(), s.getFechaEjecucion()))
                .toList();
    }
}
