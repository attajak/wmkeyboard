package com.wasimaster.wmkeyboard.core.tools

import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Amount formatting for the currency tool.
 *
 * Fiat is happy with a fixed two decimals; a coin is not — at two places
 * 0.0000154 BTC reads as "0.00". [significant] keeps a set number of
 * meaningful digits instead, so the same call handles 64,935 taka and a
 * fraction of a bitcoin.
 */
object MoneyFormat {

    /**
     * [value] to [sig] significant digits, grouped, with trailing zeros
     * dropped and never more than [maxDp] decimal places.
     *
     * The formatter is deliberately built without a locale, matching the
     * rest of the currency chip: on a Bengali device the digits and the
     * separators both follow the device, rather than one half of the chip
     * following it and the other half not.
     */
    fun significant(value: Double, sig: Int = 5, maxDp: Int = 10): String {
        if (!value.isFinite() || value == 0.0) return "0"
        val magnitude = floor(log10(abs(value))).toInt()
        val decimals = (sig - 1 - magnitude).coerceIn(0, maxDp)
        val pattern = if (decimals > 0) "#,##0." + "#".repeat(decimals) else "#,##0"
        val text = DecimalFormat(pattern).format(value)
        // Below the decimal cap even ten places round to nothing; a
        // vanishingly small holding is worth showing in exponent form
        // rather than as a flat zero.
        return if (text.any { it in '1'..'9' }) text else CalcEngine.format(value, 12)
    }

    /** Fixed [decimals] places, grouped — the fiat rendering. */
    fun fixed(value: Double, decimals: Int): String {
        val pattern = if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0"
        return DecimalFormat(pattern).format(value)
    }

    /**
     * How a converted amount is written when it lands in [code]: coins get
     * significant digits (or the user's own decimal count, when they set
     * one), everything else gets the fiat decimals.
     */
    fun amount(value: Double, isCrypto: Boolean, fiatDecimals: Int, cryptoDecimals: Int): String =
        when {
            !isCrypto -> fixed(value, fiatDecimals)
            cryptoDecimals > 0 -> fixed(value, cryptoDecimals)
            else -> significant(value)
        }
}

/**
 * How a converted fiat amount names its currency on the smart chip and in
 * the text the chip inserts: "96.04 Rupee", "₹96.04" or "96.04 INR". A
 * currency with no symbol of its own falls back to its code under [SYMBOL].
 * Coins keep their ticker whichever is picked.
 */
enum class CurrencyLabel { NAME, SYMBOL, CODE }
