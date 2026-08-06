package dev.inkstone.core.sync

/**
 * A small, namespace-aware XML reader — the part of dav4jvm this project has to
 * supply itself (SPEC §0.1 #4).
 *
 * ## Why hand-rolled
 *
 * `:core:sync` is KMP and links for iOS (SPEC §2.3). Every mature Kotlin XML parser
 * on this list is JVM-only, which means it would pass the import guardrail and kill
 * the iPad path silently — the precise failure SPEC §0.1 #4 exists to prevent. The
 * grammar actually needed is one document shape (SPEC §7.3's `multistatus`) and the
 * cost is this file.
 *
 * ## Namespaces are resolved, not assumed
 *
 * Nextcloud answers with `d:`/`oc:`/`nc:` today, and nothing in RFC 4918 obliges it
 * to keep doing so — a prefix is the *document's* choice. So `xmlns` declarations are
 * tracked down the tree and elements are matched on `(namespace, localName)`.
 * String-matching `"d:getetag"` works against the server you tested on and fails
 * against a proxy that rewrites the document.
 *
 * ## What it deliberately does not do
 *
 * No DTD processing, no external entities, no XInclude — this parser cannot be made
 * to fetch a URL or expand a billion laughs, because it does not implement the
 * features that would let it. Character references and the five predefined entities
 * are supported; anything else is left as literal text rather than resolved.
 */
public data class XmlElement(
    val namespace: String,
    val localName: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    /** Concatenated character data directly inside this element, trimmed. */
    val text: String,
) {

    public fun child(namespace: String, localName: String): XmlElement? =
        children.firstOrNull { it.namespace == namespace && it.localName == localName }

    public fun childrenNamed(namespace: String, localName: String): List<XmlElement> =
        children.filter { it.namespace == namespace && it.localName == localName }

    /** The trimmed text of a named child, or null when the child is absent. Empty stays empty. */
    public fun textOf(namespace: String, localName: String): String? = child(namespace, localName)?.text

    public fun has(namespace: String, localName: String): Boolean = child(namespace, localName) != null
}

/** What [XmlReader] says when the bytes were not the document they claimed to be. */
public class XmlParseException(message: String) : Exception(message)

public object XmlReader {

    private const val XMLNS = "xmlns"
    private const val HEX_RADIX = 16
    private const val DECIMAL_RADIX = 10

    /** Parse [source] and return its root element. Throws [XmlParseException] on malformed input. */
    public fun parse(source: String): XmlElement = Parser(source).document()

    private class Parser(private val source: String) {
        private var cursor = 0

        fun document(): XmlElement {
            skipProlog()
            val root = element(emptyMap()) ?: throw XmlParseException("no root element")
            return root
        }

        /** Everything legal before the root element: declaration, comments, PIs, DOCTYPE. */
        private fun skipProlog() {
            while (true) {
                skipWhitespace()
                when {
                    lookingAt("<?") -> skipUntil("?>")
                    lookingAt("<!--") -> skipUntil("-->")
                    lookingAt("<!") -> skipDoctype()
                    else -> return
                }
            }
        }

        /**
         * DOCTYPE, skipped rather than processed.
         *
         * Counting `<`/`>` handles an internal subset. Nothing inside is interpreted:
         * an entity declared here is not defined, which is what makes an entity-expansion
         * attack against this parser impossible rather than merely unlikely.
         */
        private fun skipDoctype() {
            var depth = 0
            while (cursor < source.length) {
                when (source[cursor]) {
                    '<' -> depth++

                    '>' -> {
                        depth--
                        cursor++
                        if (depth <= 0) return
                        continue
                    }
                }
                cursor++
            }
        }

        // One element: open tag, then a loop over what can appear inside it. Splitting the
        // loop out would hide that `</` terminates it, which is the only exit that matters.
        //
        // Four throws, and each names a different malformed document: an unterminated tag,
        // an unclosed element, a mismatched closing tag, an unexpected character. Folding
        // them into one would turn four actionable messages into "invalid XML", which is
        // exactly the wrong trade when the document came off somebody else's reverse proxy.
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
        fun element(inheritedNamespaces: Map<String, String>): XmlElement? {
            skipWhitespace()
            if (!lookingAt("<")) return null
            cursor++ // '<'
            val rawName = readName()
            val attributes = LinkedHashMap<String, String>()
            val namespaces = LinkedHashMap(inheritedNamespaces)

            while (true) {
                skipWhitespace()
                if (cursor >= source.length) throw XmlParseException("unterminated tag <$rawName>")
                if (lookingAt("/>") || lookingAt(">")) break
                val attributeName = readName()
                skipWhitespace()
                expect('=')
                skipWhitespace()
                val value = readQuoted()
                when {
                    attributeName == XMLNS -> namespaces[""] = value
                    attributeName.startsWith("$XMLNS:") -> namespaces[attributeName.substringAfter(':')] = value
                    else -> attributes[attributeName] = value
                }
            }

            val selfClosing = lookingAt("/>")
            cursor += if (selfClosing) 2 else 1

            val prefix = rawName.substringBefore(':', "")
            val localName = rawName.substringAfter(':')
            val namespace = namespaces[prefix].orEmpty()

            if (selfClosing) {
                return XmlElement(namespace, localName, attributes, emptyList(), "")
            }

            val children = ArrayList<XmlElement>()
            val text = StringBuilder()
            while (true) {
                if (cursor >= source.length) throw XmlParseException("unclosed <$rawName>")
                when {
                    lookingAt("</") -> {
                        cursor += 2
                        val closing = readName()
                        skipWhitespace()
                        expect('>')
                        if (closing != rawName) throw XmlParseException("</$closing> closes <$rawName>")
                        return XmlElement(namespace, localName, attributes, children, text.toString().trim())
                    }

                    lookingAt("<!--") -> skipUntil("-->")

                    lookingAt("<![CDATA[") -> text.append(readCdata())

                    lookingAt("<?") -> skipUntil("?>")

                    lookingAt("<") -> element(namespaces)?.let { children += it }

                    else -> text.append(readText())
                }
            }
        }

