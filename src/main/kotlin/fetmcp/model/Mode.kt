package fetmcp.model

/** The four FET modes. `xml` is the exact text FET writes in the `<Mode>` tag. */
public enum class Mode(public val xml: String) {
    OFFICIAL("Official"),
    MORNINGS_AFTERNOONS("Mornings_Afternoons"),
    BLOCK_PLANNING("Block_Planning"),
    TERMS("Terms");

    public companion object {
        public fun fromXml(text: String): Mode? = entries.firstOrNull { it.xml == text }
    }
}

/** Teacher behaviour in Mornings-Afternoons mode. `xml` is the text FET writes in `<Mornings_Afternoons_Behavior>`. */
public enum class MaBehavior(public val xml: String, public val exceptionDays: Int) {
    UNRESTRICTED("Unrestricted", 0),
    EXCLUSIVE("Exclusive", 0),
    ONE_DAY_EXCEPTION("One day exception", 1),
    TWO_DAYS_EXCEPTION("Two days exception", 2),
    THREE_DAYS_EXCEPTION("Three days exception", 3),
    FOUR_DAYS_EXCEPTION("Four days exception", 4),
    FIVE_DAYS_EXCEPTION("Five days exception", 5);

    public companion object {
        public fun fromXml(text: String): MaBehavior? = entries.firstOrNull { it.xml == text }
    }
}
