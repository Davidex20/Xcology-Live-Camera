package com.xcology.livecamera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Environment
import android.widget.*
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
 private val worker = Executors.newSingleThreadExecutor()
 private val busy = AtomicBoolean(false)
 private lateinit var image: ImageView
 private var strength = 0.7f
 private var latest: Bitmap? = null
 private var front = true
 private var provider: ProcessCameraProvider? = null
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
  image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
  root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
  root.addView(TextView(this).apply { text="Skin lightening intensity"; setTextColor(Color.WHITE); textSize=17f })
  root.addView(SeekBar(this).apply { max=100; progress=70; setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){ strength=p/100f }; override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){} }) })
  val row=LinearLayout(this)
  row.addView(Button(this).apply { text="Capture photo"; setOnClickListener { savePhoto() } }, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
  row.addView(Button(this).apply { text="Flip"; setOnClickListener { front=!front; startCamera() } }, LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
  root.addView(row); setContentView(root)
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startCamera() else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CAMERA),7)
 }
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g); if(r==7&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED) startCamera() }
 private fun startCamera(){ val future=ProcessCameraProvider.getInstance(this); future.addListener({
  provider=future.get(); provider?.unbindAll()
  val analysis=ImageAnalysis.Builder().setTargetResolution(android.util.Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
  analysis.setAnalyzer(worker) { frame ->
   if(!busy.compareAndSet(false,true)){frame.close();return@setAnalyzer}
   try { val b=frameToBitmap(frame); val m=Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()); if(front) postScale(-1f,1f) }; val oriented=Bitmap.createBitmap(b,0,0,b.width,b.height,m,true); val processed=lightenSkin(oriented,strength); runOnUiThread { latest=processed; image.setImageBitmap(processed) } } catch(_:Exception){} finally {frame.close();busy.set(false)}
  }
  provider?.bindToLifecycle(this,CameraSelector.Builder().requireLensFacing(if(front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK).build(),analysis)
 },ContextCompat.getMainExecutor(this)) }
 private fun frameToBitmap(proxy:ImageProxy):Bitmap { val w=proxy.width;val h=proxy.height;val y=proxy.planes[0];val u=proxy.planes[1];val v=proxy.planes[2];val nv21=ByteArray(w*h*3/2)
  for(row in 0 until h) for(col in 0 until w) nv21[row*w+col]=y.buffer.get(row*y.rowStride+col*y.pixelStride)
  for(row in 0 until h/2) for(col in 0 until w/2){ val pos=w*h+row*w+col*2;nv21[pos]=v.buffer.get(row*v.rowStride+col*v.pixelStride);nv21[pos+1]=u.buffer.get(row*u.rowStride+col*u.pixelStride) }
  val out=ByteArrayOutputStream();YuvImage(nv21,ImageFormat.NV21,w,h,null).compressToJpeg(Rect(0,0,w,h),85,out);return BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size()) }
 private fun lightenSkin(src:Bitmap,amount:Float):Bitmap {val w=src.width;val h=src.height;val pix=IntArray(w*h);src.getPixels(pix,0,w,0,0,w,h)
  for(i in pix.indices){val c=pix[i];val r=Color.red(c).toFloat();val g=Color.green(c).toFloat();val b=Color.blue(c).toFloat();val max=maxOf(r,g,b);val min=minOf(r,g,b)
   // Basic skin chroma threshold. Prototype only: can affect skin-colored objects and miss some skin tones.
   val cb=128f-0.168736f*r-0.331264f*g+0.5f*b;val cr=128f+0.5f*r-0.418688f*g-0.081312f*b
   if(cr in 130f..185f && cb in 75f..140f && r>g*0.8f && max-min>10f){ val k=amount*0.72f; val nr=r+(232f-r)*k;val ng=g+(194f-g)*k;val nb=b+(175f-b)*k;pix[i]=Color.rgb(nr.toInt().coerceIn(0,255),ng.toInt().coerceIn(0,255),nb.toInt().coerceIn(0,255)) }
  }
  return Bitmap.createBitmap(pix,w,h,Bitmap.Config.ARGB_8888) }
 private fun savePhoto(){val bmp=latest ?:return;try{val dir=getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?:filesDir;val f=File(dir,"xcology_${System.currentTimeMillis()}.jpg");f.outputStream().use{bmp.compress(Bitmap.CompressFormat.JPEG,95,it)};Toast.makeText(this,"Saved: ${f.absolutePath}",Toast.LENGTH_LONG).show()}catch(e:Exception){Toast.makeText(this,"Save failed: ${e.message}",Toast.LENGTH_LONG).show()} }
 override fun onDestroy(){super.onDestroy();worker.shutdown()}
}
