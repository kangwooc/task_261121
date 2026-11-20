package dev.task.member.web

import dev.task.member.api.MemberApi
import dev.task.member.api.MemberDetailResponse
import dev.task.member.api.MemberListResponse
import dev.task.member.query.GetMemberDetailHandler
import dev.task.member.query.GetMembersHandler
import dev.task.member.query.GetMembersQuery
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class MemberController(
    private val list: GetMembersHandler,
    private val detail: GetMemberDetailHandler,
) : MemberApi {
    override fun getMembers(
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) pagination: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) cursor: String?,
    ): MemberListResponse = list.handle(GetMembersQuery.of(size, sort, pagination, page, cursor))

    override fun getMember(@PathVariable memberId: String): MemberDetailResponse = detail.handle(memberId)
}
