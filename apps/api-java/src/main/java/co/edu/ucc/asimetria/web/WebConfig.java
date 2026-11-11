package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.persistencia.Usuario;
import co.edu.ucc.asimetria.usuario.UsuarioService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Cualquier parámetro {@code Usuario} de un controlador recibe el usuario autenticado del JWT.
 * Un único punto resuelve token → usuario (y responde 401 si el usuario ya no existe).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UsuarioService usuarios;

    public WebConfig(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter p) {
                return Usuario.class.equals(p.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest w, WebDataBinderFactory f) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                    throw ApiException.noAutorizado("No autenticado");
                }
                return usuarios.actual(jwt);
            }
        });
    }
}
