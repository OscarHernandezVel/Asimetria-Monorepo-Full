package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.dominio.mercado.Barra;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Proveedor determinista para desarrollo, pruebas y demostración (no requiere API key).
 * Reproduce el caso del documento: índice +2 % → RiTech +4 %; índice −2 % → RiTech −2,5 %.
 * La semilla fija hace que las series (y las pruebas) sean reproducibles.
 */
public final class ProveedorSimulado implements ProveedorMercado {

    public static final String NOMBRE = "SIMULADO";
    public static final String BANDERA_CAIDO = "sim:proveedor_caido";
    public static final int DIAS_HISTORIA = 1260;   // ≈ 5 años bursátiles (HU-02 CA-1)
    /** Elegida por búsqueda: NASDAQ +10 % en 90 días (mockup: +9,8 %) y β↑ 2,02 / β↓ 1,26 (documento: 2,0 / 1,25). */
    public static final long SEMILLA = 18;

    /** (ticker, nombre, sector, último precio, β↑, β↓, ruido diario, dividendos %, sesiones o 0 = todas). */
    private record Parametros(String ticker, String nombre, String sector, double ultimo, double betaUp,
                              double betaDown, double ruido, Double dividendos, int sesiones) {
    }

    private static final List<Parametros> CATALOGO = List.of(
            new Parametros("^NDX", "NASDAQ 100", "Índice", 18942.6, 1, 1, 0, null, 0),
            new Parametros("RTC", "RiTech S.A.S", "Tecnología", 212.40, 2.0, 1.25, 0.012, 0.4, 0),
            new Parametros("NVC", "NovaChip Corp", "Semiconductores", 88.15, 0.9, 1.05, 0.010, 0.8, 0),
            new Parametros("QFN", "Quantum Finance", "Fintech", 54.02, 1.15, 0.85, 0.006, 1.9, 0),
            new Parametros("GLX", "Global Exchange", "Servicios financieros", 140.77, 0.7, 0.8, 0.005, 3.1, 0),
            new Parametros("ADE", "Andes Energy", "Energía", 36.50, 0.6, 0.55, 0.006, 4.2, 0),
            new Parametros("SLH", "Solaris Health", "Salud", 97.30, 0.8, 0.6, 0.007, 1.2, 0),
            new Parametros("PRT", "Pacific Retail", "Consumo", 45.10, 1.1, 1.3, 0.010, 2.0, 0),
            new Parametros("KLG", "Kappa Logistics", "Industria", 63.80, 1.0, 0.95, 0.008, 1.5, 0),
            new Parametros("LMB", "Lumen Bio", "Biotecnología", 24.60, 1.6, 1.4, 0.020, 0.0, 0),
            new Parametros("ORT", "Orion Telecom", "Telecomunicaciones", 18.90, 0.5, 0.7, 0.005, 5.1, 0),
            new Parametros("HLX", "Helios Renovables", "Energía renovable", 12.40, 1.3, 1.1, 0.015, 0.0, 12)); // HU-03 CA-4

    private final Map<String, List<Barra>> series;
    private final Map<String, Parametros> porTicker = new HashMap<>();
    private final AlmacenCompartido almacen;
    private final SplittableRandom vivo;
    private final double probFallo;

    public ProveedorSimulado(Clock reloj, AlmacenCompartido almacen, double probFallo, long semilla) {
        this.almacen = almacen;
        this.probFallo = probFallo;
        this.vivo = new SplittableRandom(semilla * 31 + 7);
        CATALOGO.forEach(p -> porTicker.put(p.ticker(), p));
        this.series = generar(semilla, LocalDate.now(reloj).minusDays(1));
    }

    static List<LocalDate> diasHabiles(int n, LocalDate hasta) {
        List<LocalDate> fechas = new ArrayList<>(n);
        for (LocalDate d = hasta; fechas.size() < n; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                fechas.add(d);
            }
        }
        Collections.reverse(fechas);
        return fechas;
    }

    private static Map<String, List<Barra>> generar(long semilla, LocalDate hasta) {
        SplittableRandom rng = new SplittableRandom(semilla);
        int n = DIAS_HISTORIA;
        List<LocalDate> fechas = diasHabiles(n + 1, hasta);
        double[] rm = new double[n];
        for (int i = 0; i < n; i++) {
            rm[i] = 0.0005 + 0.012 * rng.nextGaussian();
        }
        int refUp = n - 8, refDown = n - 4;   // sesiones de referencia del documento
        rm[refUp] = 0.02;
        rm[refDown] = -0.02;

        Map<String, List<Barra>> out = new LinkedHashMap<>();
        for (Parametros p : CATALOGO) {
            double[] ra = new double[n];
            for (int i = 0; i < n; i++) {
                ra[i] = p.ticker().equals("^NDX") ? rm[i]
                        : 0.0001 + (rm[i] > 0 ? p.betaUp() : p.betaDown()) * rm[i] + p.ruido() * rng.nextGaussian();
            }
            if (p.ticker().equals("RTC")) {
                ra[refUp] = 0.04;
                ra[refDown] = -0.025;
            }
            int desde = p.sesiones() > 0 ? n - p.sesiones() : 0;
            int largo = n - desde + 1;
            double[] precios = new double[largo];
            precios[largo - 1] = p.ultimo();
            for (int i = largo - 2; i >= 0; i--) {
                precios[i] = precios[i + 1] / (1 + ra[desde + i]);   // se reconstruye hacia atrás desde el último precio
            }
            List<LocalDate> fs = fechas.subList(fechas.size() - largo, fechas.size());
            List<Barra> barras = new ArrayList<>(largo);
            for (int i = 0; i < largo; i++) {
                double c = Math.round(precios[i] * 10000) / 10000.0;
                barras.add(new Barra(fs.get(i), c, c * 1.004, c * 0.996, c, 400_000 + (long) (rng.nextDouble() * 2_400_000)));
            }
            out.put(p.ticker(), List.copyOf(barras));
        }
        return out;
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public List<EntradaCatalogo> catalogo() {
        return CATALOGO.stream().map(p -> new EntradaCatalogo(p.ticker(), p.nombre(), p.sector())).toList();
    }

    @Override
    public List<Barra> historico(String ticker, int dias) {
        List<Barra> s = series.getOrDefault(ticker, List.of());
        return s.subList(Math.max(0, s.size() - (dias + 1)), s.size());
    }

    @Override
    public synchronized Map<String, Double> cotizaciones(Map<String, Double> ultimos) {
        if (almacen.obtener(BANDERA_CAIDO).map(Boolean::parseBoolean).orElse(false) || vivo.nextDouble() < probFallo) {
            throw new ErrorProveedor("Proveedor no disponible");
        }
        Map<String, Double> out = new LinkedHashMap<>();
        ultimos.forEach((t, p) -> out.put(t, Math.round(p * (1 + 0.001 * vivo.nextGaussian()) * 10000) / 10000.0));
        return out;
    }

    @Override
    public Double dividendos(String ticker) {
        Parametros p = porTicker.get(ticker);
        return p == null ? null : p.dividendos();
    }
}
