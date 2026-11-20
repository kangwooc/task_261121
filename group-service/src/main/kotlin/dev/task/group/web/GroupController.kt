package dev.task.group.web

import dev.task.group.api.GroupApi
import dev.task.group.api.GroupSummaryResponse
import dev.task.group.api.MemberGroupDetailResponse
import dev.task.group.query.GetGroupSummariesHandler
import dev.task.group.query.GetGroupSummariesQuery
import dev.task.group.query.GetMemberGroupDetailHandler
import dev.task.group.query.MemberGroupNotFoundException
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class GroupController(
    private val summaries: GetGroupSummariesHandler,
    private val detail: GetMemberGroupDetailHandler,
) : GroupApi {
    override fun getGroupSummaries(@RequestParam memberIds: List<String>): GroupSummaryResponse =
        summaries.handle(GetGroupSummariesQuery(memberIds.toSet()))

    override fun getMemberGroup(@RequestParam memberId: String): MemberGroupDetailResponse =
        detail.handle(memberId) ?: throw MemberGroupNotFoundException(memberId)
}
