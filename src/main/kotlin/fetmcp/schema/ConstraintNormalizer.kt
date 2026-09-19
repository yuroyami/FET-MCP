package fetmcp.schema

import fetmcp.model.Constraint
import fetmcp.model.FieldValue

/**
 * Brings a constraint read from any FET version to the shape FET 7.10.4 writes.
 * Old files use alias tags (`Teacher_Name` for `Teacher`, `Preferred_Starting_Day` for `Day`, ...) that FET's reader
 * still accepts. Fields the schema does not know are kept as they are.
 */
public object ConstraintNormalizer {
    public fun normalize(c: Constraint): Constraint {
        val type = ConstraintSchema.get(c.type) ?: return c
        val fields = LinkedHashMap(c.fields)
        // Pre-5 files wrote <Weight>, whose scale was different. FET drops it and uses 100, so we do the same.
        val weight = if (fields.remove("Weight") != null) 100.0 else c.weight
        return c.copy(weight = weight, fields = normalizeFields(fields, type.fields))
    }

    private fun normalizeFields(fields: Map<String, FieldValue>, specs: List<FieldSpec>): Map<String, FieldValue> {
        val byName = specs.associateBy { it.name }
        val out = LinkedHashMap<String, FieldValue>()
        for ((rawName, rawValue) in fields) {
            val name = if (rawName in byName) rawName else alias(rawName, byName.keys) ?: rawName
            val spec = byName[name]
            val value = when {
                spec == null -> rawValue
                spec.kind == FieldKind.ARRAY -> {
                    val items = (rawValue as? FieldValue.Items)?.items ?: listOf(rawValue)
                    val itemSpec = spec.item
                    FieldValue.Items(items.map { item ->
                        if (itemSpec != null && itemSpec.kind == FieldKind.OBJECT && item is FieldValue.Obj) FieldValue.Obj(normalizeFields(item.fields, itemSpec.fields)) else item
                    })
                }
                spec.kind == FieldKind.OBJECT && rawValue is FieldValue.Obj -> FieldValue.Obj(normalizeFields(rawValue.fields, spec.fields))
                else -> rawValue
            }
            out[name] = value
        }
        // A missing boolean is left missing on purpose. FET's default differs per field (Circular and
        // Consecutive_If_Same_Day default to true, others to false), so inventing one would change the meaning.
        // The writer skips absent scalars, so FET applies its own default when it reads the file back.
        return out
    }

    /** The canonical name for a legacy tag, if the schema expects the canonical one at this level. */
    private fun alias(legacy: String, expected: Set<String>): String? {
        val candidates = buildList {
            if (legacy.endsWith("_Name")) add(legacy.removeSuffix("_Name"))
            if (legacy == "Subject_Tag_Name" || legacy == "Subject_Tag") add("Activity_Tag")
            if (legacy.endsWith("_Day")) add("Day")
            if (legacy.endsWith("_Hour")) add("Hour")
            if (legacy == "Adjacent_If_Broken") add("Consecutive_If_Same_Day")
        }
        return candidates.firstOrNull { it in expected }
    }
}
