package com.sabihon.todo.data.ai

import com.sabihon.todo.domain.model.Category
import com.sabihon.todo.domain.model.Priority
import com.sabihon.todo.domain.model.ai.AiContext
import com.sabihon.todo.domain.model.ai.DraftSource
import com.sabihon.todo.domain.model.ai.TaskDraft
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Tier 0 of the AI ladder: deterministic, on-device, zero-cost, offline.
 *
 * Extracts due date/time, priority and category from a natural-language sentence
 * and cleans the remaining text into a title. It never guesses: anything it is not
 * sure about is left `null` and reflected in a lower [TaskDraft.confidence], which is
 * what tells the repository whether a smarter (remote) tier is worth consulting.
 *
 * All time math uses the zone and "now" carried in [AiContext], so results are
 * reproducible in unit tests.
 *
 * @param monthFirstNumericDates how to read `10/30` – month/day (PH, US) or day/month.
 */
class RuleBasedTaskParser(
    private val monthFirstNumericDates: Boolean = true
) {

    fun parse(text: String, context: AiContext): TaskDraft {
        val original = text.trim().replace(WHITESPACE, " ")
        if (original.isEmpty()) return TaskDraft(title = "", confidence = 0f)

        val zone = try { ZoneId.of(context.zoneId) } catch (e: Exception) { ZoneId.systemDefault() }
        val now = Instant.ofEpochMilli(context.nowMillis).atZone(zone)
        val today = now.toLocalDate()
        var working = original

        // 1. Priority words ---------------------------------------------------------
        var priority: Priority? = null
        for ((regex, value) in PRIORITY_PATTERNS) {
            val match = regex.find(working) ?: continue
            priority = value
            working = working.replaceRange(match.range, " ")
            break
        }

        // 2. Time of day --------------------------------------------------------------
        val timeHit = findTime(working)
        val time: LocalTime? = timeHit?.time
        if (timeHit != null) {
            working = working.replaceRange(timeHit.range, " ")
        }

        // 3. Date ---------------------------------------------------------------------
        val dateHit = findDate(working, now)
        if (dateHit != null) {
            working = working.replaceRange(dateHit.range, " ")
        }

        // 4. Category -----------------------------------------------------------------
        val categoryHit = findCategory(working, context.categories)
        val categoryRange = categoryHit?.range
        if (categoryRange != null) {
            working = working.replaceRange(categoryRange, " ")
        }

        // 5. Resolve the due instant --------------------------------------------------
        val absolute = dateHit?.absolute
        val date = dateHit?.date
        val impliedTime = dateHit?.impliedTime
        val dueAt: Long? = when {
            absolute != null -> absolute.toInstant().toEpochMilli()
            date != null -> {
                val t = time ?: impliedTime ?: DEFAULT_TIME
                ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
            }
            time != null -> {
                // A bare time means "today", or tomorrow if that time has already passed.
                val candidate = ZonedDateTime.of(today, time, zone)
                (if (candidate.isBefore(now)) candidate.plusDays(1) else candidate).toInstant().toEpochMilli()
            }
            else -> null
        }

        // 6. Title clean-up -----------------------------------------------------------
        val title = cleanTitle(working).ifBlank { original }

        // 7. Explain + score ----------------------------------------------------------
        val explanations = mutableListOf<String>()
        if (dueAt != null) {
            val due = Instant.ofEpochMilli(dueAt).atZone(zone)
            val dayLabel = when (due.toLocalDate()) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> due.format(DAY_FORMAT)
            }
            explanations += "Due: $dayLabel ${due.format(TIME_FORMAT)}"
        }
        if (priority != null) {
            explanations += "Priority: " + priority.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
        }
        if (categoryHit != null) {
            explanations += "Category: ${categoryHit.category.name}"
        }

        val confidence = score(
            title = title,
            dateFound = dueAt != null && (date != null || absolute != null),
            timeFound = time != null || absolute != null,
            priorityFound = priority != null,
            categoryHit = categoryHit
        )

        return TaskDraft(
            title = title,
            categoryId = categoryHit?.category?.id,
            priority = priority,
            dueAt = dueAt,
            confidence = confidence,
            source = DraftSource.RULES,
            explanations = explanations
        )
    }

    // ---------------------------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------------------------

    private class TimeHit(val time: LocalTime, val range: IntRange)

    private fun findTime(s: String): TimeHit? {
        TIME_12H.find(s)?.let { m ->
            val hour12 = m.groupValues[1].toInt() % 12
            val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            val isPm = m.groupValues[3].equals("p", ignoreCase = true)
            return TimeHit(LocalTime.of(if (isPm) hour12 + 12 else hour12, minute), m.range)
        }
        TIME_24H.find(s)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            // "3:30" in a to-do list almost always means the afternoon.
            if (hour in 1..6) hour += 12
            return TimeHit(LocalTime.of(hour, minute), m.range)
        }
        TIME_BARE_AT.find(s)?.let { m ->
            val raw = m.groupValues[1].toInt()
            val hour = when {
                raw == 12 -> 12
                raw in 1..6 -> raw + 12
                else -> raw
            }
            return TimeHit(LocalTime.of(hour, 0), m.range)
        }
        for ((regex, value) in TIME_WORDS) {
            val m = regex.find(s) ?: continue
            return TimeHit(value, m.range)
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // Date
    // ---------------------------------------------------------------------------------

    private class DateHit(
        val range: IntRange,
        val date: LocalDate? = null,
        val impliedTime: LocalTime? = null,
        val absolute: ZonedDateTime? = null
    )

    private fun findDate(s: String, now: ZonedDateTime): DateHit? {
        val today = now.toLocalDate()

        DAY_AFTER_TOMORROW.find(s)?.let { return DateHit(it.range, date = today.plusDays(2)) }
        TOMORROW.find(s)?.let { return DateHit(it.range, date = today.plusDays(1)) }
        TONIGHT.find(s)?.let { return DateHit(it.range, date = today, impliedTime = LocalTime.of(20, 0)) }
        TODAY.find(s)?.let { return DateHit(it.range, date = today) }

        IN_RELATIVE.find(s)?.let { m ->
            val amount = wordToInt(m.groupValues[1]) ?: return@let
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            return when {
                unit.startsWith("mo") -> DateHit(m.range, date = today.plusMonths(amount.toLong()))
                unit.startsWith("mi") -> DateHit(m.range, absolute = now.plusMinutes(amount.toLong()))
                unit.startsWith("h") -> DateHit(m.range, absolute = now.plusHours(amount.toLong()))
                unit.startsWith("w") -> DateHit(m.range, date = today.plusWeeks(amount.toLong()))
                else -> DateHit(m.range, date = today.plusDays(amount.toLong()))
            }
        }

        NEXT_WEEK.find(s)?.let { return DateHit(it.range, date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))) }
        NEXT_MONTH.find(s)?.let { return DateHit(it.range, date = today.with(TemporalAdjusters.firstDayOfNextMonth())) }
        WEEKEND.find(s)?.let { m ->
            val saturday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            val date = if (m.groupValues[1].isNotEmpty()) saturday.plusWeeks(1) else saturday
            return DateHit(m.range, date = date, impliedTime = LocalTime.of(10, 0))
        }
        END_OF.find(s)?.let { m ->
            val unit = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase(Locale.ROOT)
            val eob = LocalTime.of(17, 0)
            return when {
                unit == "week" || unit == "eow" ->
                    DateHit(m.range, date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)), impliedTime = eob)
                unit == "month" || unit == "eom" ->
                    DateHit(m.range, date = today.with(TemporalAdjusters.lastDayOfMonth()), impliedTime = eob)
                else -> DateHit(m.range, date = today, impliedTime = eob)
            }
        }

        WEEKDAY.find(s)?.let { m ->
            val dow = parseDayOfWeek(m.groupValues[2]) ?: return@let
            val isNext = m.groupValues[1].isNotEmpty()
            val date = if (isNext) {
                // "next Friday" = the Friday of next week.
                today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(dow))
            } else {
                today.with(TemporalAdjusters.nextOrSame(dow))
            }
            return DateHit(m.range, date = date)
        }

        MONTH_DAY.find(s)?.let { m ->
            val date = buildDate(
                month = parseMonth(m.groupValues[1]),
                day = m.groupValues[2].toIntOrNull(),
                year = m.groupValues[3].toIntOrNull(),
                today = today
            ) ?: return@let
            return DateHit(m.range, date = date)
        }
        DAY_MONTH.find(s)?.let { m ->
            val date = buildDate(
                month = parseMonth(m.groupValues[2]),
                day = m.groupValues[1].toIntOrNull(),
                year = m.groupValues[3].toIntOrNull(),
                today = today
            ) ?: return@let
            return DateHit(m.range, date = date)
        }
        NUMERIC_DATE.find(s)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val separator = m.groupValues[2]
            val b = m.groupValues[3].toIntOrNull() ?: return@let
            val year = m.groupValues[4].toIntOrNull()
            // "10-15" without a year is more likely a range ("pages 10-15") than a date.
            if (separator == "-" && year == null) return@let
            var month = if (monthFirstNumericDates) a else b
            var day = if (monthFirstNumericDates) b else a
            if (month > 12 && day <= 12) { val t = month; month = day; day = t }
            val monthEnum = if (month in 1..12) Month.of(month) else return@let
            val date = buildDate(monthEnum, day, year, today) ?: return@let
            return DateHit(m.range, date = date)
        }
        return null
    }

    private fun buildDate(month: Month?, day: Int?, year: Int?, today: LocalDate): LocalDate? {
        if (month == null || day == null) return null
        val fullYear = when {
            year == null -> today.year
            year < 100 -> 2000 + year
            else -> year
        }
        val candidate = try { LocalDate.of(fullYear, month, day) } catch (e: Exception) { return null }
        // "Oct 30" said in November means next year.
        return if (year == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
    }

    private fun parseMonth(raw: String): Month? = when (raw.lowercase(Locale.ROOT).take(3)) {
        "jan" -> Month.JANUARY; "feb" -> Month.FEBRUARY; "mar" -> Month.MARCH
        "apr" -> Month.APRIL; "may" -> Month.MAY; "jun" -> Month.JUNE
        "jul" -> Month.JULY; "aug" -> Month.AUGUST; "sep" -> Month.SEPTEMBER
        "oct" -> Month.OCTOBER; "nov" -> Month.NOVEMBER; "dec" -> Month.DECEMBER
        else -> null
    }

    private fun parseDayOfWeek(raw: String): DayOfWeek? = when (raw.lowercase(Locale.ROOT).take(3)) {
        "mon" -> DayOfWeek.MONDAY; "tue" -> DayOfWeek.TUESDAY; "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY; "fri" -> DayOfWeek.FRIDAY; "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun wordToInt(raw: String): Int? = when (raw.lowercase(Locale.ROOT)) {
        "a", "an", "one" -> 1
        "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5
        "six" -> 6; "seven" -> 7; "eight" -> 8; "nine" -> 9; "ten" -> 10
        else -> raw.toIntOrNull()
    }

    // ---------------------------------------------------------------------------------
    // Category
    // ---------------------------------------------------------------------------------

    private class CategoryHit(val category: Category, val explicit: Boolean, val range: IntRange?)

    private fun findCategory(s: String, categories: List<Category>): CategoryHit? {
        if (categories.isEmpty()) return null

        // "#work" / "#home" – explicit, removed from the title.
        HASHTAG.find(s)?.let { m ->
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            val cat = categories.firstOrNull { normalize(it.name) == tag }
                ?: categories.firstOrNull { firstWord(it.name) == tag }
                ?: categories.firstOrNull { tag.length >= 3 && normalize(it.name).startsWith(tag) }
            if (cat != null) return CategoryHit(cat, explicit = true, range = m.range)
        }

        // "… for work related" – explicit; removed only when introduced by a preposition.
        for (cat in categories) {
            val name = cat.name.trim()
            if (name.length < 3) continue
            val regex = Regex("""(?i)(?:(for|in|under|to)\s+)?(?<!\w)${Regex.escape(name)}(?!\w)""")
            val m = regex.find(s) ?: continue
            val hasPreposition = m.groupValues[1].isNotEmpty()
            return CategoryHit(cat, explicit = true, range = if (hasPreposition) m.range else null)
        }

        // Keyword synonyms – a guess; never removed from the title.
        val lower = s.lowercase(Locale.ROOT)
        var best: Category? = null
        var bestScore = 0
        for (cat in categories) {
            val kind = kindOf(cat.name) ?: continue
            val synonyms = KIND_SYNONYM_REGEX[kind] ?: continue
            val score = synonyms.count { it.containsMatchIn(lower) }
            if (score > bestScore) { bestScore = score; best = cat }
        }
        return best?.let { CategoryHit(it, explicit = false, range = null) }
    }

    private fun kindOf(categoryName: String): String? {
        val n = categoryName.lowercase(Locale.ROOT)
        for ((kind, hints) in KIND_NAME_HINTS) {
            if (hints.any { n.contains(it) }) return kind
        }
        return null
    }

    private fun normalize(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun firstWord(name: String): String =
        name.trim().lowercase(Locale.ROOT).split(" ").firstOrNull().orEmpty().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------------------------
    // Title & confidence
    // ---------------------------------------------------------------------------------

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(WHITESPACE, " ").trim()
        t = t.replace(LEADING_FILLER, "")
        t = t.replace(SPACE_BEFORE_PUNCT, "$1")
        repeat(3) {
            t = t.replace(TRAILING_DANGLE, "")
            t = t.replace(LEADING_DANGLE, "")
            t = t.replace(EDGE_PUNCT, "")
        }
        t = t.replace(WHITESPACE, " ").trim()
        if (t.isEmpty()) return t
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    private fun score(
        title: String,
        dateFound: Boolean,
        timeFound: Boolean,
        priorityFound: Boolean,
        categoryHit: CategoryHit?
    ): Float {
        var c = 0.35f
        if (dateFound) c += 0.30f
        if (timeFound) c += 0.10f
        if (priorityFound) c += 0.10f
        if (categoryHit != null) c += if (categoryHit.explicit) 0.10f else 0.05f

        val words = title.split(" ").count { it.isNotBlank() }
        if (words in 1..6) c += 0.10f
        if (words > 12) c -= 0.15f
        // Left-over temporal words or digits mean we probably missed something a model would catch.
        if (AMBIGUITY_HINTS.containsMatchIn(title)) c -= 0.20f
        return c.coerceIn(0f, 1f)
    }

    companion object {
        private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

        private val WHITESPACE = Regex("""\s+""")
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        private val PRIORITY_PATTERNS: List<Pair<Regex, Priority>> = listOf(
            Regex("""(?i)\b(?:urgent(?:ly)?|asap|right away|immediately|top priority|p0)\b|!{2,}""") to Priority.URGENT,
            Regex("""(?i)\b(?:high priority|high prio|very important|important|critical|must do|p1)\b""") to Priority.HIGH,
            Regex("""(?i)\b(?:low priority|low prio|no rush|whenever|someday|optional|if time permits|p3)\b""") to Priority.LOW,
            Regex("""(?i)\b(?:medium priority|normal priority|p2)\b""") to Priority.MEDIUM
        )

        private val TIME_12H = Regex("""(?i)(?<![\w:])(?:at\s+|@\s*)?(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*([ap])\.?m\b""")
        private val TIME_24H = Regex("""(?<![\w:/])(?:at\s+|@\s*)?([01]?\d|2[0-3]):([0-5]\d)(?![\w:])""")
        private val TIME_BARE_AT = Regex("""(?i)\bat\s+(1[0-2]|0?[1-9])(?![\d:/\-]|\s*[ap]\.?m)""")
        private val TIME_WORDS: List<Pair<Regex, LocalTime>> = listOf(
            Regex("""(?i)\b(?:at\s+)?(?:noon|midday|lunch(?:time)?)\b""") to LocalTime.of(12, 0),
            Regex("""(?i)\b(?:in\s+the\s+|this\s+|early\s+)?morning\b""") to LocalTime.of(9, 0),
            Regex("""(?i)\b(?:in\s+the\s+|this\s+)?afternoon\b""") to LocalTime.of(15, 0),
            Regex("""(?i)\b(?:in\s+the\s+|this\s+)?evening\b""") to LocalTime.of(18, 0),
            Regex("""(?i)\bafter\s+(?:work|office|school|class)\b""") to LocalTime.of(18, 0),
            Regex("""(?i)\bbefore\s+(?:work|office|school|class)\b""") to LocalTime.of(7, 30),
            Regex("""(?i)\b(?:at\s+)?midnight\b""") to LocalTime.of(23, 59)
        )

        private const val PREP = """(?:(?:by|on|for|due|until|till|before|this|coming)\s+)?"""
        private val DAY_AFTER_TOMORROW = Regex("""(?i)\b${PREP}(?:the\s+)?day after tomorrow\b""")
        private val TOMORROW = Regex("""(?i)\b${PREP}(?:tomorrow|tmrw|tmr)\b""")
        private val TONIGHT = Regex("""(?i)\b${PREP}tonight\b""")
        private val TODAY = Regex("""(?i)\b${PREP}today\b""")
        private val IN_RELATIVE = Regex(
            """(?i)\bin\s+(\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\s+(minutes?|mins?|hours?|hrs?|days?|weeks?|wks?|months?)\b"""
        )
        private val NEXT_WEEK = Regex("""(?i)\b(?:next|coming)\s+week\b""")
        private val NEXT_MONTH = Regex("""(?i)\bnext\s+month\b""")
        private val WEEKEND = Regex("""(?i)\b(?:(?:this|on|for|over)\s+)?(?:the\s+)?(?:(next)\s+)?weekend\b""")
        private val END_OF = Regex("""(?i)\b(?:(?:by|before)\s+)?(?:the\s+)?end of (?:the\s+)?(day|week|month)\b|\b(eod|eow|eom)\b""")
        private val WEEKDAY = Regex(
            """(?i)\b${PREP}(next\s+)?(monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\b"""
        )
        private const val MONTHS =
            """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
        private val MONTH_DAY = Regex("""(?i)\b${PREP}${MONTHS}\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""")
        private val DAY_MONTH = Regex("""(?i)\b${PREP}(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?${MONTHS}\.?(?:,?\s+(\d{4}))?\b""")
        private val NUMERIC_DATE = Regex("""(?i)\b${PREP}(\d{1,2})([/-])(\d{1,2})(?:\2(\d{2,4}))?\b""")

        private val HASHTAG = Regex("""(?<!\w)#([A-Za-z][A-Za-z0-9_]*)""")

        private val LEADING_FILLER = Regex(
            """(?i)^(?:please\s+)?(?:remind me to|reminder to|remind me|remember to|don'?t forget to|i need to|i have to|i should|need to|have to|todo:?|task:?|note to self:?)\s+"""
        )
        private val TRAILING_DANGLE = Regex("""(?i)\s+(?:at|on|by|in|for|due|until|till|before|the|to|from|and|a|an|is|with)\s*$""")
        private val LEADING_DANGLE = Regex("""(?i)^(?:and|then|also)\s+""")
        private val EDGE_PUNCT = Regex("""^[\s,.;:\-–—]+|[\s,.;:\-–—]+$""")
        private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.;:!?])""")
        private val AMBIGUITY_HINTS = Regex("""(?i)\b(?:next|every|after|before|between|until|around|later|soon|weekly|daily|monthly)\b|\d""")

        /** Which built-in "kind" a user category belongs to, judged from its name. */
        private val KIND_NAME_HINTS: Map<String, List<String>> = mapOf(
            "grocery" to listOf("grocer", "shopping", "market"),
            "work" to listOf("work", "job", "office", "business"),
            "home" to listOf("home", "house", "chore"),
            "study" to listOf("educ", "study", "school", "learn", "college", "class"),
            "health" to listOf("health", "fitness", "wellness", "gym"),
            "finance" to listOf("financ", "money", "bill", "budget"),
            "personal" to listOf("personal", "family", "life", "note")
        )

        private val KIND_SYNONYMS: Map<String, List<String>> = mapOf(
            "grocery" to listOf("grocery", "groceries", "buy", "milk", "eggs", "bread", "rice", "vegetables", "fruit", "fruits", "supermarket", "shopping list"),
            "work" to listOf("meeting", "client", "deadline", "report", "boss", "presentation", "standup", "invoice", "email", "slides", "sprint", "pull request", "deploy", "shift"),
            "home" to listOf("clean", "laundry", "dishes", "fix", "repair", "garden", "rent", "plumber", "electrician", "trash", "garbage", "vacuum"),
            "study" to listOf("study", "exam", "homework", "assignment", "lecture", "quiz", "thesis", "chapter", "tutorial", "enroll", "review notes"),
            "health" to listOf("gym", "workout", "run", "jog", "doctor", "dentist", "checkup", "check-up", "medicine", "meds", "vitamins", "yoga", "therapy"),
            "finance" to listOf("pay", "bank", "budget", "tax", "taxes", "loan", "credit card", "gcash", "salary", "transfer", "bills", "bill", "insurance"),
            "personal" to listOf("birthday", "haircut", "friend", "mom", "dad", "date night", "anniversary", "gift", "journal")
        )

        private val KIND_SYNONYM_REGEX: Map<String, List<Regex>> = KIND_SYNONYMS.mapValues { (_, words) ->
            words.map { Regex("""(?<!\w)${Regex.escape(it)}(?!\w)""") }
        }
    }
}
