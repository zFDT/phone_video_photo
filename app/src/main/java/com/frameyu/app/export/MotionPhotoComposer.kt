package com.frameyu.app.export

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** 按 Motion Photo 1.0 规范把 JPEG 主图和 MP4 视频组合为单文件。 */
object MotionPhotoComposer {
    private const val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val JPEG_SOI = 0xD8
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9

    fun compose(jpeg: ByteArray, mp4: ByteArray): ByteArray {
        require(jpeg.size >= 4 && jpeg[0].toInt() and 0xFF == 0xFF && jpeg[1].toInt() and 0xFF == JPEG_SOI) {
            "主图不是有效 JPEG"
        }
        require(mp4.isNotEmpty()) { "Motion Photo 视频不能为空" }
        val xmp = xmpPacket(mp4.size).toByteArray(StandardCharsets.UTF_8)
        val identifier = XMP_IDENTIFIER.toByteArray(StandardCharsets.UTF_8)
        val segmentLength = 2 + identifier.size + xmp.size
        require(segmentLength <= 0xFFFF) { "Motion Photo XMP 元数据过大" }

        val insertionIndex = findMarker(jpeg, JPEG_SOS)
        require(insertionIndex > 0) { "JPEG 缺少图像数据起始标记" }
        val primaryEnd = findLastMarker(jpeg, JPEG_EOI)
        require(primaryEnd >= insertionIndex) { "JPEG 缺少结束标记" }

        val output = ByteArrayOutputStream(jpeg.size + mp4.size + identifier.size + xmp.size + 8)
        output.write(jpeg, 0, insertionIndex)
        output.write(0xFF)
        output.write(0xE1)
        output.write((segmentLength ushr 8) and 0xFF)
        output.write(segmentLength and 0xFF)
        output.write(identifier)
        output.write(xmp)
        output.write(jpeg, insertionIndex, primaryEnd + 2 - insertionIndex)
        output.write(mp4)
        return output.toByteArray()
    }

    private fun xmpPacket(videoLength: Int): String = """
        <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
              xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
              xmlns:Container="http://ns.google.com/photos/1.0/container/"
              xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              Camera:MotionPhoto="1"
              Camera:MotionPhotoVersion="1"
              Camera:MotionPhotoPresentationTimestampUs="-1">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource" Item:Mime="image/jpeg" Item:Semantic="Primary"/>
                  <rdf:li rdf:parseType="Resource" Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength"/>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    private fun findMarker(bytes: ByteArray, marker: Int): Int {
        for (index in 2 until bytes.size - 1) {
            if (bytes[index].toInt() and 0xFF == 0xFF && bytes[index + 1].toInt() and 0xFF == marker) {
                return index
            }
        }
        return -1
    }

    private fun findLastMarker(bytes: ByteArray, marker: Int): Int {
        for (index in bytes.size - 2 downTo 0) {
            if (bytes[index].toInt() and 0xFF == 0xFF && bytes[index + 1].toInt() and 0xFF == marker) {
                return index
            }
        }
        return -1
    }
}
