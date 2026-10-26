package io.waves.feed

/** Lenient date and duration parsing for podcast feeds (no java.time, so it works in commonMain). */
object FeedDates {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // [Day[,]] DD Mon YYYY [HH:MM[:SS[.fff]]] [zone]   — RFC 822 / 1123 / 2822 and common sloppy variants.
    private val rfc822 = Regex(
        """^(?:[A-Za-z]+\.?,?\s*)?(\d{1,2})[\s\-]+([A-Za-z]+)\.?,?[\s\-]+(\d{2,4})(?:,?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?\s*(.*)$""",
    )

    // ISO 8601 / RFC 3339, which some feeds use despite the RSS spec.
    private val iso8601 = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:[.,]\d+)?)?\s*(Z|[+\-]\d{2}(?::?\d{2})?)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val zoneOffsetsMinutes = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0, "WET" to 0, "BST" to 60, "CET" to 60, "CEST" to 120,
        "EST" to -300, "EDT" to -240, "CST" to -360, "CDT" to -300,
        "MST" to -420, "MDT" to -360, "PST" to -480, "PDT" to -420,
        "AKST" to -540, "AKDT" to -480, "HST" to -600,
        "IST" to 330, "JST" to 540, "AEST" to 600, "AEDT" to 660,
    )

    /** Parses an RSS pubDate (RFC 822 variants, or ISO 8601) to epoch milliseconds; null when unparseable. */
    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        iso8601.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val offset = parseZone(g[7]) ?: return null
            return toEpochMs(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), offset)
        }
        rfc822.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val month = months.indexOf(g[2].take(3).lowercase()) + 1
            if (month == 0) return null
            var year = g[3].toInt()
            if (g[3].length <= 2) year += if (year < 50) 2000 else 1900
            val offset = parseZone(g[7]) ?: 0 // unknown zone names: assume UTC rather than dropping the date
            return toEpochMs(year, month, g[1].toInt(), g[4].toIntOr0(), g[5].toIntOr0(), g[6].toIntOr0(), offset)
        }
        return null
    }

    /** Offset in minutes; "" → 0, null when unrecognisable. */
    private fun parseZone(raw: String): Int? {
        var z = raw.trim().removeSurrounding("(", ")").trim()
        if (z.isEmpty()) return 0
        // "GMT+0100", "UTC-05:00"
        for (prefix in listOf("GMT", "UTC", "UT")) {
            if (z.length > prefix.length && z.startsWith(prefix, ignoreCase = true) && (z[prefix.length] == '+' || z[prefix.length] == '-')) {
                z = z.substring(prefix.length)
                break
            }
        }
        // Trailing junk like "+0000 (UTC)"
        z = z.substringBefore(' ')
        if (z.startsWith('+') || z.startsWith('-')) {
            val digits = z.substring(1).replace(":", "")
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            val (h, m) = when (digits.length) {
                1, 2 -> digits.toInt() to 0
                3 -> digits.take(1).toInt() to digits.drop(1).toInt()
                4 -> digits.take(2).toInt() to digits.drop(2).toInt()
                else -> return null
            }
            if (h > 14 || m > 59) return null
            return (h * 60 + m) * if (z[0] == '-') -1 else 1
        }
        return zoneOffsetsMinutes[z.uppercase()]
    }

    private fun toEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offsetMinutes: Int): Long? {
        if (month !in 1..12 || day !in 1..31 || hour !in 0..24 || minute !in 0..59 || second !in 0..60) return null
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Parses itunes:duration: "HH:MM:SS", "MM:SS", or plain (possibly fractional) seconds. */
    fun parseDuration(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split(':')
        if (parts.size > 3) return null
        val nums = parts.map { it.trim().toDoubleOrNull() ?: return null }
        if (nums.any { it < 0 || it.isNaN() || it.isInfinite() }) return null
        val total = nums.fold(0.0) { acc, v -> acc * 60 + v }
        return total.toLong()
    }

    private fun String.toIntOr0() = toIntOrNull() ?: 0
}
