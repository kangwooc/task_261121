package dev.task.member.web

import dev.task.member.api.MemberApi
import dev.task.member.api.MemberDetailResponse
import dev.task.member.api.MemberListResponse
import dev.task.member.query.GetMemberDetailHandler
import dev.task.member.query.GetMembersHandler
import dev.task.member.query.GetMembersQuery
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// OpenAPI annotations live here, not on MemberApi: the contract module stays free of Spring Boot/springdoc.
@Tag(name = "members", description = "멤버 조회 (그룹 정보는 group-service에서 합쳐서 반환)")
@RestController
class MemberController(
    private val list: GetMembersHandler,
    private val detail: GetMemberDetailHandler,
) : MemberApi {
    @Operation(
        summary = "멤버 목록",
        description = "size 1..100(기본 20), sort=name,asc|name,desc, pagination=offset(page)|cursor(cursor). " +
            "정렬 키는 name → registeredAt → id. 그룹이 없는 멤버는 group/permission이 null.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공. offset이면 page, cursor면 cursor 블록만 포함"),
        ApiResponse(responseCode = "400", description = "잘못된 파라미터 (code=INVALID_REQUEST). 그룹 서비스는 호출하지 않음"),
        ApiResponse(responseCode = "503", description = "그룹 서비스 장애. code = 실패 단계(READ_TIMEOUT, CIRCUIT_OPEN 등)"),
    )
    override fun getMembers(
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) pagination: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) cursor: String?,
    ): MemberListResponse = list.handle(GetMembersQuery.of(size, sort, pagination, page, cursor))

    @Operation(summary = "멤버 상세", description = "그룹이 없으면 group은 null.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "400", description = "memberId 형식 오류 (^[a-z0-9]{10}$, code=INVALID_REQUEST)"),
        ApiResponse(responseCode = "404", description = "멤버 없음 (code=MEMBER_NOT_FOUND)"),
        ApiResponse(responseCode = "503", description = "그룹 서비스 장애. code = 실패 단계"),
    )
    override fun getMember(@PathVariable memberId: String): MemberDetailResponse = detail.handle(memberId)
}
