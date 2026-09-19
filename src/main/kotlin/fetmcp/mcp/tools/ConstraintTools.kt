package fetmcp.mcp

import fetmcp.model.Constraint
import fetmcp.model.Family
import fetmcp.model.FetDocument
import fetmcp.model.FieldValue
import fetmcp.model.Mode
import fetmcp.schema.ConstraintNormalizer
import fetmcp.schema.ConstraintSchema
import fetmcp.schema.ConstraintType
import fetmcp.schema.FieldKind
import fetmcp.schema.FieldSpec
import fetmcp.schema.RefKind
import fetmcp.schema.ValueType
import fetmcp.validate.Severity
import fetmcp.validate.Validator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The generic constraint surface: discover a type, add, update, list and remove. */
public object ConstraintTools {
    public fun register(tools: ToolRegistry) {
        tools.tool(
            name = "fet_constraint_types",
            description = "Find constraint types by words and see exactly which fields each one takes. FET has 344 types, so search first, then call fet_add_constraints with the type name and its fields. " +
                "Count fields such as Number_of_Activities are written by the server, never passed in.",
            inputSchema = schema {
                str("search", "Words to look for in the type name and its description, for example 'teacher max gaps'")
                str("family", "Limit to time or space constraints", enum = listOf("time", "space"))
                str("mode", "Limit to types allowed in this mode. Defaults to the open document's mode.", enum = Mode.entries.map { it.xml })
                bool("include_schema", "Include the full field list for each type (default true when few matches)")
                int("limit", "Return at most this many types (default 25)")
            },
        ) { args ->
            val mode = args.strOrNull("mode")?.let { Mode.fromXml(it) } ?: args.session.doc.mode
            val family = args.strOrNull("family")?.let { if (it.equals("space", true)) Family.SPACE else Family.TIME }
            val hits = ConstraintSchema.search(args.str("search", ""), mode, family).sortedBy { it.name }
            val limit = args.int("limit", 25)
            val withSchema = args.boolOrNull("include_schema") ?: (hits.size <= limit)
            ToolResult(
                jsonOf(
                    "mode" to mode.xml,
                    "count" to hits.size,
                    "truncated" to (hits.size > limit),
                    "items" to hits.take(limit).map { typeJson(it, withSchema) },
                ),
            )
        }

        tools.tool(
            name = "fet_add_constraints",
            description = "Add constraints. Look the type and its fields up with fet_constraint_types first. " +
                "Weight 100 means the rule can never be broken; lower weights are wishes. Every name you use must already exist.",
            mutating = true,
            inputSchema = schema {
                arr("constraints", "The constraints to add", itemsObj {
                    str("type", "Exact FET type name, for example ConstraintTeacherMaxDaysPerWeek", required = true)
                    any("fields", "The type's fields, as named by fet_constraint_types. Lists are arrays; slots are objects like {Day, Hour}.")
                    num("weight", "0 to 100 (default 100)")
                    bool("active", "Include this constraint in generation (default true)")
                    str("comments", "Notes")
                }, required = true)
            },
        ) { args ->
            val items = args.objects("constraints")
            val refs = args.session.mutate("add ${items.size} constraint(s)") { d ->
                items.map { item ->
                    val constraint = build(d, item)
                    if (d.constraint(constraint.ref) != null) {
                        throw ToolFailure("DUPLICATE_CONSTRAINT", "This exact ${constraint.type} already exists as ${constraint.ref}", mapOf("type" to constraint.type, "constraint" to constraint.ref))
                    }
                    d.constraintsOf(constraint.family) += constraint
                    constraint.ref
                }
            }
            ToolResult(jsonOf("refs" to stringsOf(refs), "added" to refs.size))
        }

        tools.tool(
            name = "fet_update_constraint",
            description = "Change one constraint. The ref changes with the content, so use the ref this returns from now on.",
            mutating = true,
            inputSchema = schema {
                str("ref", "The constraint ref, as returned by fet_add_constraints or fet_list_constraints", required = true)
                any("fields", "Fields to replace. Fields you leave out keep their value.")
                num("weight", "New weight, 0 to 100")
                bool("active", "Include this constraint in generation")
                str("comments", "New notes")
            },
        ) { args ->
            val ref = args.str("ref")
            val newRef = args.session.mutate("update constraint $ref") { d ->
                val old = d.constraint(ref) ?: throw ToolFailure("NOT_FOUND", "There is no constraint with ref $ref", mapOf("constraint" to ref))
                val type = ConstraintSchema.get(old.type)
                    ?: throw ToolFailure("UNKNOWN_CONSTRAINT_TYPE", "FET ${ConstraintSchema.fetVersion} does not know the type ${old.type}", mapOf("type" to old.type))
                val merged = if ("fields" in args) old.fields + readFields(d, type, args.rawElement("fields")) else old.fields
                val updated = old.copy(
                    weight = args.num("weight", old.weight),
                    active = args.boolOrNull("active") ?: old.active,
                    comments = args.str("comments", old.comments),
                    fields = merged,
                )
                validate(d, updated, type)
                val list = d.constraintsOf(old.family)
                val index = list.indexOfFirst { it.ref == ref }
                list[index] = updated
                updated.ref
            }
            ToolResult(jsonOf("ref" to newRef, "previous_ref" to ref, "constraint" to args.session.doc.constraint(newRef)?.toJson()))
        }

        tools.tool(
            name = "fet_remove_constraints",
            description = "Remove constraints by ref, or every constraint matching a filter.",
            mutating = true,
            inputSchema = schema {
                arr("refs", "Constraint refs to remove", itemsStr())
                str("type", "Instead of refs: remove every constraint of this type")
                str("teacher", "Instead of refs: remove every constraint naming this teacher")
                str("students", "Instead of refs: remove every constraint naming this students set")
                str("room", "Instead of refs: remove every constraint naming this room")
            },
        ) { args ->
            val doc = args.session.doc
            val refs = LinkedHashSet(args.stringsOrEmpty("refs"))
            refs += filtered(doc, args).map { it.ref }
            if (refs.isEmpty()) throw ToolFailure("INVALID_ARGUMENT", "Nothing selected. Pass refs, or a type, teacher, students or room filter.", mapOf("argument" to "refs"))
            for (ref in refs) if (doc.constraint(ref) == null) throw ToolFailure("NOT_FOUND", "There is no constraint with ref $ref", mapOf("constraint" to ref))
            val removed = args.session.mutate("remove ${refs.size} constraint(s)") { d ->
                val protectedRefs = d.constraints().filter { it.type == "ConstraintBasicCompulsoryTime" || it.type == "ConstraintBasicCompulsorySpace" }.map { it.ref }.toSet()
                val hit = refs.intersect(protectedRefs)
                if (hit.isNotEmpty()) throw ToolFailure("BASIC_CONSTRAINT_MISSING", "The basic compulsory constraints cannot be removed. FET needs them.", mapOf("constraints" to hit.joinToString()))
                d.timeConstraints.removeAll { it.ref in refs }
                d.spaceConstraints.removeAll { it.ref in refs }
                refs.toList()
            }
            ToolResult(jsonOf("removed" to stringsOf(removed)))
        }

        tools.tool(
            name = "fet_list_constraints",
            description = "List constraints with a readable one line description each. Filter by type, family, weight or the names they mention.",
            inputSchema = schema {
                str("type", "Only this exact type")
                str("type_contains", "Only types whose name contains this text")
                str("family", "time or space", enum = listOf("time", "space"))
                str("teacher", "Only constraints naming this teacher")
                str("students", "Only constraints naming this students set")
                str("subject", "Only constraints naming this subject")
                str("room", "Only constraints naming this room")
                str("activity_tag", "Only constraints naming this activity tag")
                int("activity_id", "Only constraints naming this activity")
                bool("active_only", "Skip deactivated constraints")
                num("max_weight", "Only constraints at or below this weight")
                bool("include_fields", "Include every field value (default true)")
                int("limit", "Return at most this many (default 100)")
            },
        ) { args ->
            val doc = args.session.doc
            val limit = args.int("limit", 100)
            val includeFields = args.bool("include_fields", true)
            val hits = filtered(doc, args, all = true)
            ToolResult(
                jsonOf(
                    "count" to hits.size,
                    "truncated" to (hits.size > limit),
                    "items" to hits.take(limit).map { c ->
                        buildJsonObject {
                            for ((k, v) in c.toJson(includeFields)) put(k, v)
                            put("description", describe(c))
                        }
                    },
                ),
            )
        }
    }

