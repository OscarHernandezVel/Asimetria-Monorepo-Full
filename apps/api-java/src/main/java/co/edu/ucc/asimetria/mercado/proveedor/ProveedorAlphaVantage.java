package co.edu.ucc.asimetria.mercado.proveedor;

import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.dominio.mercado.Barra;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * <b>Adapter</b> de Alpha Vantage: traduce su JSON ({@code "Time Series (Daily)"}, {@code "05. price"}…)
 * al modelo interno {@link Barra}. Además:
 * <ul>
 *   <li>Cuota diaria local (Redis): al llegar a {@code limiteDiario} deja de llamar (HU-01 CA-3).</li>
 *   <li>La API key viaja en la URL (así lo exige el proveedor) y se enmascara en cualquier mensaje
 *       de error antes de llegar a logs o trazas (HU-01 CA-1).</li>
 *   <li>El índice NASDAQ 100 se aproxima con el ETF QQQ (el plan gratuito no expone ^NDX).</li>
 * </ul>
 */
public final class ProveedorAlphaVantage implements ProveedorMercado {

    public static final String NOMBRE = "ALPHAVANTAGE";
    private static final Map<String, String> SIMBOLO = Map.of("^NDX", "QQQ");
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final String clave;
    private final int limiteDiario;
    private final AlmacenCompartido almacen;
    private final Clock reloj;

    public ProveedorAlphaVantage(RestClient http, String clave, int limiteDiario, AlmacenCompartido almacen, Clock reloj) {
        if (clave == null || clave.isBlank()) {
            throw new IllegalArgumentException("ALPHAVANTAGE_API_KEY no está configurada");
        }
        this.http = http;
        this.clave = clave;
        this.limiteDiario = limiteDiario;
        this.almacen = almacen;
        this.reloj = reloj;
    }

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public List<EntradaCatalogo> catalogo() {
        return List.of(new EntradaCatalogo("^NDX", "NASDAQ 100 (vía QQQ)", "Índice"),
                new EntradaCatalogo("AAPL", "Apple Inc.", "Tecnología"),
                new EntradaCatalogo("MSFT", "Microsoft Corp.", "Tecnología"),
                new EntradaCatalogo("NVDA", "NVIDIA Corp.", "Semiconductores"),
                new EntradaCatalogo("AMZN", "Amazon.com Inc.", "Consumo"),
                new EntradaCatalogo("GOOGL", "Alphabet Inc.", "Tecnología"));
    }

    public String claveCuota() {
        return "cuota:alphavantage:" + LocalDate.now(reloj);
    }

    public long cuotaUsadaHoy() {
        return almacen.obtener(claveCuota()).map(Long::parseLong).orElse(0L);
    }

    /** Petición GET con control de cuota, detección de límite del proveedor y enmascarado de la clave. */
    Map<String, Object> consultar(String funcion, Map<String, String> params) {
        if (cuotaUsadaHoy() >= limiteDiario) {
            throw new ErrorProveedor.LimiteExcedido("Cuota diaria de Alpha Vantage agotada (" + limiteDiario + ")");
        }
        almacen.incrementar(claveCuota(), Duration.ofHours(25));
        Function<UriBuilder, URI> uri = b -> {
            b.queryParam("function", funcion);
            params.forEach(b::queryParam);
            return b.queryParam("apikey", clave).build();
        };
        Map<String, Object> d;
        try {
            d = http.get().uri(uri).retrieve().body(JSON);
        } catch (RestClientException e) {
            throw new ErrorProveedor(enmascarar(e.getMessage()));
        }
        if (d == null) {
            throw new ErrorProveedor("Respuesta vacía de Alpha Vantage");
        }
        if (d.containsKey("Note") || d.containsKey("Information")) {
            throw new ErrorProveedor.LimiteExcedido("Límite de peticiones del proveedor alcanzado");
        }
        if (d.containsKey("Error Message")) {
            throw new ErrorProveedor(enmascarar(String.valueOf(d.get("Error Message"))));
        }
        return d;
    }

    String enmascarar(String mensaje) {
        return mensaje == null ? "" : mensaje.replace(clave, "***");
    }

    private static String simbolo(String ticker) {
        return SIMBOLO.getOrDefault(ticker, ticker);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Barra> historico(String ticker, int dias) {
        Map<String, Object> d = consultar("TIME_SERIES_DAILY", Map.of("symbol", simbolo(ticker), "outputsize", dias > 100 ? "full" : "compact"));
        Map<String, Map<String, String>> serie = new TreeMap<>((Map<String, Map<String, String>>) d.get("Time Series (Daily)"));
        List<Barra> barras = new ArrayList<>();
        serie.forEach((fecha, v) -> barras.add(new Barra(LocalDate.parse(fecha), num(v.get("1. open")), num(v.get("2. high")),
                num(v.get("3. low")), num(v.get("4. close")), (long) num(v.get("5. volume")))));
        return barras.subList(Math.max(0, barras.size() - (dias + 1)), barras.size());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Double> cotizaciones(Map<String, Double> ultimos) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String t : ultimos.keySet()) {
            Map<String, String> q = (Map<String, String>) consultar("GLOBAL_QUOTE", Map.of("symbol", simbolo(t))).get("Global Quote");
            out.put(t, num(q.get("05. price")));
        }
        return out;
    }

    @Override
    public Double dividendos(String ticker) {
        if (SIMBOLO.containsKey(ticker)) {
            return null;
        }
        Object y = consultar("OVERVIEW", Map.of("symbol", ticker)).get("DividendYield");
        if (y == null || "None".equals(y) || String.valueOf(y).isBlank()) {
            return null;
        }
        return Double.parseDouble(String.valueOf(y)) * 100;
    }

    private static double num(String v) {
        return Double.parseDouble(v);
    }
}
