package dev.task.member.web

import dev.task.member.infrastructure.group.GroupCallStage
import dev.task.member.infrastructure.group.GroupClientProperties
import dev.task.member.infrastructure.group.GroupServiceException
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.MemberNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class MemberExceptionHandler(private val props: GroupClientProperties) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(e: InvalidRequestException) = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message, null)

    @ExceptionHandler(MemberNotFoundException::class)
    fun notFound(e: MemberNotFoundException) = problem(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", e.message, null)

    @ExceptionHandler(GroupServiceException::class)
    fun group(e: GroupServiceException): ResponseEntity<*> {
        val body = problem(HttpStatus.SERVICE_UNAVAILABLE, e.stage.name, "group service unavailable", null)
        val res = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        if (e.stage == GroupCallStage.CIRCUIT_OPEN) {
            res.header(HttpHeaders.RETRY_AFTER, props.circuitBreaker.waitDurationInOpenState.seconds.toString())
        }
        return res.body(body)
    }

    override fun handleExceptionInternal(ex: Exception, body: Any?, headers: HttpHeaders, statusCode: HttpStatusCode, request: WebRequest): ResponseEntity<Any>? {
        val pd = (body as? ProblemDetail) ?: ProblemDetail.forStatus(statusCode)
        val s = statusCode.value()
        pd.setProperty("code", when { s == 404 -> "NOT_FOUND"; s == 405 -> "METHOD_NOT_ALLOWED"; s in 400..499 -> "INVALID_REQUEST"; else -> "HTTP_$s" })
        pd.setProperty("traceId", null) // Task 7 fills this
        return super.handleExceptionInternal(ex, pd, headers, statusCode, request)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("unhandled exception", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal error", null)
    }
}
