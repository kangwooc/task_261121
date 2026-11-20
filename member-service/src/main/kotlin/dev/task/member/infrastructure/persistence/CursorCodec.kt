package dev.task.member.infrastructure.persistence

import dev.task.member.query.CursorKey
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.SortDirection
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

/** Opaque cursor: base64url(no padding) of {"n":name,"r":epochMicros,"i":id,"d":"ASC"}. */
object CursorCodec {
    private val mapper = JsonMapper.builder().build()
    private const val MICROS = 1_000_000L

    fun encode(key: CursorKey): String {
        val micros = Math.addExact(
            Math.multiplyExact(key.registeredAt.epochSecond, MICROS),
            (key.registeredAt.nano / 1000).toLong(),
        )
        val json = mapper.writeValueAsString(mapOf("n" to key.name, "r" to micros, "i" to key.id, "d" to key.direction.name))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    fun decode(raw: String): CursorKey = try {
        val node = mapper.readTree(String(Base64.getUrlDecoder().decode(raw)))
        require(node.isObject)
        val n = node["n"]; val r = node["r"]; val i = node["i"]; val d = node["d"]
        require(n != null && n.isString && i != null && i.isString && d != null && d.isString)
        require(r != null && r.isIntegralNumber && r.canConvertToLong())
        val micros = r.asLong()
        CursorKey(
            n.asString(),
            Instant.ofEpochSecond(Math.floorDiv(micros, MICROS), Math.floorMod(micros, MICROS) * 1000),
            i.asString(),
            SortDirection.valueOf(d.asString()),
        )
    } catch (e: IllegalArgumentException) { // base64, missing/ill-typed field, unknown direction
        throw InvalidRequestException("invalid cursor")
    } catch (e: JacksonException) {
        throw InvalidRequestException("invalid cursor")
    }
}