        private fun readCdata(): String {
            val start = cursor + "<![CDATA[".length
            val end = source.indexOf("]]>", start)
            if (end < 0) throw XmlParseException("unterminated CDATA")
            cursor = end + "]]>".length
            return source.substring(start, end)
        }

        private fun readText(): String {
            val start = cursor
            while (cursor < source.length && source[cursor] != '<') cursor++
            return unescape(source.substring(start, cursor))
        }

        private fun readName(): String {
            skipWhitespace()
            val start = cursor
            while (cursor < source.length && !source[cursor].isNameBoundary()) cursor++
            if (cursor == start) throw XmlParseException("expected a name at offset $cursor")
            return source.substring(start, cursor)
        }

        @Suppress("ThrowsCount") // Missing, unquoted and unterminated are three real, distinct documents.
        private fun readQuoted(): String {
            if (cursor >= source.length) throw XmlParseException("expected an attribute value")
            val quote = source[cursor]
            if (quote != '"' && quote != '\'') throw XmlParseException("unquoted attribute value")
            cursor++
            val start = cursor
            while (cursor < source.length && source[cursor] != quote) cursor++
            if (cursor >= source.length) throw XmlParseException("unterminated attribute value")
            val raw = source.substring(start, cursor)
            cursor++
            return unescape(raw)
        }

        private fun expect(character: Char) {
            if (cursor >= source.length || source[cursor] != character) {
                throw XmlParseException("expected '$character' at offset $cursor")
            }
            cursor++
        }

        private fun lookingAt(token: String) = source.startsWith(token, cursor)

        private fun skipUntil(token: String) {
            val end = source.indexOf(token, cursor)
            cursor = if (end < 0) source.length else end + token.length
        }

        private fun skipWhitespace() {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        }

        private fun Char.isNameBoundary() = isWhitespace() || this == '>' || this == '/' || this == '='
    }

    /**
     * The five predefined entities and numeric character references.
     *
     * Anything else — `&nbsp;`, or an entity a DTD would have declared — is left as
     * written. That is the safe direction: a filename that renders with a stray `&amp;`
     * is a cosmetic bug, whereas resolving arbitrary entities is how a parser becomes
     * a fetch primitive.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    // The `continue`s are the three ways a `&` turns out not to start an entity — no
    // semicolon, an unknown name, an unparseable character reference — and each one means
    // "emit it literally and move on". Restructuring them into a single exit would need a
    // flag variable per case, which is how a decoder starts dropping characters.
    public fun unescape(raw: String): String {
        if ('&' !in raw) return raw
        return buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val character = raw[index]
                if (character != '&') {
                    append(character)
                    index++
                    continue
                }
                val end = raw.indexOf(';', index + 1)
                if (end < 0) {
                    append(character)
                    index++
                    continue
                }
                val entity = raw.substring(index + 1, end)
                val replacement = when {
                    entity == "amp" -> "&"

                    entity == "lt" -> "<"

                    entity == "gt" -> ">"

                    entity == "quot" -> "\""

                    entity == "apos" -> "'"

                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.drop(2).toIntOrNull(HEX_RADIX)?.let { codePointToString(it) }

                    entity.startsWith("#") ->
                        entity.drop(1).toIntOrNull(DECIMAL_RADIX)?.let { codePointToString(it) }

                    else -> null
                }
                if (replacement == null) {
                    append(character)
                    index++
                } else {
                    append(replacement)
                    index = end + 1
                }
            }
        }
    }

    private fun codePointToString(codePoint: Int): String? = when {
        codePoint in 0..MAX_BMP -> codePoint.toChar().toString()

        codePoint in (MAX_BMP + 1)..MAX_CODE_POINT -> {
            val offset = codePoint - SUPPLEMENTARY_BASE
            val high = (SURROGATE_HIGH_BASE + (offset shr HALF_BITS)).toChar()
            val low = (SURROGATE_LOW_BASE + (offset and HALF_MASK)).toChar()
            "$high$low"
        }

        else -> null
    }

    private const val MAX_BMP = 0xFFFF
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val SUPPLEMENTARY_BASE = 0x10000
    private const val SURROGATE_HIGH_BASE = 0xD800
    private const val SURROGATE_LOW_BASE = 0xDC00
    private const val HALF_BITS = 10
    private const val HALF_MASK = 0x3FF

    /** Escape a string for use as XML character data. Used when building request bodies. */
    public fun escape(raw: String): String = buildString(raw.length) {
        for (character in raw) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }
}
