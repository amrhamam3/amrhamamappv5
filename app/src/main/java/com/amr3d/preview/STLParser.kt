package com.amr3d.preview

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the parsed geometry of an STL file.
 * vertices: flat array of x,y,z per vertex
 * normals: flat array of nx,ny,nz per vertex (one normal per triangle, repeated for each of its 3 vertices)
 * triangleCount: number of triangles
 */
data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray, // [minX, minY, minZ]
    val maxBounds: FloatArray, // [maxX, maxY, maxZ]
    val isWatertightHint: Boolean // basic heuristic, not a full manifold check
)

class STLParseException(message: String) : Exception(message)

object STLParser {

    /**
     * Entry point: detects ASCII vs Binary STL and parses accordingly.
     */
    fun parse(context: Context, uri: Uri): STLModel {
        val resolver = context.contentResolver

        // Read the whole file into memory first (STL files for CNC parts are usually
        // small/medium; for very large files a streaming approach would be needed).
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw STLParseException("تعذر فتح الملف")

        if (bytes.isEmpty()) {
            throw STLParseException("الملف فارغ")
        }

        return if (isAsciiSTL(bytes)) {
            parseAscii(bytes)
        } else {
            parseBinary(bytes)
        }
    }

    /**
     * Heuristic: ASCII STL files start with "solid" (case-insensitive) AND contain "facet"
     * shortly after. Some binary files also start with "solid" in their header by mistake,
     * so we double check for the "facet normal" token, and also validate via expected
     * binary size as a fallback.
     */
    private fun isAsciiSTL(bytes: ByteArray): Boolean {
        val headerLen = minOf(bytes.size, 512)
        val header = String(bytes, 0, headerLen, Charsets.US_ASCII).trim()

        if (!header.lowercase().startsWith("solid")) {
            return false
        }

        // Check if the binary-size formula matches; if it matches well, treat as binary
        // even if it starts with "solid" (rare but happens with some exporters).
        if (bytes.size >= 84) {
            val triCountFromHeader = ByteBuffer.wrap(bytes, 80, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val expectedBinarySize = 84L + (triCountFromHeader.toLong() * 50L)
            if (expectedBinarySize == bytes.size.toLong()) {
                return false
            }
        }

        // Look for "facet" within the first chunk to confirm ASCII structure
        val sampleLen = minOf(bytes.size, 4096)
        val sample = String(bytes, 0, sampleLen, Charsets.US_ASCII)
        return sample.contains("facet", ignoreCase = true)
    }

    private fun parseBinary(bytes: ByteArray): STLModel {
        if (bytes.size < 84) {
            throw STLParseException("ملف STL (Binary) تالف أو غير مكتمل")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80) // skip 80-byte header
        val triangleCount = buffer.int

        val expectedSize = 84L + (triangleCount.toLong() * 50L)
        if (expectedSize > bytes.size.toLong()) {
            throw STLParseException(
                "عدد المثلثات في الملف ($triangleCount) لا يتطابق مع حجم الملف — الملف قد يكون تالفًا"
            )
        }
        if (triangleCount <= 0) {
            throw STLParseException("الملف لا يحتوي على أي مثلثات صالحة")
        }

        val vertices = FloatArray(triangleCount * 3 * 3)
        val normals = FloatArray(triangleCount * 3 * 3)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var vIdx = 0
        var nIdx = 0

        for (t in 0 until triangleCount) {
            val nx = buffer.float
            val ny = buffer.float
            val nz = buffer.float

            // 3 vertices per triangle
            for (v in 0 until 3) {
                val x = buffer.float
                val y = buffer.float
                val z = buffer.float

                vertices[vIdx++] = x
                vertices[vIdx++] = y
                vertices[vIdx++] = z

                normals[nIdx++] = nx
                normals[nIdx++] = ny
                normals[nIdx++] = nz

                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
            }

            buffer.short // attribute byte count, usually 0 — skip
        }

        return STLModel(
            vertices = vertices,
            normals = normals,
            triangleCount = triangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (triangleCount % 2 == 0)
        )
    }

    private fun parseAscii(bytes: ByteArray): STLModel {
        val text = String(bytes, Charsets.US_ASCII)
        val lines = text.lineSequence()

        val vertexList = ArrayList<Float>(1024)
        val normalList = ArrayList<Float>(1024)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var curNx = 0f; var curNy = 0f; var curNz = 0f
        var triangleCount = 0
        var vertsInCurrentFacet = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.startsWith("facet normal", ignoreCase = true) -> {
                    val parts = line.split(Regex("\\s+"))
                    // ["facet", "normal", x, y, z]
                    if (parts.size >= 5) {
                        curNx = parts[2].toFloatOrNull() ?: 0f
                        curNy = parts[3].toFloatOrNull() ?: 0f
                        curNz = parts[4].toFloatOrNull() ?: 0f
                    }
                    vertsInCurrentFacet = 0
                }
                line.startsWith("vertex", ignoreCase = true) -> {
                    val parts = line.split(Regex("\\s+"))
                    // ["vertex", x, y, z]
                    if (parts.size >= 4) {
                        val x = parts[1].toFloatOrNull()
                            ?: throw STLParseException("قيمة غير صالحة في الملف عند: $line")
                        val y = parts[2].toFloatOrNull()
                            ?: throw STLParseException("قيمة غير صالحة في الملف عند: $line")
                        val z = parts[3].toFloatOrNull()
                            ?: throw STLParseException("قيمة غير صالحة في الملف عند: $line")

                        vertexList.add(x); vertexList.add(y); vertexList.add(z)
                        normalList.add(curNx); normalList.add(curNy); normalList.add(curNz)

                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (z < minZ) minZ = z
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        if (z > maxZ) maxZ = z

                        vertsInCurrentFacet++
                    }
                }
                line.startsWith("endfacet", ignoreCase = true) -> {
                    if (vertsInCurrentFacet == 3) {
                        triangleCount++
                    }
                }
            }
        }

        if (triangleCount == 0) {
            throw STLParseException("لم يتم العثور على أي مثلثات صالحة في ملف الـ ASCII STL")
        }

        return STLModel(
            vertices = vertexList.toFloatArray(),
            normals = normalList.toFloatArray(),
            triangleCount = triangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (triangleCount % 2 == 0)
        )
    }
}
