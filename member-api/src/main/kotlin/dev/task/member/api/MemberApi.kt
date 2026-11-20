package dev.task.member.api

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

@HttpExchange("/api")
interface MemberApi {
    @GetExchange("/members")
    fun getMembers(
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) pagination: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) cursor: String?,
    ): MemberListResponse

    @GetExchange("/member/{memberId}")
    fun getMember(@PathVariable memberId: String): MemberDetailResponse
}
