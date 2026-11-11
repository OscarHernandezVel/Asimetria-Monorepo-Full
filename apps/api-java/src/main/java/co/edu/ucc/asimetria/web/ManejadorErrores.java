package co.edu.ucc.asimetria.web;

import co.edu.ucc.asimetria.comun.ApiException;
import co.edu.ucc.asimetria.dominio.perfil.MotorRecomendacion.PerfilRequeridoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Traduce excepciones al contrato de errores de la API: {@code {"detail": "mensaje"}} con el código
 * adecuado. Ningún detalle interno (trazas, SQL) llega al cliente; el error completo queda en el log.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    private static ResponseEntity<Map<String, String>> error(HttpStatus estado, String detalle) {
        return ResponseEntity.status(estado).body(Map.of("detail", detalle));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e) {
        return error(e.estado(), e.getMessage());
    }

    @ExceptionHandler(PerfilRequeridoException.class)
    public ResponseEntity<Map<String, String>> perfil(PerfilRequeridoException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());   // HU-04 CA-5
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validacion(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage()).findFirst().orElse("Datos inválidos");
        return error(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, String>> validacionParametros(HandlerMethodValidationException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Parámetros inválidos");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> peticionInvalida(Exception e) {
        String msg = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "Petición inválida";
        return error(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integridad(DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "El registro ya existe o no cumple las restricciones");
    }

    /** Pool de conexiones agotado o BD momentáneamente no disponible: 503 inmediato, no un cuelgue. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, TransientDataAccessException.class})
    public ResponseEntity<Map<String, String>> ocupado(Exception e) {
        log.warn("Base de datos no disponible: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2")
                .body(Map.of("detail", "El servicio está ocupado. Inténtalo de nuevo en unos segundos."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> noEncontrado(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "No encontrado");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> metodo(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> inesperado(Exception e) {
        log.error("Error no controlado", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno. Inténtalo de nuevo en unos segundos.");
    }
}
