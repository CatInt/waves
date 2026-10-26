package io.waves.feed

/**
 * Minimal, lenient XML → tree parser for RSS feeds, usable from commonMain.
 *
 * Never throws on malformed input: unknown/mismatched end tags are ignored, unclosed elements are closed
 * at end of input, stray `<` / `&` are kept as text. Handles CDATA, comments, processing instructions,
 * DOCTYPE (with internal subset), XML + numeric + a few common HTML entities.
 *
 * Element names in well-known podcast namespaces are canonicalised to fixed prefixes (`itunes:`, `content:`)
 * regardless of the prefix the feed declared, so lookups can use the conventional names.
 */
internal class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
) {
    val children = mutableListOf<XmlElement>()

    /** Ordered mixed content: [String] text runs and child [XmlElement]s. */
    private val content = mutableListOf<Any>()

    internal fun appendText(text: CharSequence) {
        if (text.isEmpty()) return
        val last = content.lastOrNull()
        if (last is StringBuilder) last.append(text) else content.add(StringBuilder(text))
    }

    internal fun appendChild(element: XmlElement) {
        children.add(element)
        content.add(element)
    }

    /** Text of this element and all descendants, in document order (tags of unescaped HTML are dropped). */
    val text: String
        get() = when {
            content.isEmpty() -> ""
            content.size == 1 && content[0] is StringBuilder -> content[0].toString()
            else -> buildString { appendTextTo(this) }
        }

    private fun appendTextTo(sb: StringBuilder) {
        for (item in content) if (item is XmlElement) item.appendTextTo(sb) else sb.append(item)
    }

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun children(name: String): List<XmlElement> = children.filter { it.name == name }

    /** Trimmed text of the first child called [name], or null when absent/blank. */
    fun childText(name: String): String? = child(name)?.text?.trim()?.takeIf { it.isNotEmpty() }

    /** Depth-first search for the first element called [name] (including this one). */
    fun find(name: String): XmlElement? {
        if (this.name == name) return this
        for (c in children) c.find(name)?.let { return it }
        return null
    }
}

internal object XmlTree {
    private val canonicalNamespaces = mapOf(
        "http://www.itunes.com/dtds/podcast-1.0.dtd" to "itunes",
        "https://www.itunes.com/dtds/podcast-1.0.dtd" to "itunes",
        "http://purl.org/rss/1.0/modules/content/" to "content",
        "http://www.w3.org/2005/atom" to "atom",
        "http://www.youtube.com/xml/schemas/2015" to "yt",
        "http://search.yahoo.com/mrss/" to "media",
    )

    private class Frame(val element: XmlElement, val rawName: String, val namespaces: Map<String, String>)

