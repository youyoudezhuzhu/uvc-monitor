package com.honksoft.monmon

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Log
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.CvException
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class ImageTools(context: Context?) {
  private val rs: RenderScript?
  private val yuvToRgbIntrinsic: ScriptIntrinsicYuvToRGB

  init {
    rs = RenderScript.create(context)
    yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
  }

  fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
    val yuvType = Type.Builder(rs, Element.U8(rs)).setX(nv21.size)
    val `in` = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)
    val rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height)
    val out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT)

    `in`.copyFrom(nv21)

    yuvToRgbIntrinsic.setInput(`in`)
    yuvToRgbIntrinsic.forEach(out)

    val bmpout = createBitmap(width, height)
    out.copyTo(bmpout)

    return bmpout
  }

  fun matToBitmap(input: Mat): Bitmap? {
    var bmp: Bitmap? = null
    val rgb = Mat()
    //Imgproc.cvtColor(input, rgb, Imgproc.COLOR_BGR2RGB)
    Imgproc.cvtColor(input, rgb, Imgproc.COLOR_RGBA2RGB)

    try {
      bmp = createBitmap(rgb.cols(), rgb.rows())
      Utils.matToBitmap(rgb, bmp)
    } catch (e: CvException) {
      Log.d("Exception", e.message!!)
    }
    return bmp
  }

}