package dev.smartdisplay.app.ui.dashboard

import dev.smartdisplay.app.ha.EntityState
import dev.smartdisplay.app.ha.HomeState
import dev.smartdisplay.app.ui.controls.ServiceCall

/** Kinds of Home Assistant item a dashboard button can run. */
private val RUNNABLE_DOMAINS = setOf("scene", "script", "automation", "button", "input_button")

/** How to run [entityId] from a button: a scene or script starts, an automation runs now, a button is pressed. */
fun runCall(entityId: String): ServiceCall? = when (entityId.substringBefore('.')) {
    "scene" -> ServiceCall("scene", "turn_on", entityId)
    "script" -> ServiceCall("script", "turn_on", entityId)
    "automation" -> ServiceCall("automation", "trigger", entityId)
    "button" -> ServiceCall("button", "press", entityId)
    "input_button" -> ServiceCall("input_button", "press", entityId)
    else -> null
}

/** Everything that can be a dashboard button, by name: scenes, scripts, automations and buttons. */
fun HomeState.runnables(): List<EntityState> = entities.values
    .filter { it.domain in RUNNABLE_DOMAINS }
    .filterNot { registry[it.entityId]?.let { entry -> entry.hidden || entry.disabled } == true }
    .sortedBy { it.friendlyName.lowercase() }

enum class Presence { Home, Away, Unknown }

/** A person in Home Assistant, and where they are: home, away, or in a named zone ([place], such as "Work"). */
data class Person(
    val entityId: String,
    val name: String,
    val initials: String,
    val presence: Presence,
    val place: String?,
)

/**
 * The household, from Home Assistant's people: home first, then away, each by name. Where someone is comes from the
 * Home Assistant app on their phone (or another tracker attached to them); without one they show as unknown.
 */
fun HomeState.people(): List<Person> = entities.values
    .filter { it.domain == "person" }
    .map { entity ->
        val name = entity.friendlyName
        val presence = when (entity.state) {
            "home" -> Presence.Home
            "unknown", "unavailable" -> Presence.Unknown
            else -> Presence.Away
        }
        Person(
            entityId = entity.entityId,
            name = name,
            initials = initials(name),
            presence = presence,
            place = entity.state.takeIf { presence == Presence.Away && it != "not_home" },
        )
    }
    .sortedWith(compareBy({ it.presence.ordinal }, { it.name.lowercase() }))

/** One or two letters for a round avatar: "Joseph Aquino" -> "JA", "Mum" -> "M". */
fun initials(name: String): String = name.trim().split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }
