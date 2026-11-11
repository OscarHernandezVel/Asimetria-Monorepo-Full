package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * IP real del cliente para auditoría y límites (RNF-04).
 *
 * <p>Orden de confianza, solo si {@code asimetria.confiar-proxy=true} (la API no es accesible salvo
 * a través del proxy): {@code CloudFront-Viewer-Address} (AWS, la fija CloudFront y el cliente no
 * puede falsificarla), {@code X-Real-IP} (Nginx local) y, por último, la dirección de la conexión.
 */
@Component
public class IpCliente {

    private final boolean confiarProxy;

    public IpCliente(PropiedadesAsimetria props) {
        this.confiarProxy = props.confiarProxy();
    }

    public String de(HttpServletRequest req) {
        if (confiarProxy) {
            String cf = req.getHeader("CloudFront-Viewer-Address");
            if (cf != null && !cf.isBlank()) {
                return sinPuerto(cf.trim());
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        return req.getRemoteAddr();
    }

    /** "198.51.100.10:46532" → "198.51.100.10"; IPv6 "2001:db8::1:443" → "2001:db8::1". */
    static String sinPuerto(String direccion) {
        int i = direccion.lastIndexOf(':');
        return i > 0 ? direccion.substring(0, i) : direccion;
    }
}
