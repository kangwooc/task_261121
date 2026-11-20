package dev.task.group.web

import dev.task.group.TestcontainersConfig
import dev.task.group.api.GroupApi
import dev.task.group.api.Permission
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import org.springframework.web.service.invoker.createClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfig::class)
class GroupApiTest {
    @LocalServerPort private var port = 0
    private lateinit var api: GroupApi
    private val mapper = JsonMapper.builder().build()

    private val admin = "27ysni0g1i"
    private val editor = "itn3xvk9tf"
    private val ungrouped = "pq12td4qds"

    @BeforeEach
    fun client() {
        api = HttpServiceProxyFactory
            .builderFor(RestClientAdapter.create(RestClient.create("http://127.0.0.1:$port")))
            .build().createClient<GroupApi>()
    }

    @Test
    fun summaryMatchesSpecShape() {
        val g = api.getGroupSummaries(listOf(admin, editor, ungrouped)).groups.single()
        assertEquals("Alpha Team", g.groupName)
        assertEquals(mapOf(admin to Permission.Admin, editor to Permission.Editor), g.members.associate { it.memberId to it.permission })
        assertTrue(g.totalMemberCount > 2)
    }

    @Test
    fun duplicateIdsAreCountedOnce() {
        assertEquals(api.getGroupSummaries(listOf(admin, admin, editor)), api.getGroupSummaries(listOf(admin, editor)))
    }

    @Test
    fun detailReturnsFormattedDate() {
        val d = api.getMemberGroup(admin)
        assertEquals("Alpha Team", d.groupName)
        assertEquals("2025-06-01T12:00", d.registeredDate.toString())
    }

    @Test
    fun detailOfUngroupedMemberIs404WithCode() {
        val e = assertFailsWith<HttpClientErrorException.NotFound> { api.getMemberGroup(ungrouped) }
        val body = mapper.readTree(e.responseBodyAsString)
        assertEquals("MEMBER_GROUP_NOT_FOUND", body["code"].asString())
        assertTrue(body.has("traceId"))
    }

    @Test
    fun invalidDetailMemberIdIs400() {
        val e = assertFailsWith<HttpClientErrorException.BadRequest> { api.getMemberGroup("ABC") }
        assertEquals("INVALID_REQUEST", mapper.readTree(e.responseBodyAsString)["code"].asString())
    }

    @Test
    fun missingParamIs400() {
        val e = assertFailsWith<HttpClientErrorException.BadRequest> {
            RestClient.create("http://127.0.0.1:$port").get().uri("/api/group").retrieve().toBodilessEntity()
        }
        assertEquals("INVALID_REQUEST", mapper.readTree(e.responseBodyAsString)["code"].asString())
    }

    @ParameterizedTest
    @MethodSource("invalidSummaryIds")
    fun invalidSummaryRequestsAre400(ids: List<String>) {
        // raw client: the proxy cannot even encode an empty list
        val query = if (ids.isEmpty()) "memberIds=" else ids.joinToString("&") { "memberIds=$it" }
        val e = assertFailsWith<HttpClientErrorException.BadRequest> {
            RestClient.create("http://127.0.0.1:$port").get().uri("/api/groups/summary?$query").retrieve().toBodilessEntity()
        }
        val body = mapper.readTree(e.responseBodyAsString)
        assertEquals("INVALID_REQUEST", body["code"].asString())
        assertTrue(body.has("traceId"))
    }

    @Test
    fun controllerImplementsContract() {
        assertTrue(GroupApi::class.java.isAssignableFrom(GroupController::class.java))
    }

    companion object {
        @JvmStatic
        fun invalidSummaryIds() = listOf(
            emptyList<String>(),
            List(101) { "a%09d".format(it) },
            listOf("ABC"),
            listOf("abcdefghij1"),
        ).map { org.junit.jupiter.params.provider.Arguments.of(it) }
    }
}