    // ---- building and validating ----

    internal fun build(doc: FetDocument, item: Args): Constraint {
        val typeName = item.str("type")
        val type = ConstraintSchema.get(typeName)
            ?: throw ToolFailure(
                "UNKNOWN_CONSTRAINT_TYPE",
                "FET ${ConstraintSchema.fetVersion} has no constraint type called '$typeName'. Search for the right name with fet_constraint_types.",
                mapOf("type" to typeName),
            )
        val constraint = Constraint(
            family = type.family,
            type = typeName,
            weight = item.num("weight", 100.0),
            active = item.bool("active", true),
            comments = item.str("comments", ""),
            fields = readFields(doc, type, item.rawElement("fields")),
        )
        validate(doc, constraint, type)
        return ConstraintNormalizer.normalize(constraint)
    }

    /** Turns the tool's JSON into field values, refusing anything the schema does not declare. */
    private fun readFields(doc: FetDocument, type: ConstraintType, element: kotlinx.serialization.json.JsonElement?): Map<String, FieldValue> {
        val raw = when (element) {
            null -> return emptyMap()
            is JsonObject -> element
            else -> throw ToolFailure("SCHEMA_MISMATCH", "fields must be an object", mapOf("type" to type.name))
        }
        val known = type.fields.associateBy { it.name }
        val counts = type.fields.mapNotNull { it.countTag }.toSet()
        val out = LinkedHashMap<String, FieldValue>()
        for ((name, value) in raw) {
            if (name in counts) {
                throw ToolFailure("SCHEMA_MISMATCH", "Do not pass '$name'. The server counts the entries and writes it.", mapOf("type" to type.name, "field" to name))
            }
            val spec = known[name] ?: throw ToolFailure(
                "SCHEMA_MISMATCH",
                "${type.name} has no field '$name'. Its fields are: ${type.fields.joinToString { it.name }}.",
                mapOf("type" to type.name, "field" to name),
            )
            out[name] = read(type, spec, value)
        }
        return out
    }

