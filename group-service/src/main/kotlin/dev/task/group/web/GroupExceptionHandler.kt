package dev.task.group.web

import dev.task.group.query.InvalidRequestException
import dev.task.group.query.MemberGroupNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GroupExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(e: InvalidRequestException) = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message)

    @ExceptionHandler(MemberGroupNotFoundException::class)
    fun notFound(e: MemberGroupNotFoundException) = problem(HttpStatus.NOT_FOUND, "MEMBER_GROUP_NOT_FOUND", e.message)

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
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal error")
    }

    private fun problem(status: HttpStatus, code: String, detail: String?) =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            setProperty("code", code)
            setProperty("traceId", null) // Task 7 fills this
        }
}
