package com.wnc.bloodpressuretracker

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wnc.bloodpressuretracker.databinding.ActivityMainBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: BloodPressureViewModel by viewModels()
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    
    private val localRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // BuildConfig에서 API 키를 안전하게 가져옵니다.
    private val CLOUD_VISION_API_KEY = BuildConfig.CLOUD_VISION_API_KEY

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val date = getPhotoDate(it)
            val bitmap = uriToBitmap(it)
            if (bitmap != null) processImage(bitmap, date)
        }
    }

    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importCsv(it) }
    }

    private val createCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        uri?.let { exportCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = BPAdapter(
            onDelete = { id ->
                viewModel.delete(id)
                Toast.makeText(this, "기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            },
            onEdit = { record ->
                showEditDialog(null, record.timestamp, record)
            }
        )
        
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
        binding.btnDoctorRecord.setOnClickListener {
            val intent = Intent(this, DoctorRecordActivity::class.java)
            startActivity(intent)
        }

        binding.btnExportCsv.setOnClickListener {
            val fileName = "BloodPressure_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.csv"
            createCsvLauncher.launch(fileName)
        }
        binding.btnImportCsv.setOnClickListener {
            pickCsvLauncher.launch("*/*")
        }

        binding.btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("기록 전체 삭제")
                .setMessage("정말 모든 혈압 기록을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ -> viewModel.deleteAll() }
                .setNegativeButton("취소", null)
                .show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun processImage(bitmap: Bitmap, photoDate: Long) {
        if (isImageTooBlurry(bitmap)) {
            Toast.makeText(this, "사진이 너무 흐립니다. 다시 선명하게 찍어주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val processedBitmap = enhanceAndCropForCloud(bitmap)
        val inputImage = InputImage.fromBitmap(processedBitmap, 0)

        localRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank()) {
                    Toast.makeText(this, "숫자를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
                } else {
                    val base64Image = bitmapToBase64(processedBitmap)
                    callCloudVisionApi(base64Image, photoDate)
                }
            }
    }

    private fun isImageTooBlurry(bitmap: Bitmap): Boolean {
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, false)
        var maxDiff = 0
        for (y in 0 until 99) {
            for (x in 0 until 99) {
                val p1 = Color.red(smallBitmap.getPixel(x, y))
                val p2 = Color.red(smallBitmap.getPixel(x + 1, y))
                val diff = Math.abs(p1 - p2)
                if (diff > maxDiff) maxDiff = diff
            }
        }
        return maxDiff < 30 
    }

    private fun exportCsv(uri: Uri) {
        val records = viewModel.allRecords.value ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(0xEF); outputStream.write(0xBB); outputStream.write(0xBF)
                val writer = BufferedWriter(OutputStreamWriter(outputStream))
                writer.write("날짜시간,수축기(SYS),이완기(DIA),맥박(PULSE)\n")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                for (record in records) {
                    writer.write("${sdf.format(Date(record.timestamp))},${record.systolic},${record.diastolic},${record.pulse}\n")
                }
                writer.flush()
                Toast.makeText(this, "CSV 저장 완료", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show() }
    }

    private fun importCsv(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?; val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line?.split(",") ?: continue
                    if (tokens.size >= 4) {
                        try {
                            val record = BloodPressureRecord(
                                systolic = tokens[1].toInt(), diastolic = tokens[2].toInt(),
                                pulse = tokens[3].toInt(), timestamp = sdf.parse(tokens[0])?.time ?: 0
                            )
                            viewModel.insert(record)
                        } catch (e: Exception) { continue }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun getPhotoDate(uri: Uri): Long {
        var date = System.currentTimeMillis()
        try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) date = cursor.getLong(0)
            }
        } catch (e: Exception) { }
        return date
    }

    private fun takePhoto() {
        imageCapture?.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image); image.close()
                if (bitmap != null) processImage(bitmap, System.currentTimeMillis())
            }
            override fun onError(exc: ImageCaptureException) { }
        })
    }

    private fun enhanceAndCropForCloud(source: Bitmap): Bitmap {
        val width = source.width; val height = source.height
        val cropSize = (minOf(width, height) * 0.75).toInt() 
        return resizeBitmap(Bitmap.createBitmap(source, (width - cropSize) / 2, (height - cropSize) / 2, cropSize, cropSize), 1200)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun callCloudVisionApi(base64Image: String, photoDate: Long) {
        val retrofit = Retrofit.Builder().baseUrl("https://vision.googleapis.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val service = retrofit.create(CloudVisionService::class.java)
        service.annotateImage(CLOUD_VISION_API_KEY, CloudVisionRequest(listOf(AnnotateRequest(ImageSource(base64Image), listOf(Feature()))))).enqueue(object : Callback<CloudVisionResponse> {
            override fun onResponse(call: Call<CloudVisionResponse>, response: Response<CloudVisionResponse>) {
                if (response.isSuccessful) {
                    val text = response.body()?.responses?.getOrNull(0)?.fullTextAnnotation?.text
                    runOnUiThread { showEditDialog(text, photoDate, null) }
                } else {
                    val msg = if(response.code() == 429) "무료 한도 초과" else "오류 ${response.code()}"
                    runOnUiThread { AlertDialog.Builder(this@MainActivity).setMessage(msg).show() }
                }
            }
            override fun onFailure(call: Call<CloudVisionResponse>, t: Throwable) { }
        })
    }

    private fun showEditDialog(fullText: String?, photoDate: Long, existingRecord: BloodPressureRecord?) {
        val isUpdate = existingRecord != null
        var s = existingRecord?.systolic ?: 120
        var d = existingRecord?.diastolic ?: 80
        var p = existingRecord?.pulse ?: 70
        val cal = Calendar.getInstance().apply { timeInMillis = existingRecord?.timestamp ?: photoDate }

        fullText?.let {
            Regex("(\\d{2,3})").findAll(it).map { m -> m.value.toInt() }.filter { v -> v in 40..250 }.toList().let { list ->
                if (list.size >= 2) { s = maxOf(list[0], list[1]); d = minOf(list[0], list[1]); p = list.getOrNull(2) ?: p }
            }
        }

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val etDate = createEditField(layout, "일시", sdf.format(cal.time), InputType.TYPE_NULL).apply { 
            isFocusable = false; setOnClickListener {
                DatePickerDialog(this@MainActivity, { _, y, m, day ->
                    cal.set(y, m, day); TimePickerDialog(this@MainActivity, { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); setText(sdf.format(cal.time))
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        val etS = createEditField(layout, "SYS", s.toString(), InputType.TYPE_CLASS_NUMBER)
        val etD = createEditField(layout, "DIA", d.toString(), InputType.TYPE_CLASS_NUMBER)
        val etP = createEditField(layout, "Pulse", p.toString(), InputType.TYPE_CLASS_NUMBER)

        AlertDialog.Builder(this).setTitle("확인").setView(layout).setPositiveButton("저장") { _, _ ->
            val record = BloodPressureRecord(id = existingRecord?.id ?: 0, systolic = etS.text.toString().toInt(), diastolic = etD.text.toString().toInt(), pulse = etP.text.toString().toInt(), timestamp = cal.timeInMillis)
            if (isUpdate) viewModel.update(record) else viewModel.insert(record)
        }.show()
    }

    private fun createEditField(p: LinearLayout, l: String, v: String, t: Int) = EditText(this).apply { 
        hint = l; setText(v); inputType = t; p.addView(TextView(this@MainActivity).apply { text = l }); p.addView(this) 
    }

    private fun resizeBitmap(s: Bitmap, m: Int): Bitmap {
        if (s.width <= m && s.height <= m) return s
        val r = s.width.toFloat() / s.height.toFloat()
        return Bitmap.createScaledBitmap(s, if(r>1) m else (m*r).toInt(), if(r>1) (m/r).toInt() else m, true)
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val s = contentResolver.openInputStream(uri); val b = BitmapFactory.decodeStream(s); s?.close()
            val exif = contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val m = Matrix().apply { 
                when(exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> postRotate(90f); 3 -> postRotate(180f); 8 -> postRotate(270f)
                }
            }
            if (b != null) Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true) else null
        } catch (e: Exception) { null }
    }

    private fun imageProxyToBitmap(i: ImageProxy): Bitmap? {
        val b = i.planes[0].buffer; val bytes = ByteArray(b.remaining()); b.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return if (bitmap != null) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(i.imageInfo.rotationDegrees.toFloat()) }, true) else null
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get(); val pre = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            try { p.unbindAll(); p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pre, imageCapture) } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(baseContext, it) == 0 }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

interface CloudVisionService {
    @POST("v1/images:annotate")
    fun annotateImage(@Query("key") apiKey: String, @Body request: CloudVisionRequest): Call<CloudVisionResponse>
}
data class CloudVisionRequest(val requests: List<AnnotateRequest>)
data class AnnotateRequest(val image: ImageSource, val features: List<Feature>)
data class ImageSource(val content: String)
data class Feature(val type: String = "TEXT_DETECTION")
data class CloudVisionResponse(val responses: List<AnnotateResponse>)
data class AnnotateResponse(val fullTextAnnotation: FullText?)
data class FullText(val text: String)
