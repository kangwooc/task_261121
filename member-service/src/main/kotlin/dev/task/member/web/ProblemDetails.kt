package dev.task.member.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail

fun problem(status: HttpStatus, code: String, detail: String?, traceId: String?): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
        setProperty("code", code)
        setProperty("traceId", traceId)
    }
