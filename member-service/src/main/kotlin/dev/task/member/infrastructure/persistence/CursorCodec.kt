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
    private const val MIN_MICROS = -62_135_596_800L * MICROS      // 0001-01-01T00:00:00Z
    private const val MAX_MICROS = 253_402_300_800L * MICROS - 1  // 9999-12-31T23:59:59.999999Z
    private val ID = Regex("^[a-z0-9]{10}$")

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
        // sane bounds (year 0001..9999, well inside timestamptz) and DB-safe values, so a tampered cursor is a 400, never a 500
        require(micros in MIN_MICROS..MAX_MICROS)
        require(!n.asString().contains('\u0000') && ID.matches(i.asString()))
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
