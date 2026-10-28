package dev.smartdisplay.app.kiosk

/**
 * A family / kitchen display: by day a dashboard with buttons for Home Assistant scenes, scripts and automations,
 * who's home, with Home and Away buttons, and the family calendar, colored by person.
 */
data class KitchenConfig(
    /** Scenes, scripts, automations and buttons shown as shortcut buttons, in this order. */
    val shortcuts: List<String> = emptyList(),
    /** What the Home and Away buttons run (a scene or script, say); null leaves the button out. */
    val homeAction: String? = null,
    val awayAction: String? = null,
    /** Calendars left off the dashboard; every other one shows, including ones added later. */
    val hiddenCalendars: Set<String> = emptySet(),
    /** Whose each calendar is, as a `person.*` entity; a calendar not listed is the family's (everyone's). */
    val calendarOwners: Map<String, String> = emptyMap(),
    /** Colors the owner chose for people; anyone else gets the next unused one (see `personColors`). */
    val personColors: Map<String, PersonColor> = emptyMap(),
    /**
     * Events given to people on this display, by event key (`seriesKey` for every time, `instanceKey` for one time
     * of a repeating event), overriding whose calendar they're on.
     */
    val eventPeople: Map<String, List<String>> = emptyMap(),
    /**
     * By day, minutes untouched before the dashboard gives way to the clock over the photos, which moves against
     * burn-in; 0 for never. At night it always does, after 2 minutes.
     */
    val screensaverMinutes: Int = DEFAULT_SCREENSAVER_MINUTES,
    /** The dashboard's cards and where they are, as arranged in its edit mode. */
    val layout: DashLayout = DashLayout(),
    /** People left out of Who's home; everyone else shows, including people added later. */
    val hiddenPeople: Set<String> = emptySet(),
    /** The weather shown on the dashboard and the clock; null for the first weather entity. */
    val weatherEntity: String? = null,
)

const val DEFAULT_SCREENSAVER_MINUTES = 10

/** The screensaver times Settings offers; 0 is never. */
val SCREENSAVER_CHOICES = listOf(0, 2, 5, 10, 15, 30)

/** The colors a person can have on the calendar, in the order they're handed out. */
enum class PersonColor { Pink, Blue, Lavender, Mint, Peach, Butter, Coral, Sky }

/**
 * Everyone's color: the owner's choice, else the first color nobody has chosen yet, handed out in entity ID order
 * so a person keeps theirs as others come and go. With more people than colors, they repeat.
 */
fun personColors(people: Collection<String>, chosen: Map<String, PersonColor>): Map<String, PersonColor> {
    val taken = people.mapNotNull { chosen[it] }.toSet()
    val free = PersonColor.entries.filterNot { it in taken }.ifEmpty { PersonColor.entries }
    var next = 0
    return people.sorted().associateWith { person ->
        chosen[person] ?: free[next++ % free.size]
    }
}