    private fun read(type: ConstraintType, spec: FieldSpec, value: kotlinx.serialization.json.JsonElement): FieldValue = when (spec.kind) {
        FieldKind.SCALAR -> value.toFieldValue().also {
            if (it !is FieldValue.Scalar) throw ToolFailure("SCHEMA_MISMATCH", "Field '${spec.name}' of ${type.name} takes a single value", mapOf("type" to type.name, "field" to spec.name))
        }
        FieldKind.OBJECT -> {
            val obj = value as? JsonObject ?: throw ToolFailure("SCHEMA_MISMATCH", "Field '${spec.name}' of ${type.name} takes an object with ${spec.fields.joinToString { it.name }}", mapOf("type" to type.name, "field" to spec.name))
            FieldValue.Obj(LinkedHashMap<String, FieldValue>().also { out ->
                for ((k, v) in obj) {
                    val child = spec.fields.firstOrNull { it.name == k }
                        ?: throw ToolFailure("SCHEMA_MISMATCH", "'${spec.name}' of ${type.name} has no part called '$k'. It takes ${spec.fields.joinToString { it.name }}.", mapOf("type" to type.name, "field" to k))
                    out[k] = read(type, child, v)
                }
            })
        }
        FieldKind.ARRAY -> {
            val array = value as? JsonArray ?: throw ToolFailure("SCHEMA_MISMATCH", "Field '${spec.name}' of ${type.name} takes a list", mapOf("type" to type.name, "field" to spec.name))
            val item = spec.item ?: throw ToolFailure("SCHEMA_MISMATCH", "Field '${spec.name}' of ${type.name} cannot be set", mapOf("type" to type.name, "field" to spec.name))
            FieldValue.Items(array.map { read(type, item, it) })
        }
    }

    /** Runs the shared validator on the constraint alone, so the tool answers before anything is written. */
    private fun validate(doc: FetDocument, constraint: Constraint, type: ConstraintType) {
        if (doc.mode !in type.modes) {
            throw ToolFailure(
                "MODE_FORBIDS",
                "${type.name} is not allowed in mode ${doc.mode.xml}. It works in: ${type.modes.joinToString { it.xml }}.",
                mapOf("type" to type.name, "mode" to doc.mode.xml),
            )
        }
        if (constraint.weight < 0.0 || constraint.weight > 100.0) {
            throw ToolFailure("INVALID_WEIGHT", "Weight must be between 0 and 100, got ${constraint.weight}", mapOf("type" to type.name))
        }
        val probe = doc.copy()
        probe.timeConstraints.clear()
        probe.spaceConstraints.clear()
        probe.constraintsOf(constraint.family) += constraint
        val issues = Validator.validate(probe).filter { it.severity == Severity.ERROR && it.where["constraint"] == constraint.ref }
        val issue = issues.firstOrNull() ?: return
        throw ToolFailure(issue.code, issue.message.replace(constraint.ref, type.name), issue.where + ("type" to type.name))
    }