    /** Parses [xml] and returns a synthetic document root whose children are the top-level elements. */
    fun parse(xml: String): XmlElement {
        val root = XmlElement("#document", emptyMap())
        val stack = ArrayList<Frame>()
        stack.add(Frame(root, "#document", emptyMap()))
        val s = xml
        val n = s.length
        var i = if (s.startsWith('﻿')) 1 else 0

        fun current() = stack.last().element

        while (i < n) {
            val c = s[i]
            if (c != '<') {
                val end = s.indexOf('<', i).let { if (it < 0) n else it }
                current().appendText(decodeEntities(s, i, end))
                i = end
                continue
            }
            when {
                s.startsWith("<!--", i) -> {
                    val end = s.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                s.startsWith("<![CDATA[", i) -> {
                    val end = s.indexOf("]]>", i + 9)
                    val stop = if (end < 0) n else end
                    current().appendText(s.subSequence(i + 9, stop))
                    i = if (end < 0) n else end + 3
                }
                s.startsWith("<!", i) -> i = skipDeclaration(s, i)
                s.startsWith("<?", i) -> {
                    val end = s.indexOf("?>", i + 2)
                    i = if (end < 0) n else end + 2
                }
                s.startsWith("</", i) -> {
                    val end = s.indexOf('>', i + 2).let { if (it < 0) n else it }
                    val rawName = s.substring(i + 2, end).trim()
                    val idx = stack.indexOfLast { it.rawName == rawName }
                    if (idx > 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                    i = if (end >= n) n else end + 1
                }
                i + 1 < n && isNameStart(s[i + 1]) -> {
                    i = parseStartTag(s, i, stack)
                }
                else -> {
                    // Stray '<' — keep it as text.
                    current().appendText("<")
                    i++
                }
            }
        }
        return root
    }

    private fun skipDeclaration(s: String, start: Int): Int {
        var depth = 0
        var i = start + 2
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return s.length
    }

    private fun parseStartTag(s: String, start: Int, stack: ArrayList<Frame>): Int {
        val n = s.length
        var i = start + 1
        val nameStart = i
        while (i < n && isNameChar(s[i])) i++
        val rawName = s.substring(nameStart, i)
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            val c = s[i]
            when {
                c == '>' -> { i++; break }
                c == '/' && i + 1 < n && s[i + 1] == '>' -> { selfClosing = true; i += 2; break }
                c == '<' -> break // malformed: tag never closed; let the main loop handle the '<'
                isNameStart(c) -> {
                    val aStart = i
                    while (i < n && isNameChar(s[i])) i++
                    val aName = s.substring(aStart, i)
                    while (i < n && s[i].isWhitespace()) i++
                    var value = ""
                    if (i < n && s[i] == '=') {
                        i++
                        while (i < n && s[i].isWhitespace()) i++
                        if (i < n && (s[i] == '"' || s[i] == '\'')) {
                            val q = s[i]
                            val end = s.indexOf(q, i + 1).let { if (it < 0) n else it }
                            value = decodeEntities(s, i + 1, end)
                            i = if (end >= n) n else end + 1
                        } else {
                            val vStart = i
                            while (i < n && !s[i].isWhitespace() && s[i] != '>' &&
                                !(s[i] == '/' && i + 1 < n && s[i + 1] == '>')
                            ) i++
                            value = decodeEntities(s, vStart, i)
                        }
                    }
                    attrs[aName] = value
                }
                else -> i++
            }
        }

        val parentNs = stack.last().namespaces
        val declared = attrs.filterKeys { it.startsWith("xmlns:") }
        val namespaces = if (declared.isEmpty()) parentNs else parentNs + declared.mapKeys { it.key.substringAfter(':') }
        val element = XmlElement(canonicalName(rawName, namespaces), attrs)
        stack.last().element.appendChild(element)
        if (!selfClosing) stack.add(Frame(element, rawName, namespaces))
        return i
    }

    private fun canonicalName(rawName: String, namespaces: Map<String, String>): String {
        val colon = rawName.indexOf(':')
        if (colon <= 0) return rawName
        val uri = namespaces[rawName.substring(0, colon)] ?: return rawName
        val canonical = canonicalNamespaces[uri.trim().lowercase()] ?: return rawName
        return canonical + ":" + rawName.substring(colon + 1)
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || c == ':'
    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        // Not valid XML, but common in sloppy feeds.
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "copy" to "©", "reg" to "®", "trade" to "™", "eacute" to "é",
    )

    internal fun decodeEntities(s: String, start: Int, end: Int): String {
        // Bounded scans only: an unbounded indexOf would run to the next '&' / ';' anywhere in the document,
        // making parsing quadratic on large feeds with few entities.
        var amp = -1
        for (k in start until end) if (s[k] == '&') { amp = k; break }
        if (amp < 0) return s.substring(start, end)
        val sb = StringBuilder(end - start)
        sb.append(s, start, amp)
        var i = amp
        while (i < end) {
            val c = s[i]
            if (c == '&') {
                var semi = -1
                for (k in i + 1..minOf(end - 1, i + 12)) if (s[k] == ';') { semi = k; break }
                if (semi >= i + 2) {
                    val ent = s.substring(i + 1, semi)
                    val decoded = decodeEntity(ent)
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun decodeEntity(ent: String): String? {
        if (ent.startsWith("#")) {
            val code = if (ent.startsWith("#x") || ent.startsWith("#X")) ent.substring(2).toIntOrNull(16)
            else ent.substring(1).toIntOrNull()
            if (code == null || code < 0 || code > 0x10FFFF) return null
            return codePointToString(code)
        }
        return namedEntities[ent]
    }

    private fun codePointToString(code: Int): String =
        if (code < 0x10000) code.toChar().toString()
        else {
            val v = code - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}
