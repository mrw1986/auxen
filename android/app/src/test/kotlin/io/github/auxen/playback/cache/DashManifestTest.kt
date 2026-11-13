package io.github.auxen.playback.cache

import java.io.IOException
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestTest {

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResource(path)) { "missing fixture $path" }.readText()

    private val tidalFixture get() = resource("dash/tidal_hires_manifest.mpd")

    private fun template(
        timeline: String = """<S d="10" r="2"/><S d="5"/>""",
        startNumber: String? = "1",
        media: String = MEDIA,
        initialization: String = INIT,
    ): String {
        val start = startNumber?.let { " startNumber=\"$it\"" }.orEmpty()
        return "<SegmentTemplate timescale=\"96000\" initialization=\"$initialization\" media=\"$media\"$start>" +
            "<SegmentTimeline>$timeline</SegmentTimeline></SegmentTemplate>"
    }

    private fun mpd(
        template: String = template(),
        mpdChildren: String = "",
        representationChildren: String = "",
        extraRepresentations: String = "",
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
        |<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">$mpdChildren
        |  <Period id="0">
        |    <AdaptationSet id="0" mimeType="audio/mp4">
        |      <Representation id="a" bandwidth="1">$representationChildren$template</Representation>
        |      $extraRepresentations
        |    </AdaptationSet>
        |  </Period>
        |</MPD>
        """.trimMargin()

    @Test
    fun `parses the Tidal fixture init and segment count`() {
        val list = parseSegmentList(tidalFixture)

        assertEquals("https://cdn.example/mediatracks/ASSET/0.mp4?token=x", list.initUrl)
        assertEquals(80, list.media.size)
    }

    @Test
    fun `segment numbers start at startNumber and are contiguous`() {
        assertEquals((1..80).toList(), parseSegmentList(tidalFixture).media.map { it.first })
        assertEquals(
            listOf(5, 6, 7, 8),
            parseSegmentList(mpd(template(startNumber = "5"))).media.map { it.first },
        )
    }

    @Test
    fun `segment URLs substitute Number`() {
        val media = parseSegmentList(tidalFixture).media

        assertEquals("https://cdn.example/mediatracks/ASSET/1.mp4?token=x", media.first().second)
        assertEquals("https://cdn.example/mediatracks/ASSET/80.mp4?token=x", media.last().second)
        assertTrue(media.none { "\$Number\$" in it.second })
    }

    @Test
    fun `S elements with r expand to 1 + r segments`() {
        val list = parseSegmentList(mpd(template(timeline = """<S d="10" r="2"/><S d="5"/>""")))

        assertEquals(4, list.media.size)
    }

    @Test
    fun `missing startNumber defaults to 1`() {
        val list = parseSegmentList(mpd(template(startNumber = null)))

        assertEquals(1, list.media.first().first)
    }

    @Test
    fun `negative r is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(timeline = """<S d="10" r="-1"/>""")))
        }
    }

    @Test
    fun `exactly MAX_SEGMENTS segments are accepted`() {
        val list = parseSegmentList(mpd(template(timeline = """<S d="1" r="${MAX_SEGMENTS - 1}"/>""")))

        assertEquals(MAX_SEGMENTS, list.media.size)
    }

    @Test
    fun `empty and oversized timelines are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(timeline = "")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(timeline = """<S d="10" r="${MAX_SEGMENTS}"/>""")))
        }
    }

    @Test
    fun `unsupported template identifiers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(media = "https://cdn.example/a/\$Time\$.mp4")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(media = "https://cdn.example/\$RepresentationID\$/\$Number\$.mp4")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(media = "https://cdn.example/\$Bandwidth\$/\$Number\$.mp4")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(media = "https://cdn.example/a/\$Number%05d\$.mp4")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(initialization = "https://cdn.example/\$RepresentationID\$/0.mp4")))
        }
    }

    @Test
    fun `missing initialization is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(template(initialization = "")))
        }
    }

    @Test
    fun `an MPD with more than one Representation is rejected`() {
        val second = "<Representation id=\"b\" bandwidth=\"2\">${template()}</Representation>"

        assertThrows(IllegalArgumentException::class.java) {
            parseSegmentList(mpd(extraRepresentations = second))
        }
    }

    @Test
    fun `DOCTYPE and malformed XML are rejected`() {
        val withDoctype = mpd().replace("<MPD ", "<!DOCTYPE MPD [<!ENTITY t \"x\">]><MPD ")

        assertThrows(IllegalArgumentException::class.java) { parseSegmentList(withDoctype) }
        assertThrows(IllegalArgumentException::class.java) { parseSegmentList("<MPD><Period>") }
    }

    @Test
    fun `parse errors keep segment URLs out of the exception chain`() {
        // Android's KXml prints the current tag's attributes into its parse errors, and Xerces
        // echoes entity names, so a chained cause can carry a token.
        val truncated = mpd(template(media = "https://cdn.example/a/\$Number\$.mp4?token=SECRET"))
            .substringBefore("<SegmentTimeline>")
        val badEntity = mpd(template(media = "https://cdn.example/a/\$Number\$.mp4?p=1&SECRET=x"))

        listOf(truncated, badEntity).forEach { xml ->
            val error = assertThrows(IllegalArgumentException::class.java) { parseSegmentList(xml) }
            generateSequence(error as Throwable) { it.cause }.forEach { cause ->
                assertFalse(cause.toString(), cause.toString().contains("SECRET"))
            }
        }
    }

    @Test
    fun `segment list toString leaves the URLs out`() {
        val list = parseSegmentList(tidalFixture)

        assertFalse(list.toString().contains("token="))
        assertTrue(list.toString().contains("80"))
    }

    @Test
    fun `localizing an MPD it can't parse is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            localizeMpd(mpd(template(media = "https://cdn.example/a/\$Time\$.mp4")))
        }
    }

    @Test
    fun `localized MPD points at local file names and keeps the timeline`() {
        val localizedXml = localizeMpd(tidalFixture)
        val localized = parseSegmentList(localizedXml)
        val spikeProven = parseSegmentList(resource("dash/local_flac/manifest.mpd"))

        assertEquals(INIT_FILE, localized.initUrl)
        assertEquals("seg_1.mp4", localized.media.first().second)
        assertEquals(80, localized.media.size)
        assertTrue(localized.media.all { (number, name) -> name == segmentFileName(number) })
        assertEquals(spikeProven.initUrl, localized.initUrl)
        assertEquals(spikeProven.media.first().second, localized.media.first().second)
        assertFalse(localizedXml.contains("token="))
        val root = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(localizedXml.byteInputStream())
            .documentElement
        assertEquals("urn:mpeg:dash:schema:mpd:2011", root.namespaceURI)
    }

    @Test
    fun `localized MPD drops BaseURL elements`() {
        val withBaseUrls = mpd(
            mpdChildren = "<BaseURL>https://x/</BaseURL>",
            representationChildren = "<BaseURL>https://x/rep/</BaseURL>",
        )

        val localized = localizeMpd(withBaseUrls)

        assertFalse(localized.contains("BaseURL"))
        assertEquals(INIT_FILE, parseSegmentList(localized).initUrl)
    }

    @Test
    fun `decodes a base64 data URI`() {
        val xml = "<MPD type=\"static\"/>"
        val uri = DATA_URI_PREFIX +
            Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))

        assertEquals(xml, decodeDataUriMpd(uri))
    }

    @Test
    fun `decodes base64 split across lines`() {
        val xml = "<MPD type=\"static\"><Period id=\"0\"/></MPD>"
        val encoded = Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8)).chunked(8)

        assertEquals(xml, decodeDataUriMpd(DATA_URI_PREFIX + encoded.joinToString("\n")))
    }

    @Test
    fun `rejects a non-data URI`() {
        assertThrows(IOException::class.java) { decodeDataUriMpd("https://x") }
    }

    @Test
    fun `rejects a data URI whose payload isn't base64`() {
        assertThrows(IOException::class.java) { decodeDataUriMpd(DATA_URI_PREFIX + "@@@") }
        assertThrows(IOException::class.java) { decodeDataUriMpd(DATA_URI_PREFIX + "Q") }
    }

    private companion object {
        const val INIT = "https://cdn.example/a/0.mp4?token=x"
        const val MEDIA = "https://cdn.example/a/\$Number\$.mp4?token=x"
        const val DATA_URI_PREFIX = "data:application/dash+xml;base64,"
    }
}
