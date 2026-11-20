package dev.task.group.api

import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange

@HttpExchange("/api")
interface GroupApi {
    @GetExchange("/groups/summary")
    fun getGroupSummaries(@RequestParam memberIds: List<String>): GroupSummaryResponse

    @GetExchange("/group")
    fun getMemberGroup(@RequestParam memberId: String): MemberGroupDetailResponse
}
