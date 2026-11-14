package io.selfcheck.knowledge

/** Whether a matched condition means the value is low, high or inside a range. */
enum class Direction { LOW, HIGH, IN_RANGE }

/** A threshold condition from the rule files: `< x`, `<= x`, `> x`, `>= x` or an inclusive range `a..b`. */
data class Condition(val text: String, val direction: Direction, private val test: (Double) -> Boolean) {

    fun matches(value: Double): Boolean = test(value)

    companion object {
        private val comparison = Regex("""^(<=|>=|<|>)\s*(-?\d+(?:\.\d+)?)$""")
        private val range = Regex("""^(-?\d+(?:\.\d+)?)\s*\.\.\s*(-?\d+(?:\.\d+)?)$""")

        fun parse(raw: String): Condition {
            val text = raw.trim()
            comparison.matchEntire(text)?.let { match ->
                val (op, number) = match.destructured
                val limit = number.toDouble()
                return when (op) {
                    "<" -> Condition(text, Direction.LOW) { it < limit }
                    "<=" -> Condition(text, Direction.LOW) { it <= limit }
                    ">" -> Condition(text, Direction.HIGH) { it > limit }
                    else -> Condition(text, Direction.HIGH) { it >= limit }
                }
            }
            range.matchEntire(text)?.let { match ->
                val (low, high) = match.destructured
                val lo = low.toDouble()
                val hi = high.toDouble()
                require(lo <= hi) { "Invalid range '$raw'" }
                return Condition(text, Direction.IN_RANGE) { it in lo..hi }
            }
            throw IllegalArgumentException("Invalid condition '$raw'")
        }
    }
}
