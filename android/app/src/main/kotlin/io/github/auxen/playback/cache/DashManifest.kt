package io.github.auxen.playback.cache

import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

const val INIT_FILE = "init.mp4"
const val MANIFEST_FILE = "manifest.mpd"

/** Upper bound on media segments, so a garbage `r` can't build a list that exhausts memory. */
internal const val MAX_SEGMENTS = 100_000

private const val DASH_DATA_URI_PREFIX = "data:application/dash+xml;base64,"
private const val NUMBER_TOKEN = "\$Number\$"
private const val LOCAL_MEDIA_TEMPLATE = "seg_$NUMBER_TOKEN.mp4"

// Derived from the template so the files the downloader writes always match what the manifest asks for.
fun segmentFileName(number: Int): String = LOCAL_MEDIA_TEMPLATE.replace(NUMBER_TOKEN, number.toString())

/** The init URL and every media segment of a DASH track, as (segment number, URL) in play order. */
data class DashSegmentList(val initUrl: String, val media: List<Pair<Int, String>>) {
    // The URLs carry tokens, so a logged list shows only its size.
    override fun toString(): String = "DashSegmentList(media=${media.size} segments)"
}

/**
 * Decodes Tidal's `data:application/dash+xml;base64,...` stream URI to MPD XML. Line breaks in
 * the payload are fine, matching the lenient decoder Media3's streaming path uses.
 *
 * @throws IOException when [uri] isn't a DASH data URI or its payload doesn't decode to
 *   anything. Messages leave the URI out, since a progressive stream URI carries a token.
 */
fun decodeDataUriMpd(uri: String): String {
    if (!uri.startsWith(DASH_DATA_URI_PREFIX)) throw IOException("Not a DASH data URI")
    val bytes = try {
        Base64.getMimeDecoder().decode(uri.substring(DASH_DATA_URI_PREFIX.length))
    } catch (e: IllegalArgumentException) {
        // The JDK's messages name the bad character or position, never the input, so chaining is safe.
        throw IOException("Bad base64 in DASH data URI", e)
    }
    // The MIME decoder skips characters outside the alphabet, so garbage can decode to nothing.
    if (bytes.isEmpty()) throw IOException("Empty DASH data URI")
    return String(bytes, Charsets.UTF_8)
}

/**
 * Lists the init and media segment URLs of a static, single-Representation MPD that uses
 * `SegmentTemplate` + `SegmentTimeline` with `$Number$` addressing (Tidal's Hi-Res layout).
 *
 * @throws IllegalArgumentException when the MPD is malformed or uses anything else.
 */
fun parseSegmentList(mpdXml: String): DashSegmentList {
    val template = segmentTemplate(parseMpd(mpdXml))
    val initUrl = template.getAttribute("initialization")
    require(initUrl.isNotEmpty()) { "The SegmentTemplate has no initialization" }
    require('$' !in initUrl) { "Unsupported identifier in the initialization template" }
    val media = template.getAttribute("media")
    require(NUMBER_TOKEN in media && '$' !in media.replace(NUMBER_TOKEN, "")) {
        "The media template must use \$Number\$ and nothing else"
    }
    val start = template.getAttribute("startNumber").ifEmpty { "1" }.toInt()
    val numbers = List(segmentCount(template)) { start + it }
    return DashSegmentList(initUrl, numbers.map { it to media.replace(NUMBER_TOKEN, it.toString()) })
}

/**
 * Rewrites the MPD to play from a folder holding [INIT_FILE] and [segmentFileName] files.
 * BaseURL elements go too, or Media3 would resolve the local names against the network.
 */
fun localizeMpd(mpdXml: String): String {
    // Same checks as the download path, so an MPD it can't mirror is never rewritten.
    parseSegmentList(mpdXml)
    val document = parseMpd(mpdXml)
    val template = segmentTemplate(document)
    template.setAttribute("initialization", INIT_FILE)
    template.setAttribute("media", LOCAL_MEDIA_TEMPLATE)
    // NodeList is live, so snapshot it before removing nodes.
    val baseUrls = document.getElementsByTagNameNS("*", "BaseURL").let { list ->
        List(list.length) { list.item(it) }
    }
    baseUrls.forEach { it.parentNode.removeChild(it) }
    return serialize(document)
}

private fun segmentTemplate(document: Document): Element {
    val representations = document.getElementsByTagNameNS("*", "Representation")
    require(representations.length == 1) {
        // A second Representation would stay on the network and break offline playback.
        "Expected one Representation, found ${representations.length}"
    }
    val representation = representations.item(0) as Element
    return requireNotNull(childElements(representation, "SegmentTemplate").firstOrNull()) {
        "The Representation has no SegmentTemplate"
    }
}

private fun segmentCount(template: Element): Int {
    val entries = childElements(template, "SegmentTimeline").flatMap { childElements(it, "S") }
    val count = entries.sumOf { 1L + repeatCount(it) }
    require(count in 1..MAX_SEGMENTS) { "Unsupported media segment count $count" }
    return count.toInt()
}

private fun repeatCount(entry: Element): Int {
    val raw = entry.getAttribute("r")
    if (raw.isEmpty()) return 0
    val repeat = raw.toInt()
    // r = -1 means "repeat to the end of the Period", which a static count can't express.
    require(repeat >= 0) { "Open-ended S@r=$repeat isn't supported" }
    return repeat
}

private fun childElements(parent: Element, localName: String): List<Element> {
    val nodes = parent.childNodes
    return List(nodes.length) { nodes.item(it) }
        .filterIsInstance<Element>()
        .filter { it.localName == localName }
}

private val HARDENING_FEATURES = mapOf(
    "http://apache.org/xml/features/disallow-doctype-decl" to true,
    "http://xml.org/sax/features/external-general-entities" to false,
    "http://xml.org/sax/features/external-parameter-entities" to false,
    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    XMLConstants.FEATURE_SECURE_PROCESSING to true,
)

private fun parseMpd(xml: String): Document {
    // The MPD comes from the network. Android's parser may not support the hardening features
    // below, so this check is what keeps entity declarations out on every platform. A real MPD
    // has no DTD.
    require("<!DOCTYPE" !in xml) { "MPD has a DOCTYPE declaration" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    HARDENING_FEATURES.forEach { (feature, enabled) ->
        try {
            factory.setFeature(feature, enabled)
        } catch (e: ParserConfigurationException) {
            // Unsupported on this platform's parser. The DOCTYPE check above still applies.
        }
    }
    return try {
        factory.newDocumentBuilder()
            .apply { setErrorHandler(QuietErrorHandler) }
            .parse(InputSource(StringReader(xml)))
    } catch (e: SAXParseException) {
        // No cause: Android's KXml puts the current tag's attributes, token URLs included, in the
        // message, and Xerces echoes entity names. The position is enough to debug.
        throw IllegalArgumentException("Malformed MPD at ${e.lineNumber}:${e.columnNumber}")
    } catch (e: SAXException) {
        throw IllegalArgumentException("Malformed MPD")
    } catch (e: RuntimeException) {
        // Android's DocumentBuilderImpl can rethrow a parser RuntimeException unwrapped.
        throw IllegalArgumentException("Malformed MPD")
    }
}

// The default handler prints parse errors to stderr before throwing. We report them by throwing.
private object QuietErrorHandler : ErrorHandler {
    override fun warning(exception: SAXParseException) = Unit
    override fun error(exception: SAXParseException) = throw exception
    override fun fatalError(exception: SAXParseException) = throw exception
}

private fun serialize(document: Document): String {
    val writer = StringWriter()
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    }.transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
}
