package co.edu.ucc.asimetria.comun;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio con su código HTTP. El {@code ManejadorErrores} lo traduce a
 * {@code {"detail": "..."}}, el mismo contrato que consume el frontend.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus estado;

    public ApiException(HttpStatus estado, String detalle) {
        super(detalle);
        this.estado = estado;
    }

    public HttpStatus estado() {
        return estado;
    }

    public static ApiException noEncontrado(String detalle) {
        return new ApiException(HttpStatus.NOT_FOUND, detalle);
    }

    public static ApiException conflicto(String detalle) {
        return new ApiException(HttpStatus.CONFLICT, detalle);
    }

    public static ApiException invalido(String detalle) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, detalle);
    }

    public static ApiException noAutorizado(String detalle) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detalle);
    }
}
