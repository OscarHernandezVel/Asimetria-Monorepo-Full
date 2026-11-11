package co.edu.ucc.asimetria.usuario;

import co.edu.ucc.asimetria.auditoria.AuditoriaService;
import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.comun.almacen.AlmacenCompartido;
import co.edu.ucc.asimetria.config.PropiedadesAsimetria;
import co.edu.ucc.asimetria.dominio.perfil.PerfilRiesgo;
import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.persistencia.UsuarioRepositorio;
import co.edu.ucc.asimetria.usuario.TokenService.TokenVista;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Registro e inicio de sesión (HU-07, RNF-03).
 * <ul>
 *   <li>Contraseñas con bcrypt (costo configurable, 12 en producción); máximo 72 bytes (límite de bcrypt).</li>
 *   <li>Mensaje genérico «Credenciales inválidas» y tiempo constante: si el correo no existe se compara
 *       contra un hash ficticio, así no se pueden enumerar usuarios (CA-4).</li>
 *   <li>Bloqueo de 60 s tras 5 intentos fallidos, compartido entre instancias (almacén Redis).</li>
 * </ul>
 */
@Service
public class AuthService {

    static final int MAX_INTENTOS = 5;
    static final Duration BLOQUEO = Duration.ofSeconds(60);

    private final UsuarioRepositorio usuarios;
    private final PasswordEncoder hasher;
    private final TokenService tokens;
    private final AuditoriaService auditoria;
    private final AlmacenCompartido almacen;
    private final UsuarioService usuarioService;
    private final PropiedadesAsimetria props;
    private final String hashFicticio;

    public AuthService(UsuarioRepositorio usuarios, PasswordEncoder hasher, TokenService tokens, AuditoriaService auditoria,
                       AlmacenCompartido almacen, UsuarioService usuarioService, PropiedadesAsimetria props) {
        this.usuarios = usuarios;
        this.hasher = hasher;
        this.tokens = tokens;
        this.auditoria = auditoria;
        this.almacen = almacen;
        this.usuarioService = usuarioService;
        this.props = props;
        this.hashFicticio = hasher.encode("contraseña-ficticia-para-tiempo-constante");
    }

    static void validarContrasena(String p) {
        boolean fuerte = p != null && p.length() >= 8 && p.getBytes(StandardCharsets.UTF_8).length <= 72
                && p.chars().anyMatch(Character::isLetter) && p.chars().anyMatch(Character::isDigit);
        if (!fuerte) {
            throw ApiException.invalido("La contraseña debe tener mínimo 8 caracteres, con letras y números");
        }
    }

    @Transactional
    public TokenVista registrar(String nombre, String email, String password, String perfil, String ip) {
        String correo = email.trim().toLowerCase(Locale.ROOT);
        if (nombre == null || nombre.isBlank()) {
            throw ApiException.invalido("Ingresa tu nombre completo");
        }
        validarContrasena(password);
        if (perfil != null) {
            PerfilRiesgo.valueOf(perfil);
        }
        if (usuarios.existsByEmail(correo)) {   // CA-5
            auditoria.error("REGISTRO", null, correo, ip, "correo en uso");
            throw ApiException.conflicto("Este correo ya está en uso. Inicia sesión o usa otro correo.");
        }
        Usuario u = usuarios.save(new Usuario(correo, hasher.encode(password), nombre.trim(), perfil));
        auditoria.exito("REGISTRO", u, ip, "perfil " + (perfil == null ? "pendiente" : perfil));
        return tokens.emitir(u);
    }

    /** Sin transacción propia: la auditoría (también en los intentos fallidos) se escribe en la suya. */
    public TokenVista login(String email, String password, String ip) {
        String correo = email.trim().toLowerCase(Locale.ROOT);
        String clave = "login:" + correo;
        long intentos = almacen.obtener(clave).map(Long::parseLong).orElse(0L);
        if (intentos >= MAX_INTENTOS) {
            auditoria.error("INICIO_SESION", null, correo, ip, "bloqueado por intentos");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera un minuto e inténtalo de nuevo.");
        }
        Usuario u = usuarios.findByEmail(correo).orElse(null);
        boolean valida = hasher.matches(password == null ? "" : password, u != null ? u.getPasswordHash() : hashFicticio);
        if (u == null || !valida) {
            almacen.incrementar(clave, BLOQUEO);
            auditoria.error("INICIO_SESION", null, correo, ip, "credenciales inválidas");
            throw ApiException.noAutorizado("Credenciales inválidas");
        }
        almacen.eliminar(clave);
        auditoria.exito("INICIO_SESION", u, ip, null);
        return tokens.emitir(u);
    }

    public TokenVista demo(String ip) {
        if (!props.modoDemo()) {
            throw ApiException.noEncontrado("No encontrado");
        }
        Usuario u = usuarioService.crearDemo();
        auditoria.exito("INICIO_SESION", u, ip, "cuenta demo");
        return tokens.emitir(u);
    }

    public void logout(Usuario u, String ip) {
        auditoria.exito("CIERRE_SESION", u, ip, null);
    }
}
