package co.edu.ucc.asimetria;

import co.edu.ucc.asimetria.persistencia.UsuarioRepositorio;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HU-07, RNF-03 (bcrypt, JWT, 401) y RNF-04 (auditoría) contra la API real. */
class AuthApiTest extends PruebaIntegracion {

    @Autowired
    private UsuarioRepositorio usuarios;
    @Autowired
    private JwtDecoder decoder;
    @Autowired
    private JwtEncoder encoder;

    private String login(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    @Test
    void ca1RegistroGuardaHashBcryptNuncaTextoPlano() throws Exception {
        Cuenta c = registrar();
        String hash = usuarios.findByEmail(c.email()).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$").doesNotContain("Prueba2026x");
    }

    @Test
    void ca5CorreoYaRegistrado() throws Exception {
        Cuenta c = registrar();
        mvc.perform(post(V1 + "/auth/registro").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"X","email":"%s","password":"Otra12345"}""".formatted(c.email())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ya está en uso")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"corta1", "sololetras", "12345678"})
    void registroRechazaContrasenasDebiles(String password) throws Exception {
        mvc.perform(post(V1 + "/auth/registro").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"X","email":"debil@prueba.test","password":"%s"}""".formatted(password)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ca2LoginEntregaJwtHs256De120Minutos() throws Exception {
        Cuenta c = registrar();
        String r = cuerpo(mvc.perform(post(V1 + "/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(c.email(), "Prueba2026x")))
                .andExpect(status().isOk()));
        Jwt jwt = decoder.decode(JsonPath.read(r, "$.access_token"));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(120));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat((String) JsonPath.read(r, "$.token_type")).isEqualTo("bearer");
    }

    @Test
    void ca4CredencialesInvalidasMensajeGenerico() throws Exception {
        Cuenta c = registrar();
        mvc.perform(post(V1 + "/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(c.email(), "Incorrecta1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Credenciales inválidas"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        mvc.perform(post(V1 + "/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("nadie@prueba.test", "Incorrecta1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Credenciales inválidas"));
    }

    @Test
    void bloqueoTrasCincoIntentos() throws Exception {
        Cuenta c = registrar();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(V1 + "/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(c.email(), "Incorrecta1")));
        }
        mvc.perform(post(V1 + "/auth/login").contentType(MediaType.APPLICATION_JSON).content(login(c.email(), "Prueba2026x")))
                .andExpect(status().isTooManyRequests());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/usuarios/me", "/watchlist", "/alertas", "/metrics/", "/recommendations/", "/mercado/cotizaciones",
            "/simulaciones", "/auditoria", "/integraciones"})
    void rnf03Cien100PorCientoDe401SinToken(String ruta) throws Exception {
        mvc.perform(get(V1 + ruta)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("$.detail").value("No autenticado"));
    }

    @Test
    void rnf03TokenCaducadoAlteradoOHuerfano() throws Exception {
        Cuenta c = registrar();
        String caducado = firmar(String.valueOf(c.id()), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));
        String huerfano = firmar("999999", Instant.now(), Instant.now().plusSeconds(300));
        String alterado = c.token().substring(0, c.token().length() - 4) + "abcd";
        mvc.perform(get(V1 + "/usuarios/me").header("Authorization", "Bearer " + caducado))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("La sesión expiró"));
        mvc.perform(get(V1 + "/usuarios/me").header("Authorization", "Bearer " + alterado))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Token inválido"));
        mvc.perform(get(V1 + "/usuarios/me").header("Authorization", "Bearer " + huerfano))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Token inválido"));
    }

    private String firmar(String sub, Instant iat, Instant exp) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(sub).issuedAt(iat).expiresAt(exp).build())).getTokenValue();
    }

    @Test
    void ca3QuizClasificaYGuardaPerfil() throws Exception {
        Cuenta c = registrar(null);
        getCon(c, "/usuarios/me").andExpect(jsonPath("$.perfil_riesgo").doesNotExist());
        postCon(c, "/usuarios/me/quiz", "{\"respuestas\":[3,3,3,3,2]}").andExpect(jsonPath("$.perfil_riesgo").value("AGRESIVO"));
        postCon(c, "/usuarios/me/quiz", "{\"respuestas\":[1,2,3,4,1]}").andExpect(status().isUnprocessableEntity());
        postCon(c, "/usuarios/me/quiz", "{\"respuestas\":[1,2]}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void planDemoYCierreDeSesion() throws Exception {
        Cuenta c = registrar();
        putCon(c, "/usuarios/me/plan", "{\"plan\":\"MODERADO\"}").andExpect(jsonPath("$.plan").value("MODERADO"));
        putCon(c, "/usuarios/me/plan", "{\"plan\":\"PREMIUM\"}").andExpect(status().isUnprocessableEntity());
        mvc.perform(post(V1 + "/auth/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.usuario.email").value("demo@asimetria.app"));
        postCon(c, "/auth/logout", "{}").andExpect(status().isNoContent());
        getCon(c, "/auditoria").andExpect(jsonPath("$[0].evento").value("CIERRE_SESION"));
    }

    @Test
    void rnf04AuditoriaRegistraCamposObligatorios() throws Exception {
        Cuenta c = registrar();
        mvc.perform(post(V1 + "/auth/login").header("X-Real-IP", "203.0.113.7").contentType(MediaType.APPLICATION_JSON)
                .content(login(c.email(), "Prueba2026x"))).andExpect(status().isOk());
        String r = cuerpo(getCon(c, "/auditoria"));
        List<String> eventos = JsonPath.read(r, "$[*].evento");
        assertThat(eventos).contains("REGISTRO", "INICIO_SESION");
        getCon(c, "/auditoria").andExpect(jsonPath("$[0].ip").value("203.0.113.7")).andExpect(jsonPath("$[0].estado").value("EXITO"))
                .andExpect(jsonPath("$[0].ts").exists()).andExpect(jsonPath("$[*].email", hasItem(c.email())));
    }
}
