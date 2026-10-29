package dev.founder.intelligentcore.api

import dev.founder.intelligentcore.agent.AgentUnavailableException
import dev.founder.intelligentcore.domain.AccountDataUnavailableException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = problem(HttpStatus.BAD_REQUEST, e)

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = problem(HttpStatus.NOT_FOUND, e)

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(e: IllegalStateException) = problem(HttpStatus.CONFLICT, e)

    @ExceptionHandler(AgentUnavailableException::class, AccountDataUnavailableException::class)
    fun unavailable(e: RuntimeException) = problem(HttpStatus.SERVICE_UNAVAILABLE, e)

    private fun problem(status: HttpStatus, e: Exception): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, e.message ?: status.reasonPhrase)
}
