package com.xcology.livecamera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixel-processing core independent of Android. No image data leaves the device. */
data class Point2(val x: Float, val y: Float)
data class FaceArea(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val leftEye: Point2? = null, val rightEye: Point2? = null,
    val mouth: Point2? = null, val cheekCr: Float = 149f, val cheekCb: Float = 111f
)

data class ToneSettings(
    val intensity: Float = 0.85f, val warmth: Float = 0.45f, val targetLightness: Float = 0.75f
)

object SkinToneEngine {
    fun transform(
        source: IntArray,
        width: Int,
        height: Int,
        personMask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        settings: ToneSettings,
        face: FaceArea? = null,
        semanticSkinMask: Boolean = false
    ): IntArray {
        require(width > 0 && height > 0 && source.size == width * height)
        require(maskWidth > 0 && maskHeight > 0 && personMask.size == maskWidth * maskHeight)
        val out = source.copyOf()
        val intensity = settings.intensity.coerceIn(0f, 1f)
        if (intensity < 0.005f) return out
        val warmth = settings.warmth.coerceIn(0f, 1f)
        for (y in 0 until height) {
            val maskY = ((y.toFloat() + 0.5f) * maskHeight / height - 0.5f).coerceIn(0f, (maskHeight - 1).toFloat())
            val y0 = maskY.toInt(); val y1 = min(y0 + 1, maskHeight - 1); val ty = maskY - y0
            for (x in 0 until width) {
                val maskX = ((x.toFloat() + 0.5f) * maskWidth / width - 0.5f).coerceIn(0f, (maskWidth - 1).toFloat())
                val x0 = maskX.toInt(); val x1 = min(x0 + 1, maskWidth - 1); val tx = maskX - x0
                val a = personMask[y0 * maskWidth + x0] * (1f - tx) + personMask[y0 * maskWidth + x1] * tx
                val b = personMask[y1 * maskWidth + x0] * (1f - tx) + personMask[y1 * maskWidth + x1] * tx
                val foreground = (a * (1f - ty) + b * ty)
                if (foreground <= 0.42f) continue

                val idx = y * width + x
                val argb = source[idx]
                val r = ((argb shr 16) and 255).toFloat()
                val g = ((argb shr 8) and 255).toFloat()
                val blue = (argb and 255).toFloat()
                val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * blue
                val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * blue
                val maximum = max(r, max(g, blue))
                val minimum = min(r, min(g, blue))
                val chroma = maximum - minimum

                // Broad chroma-based skin estimation works on many skin tones, including darker tones.
                // A face-derived reference narrows the classifier for this person. It is not a dedicated
                // skin segmentation network, and may still miss skin or affect similarly-colored clothing.
                val crRange = smooth(115f, 137f, cr) * (1f - smooth(178f, 195f, cr))
                val cbRange = smooth(65f, 86f, cb) * (1f - smooth(137f, 154f, cb))
                val primary = crRange * cbRange * smooth(2f, 12f, chroma) *
                    smooth(12f, 34f, maximum) * smooth(0.68f, 0.96f, r / (g + 1f))
                val faceWeight = if (face != null) ellipseWeight(x.toFloat(), y.toFloat(), face) else 0f
                val reference = if (face != null) {
                    val d = sqrt((cr - face.cheekCr) * (cr - face.cheekCr) +
                        (cb - face.cheekCb) * (cb - face.cheekCb))
                    1f - smooth(13f, 36f, d)
                } else 0f
                var skin = if (semanticSkinMask) 0.97f else
                    max(primary, primary * 0.45f + reference * faceWeight * 0.5f)
                if (face != null) {
                    // Preserve eyes and mouth where landmarks are available.
                    val radius = max(4f, (face.right - face.left) * 0.095f)
                    face.leftEye?.let { skin *= smooth(radius * 0.52f, radius, distance(x.toFloat(), y.toFloat(), it)) }
                    face.rightEye?.let { skin *= smooth(radius * 0.52f, radius, distance(x.toFloat(), y.toFloat(), it)) }
                    face.mouth?.let { mouth ->
                        val lipRadius = max(4f, (face.right - face.left) * 0.13f)
                        skin *= smooth(lipRadius * 0.55f, lipRadius, distance(x.toFloat(), y.toFloat(), mouth))
                    }
                }
                val softForeground = smooth(0.42f, 0.82f, foreground)
                val blend = (intensity * skin * softForeground).coerceIn(0f, 1f)
                if (blend <= 0.001f) continue

                // Tone mapping retains per-pixel brightness variations rather than flat recoloring.
                val luma = 0.299f * r + 0.587f * g + 0.114f * blue
                val lightY = (155f + 52f * settings.targetLightness.coerceIn(0f, 1f) +
                    0.42f * (luma - 75f)).coerceIn(136f, 236f)
                val desiredR = (lightY + 7f + 22f * warmth).coerceIn(0f, 255f)
                val desiredG = (lightY + 1f + 2f * warmth).coerceIn(0f, 255f)
                val desiredB = (lightY + 8f - 20f * warmth).coerceIn(0f, 255f)
                fun channel(a: Float, target: Float) = (a + (target - a) * blend).toInt().coerceIn(0, 255)
                out[idx] = (argb and -0x1000000) or
                    (channel(r, desiredR) shl 16) or (channel(g, desiredG) shl 8) or channel(blue, desiredB)
            }
        }
        return out
    }

    /** Extract a reference from face cheek patches; deweights dark hair and white highlights. */
    fun sampleCheeks(pixels: IntArray, width: Int, height: Int,
                     cheekCenters: List<Point2>): Pair<Float, Float>? {
        var crSum = 0f; var cbSum = 0f; var count = 0
        for (p in cheekCenters) {
            val cx = p.x.toInt(); val cy = p.y.toInt()
            for (yy in (cy-3).coerceAtLeast(0)..(cy+3).coerceAtMost(height-1)) {
                for (xx in (cx-3).coerceAtLeast(0)..(cx+3).coerceAtMost(width-1)) {
                    val rgb = pixels[yy * width + xx]
                    val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
                    if (max(r, max(g, b)) < 22 || max(r, max(g, b)) - min(r, min(g, b)) < 4) continue
                    crSum += 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
                    cbSum += 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                    count++
                }
            }
        }
        return if (count >= 12) Pair(crSum / count, cbSum / count) else null
    }

    private fun ellipseWeight(x: Float, y: Float, f: FaceArea): Float {
        val w = f.right - f.left; val h = f.bottom - f.top
        if (w < 1f || h < 1f) return 0f
        val dx = (x - (f.left + f.right) / 2f) / (w * 0.48f)
        val dy = (y - (f.top + h * 0.55f)) / (h * 0.53f)
        return 1f - smooth(0.74f, 1.02f, dx * dx + dy * dy)
    }
    private fun distance(x: Float, y: Float, p: Point2): Float = sqrt((x-p.x)*(x-p.x)+(y-p.y)*(y-p.y))
    private fun smooth(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
}
