package com.setholson.ereader

import com.setholson.ereader.data.PdfTextExtractionResult
import com.setholson.ereader.data.PdfTextExtractor
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PdfTextExtractorTest {

    @Test
    fun extractsMultiPagePdfWithHeadingsKerningAndDehyphenation() {
        val page1Stream = """
            BT
            /F1 18 Tf
            72 740 Td
            (Chapter 1: Into the Light) Tj
            /F1 12 Tf
            0 -32 Td
            [(It was a bright cold day in April, and the clocks were strik-) -20 ()] TJ
            0 -15 Td
            (ing thirteen across the valley.) Tj
            0 -30 Td
            (A second paragraph starts clearly below.) Tj
            ET
        """.trimIndent()

        val page2Stream = """
            BT
            /F1 12 Tf
            72 720 Td
            [(Hello) -260 (World) -260 (from) -260 (Page) -260 (Two!)] TJ
            ET
        """.trimIndent()

        val pdf = buildSimplePdf(
            objects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Resources << /Font << /F1 5 0 R >> >> >>",
                3 to "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>",
                4 to "<< /Type /Page /Parent 2 0 R /Contents 7 0 R >>",
                5 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                6 to streamObject(page1Stream.toByteArray(Charsets.ISO_8859_1), compress = false),
                7 to streamObject(page2Stream.toByteArray(Charsets.ISO_8859_1), compress = false),
            ),
            rootObj = 1,
        )

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val result = PdfTextExtractor.extractBytes(
            bytes = pdf,
            onProgress = { cur, total -> progressCalls.add(cur to total) },
        )

        val success = assertIs<PdfTextExtractionResult.Success>(result)
        assertEquals(2, success.book.pageCount)
        assertEquals(listOf(1 to 2, 2 to 2), progressCalls)

        val p1 = success.book.pages[0]
        assertTrue(p1.blocks.size >= 3, "Expected heading + 2 paragraphs, got: ${p1.blocks}")
        assertEquals("Chapter 1: Into the Light", p1.blocks[0].text)
        assertTrue(p1.blocks[0].isHeading, "Larger font chapter line should be detected as heading")
        assertTrue(
            p1.blocks[1].text.contains("striking thirteen across the valley."),
            "Line-break hyphenation should be joined cleanly, got: ${p1.blocks[1].text}",
        )
        assertEquals("A second paragraph starts clearly below.", p1.blocks[2].text)

        val p2 = success.book.pages[1]
        assertEquals(1, p2.blocks.size)
        assertEquals("Hello World from Page Two!", p2.blocks[0].text)
    }

    @Test
    fun extractsFlateCompressedContentWithTwoByteToUnicodeCMapAndLigatures() {
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            3 beginbfchar
            <0001> <00660069>
            <0002> <201C>
            <0003> <201D>
            endbfchar
            1 beginbfrange
            <0010> <0015> <0041>
            endbfrange
            endcmap
            end
            end
        """.trimIndent()

        // <0002><0010><0011><0012><0003> -> “ABC”  and <0001><0013> -> fiD
        val content = """
            BT
            /F0 12 Tf
            72 700 Td
            <00020010001100120003> Tj
            0 -28 Td
            <00010013> Tj
            ET
        """.trimIndent()

        val pdf = buildSimplePdf(
            objects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                3 to "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F0 4 0 R >> >> /Contents 6 0 R >>",
                4 to "<< /Type /Font /Subtype /Type0 /BaseFont /CustomSubset /ToUnicode 5 0 R >>",
                5 to streamObject(cmap.toByteArray(Charsets.ISO_8859_1), compress = true),
                6 to streamObject(content.toByteArray(Charsets.ISO_8859_1), compress = true),
            ),
            rootObj = 1,
        )

        val result = PdfTextExtractor.extractBytes(pdf)
        val success = assertIs<PdfTextExtractionResult.Success>(result)
        assertEquals(1, success.book.pageCount)
        val texts = success.book.pages[0].blocks.map { it.text }
        assertTrue(texts.any { it.contains("\u201CABC\u201D") }, "Expected smart-quoted ABC, got: $texts")
        assertTrue(texts.any { it.contains("fiD") }, "Expected ligature fiD, got: $texts")
    }

    @Test
    fun extractsTextFromPdf15CompressedObjectStream() {
        // Put Page (3 0) and Font (4 0) inside a compressed /ObjStm (10 0)
        val obj3Body = "<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
        val obj4Body = "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>"
        val obj3Offset = 0
        val obj4Offset = obj3Body.length + 1
        val header = "3 $obj3Offset 4 $obj4Offset "
        val objStmPayload = (header + obj3Body + "\n" + obj4Body).toByteArray(Charsets.ISO_8859_1)

        val compressedObjStm = flateCompress(objStmPayload)
        val objStmDictAndStream = buildByteStreamObject(
            extraDictEntries = "/Type /ObjStm /N 2 /First ${header.length} /Filter /FlateDecode",
            rawBytes = compressedObjStm,
        )

        val content = "BT /F1 12 Tf 72 720 Td (Modern PDF 1.5 compressed objects work!) Tj ET"

        val pdf = buildRawPdf(
            objects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1),
                5 to streamObject(content.toByteArray(Charsets.ISO_8859_1), compress = true),
                10 to objStmDictAndStream,
            ),
            trailer = "<< /Root 1 0 R >>",
        )

        val result = PdfTextExtractor.extractBytes(pdf)
        val success = assertIs<PdfTextExtractionResult.Success>(result)
        assertEquals(1, success.book.pageCount)
        assertEquals(
            "Modern PDF 1.5 compressed objects work!",
            success.book.pages[0].blocks.first().text,
        )
    }

    @Test
    fun returnsNoTextFoundForScannedImageOnlyPdf() {
        val imageOnlyContent = "q 612 0 0 792 0 0 cm /Im0 Do Q"
        val pdf = buildSimplePdf(
            objects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                3 to "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>",
                4 to streamObject(imageOnlyContent.toByteArray(Charsets.ISO_8859_1), compress = false),
            ),
            rootObj = 1,
        )

        val result = PdfTextExtractor.extractBytes(pdf)
        assertIs<PdfTextExtractionResult.NoTextFound>(result)
    }

    @Test
    fun detectsEncryptedPdf() {
        val pdf = buildRawPdf(
            objects = listOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1),
                2 to "<< /Type /Pages /Kids [] /Count 0 >>".toByteArray(Charsets.ISO_8859_1),
                9 to "<< /Filter /Standard /V 2 /R 3 >>".toByteArray(Charsets.ISO_8859_1),
            ),
            trailer = "<< /Root 1 0 R /Encrypt 9 0 R >>",
        )

        val result = PdfTextExtractor.extractBytes(pdf)
        assertIs<PdfTextExtractionResult.Encrypted>(result)
    }

    // -------------------------------------------------------------------------
    // Minimal synthetic PDF builder helpers for unit tests
    // -------------------------------------------------------------------------

    private fun buildSimplePdf(objects: List<Pair<Int, Any>>, rootObj: Int): ByteArray {
        val rawObjects = objects.map { (id, body) ->
            id to when (body) {
                is ByteArray -> body
                is String -> body.toByteArray(Charsets.ISO_8859_1)
                else -> error("Unsupported body")
            }
        }
        return buildRawPdf(rawObjects, "<< /Root $rootObj 0 R >>")
    }

    private fun buildRawPdf(objects: List<Pair<Int, ByteArray>>, trailer: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        for ((id, body) in objects) {
            out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("trailer\n$trailer\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun streamObject(data: ByteArray, compress: Boolean): ByteArray {
        return if (compress) {
            val deflated = flateCompress(data)
            buildByteStreamObject("/Filter /FlateDecode", deflated)
        } else {
            buildByteStreamObject("", data)
        }
    }

    private fun buildByteStreamObject(extraDictEntries: String, rawBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val extra = if (extraDictEntries.isBlank()) "" else " $extraDictEntries"
        out.write("<< /Length ${rawBytes.size}$extra >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(rawBytes)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun flateCompress(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            if (count > 0) out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }
}
