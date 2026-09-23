package dev.smartdisplay.app.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why the connection is down and waiting to retry. */
enum class DisconnectReason {
    /** Couldn't reach Home Assistant, or the connection dropped. */
    Unreachable,
    /** Home Assistant refused the access token even after a refresh. */
    Rejected,
    /** Home Assistant sent something the app didn't expect. */
    Protocol,
}

sealed interface ConnectionStatus {
    /** Nothing is showing live data, so there's no connection. */
    data object Idle : ConnectionStatus
    data object Connecting : ConnectionStatus
    /** Authenticated, initial data loaded, receiving changes. */
    data object Connected : ConnectionStatus
    data class Waiting(val retryAtMillis: Long, val reason: DisconnectReason) : ConnectionStatus
    /** No sign-in to connect with. */
    data object SignedOut : ConnectionStatus
}

/** Home Assistant's core settings, from `get_config`. */
data class HaConfig(
    val locationName: String?,
    val version: String?,
    val timeZone: String?,
    val temperatureUnit: String?,
)

/** One entity's current state, from `get_states` and `state_changed` events. */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: String?,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String get() = attributes.string("friendly_name") ?: entityId
}

data class Area(val id: String, val name: String, val floorId: String?, val icon: String?)

data class Device(val id: String, val name: String?, val areaId: String?)

/** An entity's registry entry: its area and device, and whether it's hidden or disabled. */
data class EntityEntry(
    val entityId: String,
    val name: String?,
    val areaId: String?,
    val deviceId: String?,
    val hidden: Boolean,
    val disabled: Boolean,
    val category: String?,
)

/**
 * Everything the display knows about Home Assistant. Data is kept through reconnects, so screens can keep showing the
 * last known state while [status] says it's stale.
 */
data class HomeState(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val config: HaConfig? = null,
    val entities: Map<String, EntityState> = emptyMap(),
    val areas: List<Area> = emptyList(),
    val devices: Map<String, Device> = emptyMap(),
    val registry: Map<String, EntityEntry> = emptyMap(),
    /** The most recent state change, for showing that updates are live. */
    val lastChange: EntityState? = null,
) {
    /** True once the first connection has loaded everything. */
    val loaded: Boolean get() = config != null

    /** The entity's own area, or else its device's area. */
    fun areaIdOf(entityId: String): String? {
        val entry = registry[entityId] ?: return null
        return entry.areaId ?: entry.deviceId?.let { devices[it]?.areaId }
    }
}

// Parsers for Home Assistant's JSON. They skip malformed items rather than failing the whole list.

internal fun parseConfig(json: JsonElement): HaConfig {
    val obj = json as? JsonObject ?: throw ProtocolException("get_config: not an object")
    return HaConfig(
        locationName = obj.string("location_name"),
        version = obj.string("version"),
        timeZone = obj.string("time_zone"),
        temperatureUnit = (obj["unit_system"] as? JsonObject)?.string("temperature"),
    )
}

internal fun parseStates(json: JsonElement): Map<String, EntityState> =
    json.objects().mapNotNull(::parseState).associateBy { it.entityId }

internal fun parseState(json: JsonElement?): EntityState? {
    val obj = json as? JsonObject ?: return null
    return EntityState(
        entityId = obj.string("entity_id") ?: return null,
        state = obj.string("state") ?: return null,
        attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
        lastChanged = obj.string("last_changed"),
    )
}

internal fun parseAreas(json: JsonElement): List<Area> = json.objects().mapNotNull { obj ->
    Area(
        id = obj.string("area_id") ?: return@mapNotNull null,
        name = obj.string("name") ?: return@mapNotNull null,
        floorId = obj.string("floor_id"),
        icon = obj.string("icon"),
    )
}.sortedBy { it.name.lowercase() }

internal fun parseDevices(json: JsonElement): Map<String, Device> = json.objects().mapNotNull { obj ->
    Device(
        id = obj.string("id") ?: return@mapNotNull null,
        name = obj.string("name_by_user") ?: obj.string("name"),
        areaId = obj.string("area_id"),
    )
}.associateBy { it.id }

internal fun parseEntityRegistry(json: JsonElement): Map<String, EntityEntry> = json.objects().mapNotNull { obj ->
    EntityEntry(
        entityId = obj.string("entity_id") ?: return@mapNotNull null,
        name = obj.string("name") ?: obj.string("original_name"),
        areaId = obj.string("area_id"),
        deviceId = obj.string("device_id"),
        hidden = obj.string("hidden_by") != null,
        disabled = obj.string("disabled_by") != null,
        category = obj.string("entity_category"),
    )
}.associateBy { it.entityId }

/**
 * Applies a `state_changed` event's data to [entities]. A null `new_state` means the entity was removed. Returns the
 * new state (or null) and the updated map.
 */
internal fun applyStateChanged(
    entities: Map<String, EntityState>,
    eventData: JsonObject,
): Pair<EntityState?, Map<String, EntityState>> {
    val entityId = eventData.string("entity_id") ?: return null to entities
    val newState = parseState(eventData["new_state"].takeUnless { it is JsonNull })
    return newState to if (newState == null) entities - entityId else entities + (entityId to newState)
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
