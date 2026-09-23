package metifikys.config

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * The set of config keys [AppConfig] accepts, as dotted paths: a `Map<String, X>` level is `*`
 * (`categories.*.post.longForm.minComposite`), a list level `[]` (`categories.*.feeds[].url`).
 * Built once by walking the config classes through Jackson's own introspection, so it is exactly
 * what [ConfigLoader] binds — no hand-kept key list to drift.
 */
class ConfigSchema private constructor(private val root: Node) {

    /** One level of the schema: named children (a bean), or the element of a map / list, or a leaf. */
    class Node(
        val children: Map<String, Node> = emptyMap(),
        val mapValue: Node? = null,
        val listItem: Node? = null
    ) {
        val isLeaf: Boolean get() = children.isEmpty() && mapValue == null && listItem == null
    }

    /** Every path, intermediate levels included. */
    val paths: Set<String> by lazy { buildSet { collect(root, "", this) } }

    fun contains(path: String): Boolean = path in paths

    /** Paths found in a parsed YAML tree, normalized to schema form, plus keys the schema lacks. */
    data class YamlScan(val known: Set<String>, val unknown: List<String>)

    /**
     * Walks [yaml] alongside the schema: map keys become `*`, list items `[]`. A key the schema
     * does not have is reported with its real name (`categories.gaming.post.hashtag`) and not
     * descended into. A plain-string feed (`- https://…`) is the list item itself.
     */
    fun scan(yaml: JsonNode): YamlScan {
        val known = linkedSetOf<String>()
        val unknown = mutableListOf<String>()
        fun walk(node: JsonNode, schema: Node, schemaPath: String, realPath: String) {
            if (schemaPath.isNotEmpty()) known += schemaPath
            when {
                schema.mapValue != null && node.isObject -> node.fields().forEach { (k, v) ->
                    walk(v, schema.mapValue, join(schemaPath, "*"), join(realPath, k))
                }
                schema.listItem != null && node.isArray -> node.forEach { item ->
                    walk(item, schema.listItem, "$schemaPath[]", "$realPath[]")
                }
                schema.children.isNotEmpty() && node.isObject -> node.fields().forEach { (k, v) ->
                    val child = schema.children[k]
                    if (child == null) unknown += join(realPath, k) else walk(v, child, join(schemaPath, k), join(realPath, k))
                }
            }
        }
        walk(yaml, root, "", "")
        return YamlScan(known, unknown)
    }

    /** Known key names directly under [path] (schema form), for "did you mean" hints. */
    fun childrenOf(path: String): Set<String> {
        var node: Node = root
        for (seg in segments(path)) {
            node = when (seg) {
                "*" -> node.mapValue
                "[]" -> node.listItem
                else -> node.children[seg]
            } ?: return emptySet()
        }
        return node.children.keys
    }

    private fun collect(node: Node, prefix: String, out: MutableSet<String>) {
        if (prefix.isNotEmpty()) out += prefix
        node.children.forEach { (k, c) -> collect(c, join(prefix, k), out) }
        node.mapValue?.let { collect(it, join(prefix, "*"), out) }
        node.listItem?.let { collect(it, "$prefix[]", out) }
    }

    companion object {
        private val defaultMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        /** Schema of [AppConfig] as the config loader's mapper sees it. */
        val APP: ConfigSchema by lazy { of(AppConfig::class.java) }

        fun of(type: Class<*>, mapper: ObjectMapper = defaultMapper): ConfigSchema =
            ConfigSchema(build(mapper.constructType(type), mapper, emptySet()))

        private fun build(type: JavaType, mapper: ObjectMapper, visiting: Set<Class<*>>): Node {
            val raw = type.rawClass
            return when {
                type.isMapLikeType -> Node(mapValue = build(type.contentType, mapper, visiting))
                type.isCollectionLikeType || type.isArrayType -> Node(listItem = build(type.contentType, mapper, visiting))
                !isConfigBean(raw) || raw in visiting -> Node()
                else -> {
                    val desc = mapper.deserializationConfig.introspect(type)
                    val props = desc.findProperties()
                        // Constructor-bound or settable only: a computed getter (BadgesConfig.enabledIds) is not a key.
                        .filter { it.hasConstructorParameter() || it.hasSetter() || it.hasField() }
                    Node(children = props.associate { it.name to build(it.primaryType, mapper, visiting + raw) }.toSortedMap())
                }
            }
        }

        private fun isConfigBean(c: Class<*>): Boolean =
            !c.isPrimitive && !c.isEnum && c.name.startsWith("metifikys.")

        private fun join(prefix: String, seg: String) = if (prefix.isEmpty()) seg else "$prefix.$seg"

        /** `a.*.b[].c` → [a, *, b, [], c]. */
        fun segments(path: String): List<String> =
            path.split('.').filter { it.isNotEmpty() }.flatMap { seg ->
                if (seg.endsWith("[]")) listOf(seg.removeSuffix("[]"), "[]").filter { it.isNotEmpty() } else listOf(seg)
            }
    }
}
