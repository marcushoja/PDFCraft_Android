package de.pdfcraft.android

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Cylindrical paper cross-section around the finger-controlled oblique crease.
 * This is Canvas bitmap-mesh geometry, not an OpenGL surface or a whole-sheet transform.
 */
internal class PdfCurvedMesh(private val fold: PdfCurlGeometry) {
    data class Vertex(val x: Float, val y: Float, val depth: Float, val back: Boolean, val light: Float)

    // Radius grows with the pull, producing a visibly rounded roll rather than a rigid flap.
    val radius = min(90f, max(.1f, fold.length * .12f))

    fun project(x: Float, y: Float): Vertex {
        val out=FloatArray(5)
        projectInto(x,y,out)
        return Vertex(out[0],out[1],out[2],out[3]>0f,out[4])
    }

    /** Allocation-free draw-loop variant: x, y, depth, back-facing flag, light. */
    fun projectInto(x:Float,y:Float,out:FloatArray) {
        out[0]=x;out[1]=y;out[2]=0f;out[3]=0f;out[4]=1f
        if (fold.length < .001f) return
        val d = (x - fold.mx) * fold.nx + (y - fold.my) * fold.ny
        val half = (PI * radius / 2).toFloat()
        if (d <= -half) return
        val theta = min(PI.toFloat(), (d + half) / radius)
        val cosine=if(d>=half) -1f else cos(theta)
        val projected = if (d < half) -half + radius * sin(theta) else -d
        val depth = if (d < half) radius * (1f - cosine) else 2f * radius
        // Modest orthographic foreshortening along the crease's tangent gives lifted paper depth.
        val tangent = (x - fold.mx) * -fold.ny + (y - fold.my) * fold.nx
        val perspective = 1f / (1f + depth / 1600f)
        val tx = -fold.ny * tangent * (perspective - 1f)
        val ty = fold.nx * tangent * (perspective - 1f)
        out[0]=x + fold.nx * (projected - d) + tx
        out[1]=y + fold.ny * (projected - d) + ty
        out[2]=depth;out[3]=if(theta>PI/2) 1f else 0f
        out[4]=(.66f + .34f * kotlin.math.abs(cosine)).coerceIn(0f,1f)
    }
}
