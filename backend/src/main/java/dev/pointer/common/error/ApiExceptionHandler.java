package dev.pointer.common.error;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    static final String CODE_PROPERTY = "code";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        var problem = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed");
        Map<String, String> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> String.valueOf(error.getDefaultMessage()),
                        (first, second) -> first));
        problem.setProperty("fields", fieldErrors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", "Request body is malformed");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        var problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setProperty(CODE_PROPERTY, code);
        return problem;
    }
}
