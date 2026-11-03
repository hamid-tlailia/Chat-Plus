package app.zura.i18n

import android.content.Context
import app.zura.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Locale-correct times, day separators, durations and sizes.
 * Arabic gets native Arabic-Indic digits (٠١٢٣…) and "م/ص" day periods; English gets Latin.
 * All formatters are cached per locale — formatting is on the hot path of every bubble.
 */
/** "@username" wrapped in a left-to-right isolate so RTL layouts don't flip it to "username@". */
fun handle(username: String): String = "\u2066@$username\u2069"

class ZuraFormat(private val locale: Locale, private val zone: ZoneId = ZoneId.systemDefault()) {

    private val digits = DecimalStyle.of(locale)
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withDecimalStyle(digits)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale).withDecimalStyle(digits)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale).withDecimalStyle(digits)
    private val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withDecimalStyle(digits)
    private val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).withDecimalStyle(digits)

    fun time(epochMs: Long): String = time.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** "Today" / "Yesterday" / weekday within a week / "3 March" this year / full date. */
    fun daySeparator(context: Context, epochMs: Long): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            d == today -> context.getString(R.string.today)
            d == today.minusDays(1) -> context.getString(R.string.yesterday)
            d.isAfter(today.minusDays(7)) -> weekday.format(d)
            d.year == today.year -> dayMonth.format(d)
            else -> full.format(d)
        }
    }

    /** Chat-list timestamp: time today, weekday this week, short date otherwise. */
    fun listStamp(epochMs: Long): String {
        val z = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = LocalDate.now(zone)
        return when {
            z.toLocalDate() == today -> time.format(z)
            z.toLocalDate().isAfter(today.minusDays(7)) -> weekday.format(z)
            else -> shortDate.format(z)
        }
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return localizeDigits("%d:%02d".format(Locale.ROOT, s / 60, s % 60))
    }

    fun size(bytes: Long): String {
        val (v, u) = when {
            bytes >= 1L shl 30 -> bytes / (1L shl 30).toDouble() to if (isArabic) "ج.ب" else "GB"
            bytes >= 1L shl 20 -> bytes / (1L shl 20).toDouble() to if (isArabic) "م.ب" else "MB"
            else -> bytes / 1024.0 to if (isArabic) "ك.ب" else "KB"
        }
        return localizeDigits("%.1f".format(Locale.ROOT, v)) + " " + u
    }

    fun count(n: Int): String = localizeDigits(if (n > 999) "999+" else n.toString())

    val isArabic get() = locale.language == "ar"

    private fun localizeDigits(s: String): String {
        val zero = digits.zeroDigit
        if (zero == '0') return s
        return buildString(s.length) {
            s.forEach { c ->
                append(
                    when (c) {
                        in '0'..'9' -> zero + (c - '0')
                        '.' -> digits.decimalSeparator
                        else -> c
                    },
                )
            }
        }
    }
}
