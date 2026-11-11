package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.usuario.AuthService;
import co.edu.ucc.asimetria.usuario.TokenService.TokenVista;
import co.edu.ucc.asimetria.usuario.TokenService.UsuarioVista;
import co.edu.ucc.asimetria.usuario.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RF-07 / HU-07: registro, login, demo, cierre de sesión y perfil. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Autenticación y perfil")
public class AuthController {

    private final AuthService auth;
    private final UsuarioService usuarios;
    private final IpCliente ip;

    public AuthController(AuthService auth, UsuarioService usuarios, IpCliente ip) {
        this.auth = auth;
        this.usuarios = usuarios;
        this.ip = ip;
    }

    @PostMapping("/auth/registro")
    @Operation(summary = "Crear cuenta (409 si el correo ya existe)")
    public ResponseEntity<TokenVista> registro(@Valid @RequestBody Solicitudes.Registro s, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.registrar(s.nombre(), s.email(), s.password(), s.perfil(), ip.de(req)));
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Iniciar sesión (401 genérico; 429 tras 5 intentos)")
    public TokenVista login(@Valid @RequestBody Solicitudes.Login s, HttpServletRequest req) {
        return auth.login(s.email(), s.password(), ip.de(req));
    }

    @PostMapping("/auth/demo")
    @Operation(summary = "Sesión de la cuenta demo (solo con MODO_DEMO)")
    public TokenVista demo(HttpServletRequest req) {
        return auth.demo(ip.de(req));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(Usuario u, HttpServletRequest req) {
        auth.logout(u, ip.de(req));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/usuarios/me")
    public UsuarioVista yo(Usuario u) {
        return UsuarioVista.de(u);
    }

    @PostMapping("/usuarios/me/quiz")
    @Operation(summary = "Responder el quiz de perfil de riesgo (HU-07 CA-3)")
    public UsuarioVista quiz(Usuario u, @Valid @RequestBody Solicitudes.Quiz s, HttpServletRequest req) {
        return UsuarioVista.de(usuarios.responderQuiz(u, s.respuestas(), ip.de(req)));
    }

    @PutMapping("/usuarios/me/plan")
    @Operation(summary = "Activar un plan (no recibe datos de tarjeta)")
    public UsuarioVista plan(Usuario u, @Valid @RequestBody Solicitudes.Plan s, HttpServletRequest req) {
        return UsuarioVista.de(usuarios.cambiarPlan(u, s.plan(), ip.de(req)));
    }
}
