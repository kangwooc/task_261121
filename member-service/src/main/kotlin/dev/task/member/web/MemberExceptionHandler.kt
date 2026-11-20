package dev.task.member.web

import dev.task.member.infrastructure.group.GroupCallStage
import dev.task.member.infrastructure.group.GroupClientProperties
import dev.task.member.infrastructure.group.GroupServiceException
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.MemberNotFoundException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class MemberExceptionHandler(private val props: GroupClientProperties) {
    @ExceptionHandler(InvalidRequestException::class, MethodArgumentTypeMismatchException::class)
    fun invalid(e: Exception) = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message, null)

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
}
