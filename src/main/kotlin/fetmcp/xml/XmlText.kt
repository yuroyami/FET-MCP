package fetmcp.xml

/** Text helpers that match FET's own `protect()` in `timetable_defs.cpp`. */
public object XmlText {
    /** Escapes the same five characters FET escapes, in the same order. */
    public fun protect(text: String): String = text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace(">", "&gt;")
        .replace("<", "&lt;")
        .replace("'", "&apos;")

    public fun trueFalse(flag: Boolean): String = if (flag) "true" else "false"

    /** FET writes whole numbers without a decimal part and keeps decimals otherwise. */
    public fun number(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

    /** Indentation levels IL1..IL5 from `timetable_defs.cpp`: 2, 4, 6, 8, 10 spaces. */
    public fun indent(level: Int): String = " ".repeat(2 * level)
}
