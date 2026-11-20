package com.sanvi.jobscheduler.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Factories for RFC 7807 errors; rendered by {@link ApiExceptionHandler}. */
public final class ApiProblems {
    private ApiProblems() {
    }

    public static ErrorResponseException notFound(String detail) {
        return of(HttpStatus.NOT_FOUND, "Not Found", detail);
    }

    public static ErrorResponseException conflict(String detail) {
        return of(HttpStatus.CONFLICT, "Conflict", detail);
    }

    public static ErrorResponseException badRequest(String detail) {
        return of(HttpStatus.BAD_REQUEST, "Bad Request", detail);
    }

    private static ErrorResponseException of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return new ErrorResponseException(status, problem, null);
    }
}
