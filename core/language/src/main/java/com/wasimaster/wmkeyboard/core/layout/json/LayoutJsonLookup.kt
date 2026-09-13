package com.wasimaster.wmkeyboard.core.layout.json

/** What the doc strip shows for the name under the caret. */
data class JsonDocEntry(
    /** The doc key: `Key.width`, `action:tool`. */
    val path: String,
    /** How the name is written, with its type and default: `"width": number = 1.0`. */
    val signature: String,
    val body: String,
)

object LayoutJsonLookup {

    /**
     * The property whose key the caret is on, or whose plain value it is in.
     * An action's `type` value explains the action itself. A caret between
     * members, on a bracket or in blank space explains nothing, so the strip
     * does not flicker to the enclosing object on every move.
     */
    fun at(document: JsonDocument, caret: Int, root: LayoutJsonRoot): JsonDocEntry? {
        val node = document.root ?: return null
        return visit(node, LayoutJsonSchema.rootShape(document, root), null, caret)
    }

    /**
     * The blocks that fold: every object and array that spans more than one line,
     * from its opening bracket to the end of its closing one, in document order.
     */
    fun foldRegions(document: JsonDocument): List<JsonSpan> {
        val source = document.source
        return document.allContainers
            .filter { container ->
                val closed = (container as? JsonObjectNode)?.closed ?: (container as? JsonArrayNode)?.closed ?: false
                val end = if (closed) container.end else -1
                end > container.start && source.indexOf('\n', container.start).let { it in container.start until end }
            }
            .map { JsonSpan(it.start, it.end) }
            .sortedBy { it.start }
    }

    private fun covers(start: Int, end: Int, caret: Int) = caret in start..end

    private fun visit(node: JsonNode, shape: JsonShape, property: PropertyShape?, caret: Int): JsonDocEntry? = when (shape) {
        is ObjectShape -> (node as? JsonObjectNode)?.let { objectEntry(it, shape, caret, discriminator = null, variants = null) }
        is VariantShape -> (node as? JsonObjectNode)?.let { obj ->
            objectEntry(obj, shape.variantOf(obj), caret, discriminator = shape.discriminator, variants = shape)
        }
        is ListShape -> (node as? JsonArrayNode)?.items?.firstOrNull { covers(it.start, it.end, caret) }?.let { item ->
            if (item is JsonScalarNode) property?.let(::propertyEntry) else visit(item, shape.item, property, caret)
        }
        is MapShape -> (node as? JsonObjectNode)?.members?.firstNotNullOfOrNull { member ->
            when {
                covers(member.key.start, member.key.end, caret) -> property?.let(::propertyEntry)
                member.value != null && covers(member.value.start, member.value.end, caret) ->
                    if (member.value is JsonScalarNode) {
                        property?.let(::propertyEntry)
                    } else {
                        visit(member.value, shape.value, property, caret)
                    }
                else -> null
            }
        }
        else -> null
    }

    private fun objectEntry(
        obj: JsonObjectNode,
        shape: ObjectShape?,
        caret: Int,
        discriminator: String?,
        variants: VariantShape?,
    ): JsonDocEntry? {
        for (member in obj.members) {
            val value = member.value
            val onKey = covers(member.key.start, member.key.end, caret)
            val inValue = value != null && covers(value.start, value.end, caret)
            if (!onKey && !inValue) continue
            if (member.name == discriminator && variants != null) {
                val tag = (value as? JsonScalarNode)?.text
                val variant = tag?.let { variants.variants[it] }
                return if (variant != null && tag != null) actionEntry(tag, variant) else typeEntry()
            }
            val property = shape?.property(member.name) ?: return null
            if (onKey || value is JsonScalarNode) return propertyEntry(property)
            return value?.let { visit(it, property.shape, property, caret) }
        }
        return null
    }

    private fun propertyEntry(property: PropertyShape): JsonDocEntry? {
        val body = LayoutJsonDocs.of(property.docKey) ?: return null
        val default = property.default?.let { " = $it" }.orEmpty()
        val nullable = if (property.nullable) " | null" else ""
        return JsonDocEntry(property.docKey, "\"${property.name}\": ${property.shape.summary()}$nullable$default", body)
    }

    private fun actionEntry(tag: String, variant: ObjectShape): JsonDocEntry? {
        val body = LayoutJsonDocs.of(variant.typeName) ?: return null
        val fields = variant.properties.filterNot { it.hint.hidden }.joinToString("") { ", \"${it.name}\": ${it.shape.summary()}" }
        return JsonDocEntry(variant.typeName, "{\"type\": \"$tag\"$fields}", body)
    }

    private fun typeEntry(): JsonDocEntry? =
        LayoutJsonDocs.of(LayoutJsonDocs.ACTION_TYPE)?.let { JsonDocEntry(LayoutJsonDocs.ACTION_TYPE, "\"type\": action type", it) }
}
