package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 参数 Schema 的小型构建器。只生成各家协议都接受的子集（object、string、integer、number、
 * boolean、array、enum、min/max），不用 oneOf，互斥参数由工具在执行时校验。
 */
internal class SchemaBuilder {
    private val properties = JSONObject()
    private val required = JSONArray()

    fun string(
        name: String,
        description: String,
        required: Boolean = false,
        enum: List<String>? = null,
        maxLength: Int? = null,
        minLength: Int? = null,
    ) = put(name, required, JSONObject().put("type", "string").put("description", description).apply {
        enum?.let { put("enum", JSONArray(it)) }
        maxLength?.let { put("maxLength", it) }
        minLength?.let { put("minLength", it) }
    })

    fun integer(
        name: String,
        description: String,
        required: Boolean = false,
        min: Long? = null,
        max: Long? = null,
    ) = put(name, required, JSONObject().put("type", "integer").put("description", description).apply {
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
    })

    fun number(
        name: String,
        description: String,
        required: Boolean = false,
        min: Double? = null,
        max: Double? = null,
    ) = put(name, required, JSONObject().put("type", "number").put("description", description).apply {
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
    })

    fun boolean(name: String, description: String, required: Boolean = false) =
        put(name, required, JSONObject().put("type", "boolean").put("description", description))

    fun stringArray(
        name: String,
        description: String,
        required: Boolean = false,
        enum: List<String>? = null,
        minItems: Int? = null,
        maxItems: Int? = null,
        maxLength: Int? = null,
    ) = put(name, required, JSONObject().put("type", "array").put("description", description).apply {
        put("items", JSONObject().put("type", "string").apply {
            enum?.let { put("enum", JSONArray(it)) }
            maxLength?.let { put("maxLength", it) }
        })
        minItems?.let { put("minItems", it) }
        maxItems?.let { put("maxItems", it) }
    })

    fun obj(
        name: String,
        description: String,
        required: Boolean = false,
        block: SchemaBuilder.() -> Unit,
    ) = put(name, required, SchemaBuilder().apply(block).build().put("description", description))

    fun raw(name: String, schema: JSONObject, required: Boolean = false) = put(name, required, schema)

    private fun put(name: String, isRequired: Boolean, schema: JSONObject) {
        properties.put(name, schema)
        if (isRequired) required.put(name)
    }

    fun build(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .apply { if (required.length() > 0) put("required", required) }
        .put("additionalProperties", false)
}

internal fun objectSchema(block: SchemaBuilder.() -> Unit): JSONObject = SchemaBuilder().apply(block).build()

/** 界面目标：element 优先，其次 area、point；坐标都在最近一次 ui_observe 的 coord_space 中。 */
internal fun SchemaBuilder.uiTarget(
    name: String = "target",
    description: String = "操作目标，三选一：element（来自最近一次 ui_observe）、area、point。坐标都在该观察的 coord_space 中。",
    required: Boolean = true,
    allowCoordinates: Boolean = true,
) = obj(name, description, required) {
    obj("element", "界面节点引用") {
        string("observation_id", "ui_observe 返回的 observation_id", required = true, maxLength = 64)
        integer("index", "节点 index", required = true, min = 0)
    }
    if (allowCoordinates) {
        obj("area", "矩形区域，点击其中心") {
            number("x1", "左", required = true)
            number("y1", "上", required = true)
            number("x2", "右", required = true)
            number("y2", "下", required = true)
        }
        obj("point", "坐标点") {
            number("x", "横坐标", required = true)
            number("y", "纵坐标", required = true)
        }
    }
}
