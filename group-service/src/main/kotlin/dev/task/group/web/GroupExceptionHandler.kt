package dev.task.group.web

import dev.task.group.query.InvalidRequestException
import dev.task.group.query.MemberGroupNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GroupExceptionHandler {
    @ExceptionHandler(InvalidRequestException::class, MissingServletRequestParameterException::class)
    fun invalid(e: Exception) = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message)

    @ExceptionHandler(MemberGroupNotFoundException::class)
    fun notFound(e: MemberGroupNotFoundException) = problem(HttpStatus.NOT_FOUND, "MEMBER_GROUP_NOT_FOUND", e.message)

    private fun problem(status: HttpStatus, code: String, detail: String?) =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            setProperty("code", code)
            setProperty("traceId", null) // Task 7 fills this
        }
}