    // ---- reading ----

    private fun typeJson(type: ConstraintType, withSchema: Boolean): JsonObject = buildJsonObject {
        put("name", type.name)
        put("family", type.family.name.lowercase())
        put("description", type.description)
        put("modes", stringsOf(type.modes.map { it.xml }))
        if (withSchema) put("fields", buildJsonArray { type.fields.forEach { add(fieldJson(it)) } })
    }

    private fun fieldJson(spec: FieldSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("kind", spec.kind.name.lowercase())
        spec.value?.let { put("value_type", it.name.lowercase()) }
        spec.ref?.let { put("refers_to", it.name.lowercase()) }
        if (spec.optional) put("optional", true)
        spec.countTag?.let { put("count_written_by_server", it) }
        spec.item?.let { put("item", fieldJson(it)) }
        if (spec.fields.isNotEmpty()) put("parts", buildJsonArray { spec.fields.forEach { add(fieldJson(it)) } })
    }

    /** A readable line: the FET description of the type plus its main field values. */
    internal fun describe(c: Constraint): String {
        val type = ConstraintSchema.get(c.type)
        val head = type?.description ?: c.type
        val parts = ArrayList<String>()
        if (c.weight != 100.0) parts += "weight ${fetmcp.xml.XmlText.number(c.weight)}%"
        type?.fields?.forEach { spec ->
            val value = c.fields[spec.name] ?: return@forEach
            when (value) {
                is FieldValue.Scalar -> if (value.text.isNotEmpty()) parts += "${label(spec.name)} ${value.text}"
                is FieldValue.Items -> if (value.items.isNotEmpty()) parts += "${value.items.size} ${label(spec.name).lowercase()}"
                is FieldValue.Obj -> Unit
            }
        }
        if (!c.active) parts += "inactive"
        return if (parts.isEmpty()) head else "$head: ${parts.joinToString(", ")}"
    }

    private fun label(field: String): String = field.replace('_', ' ')

    private fun filtered(doc: FetDocument, args: Args, all: Boolean = false): List<Constraint> {
        val type = args.strOrNull("type")
        val contains = args.strOrNull("type_contains")
        val family = args.strOrNull("family")?.let { if (it.equals("space", true)) Family.SPACE else Family.TIME }
        val names = listOfNotNull(
            args.strOrNull("teacher")?.let { RefKind.TEACHER to it },
            args.strOrNull("students")?.let { RefKind.STUDENTS to it },
            args.strOrNull("subject")?.let { RefKind.SUBJECT to it },
            args.strOrNull("room")?.let { RefKind.ROOM to it },
            args.strOrNull("activity_tag")?.let { RefKind.ACTIVITY_TAG to it },
            args.intOrNull("activity_id")?.let { RefKind.ACTIVITY to it.toString() },
        )
        val maxWeight = args.numOrNull("max_weight")
        val activeOnly = args.bool("active_only", false)
        if (!all && type == null && contains == null && names.isEmpty()) return emptyList()
        return doc.constraints().filter { c ->
            (type == null || c.type == type) &&
                (contains == null || c.type.contains(contains, ignoreCase = true)) &&
                (family == null || c.family == family) &&
                (maxWeight == null || c.weight <= maxWeight) &&
                (!activeOnly || c.active) &&
                names.all { (kind, value) -> mentions(c, kind, value) }
        }.toList()
    }

    private fun mentions(c: Constraint, kind: RefKind, name: String): Boolean {
        val type = ConstraintSchema.get(c.type) ?: return false
        return type.fields.any { spec -> mentions(spec, c.fields[spec.name], kind, name) }
    }

    private fun mentions(spec: FieldSpec, value: FieldValue?, kind: RefKind, name: String): Boolean {
        if (value == null) return false
        return when (spec.kind) {
            FieldKind.SCALAR -> spec.ref == kind && (value as? FieldValue.Scalar)?.text == name
            FieldKind.OBJECT -> (value as? FieldValue.Obj)?.let { obj -> spec.fields.any { mentions(it, obj.fields[it.name], kind, name) } } ?: false
            FieldKind.ARRAY -> {
                val item = spec.item ?: return false
                val items = (value as? FieldValue.Items)?.items ?: listOf(value)
                items.any { mentions(item, it, kind, name) }
            }
        }
    }
}
