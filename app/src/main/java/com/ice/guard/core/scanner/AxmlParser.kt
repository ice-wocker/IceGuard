package com.ice.guard.core.scanner

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 自研 AndroidManifest.xml 二进制（AXML）解析器。
 *
 * 背景：APK 内的 AndroidManifest.xml 是编译后的二进制格式（chunk-based），
 * 无法当作普通 XML 文本读取。常规做法是引入 apk-parser 之类的库，
 * 本项目为满足「零第三方依赖」要求，直接按 AOSP 定义的格式解析。
 *
 * 格式要点（来自 AOSP resource 定义）：
 *   Header:      ResChunk_header { type(u16), headerSize(u16), size(u32) }
 *   StringPool:  type=0x0001，含字符串偏移表与字符串数据
 *                字符串池既可能是 UTF-8（flags & 0x100）也可能是 UTF-16，
 *                必须按 flags 判断，不能假定。实测 AAPT2 输出的清单为 UTF-16。
 *   StartTag:    type=0x0102，含属性列表
 *   Attribute:   { ns(u32), name(u32), rawValue(u32), typedValue(Res_value) }
 *                Res_value 结构：size(u16) res0(u8) dataType(u8) data(u32)
 *                因此 dataType 位于属性块起始偏移 +15 处。
 *
 * 本解析器目标明确：提取三样东西，不做完整 DOM 还原。
 *   1. <manifest> 的 package 属性
 *   2. <uses-permission> 的 android:name 属性
 *   3. <activity>/<service>/<receiver>/<provider> 四类组件的
 *      android:name、android:exported、android:permission，以及其 <intent-filter>
 *      下声明的 <action>（用于判断"能否被外部应用唤起"）
 *
 * 健壮性设计：所有越界访问均有防护，单个属性解析失败不影响整体；
 * 解析异常统一转为 success=false 的返回值，不向调用方抛异常。
 */
object AxmlParser {

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_TAG = 0x0102
    private const val CHUNK_END_TAG = 0x0103
    private const val RES_XML_TYPE = 0x0003

    private const val FLAG_UTF8 = 0x100

    /** 属性块单条记录长度 */
    private const val ATTR_SIZE = 20

    /** 组件类型。仅覆盖需要判断"能否被外部唤起"的四类。 */
    enum class ComponentType(val tag: String, val label: String) {
        ACTIVITY("activity", "Activity"),
        SERVICE("service", "Service"),
        RECEIVER("receiver", "Receiver"),
        PROVIDER("provider", "Provider");

        companion object {
            fun of(tag: String): ComponentType? = entries.firstOrNull { it.tag == tag }
        }
    }

    /**
     * 一个组件的关键属性。
     *
     * @param exportedExplicit 显式声明的 android:exported；null 表示未声明
     * @param permission       组件上声明的 android:permission（外部调用者需持有该权限）
     * @param actions          该组件 intent-filter 中声明的 action
     */
    data class ComponentInfo(
        val type: ComponentType,
        val name: String,
        val exportedExplicit: Boolean?,
        val permission: String?,
        val actions: List<String>
    ) {
        /**
         * 是否可被外部应用唤起。
         *
         * 判定规则：显式 exported=true 即可导出；未显式声明时，按 Android 12 之前的
         * 系统默认行为——**带 intent-filter 的组件默认可导出**。
         * 注意这是"声明层面"的判定，不代表组件一定存在漏洞。
         */
        val exported: Boolean get() = exportedExplicit ?: actions.isNotEmpty()

        /** 可导出且未加权限保护——这是真正需要关注的情况 */
        val unprotected: Boolean get() = exported && permission.isNullOrBlank()
    }

    /** 解析结果 */
    data class ManifestInfo(
        val packageName: String?,
        val permissions: List<String>,
        val success: Boolean,
        val error: String? = null,
        val components: List<ComponentInfo> = emptyList()
    )

