package app.offlineresearch.rag

import java.io.BufferedReader
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A position on Earth, in degrees. */
data class Position(val latitude: Double, val longitude: Double) {
    companion object {
        /** Reads "39.7392,-104.9903"; null when the text is not a position. */
        fun parse(text: String): Position? {
            val parts = text.split(',').map { it.trim().toDoubleOrNull() }
            val (latitude, longitude) = if (parts.size == 2) parts else return null
            if (latitude == null || longitude == null || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return Position(latitude, longitude)
        }
    }
}

/** The city a position belongs to. */
data class Place(val city: String, val country: String, val distanceKm: Double)

/**
 * Turns a position into a city name with no network: the largest city within
 * 15 km, or failing that the nearest within 50 km. This is the rule the place
 * packs were built with, so the name is one their lists use.
 */
class Gazetteer(private val cities: List<City>) {
    data class City(val name: String, val country: String, val latitude: Double, val longitude: Double, val population: Long)

    private val grid: Map<Pair<Int, Int>, List<City>> =
        cities.groupBy { floor(it.latitude).toInt() to floor(it.longitude).toInt() }

    val size: Int get() = cities.size

    fun nearest(position: Position): Place? {
        // 50 km is under one degree of latitude; longitude cells shrink toward the poles.
        val reach = if (kotlin.math.abs(position.latitude) < 60) 1 else 3
        val row = floor(position.latitude).toInt()
        val column = floor(position.longitude).toInt()
        val near = mutableListOf<Pair<Double, City>>()
        for (dRow in -1..1) {
            for (dColumn in -reach..reach) {
                for (city in grid[row + dRow to column + dColumn].orEmpty()) {
                    val km = distanceKm(position.latitude, position.longitude, city.latitude, city.longitude)
                    if (km <= FAR_KM) near += km to city
                }
            }
        }
        val close = near.filter { it.first <= CLOSE_KM }
        val (km, city) = close.maxByOrNull { it.second.population } ?: near.minByOrNull { it.first } ?: return null
        return Place(city.name, city.country, km)
    }

    companion object {
        private const val CLOSE_KM = 15.0
        private const val FAR_KM = 50.0

        /** Reads lines of name, country, latitude, longitude, population, separated by tabs. */
        fun read(reader: BufferedReader): Gazetteer = Gazetteer(
            reader.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split('\t')
                    if (fields.size < 5) return@mapNotNull null
                    City(fields[0], fields[1], fields[2].toDouble(), fields[3].toDouble(), fields[4].toLongOrNull() ?: 0)
                }.toList()
            },
        )

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
            return 12742 * asin(sqrt(a))
        }
    }
}

/** Questions about where the reader is: "vegan restaurants near me". */
object Here {
    // Phrases that stand for the reader's own position and can be replaced by "in <city>".
    private val PHRASE = Regex(
        """\b(?:near(?:est| by|by)? (?:to )?(?:me|here|my (?:location|position|hotel|place))|close to (?:me|here)|around (?:me|here)|in my (?:area|city|town|neighbou?rhood)|in this (?:area|city|town)|nearby|near here|where i am(?: now)?|at my (?:current )?location)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "Where is the nearest pharmacy?" The reader's position is meant though not said.
    // A bare "nearest" is not enough: "two hours from the nearest road" asks nothing about places.
    private val SEEKING = Regex(
        """\b(?:where(?:'s| is| are| can i find)?|find|is there|are there|how far is)\b.{0,40}\b(?:nearest|closest)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val WHERE_AM_I = Regex("""\bwhere am i\b|\bwhat (?:city|town|country) am i in\b""", RegexOption.IGNORE_CASE)

    /** True when the answer depends on where the reader is right now. */
    fun asksAbout(question: String): Boolean =
        PHRASE.containsMatchIn(question) || SEEKING.containsMatchIn(question) || WHERE_AM_I.containsMatchIn(question)

    /**
     * The question with the reader's city written into it, so that search and
     * the model treat it like any question that names a city.
     */
    fun inPlace(question: String, place: Place): String {
        val text = question.trim()
        // "Where am I?" names no place to swap in; the prompt's location line answers it.
        if (WHERE_AM_I.containsMatchIn(text)) return text
        val phrase = PHRASE.find(text)
        if (phrase != null) return text.replaceRange(phrase.range, "in ${place.city}")
        val end = text.trimEnd('?', '.', '!', ' ')
        return "$end in ${place.city}${text.substring(end.length)}"
    }

    /** The line that tells the model where the reader is. */
    fun describe(place: Place): String = "${place.city}, ${place.country}"
}
