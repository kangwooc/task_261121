package dev.task.group.web

import dev.task.group.api.GroupApi
import dev.task.group.api.GroupSummaryResponse
import dev.task.group.api.MemberGroupDetailResponse
import dev.task.group.query.GetGroupSummariesHandler
import dev.task.group.query.GetGroupSummariesQuery
import dev.task.group.query.GetMemberGroupDetailHandler
import dev.task.group.query.MemberGroupNotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// OpenAPI annotations live here, not on GroupApi: the contract module stays free of Spring Boot/springdoc.
@Tag(name = "groups", description = "그룹 조회 (내부 전용)")
@RestController
class GroupController(
    private val summaries: GetGroupSummariesHandler,
    private val detail: GetMemberGroupDetailHandler,
) : GroupApi {
    @Operation(
        summary = "멤버 ID 목록의 그룹 요약",
        description = "memberIds 1..100개(중복 제거). totalMemberCount는 그룹 전체 멤버 수, members는 요청 ID 중 소속 멤버만. " +
            "그룹이 없는 ID는 응답에 나오지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "memberIds 비었음/100개 초과/형식 오류 (code=INVALID_REQUEST)"),
    )
    override fun getGroupSummaries(@RequestParam memberIds: List<String>): GroupSummaryResponse =
        summaries.handle(GetGroupSummariesQuery(memberIds.toSet()))

    @Operation(summary = "멤버의 그룹 상세", description = "registeredDate = 멤버가 그룹에 추가된 시각.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "memberId 형식 오류 (code=INVALID_REQUEST)"),
        ApiResponse(responseCode = "404", description = "소속 그룹 없음 (code=MEMBER_GROUP_NOT_FOUND)"),
    )
    override fun getMemberGroup(@RequestParam memberId: String): MemberGroupDetailResponse =
        detail.handle(memberId) ?: throw MemberGroupNotFoundException(memberId)
}
