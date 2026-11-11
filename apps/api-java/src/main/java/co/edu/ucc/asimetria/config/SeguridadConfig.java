package co.edu.ucc.asimetria.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Seguridad (RNF-03): API sin estado con JWT HS256, bcrypt, 401 con {@code WWW-Authenticate: Bearer}
 * para toda ruta protegida sin token válido, y cabeceras de seguridad.
 */
@Configuration
public class SeguridadConfig {

    private static final String[] PUBLICAS = {
            "/api/v1/destacado", "/health", "/actuator/health/**", "/docs", "/docs/**", "/openapi.json", "/openapi.json/**",
            "/swagger-ui/**", "/v3/api-docs/**"};

    @Bean
    public PasswordEncoder passwordEncoder(PropiedadesAsimetria props) {
        return new BCryptPasswordEncoder(props.bcryptCosto());
    }

    @Bean
    public SecretKey claveJwt(PropiedadesAsimetria props) {
        return new SecretKeySpec(props.jwt().secreto().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey clave) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(clave));
    }

    /** Sin tolerancia de reloj: un token caducado se rechaza en el segundo exacto de su expiración. */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey clave) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(clave).macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new JwtTimestampValidator(Duration.ZERO));
        return d;
    }

    /** Respuesta 401 con el contrato de la API ({@code {"detail": ...}}) y el motivo concreto. */
    @Bean
    public AuthenticationEntryPoint puntoEntrada401() {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> escribir401(req, res, ex);
    }

    static void escribir401(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        String motivo;
        if (req.getHeader("Authorization") == null) {
            motivo = "No autenticado";
        } else if (ex != null && ex.getMessage() != null && ex.getMessage().toLowerCase().contains("expired")) {
            motivo = "La sesión expiró";
        } else {
            motivo = "Token inválido";
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"detail\":\"" + motivo + "\"}");
    }

    @Bean
    public SecurityFilterChain cadenaSeguridad(HttpSecurity http, AuthenticationEntryPoint puntoEntrada401) throws Exception {
        http.csrf(c -> c.disable())   // API con Bearer token, sin cookies de sesión: CSRF no aplica
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registro", "/api/v1/auth/login", "/api/v1/auth/demo").permitAll()
                        .requestMatchers(PUBLICAS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(puntoEntrada401))
                .exceptionHandling(e -> e.authenticationEntryPoint(puntoEntrada401))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .contentTypeOptions(Customizer.withDefaults()));
        return http.build();
    }
}
