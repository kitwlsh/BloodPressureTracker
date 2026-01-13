package com.wnc.bloodpressuretracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import com.wnc.bloodpressuretracker.databinding.ActivityMainBinding
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BloodPressureViewModel by viewModels()
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private val ocrProcessor = OCRProcessor()

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val date = getPhotoDate(it)
            val bitmap = uriToBitmap(it)
            if (bitmap != null) processImage(bitmap, date)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = BPAdapter { id ->
            viewModel.delete(id)
            Toast.makeText(this, "기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.allRecords.observe(this) { records ->
            adapter.submitList(records)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnGraph.setOnClickListener {
            val intent = Intent(this, GraphActivity::class.java)
            startActivity(intent)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun getPhotoDate(uri: Uri): Long {
        var date = System.currentTimeMillis()
        val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    date = cursor.getLong(columnIndex)
                }
            }
        } catch (e: Exception) { Log.e("MainActivity", "Error getting date", e) }
        return date
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    if (bitmap != null) processImage(bitmap, System.currentTimeMillis())
                }
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(baseContext, "촬영 실패", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImage(bitmap: Bitmap, photoDate: Long) {
        // 인식을 돕기 위해 이미지 크기 조정 (최대 1600px)
        val resizedBitmap = resizeBitmap(bitmap, 1600)
        
        ocrProcessor.processImage(resizedBitmap, object : OCRProcessor.OnResultListener {
            override fun onSuccess(systolic: Int, diastolic: Int, pulse: Int, timeStr: String?) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = photoDate
                
                timeStr?.let {
                    try {
                        val parts = it.split(":")
                        if (parts.size == 2) {
                            cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                            cal.set(Calendar.MINUTE, parts[1].toInt())
                            cal.set(Calendar.SECOND, 0)
                        }
                    } catch (e: Exception) {}
                }

                val record = BloodPressureRecord(
                    systolic = systolic, 
                    diastolic = diastolic, 
                    pulse = pulse,
                    timestamp = cal.timeInMillis
                )
                viewModel.insert(record)
                Toast.makeText(this@MainActivity, "저장 성공: $systolic/$diastolic 맥박:$pulse", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(e: Exception) {
                Toast.makeText(this@MainActivity, "인식 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun resizeBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        if (source.width <= maxDimension && source.height <= maxDimension) return source
        val aspectRatio = source.width.toFloat() / source.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (aspectRatio > 1) {
            targetWidth = maxDimension
            targetHeight = (maxDimension / aspectRatio).toInt()
        } else {
            targetHeight = maxDimension
            targetWidth = (maxDimension * aspectRatio).toInt()
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            val exifStream = contentResolver.openInputStream(uri)
            val exif = exifStream?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifStream?.close()

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            
            if (bitmap != null) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else null
        } catch (e: Exception) { null }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        return if (bitmap != null) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
