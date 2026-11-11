package co.edu.ucc.asimetria.usuario;

import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.persistencia.Usuario;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Emite JWT HS256 con expiración de 120 min (RNF-03). El sujeto es el id del usuario. */
@Service
public class TokenService {

    public record UsuarioVista(Long id, String email, String nombre, String perfilRiesgo, String plan) {
        public static UsuarioVista de(Usuario u) {
            return new UsuarioVista(u.getId(), u.getEmail(), u.getNombre(), u.getPerfilRiesgo(), u.getPlan());
        }
    }

    public record TokenVista(String accessToken, String tokenType, Instant expira, UsuarioVista usuario) {
    }

    private final JwtEncoder encoder;
    private final Duration vigencia;
    private final Clock reloj;

    public TokenService(JwtEncoder encoder, PropiedadesAsimetria props, Clock reloj) {
        this.encoder = encoder;
        this.vigencia = Duration.ofMinutes(props.jwt().minutos());
        this.reloj = reloj;
    }

    public TokenVista emitir(Usuario u) {
        Instant ahora = reloj.instant();
        Instant expira = ahora.plus(vigencia);
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(u.getId())).issuedAt(ahora).expiresAt(expira).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenVista(token, "bearer", expira, UsuarioVista.de(u));
    }
}
