package co.edu.ucc.asimetria.usuario;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.dominio.calculo.MotorCalculo;
import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;
import co.edu.ucc.asimetria.persistencia.EmpresaRepositorio;
import co.edu.ucc.asimetria.persistencia.ListaSeguimiento;
import co.edu.ucc.asimetria.persistencia.ListaSeguimientoRepositorio;
import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.persistencia.UsuarioRepositorio;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Usuario autenticado, perfil de riesgo (quiz), plan y cuenta demo. */
@Service
public class UsuarioService {

    public static final String EMAIL_DEMO = "demo@asimetria.app";

    private final UsuarioRepositorio usuarios;
    private final EmpresaRepositorio empresas;
    private final ListaSeguimientoRepositorio listas;
    private final PasswordEncoder hasher;
    private final AuditoriaService auditoria;

    public UsuarioService(UsuarioRepositorio usuarios, EmpresaRepositorio empresas, ListaSeguimientoRepositorio listas,
                          PasswordEncoder hasher, AuditoriaService auditoria) {
        this.usuarios = usuarios;
        this.empresas = empresas;
        this.listas = listas;
        this.hasher = hasher;
        this.auditoria = auditoria;
    }

    /** Resuelve el usuario del token; si fue eliminado, el token deja de valer (401). */
    @Transactional(readOnly = true)
    public Usuario actual(Jwt jwt) {
        return usuarios.findById(Long.valueOf(jwt.getSubject())).orElseThrow(() -> ApiException.noAutorizado("Token inválido"));
    }

    @Transactional
    public Usuario responderQuiz(Usuario u, List<Integer> respuestas, String ip) {
        if (respuestas == null || respuestas.size() != 5) {
            throw ApiException.invalido("Responde todas las preguntas del quiz");
        }
        PerfilRiesgo perfil;
        try {
            perfil = MotorCalculo.perfilDesdeQuiz(respuestas);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalido(e.getMessage());
        }
        String anterior = u.getPerfilRiesgo();
        u.setPerfilRiesgo(perfil.name());
        Usuario guardado = usuarios.save(u);
        auditoria.exito("CAMBIO_PERFIL_RIESGO", guardado, ip, (anterior == null ? "ninguno" : anterior) + " → " + perfil);
        return guardado;
    }

    @Transactional
    public Usuario cambiarPlan(Usuario u, String plan, String ip) {
        try {
            PerfilRiesgo.valueOf(plan);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalido("Plan no válido");
        }
        u.setPlan(plan);
        Usuario guardado = usuarios.save(u);
        auditoria.exito("SUSCRIPCION", guardado, ip, "plan " + plan);
        return guardado;
    }

    /** Cuenta demo con contraseña aleatoria (solo accesible vía /auth/demo) y una cartera de ejemplo. */
    @Transactional
    public Usuario crearDemo() {
        return usuarios.findByEmail(EMAIL_DEMO).orElseGet(() -> {
            byte[] azar = new byte[24];
            new SecureRandom().nextBytes(azar);
            Usuario u = usuarios.save(new Usuario(EMAIL_DEMO, hasher.encode(Base64.getEncoder().encodeToString(azar)),
                    "Oscar Hernández", PerfilRiesgo.MODERADO.name()));
            record Item(String ticker, int cantidad, boolean favorito) {
            }
            for (Item i : List.of(new Item("RTC", 120, false), new Item("NVC", 80, true), new Item("QFN", 150, true), new Item("GLX", 55, false))) {
                empresas.findByTickerAndActivaTrue(i.ticker()).ifPresent(e -> listas.save(new ListaSeguimiento(u.getId(), e, i.cantidad(), i.favorito())));
            }
            return u;
        });
    }
}
