package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.comun.estructuras.CacheLRU;
import co.edu.ucc.asimetria.comun.estructuras.TokenBucket;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Límite de peticiones por IP (RNF-03/RNF-05), defensa en profundidad bajo el WAF de AWS.
 *
 * <p>Una {@link TokenBucket} por IP, guardadas en una {@link CacheLRU} acotada (10 000 IPs): la
 * memoria no crece aunque lleguen peticiones desde miles de direcciones. La autenticación tiene su
 * propia cubeta, mucho más estricta, contra fuerza bruta distribuida en el tiempo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LimiteTasaFiltro extends OncePerRequestFilter {

    private final PropiedadesAsimetria.LimiteTasa config;
    private final IpCliente ip;
    private final CacheLRU<String, TokenBucket> general = new CacheLRU<>(10_000);
    private final CacheLRU<String, TokenBucket> auth = new CacheLRU<>(10_000);

    public LimiteTasaFiltro(PropiedadesAsimetria props, IpCliente ip) {
        this.config = props.limiteTasa();
        this.ip = ip;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !config.habilitado() || !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain cadena)
            throws ServletException, IOException {
        String origen = ip.de(req);
        boolean esAuth = req.getRequestURI().startsWith("/api/v1/auth/");
        TokenBucket cubeta = esAuth
                ? auth.obtenerOCrear(origen, k -> new TokenBucket(config.authRafaga(), config.authPorMinuto() / 60.0))
                : general.obtenerOCrear(origen, k -> new TokenBucket(config.rafaga(), config.porSegundo()));
        if (!cubeta.intentarConsumir()) {
            res.setStatus(429);
            res.setHeader("Retry-After", esAuth ? "60" : "1");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"detail\":\"Demasiadas solicitudes seguidas. Espera unos segundos e inténtalo de nuevo.\"}");
            return;
        }
        cadena.doFilter(req, res);
    }
}