    /**
     * 从 APK 文件中解析 AndroidManifest.xml。
     */
    fun parseFromApk(apk: File): ManifestInfo = try {
        java.util.zip.ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: return ManifestInfo(null, emptyList(), false, "包内未找到 AndroidManifest.xml")
            zip.getInputStream(entry).use { parse(it) }
        }
    } catch (t: Throwable) {
        ManifestInfo(null, emptyList(), false, t.message ?: t.javaClass.simpleName)
    }

    /**
     * 解析 AXML 输入流。
     */
    fun parse(input: InputStream): ManifestInfo = try {
        val bytes = input.readBytes()
        parseBytes(bytes)
    } catch (t: Throwable) {
        ManifestInfo(null, emptyList(), false, t.message ?: t.javaClass.simpleName)
    }

    /**
     * 解析 AXML 字节数组。测试与复用入口。
     */
    fun parseBytes(bytes: ByteArray): ManifestInfo {
        if (bytes.size < 8) return ManifestInfo(null, emptyList(), false, "文件过小")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val xmlType = buf.getShort(0).toInt() and 0xFFFF
        if (xmlType != RES_XML_TYPE) {
            return ManifestInfo(
                null, emptyList(), false,
                "不是有效的二进制 XML（type=0x${xmlType.toString(16)}）"
            )
        }

        var stringPool: StringPool? = null
        val permissions = mutableListOf<String>()
        var packageName: String? = null

        // —— 组件收集状态 ——
        val components = mutableListOf<ComponentInfo>()
        var pending: PendingComponent? = null
        var inIntentFilter = false

        fun flushComponent() {
            val c = pending ?: return
            components += ComponentInfo(
                type = c.type,
                name = c.name,
                exportedExplicit = c.exportedExplicit,
                permission = c.permission,
                actions = c.actions.toList()
            )
            pending = null
            inIntentFilter = false
        }

        var offset = 8
        val total = bytes.size

        while (offset + 8 <= total) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)

            // 防御：非法的 chunkSize 会导致死循环或越界
            if (chunkSize < 8 || offset + chunkSize > total) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    stringPool = runCatching {
                        readStringPool(buf, offset, headerSize)
                    }.getOrNull()
                }
                CHUNK_START_TAG -> {
                    val pool = stringPool
                    if (pool != null) {
                        runCatching {
                            readStartTag(buf, offset, pool)
                        }.getOrNull()?.also { tag ->
                            when (tag.name) {
                                "manifest" -> tag.attributes["package"]
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { packageName = it }

                                "uses-permission" -> tag.attributes["name"]
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { permissions += it }

                                "activity", "service", "receiver", "provider" -> {
                                    // 组件之间不嵌套，遇到新组件先落盘上一个
                                    flushComponent()
                                    val type = ComponentType.of(tag.name)
                                    val name = tag.attributes["name"].orEmpty()
                                    if (type != null && name.isNotBlank()) {
                                        pending = PendingComponent(
                                            type = type,
                                            name = name,
                                            exportedExplicit = parseBool(tag.attributes["exported"]),
                                            permission = permissionOf(tag.name, tag.attributes)
                                        )
                                    }
                                }

                                "intent-filter" -> if (pending != null) inIntentFilter = true

                                "action" -> {
                                    val c = pending
                                    if (inIntentFilter && c != null) {
                                        tag.attributes["name"]
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { c.actions += it }
                                    }
                                }

                                else -> Unit // 其余标签（application / meta-data / uses-sdk 等）不在提取范围
                            }
                        }
                    }
                }
                CHUNK_END_TAG -> {
                    val pool = stringPool
                    if (pool != null) {
                        val endName = runCatching { readNodeName(buf, offset, pool) }.getOrNull()
                        when (endName) {
                            "intent-filter" -> inIntentFilter = false
                            "activity", "service", "receiver", "provider" -> flushComponent()
                            else -> Unit
                        }
                    }
                }
            }

            offset += chunkSize
        }

        // 清单被截断时，最后一个组件可能没有对应的 END_TAG
        flushComponent()

        return ManifestInfo(
            packageName = packageName,
            permissions = permissions.distinct(),
            success = true,
            components = components
        )
    }

    /** 组件收集期间的中间态 */
    private class PendingComponent(
        val type: ComponentType,
        val name: String,
        val exportedExplicit: Boolean?,
        val permission: String?
    ) {
        val actions = mutableListOf<String>()
    }

    /** 解析 "true"/"false"；未声明或无法识别时返回 null（而不是当成 false） */
    private fun parseBool(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * 取组件的访问门槛声明。
     *
     * `<provider>` 特殊：它的读、写权限是**分开**声明的
     * （`android:permission` 等价于同时设置 readPermission 与 writePermission）。
     * 只要读写之中有一个没被保护，就不算"有门槛"——不能因为写了写权限
     * 就认为读取也是安全的。
     *
     * 其余组件统一用 `android:permission`。
     */
    private fun permissionOf(tagName: String, attrs: Map<String, String>): String? {
        attrs["permission"]?.takeIf { it.isNotBlank() }?.let { return it }
        if (tagName != "provider") return null

        val read = attrs["readPermission"]
        val write = attrs["writePermission"]
        return if (!read.isNullOrBlank() && !write.isNullOrBlank()) read else null
    }

    // ———————————————————— StringPool ————————————————————

    /** 字符串池：按 flags 决定 UTF-8 / UTF-16 解码路径 */
    private class StringPool(
        private val buf: ByteBuffer,
        private val stringsBase: Int,
        private val offsetsBase: Int,
        private val count: Int,
        private val isUtf8: Boolean
    ) {
        private val cache = HashMap<Int, String>()

        fun get(index: Int): String? {
            if (index < 0 || index >= count) return null
            cache[index]?.let { return it }
            val s = runCatching { read(index) }.getOrNull() ?: return null
            cache[index] = s
            return s
        }

        private fun read(index: Int): String {
            val offPos = offsetsBase + index * 4
            if (offPos + 4 > buf.capacity()) return ""
            val strOffset = buf.getInt(offPos)
            val abs = stringsBase + strOffset
            if (abs < 0 || abs >= buf.capacity()) return ""
            return if (isUtf8) readUtf8(abs) else readUtf16(abs)
        }

        /**
         * UTF-8 字符串：[u16变长长度][u8变长字节长度][字节数据]
         * 注意：第一个长度字段以「字符数」计，第二个以「字节数」计，
         * 解码时以字节长度为准。
         */
        private fun readUtf8(pos: Int): String {
            var p = pos
            if (p >= buf.capacity()) return ""
            val first = buf.get(p).toInt() and 0xFF
            p += if ((first and 0x80) != 0) 2 else 1
            if (p >= buf.capacity()) return ""
            var len = buf.get(p).toInt() and 0xFF
            p += 1
            if ((len and 0x80) != 0) {
                if (p >= buf.capacity()) return ""
                len = ((len and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
                p += 1
            }
            if (len <= 0 || p + len > buf.capacity()) return ""
            val arr = ByteArray(len)
            for (i in 0 until len) arr[i] = buf.get(p + i)
            return String(arr, Charsets.UTF_8)
        }

        /**
         * UTF-16 字符串：[u16长度][UTF-16LE 字符数据]
         * 长度字段的最高位为 1 时，表示长度用两个 u16 表示（大字符串）。
         */
        private fun readUtf16(pos: Int): String {
            var p = pos
            if (p + 2 > buf.capacity()) return ""
            var len = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            if ((len and 0x8000) != 0) {
                if (p + 2 > buf.capacity()) return ""
                val low = buf.getShort(p).toInt() and 0xFFFF
                len = ((len and 0x7FFF) shl 16) or low
                p += 2
            }
            if (len <= 0) return ""
            val byteLen = len * 2
            if (p + byteLen > buf.capacity()) return ""
            val sb = StringBuilder(len)
            for (i in 0 until len) {
                sb.append(buf.getShort(p + i * 2).toInt().toChar())
            }
            return sb.toString()
        }
    }

    private fun readStringPool(buf: ByteBuffer, chunkStart: Int, headerSize: Int): StringPool? {
        if (chunkStart + 28 > buf.capacity()) return null

        val stringCount = buf.getInt(chunkStart + 8)
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)

        if (stringCount < 0 || stringCount > 100_000) return null

        return StringPool(
            buf = buf,
            stringsBase = chunkStart + stringsStart,
            offsetsBase = chunkStart + headerSize,
            count = stringCount,
            isUtf8 = (flags and FLAG_UTF8) != 0
        )
    }

    // ———————————————————— StartTag ————————————————————

    private data class StartTagInfo(
        val name: String,
        val attributes: Map<String, String>
    )

    /**
     * 读取任意 node（START_TAG / END_TAG）的标签名。
     * 两者的 node 头部布局一致：header(8) + lineNumber(4) + comment(4)，
     * 其后的 ext 结构中 +0 为 ns、+4 为 name。
     */
    private fun readNodeName(buf: ByteBuffer, chunkStart: Int, pool: StringPool): String? {
        if (chunkStart + 4 > buf.capacity()) return null
        val headerSize = buf.getShort(chunkStart + 2).toInt() and 0xFFFF
        val nodeBase = chunkStart + headerSize
        if (nodeBase < 0 || nodeBase + 8 > buf.capacity()) return null
        return pool.get(buf.getInt(nodeBase + 4))
    }

    /**
     * 读取 StartTag 节点。
     *
     * 布局：
     *   ResXMLTree_node : header(8) + lineNumber(4) + comment(4) = 16 字节
     *   ResXMLTree_attrExt 紧接 node header 之后：
     *       ns(u32) name(u32) attributeStart(u16) attributeSize(u16)
     *       attributeCount(u16) idIndex(u16) classIndex(u16) styleIndex(u16)
     *   即相对 node 起始：attrStart 在 +8，attrCount 在 +12，idIndex 在 +14。
     *   属性区起始 = node 起始 + attributeStart（注意 attributeStart 是
     *   相对 node 起始的偏移，不要再叠加 header 大小）。
     *
     * 实测注意：命名空间属性的 name 索引可能指向字符串池中的空串，
     * 此时以 namespace URI 的最后一段作为属性名回退，避免丢失信息。
     */
    private fun readStartTag(buf: ByteBuffer, chunkStart: Int, pool: StringPool): StartTagInfo? {
        // ResXMLTree_node 的 header 大小通常为 16，此处按实际 headerSize 推进
        val headerSize = buf.getShort(chunkStart + 2).toInt() and 0xFFFF
        val nodeBase = chunkStart + headerSize

        if (nodeBase + 20 > buf.capacity()) return null

        val nameIdx = buf.getInt(nodeBase + 4)
        val tagName = pool.get(nameIdx) ?: return null

        // ResXMLTree_attrExt 布局（相对 node 起始）：
        //   +0  ns(u32)  +4  name(u32)
        //   +8  attributeStart(u16)  +10 attributeSize(u16)
        //   +12 attributeCount(u16)  +14 idIndex(u16)
        //   +16 classIndex(u16)      +18 styleIndex(u16)
        val attrStart = buf.getShort(nodeBase + 8).toInt() and 0xFFFF
        val attrCount = buf.getShort(nodeBase + 12).toInt() and 0xFFFF

        if (attrCount < 0 || attrCount > 1000) return null

        val attrs = HashMap<String, String>(attrCount)
        val attrBase = nodeBase + attrStart

        for (i in 0 until attrCount) {
            val base = attrBase + i * ATTR_SIZE
            if (base + ATTR_SIZE > buf.capacity()) break

            val nsIdx = buf.getInt(base)
            val nameIndex = buf.getInt(base + 4)
            val rawValueIdx = buf.getInt(base + 8)
            // Res_value: size(u16) res0(u8) dataType(u8) data(u32)
            val dataType = buf.get(base + 15).toInt() and 0xFF
            val dataValue = buf.getInt(base + 16)

            var key = pool.get(nameIndex).orEmpty()
            if (key.isBlank()) {
                // 回退：用命名空间 URI 末段（如 android）作为键
                key = pool.get(nsIdx)?.substringAfterLast('/') ?: continue
            }
            if (key.isBlank()) continue

            val value: String? = when {
                // rawValue 存在时优先使用
                rawValueIdx >= 0 && rawValueIdx != -1 -> pool.get(rawValueIdx)
                // dataType 0x03 表示字符串引用
                dataType == 0x03 -> pool.get(dataValue)
                // 布尔类型
                dataType == 0x12 -> if (dataValue != 0) "true" else "false"
                // 整数类型
                dataType == 0x10 || dataType == 0x11 -> dataValue.toString()
                else -> dataValue.toString()
            }

            if (!value.isNullOrBlank()) attrs[key] = value
        }

        return StartTagInfo(tagName, attrs)
    }
}
