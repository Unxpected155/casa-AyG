package com.casaayg.shared.web;

import java.net.URI;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import com.casaayg.shared.security.RecentAuthentication.ReauthenticationRequiredException;

/**
 * Errores de la API: mensajes GENÉRICOS (threat model 5.1-I). Nunca se devuelven stack traces,
 * SQL, nombres de tablas ni el valor que se envió. El detalle queda solo en el log del servidor.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({NotFoundException.class, MethodArgumentTypeMismatchException.class, NoResourceFoundException.class})
    ProblemDetail notFound(Exception e) {
        // Un id mal formado también es "no existe": no se distingue de un id ajeno.
        return problem(HttpStatus.NOT_FOUND, "No encontrado", "no-encontrado");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
                       HttpMessageNotReadableException.class, IllegalArgumentException.class,
                       MissingServletRequestParameterException.class, ConstraintViolationException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Solicitud inválida", "solicitud-invalida");
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        // El mensaje de ConflictException lo escribe la app (nunca viene de la BD ni del usuario).
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflicto", "conflicto");
        problem.setDetail(e.getMessage());
        return problem;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        return problem(HttpStatus.CONFLICT, "Ya existe", "duplicado");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.info("Violación de integridad: {}", sqlState(e));
        return problem(HttpStatus.CONFLICT, "Conflicto", "conflicto");
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    ProblemDetail reauth(ReauthenticationRequiredException e) {
        // El cliente debe pedir la passkey otra vez y reintentar.
        return problem(HttpStatus.FORBIDDEN, "Vuelve a confirmar con tu passkey", "reautenticacion");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Prohibido", "prohibido");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido", "metodo");
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException e) {
        log.error("Error de base de datos: {}", sqlState(e));
        // El detalle puede traer datos: solo en DEBUG (apagado en producción).
        log.debug("Detalle del error de base de datos", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "error-interno");
    }

    /**
     * El navegador cerró una conexión que seguía abierta (los avisos en vivo, al salir de la
     * pantalla): es normal, no es un error y ya no hay a quién responderle.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone(AsyncRequestNotUsableException e) {
        log.debug("El navegador cerró la conexión: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Error inesperado", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno", "error-interno");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String type) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setType(URI.create("urn:casaayg:" + type));
        return problem;
    }

    private static String sqlState(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return e.getClass().getSimpleName();
    }
}
