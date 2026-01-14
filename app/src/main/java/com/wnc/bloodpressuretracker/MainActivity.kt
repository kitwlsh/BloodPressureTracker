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
    
    // 로컬 텍스트 인식기 (게이트키퍼 용도)
    private val localRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    private val CLOUD_VISION_API_KEY = "AIzaSyCKGdFu7uwdNueA73DdW0eJliuafpUatiM"

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

        supportActionBar?.apply {
            title = getString(R.string.app_name)
            subtitle = getString(R.string.developer_info)
        }

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
                .setMessage("정말 모든 혈압 기록을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    viewModel.deleteAll()
                    Toast.makeText(this, "모든 기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun processImage(bitmap: Bitmap, photoDate: Long) {
        // 1. 선명도 검사 (Blur Detection)
        if (isImageTooBlurry(bitmap)) {
            Toast.makeText(this, "사진이 너무 흐립니다. 다시 선명하게 찍어주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val processedBitmap = enhanceAndCropForCloud(bitmap)
        val inputImage = InputImage.fromBitmap(processedBitmap, 0)

        // 2. 로컬에서 텍스트 존재 여부 1차 확인 (Google Cloud 비용 절약 핵심)
        localRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val localText = visionText.text
                if (localText.isBlank()) {
                    Toast.makeText(this, "숫자를 찾을 수 없습니다. 계기판을 사각형에 맞춰주세요.", Toast.LENGTH_LONG).show()
                } else {
                    // 텍스트가 존재할 때만 클라우드 API 호출
                    val base64Image = bitmapToBase64(processedBitmap)
                    callCloudVisionApi(base64Image, photoDate)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "분석 준비 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            }
    }

    // 간단한 선명도 검사 알고리즘
    private fun isImageTooBlurry(bitmap: Bitmap): Boolean {
        // 이미지를 작게 줄여서 인접 픽셀간의 밝기 차이(Contrast)를 분석
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
        // 차이가 너무 적으면(평평하면) 흐릿한 것으로 판단
        return maxDiff < 30 
    }

    private fun exportCsv(uri: Uri) {
        val records = viewModel.allRecords.value ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(0xEF)
                outputStream.write(0xBB)
                outputStream.write(0xBF)
                val writer = BufferedWriter(OutputStreamWriter(outputStream))
                writer.write("날짜시간,수축기(SYS),이완기(DIA),맥박(PULSE)\n")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                for (record in records) {
                    val line = "${sdf.format(Date(record.timestamp))},${record.systolic},${record.diastolic},${record.pulse}\n"
                    writer.write(line)
                }
                writer.flush()
                Toast.makeText(this, "CSV 파일이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importCsv(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                var count = 0
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                reader.readLine()
                while (reader.readLine().also { line = it } != null) {
                    val tokens = line?.split(",") ?: continue
                    if (tokens.size >= 4) {
                        try {
                            val timestamp = sdf.parse(tokens[0])?.time ?: System.currentTimeMillis()
                            val record = BloodPressureRecord(
                                systolic = tokens[1].toInt(),
                                diastolic = tokens[2].toInt(),
                                pulse = tokens[3].toInt(),
                                timestamp = timestamp
                            )
                            viewModel.insert(record)
                            count++
                        } catch (e: Exception) { continue }
                    }
                }
                Toast.makeText(this, "${count}개의 기록을 불러왔습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    private fun enhanceAndCropForCloud(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val cropSize = (minOf(width, height) * 0.75).toInt() 
        val left = (width - cropSize) / 2
        val top = (height - cropSize) / 2
        val cropped = Bitmap.createBitmap(source, left, top, cropSize, cropSize)
        return resizeBitmap(cropped, 1200)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun callCloudVisionApi(base64Image: String, photoDate: Long) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://vision.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = retrofit.create(CloudVisionService::class.java)
        val request = CloudVisionRequest(listOf(
            AnnotateRequest(ImageSource(base64Image), listOf(Feature()))
        ))
        service.annotateImage(CLOUD_VISION_API_KEY, request).enqueue(object : Callback<CloudVisionResponse> {
            override fun onResponse(call: Call<CloudVisionResponse>, response: Response<CloudVisionResponse>) {
                if (response.isSuccessful) {
                    val fullText = response.body()?.responses?.getOrNull(0)?.fullTextAnnotation?.text ?: ""
                    runOnUiThread { showEditDialog(fullText, photoDate, null) }
                } else {
                    val errorMessage = when(response.code()) {
                        403 -> "API 키가 잘못되었거나 사용 권한이 없습니다."
                        429 -> "무료 사용 한도(월 1,000건)를 초과했습니다. 다음 달에 리셋됩니다."
                        else -> "분석 실패 (오류 코드: ${response.code()})"
                    }
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("인식 오류")
                            .setMessage(errorMessage)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
            }
            override fun onFailure(call: Call<CloudVisionResponse>, t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "네트워크 연결 상태를 확인해주세요.", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun showEditDialog(fullText: String?, photoDate: Long, existingRecord: BloodPressureRecord?) {
        val isUpdate = existingRecord != null
        var initialSys = existingRecord?.systolic ?: 120
        var initialDia = existingRecord?.diastolic ?: 80
        var initialPulse = existingRecord?.pulse ?: 70
        
        val selectedCal = Calendar.getInstance()
        selectedCal.timeInMillis = existingRecord?.timestamp ?: photoDate

        if (fullText != null) {
            val timeRegex = Regex("([01]?\\d|2[0-3])\\s*:\\s*([0-5]\\d)")
            timeRegex.find(fullText)?.let {
                val h = it.groupValues[1].toInt()
                val m = it.groupValues[2].toInt()
                selectedCal.set(Calendar.HOUR_OF_DAY, h)
                selectedCal.set(Calendar.MINUTE, m)
                selectedCal.set(Calendar.SECOND, 0)
            }

            val dateRegex = Regex("([01]?\\d)\\s*[/-]\\s*([0-3]?\\d)")
            dateRegex.find(fullText.replace(Regex("\\d{4}"), ""))?.let {
                val month = it.groupValues[1].toInt() - 1
                val day = it.groupValues[2].toInt()
                if (month in 0..11 && day in 1..31) {
                    selectedCal.set(Calendar.MONTH, month)
                    selectedCal.set(Calendar.DAY_OF_MONTH, day)
                }
            }

            var cleanedText = fullText.replace(Regex("\\d{4}"), " ")
            cleanedText = cleanedText.replace(Regex("\\d{1,2}\\s*[:/]\\s*\\d{1,2}"), " ")
            val allDigits = Regex("\\d{2,3}").findAll(cleanedText).map { it.value.toInt() }.toList()
            val filtered = allDigits.filter { it in 30..250 }
            if (filtered.size >= 2) {
                var s = filtered[0]
                var d = filtered[1]
                if (s < d) { val temp = s; s = d; d = temp }
                initialSys = s
                initialDia = d
                initialPulse = filtered.getOrNull(2) ?: initialPulse
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val etDate = createEditField(layout, "측정 날짜 시간 (클릭하여 수정)", sdf.format(selectedCal.time), InputType.TYPE_NULL)
        etDate.isFocusable = false
        etDate.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                selectedCal.set(Calendar.YEAR, year)
                selectedCal.set(Calendar.MONTH, month)
                selectedCal.set(Calendar.DAY_OF_MONTH, day)
                TimePickerDialog(this, { _, hour, minute ->
                    selectedCal.set(Calendar.HOUR_OF_DAY, hour)
                    selectedCal.set(Calendar.MINUTE, minute)
                    etDate.setText(sdf.format(selectedCal.time))
                }, selectedCal.get(Calendar.HOUR_OF_DAY), selectedCal.get(Calendar.MINUTE), true).show()
            }, selectedCal.get(Calendar.YEAR), selectedCal.get(Calendar.MONTH), selectedCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val etSys = createEditField(layout, "수축기 혈압 (최고)", initialSys.toString(), InputType.TYPE_CLASS_NUMBER)
        val etDia = createEditField(layout, "이완기 혈압 (최저)", initialDia.toString(), InputType.TYPE_CLASS_NUMBER)
        val etPulse = createEditField(layout, "맥박 (회/분)", initialPulse.toString(), InputType.TYPE_CLASS_NUMBER)

        AlertDialog.Builder(this)
            .setTitle(if (isUpdate) "기록 수정" else "인식 결과 확인/수정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                try {
                    val sys = etSys.text.toString().toInt()
                    val dia = etDia.text.toString().toInt()
                    val pulse = etPulse.text.toString().toInt()
                    val record = BloodPressureRecord(
                        id = existingRecord?.id ?: 0,
                        systolic = sys, diastolic = dia, pulse = pulse, timestamp = selectedCal.timeInMillis
                    )
                    if (isUpdate) viewModel.update(record) else viewModel.insert(record)
                    Toast.makeText(this, if (isUpdate) "수정되었습니다." else "저장되었습니다.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "입력값이 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createEditField(parent: LinearLayout, label: String, value: String, inputType: Int): EditText {
        val textView = TextView(this).apply {
            text = label
            setPadding(0, 12, 0, 4)
            setTextColor(Color.parseColor("#333333"))
            textSize = 14f
        }
        parent.addView(textView)
        val editText = EditText(this).apply {
            setText(value)
            this.inputType = inputType
            textSize = 18f
        }
        parent.addView(editText)
        return editText
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

interface CloudVisionService {
    @POST("v1/images:annotate")
    fun annotateImage(
        @Query("key") apiKey: String,
        @Body request: CloudVisionRequest
    ): Call<CloudVisionResponse>
}

data class CloudVisionRequest(val requests: List<AnnotateRequest>)
data class AnnotateRequest(val image: ImageSource, val features: List<Feature>)
data class ImageSource(val content: String)
data class Feature(val type: String = "TEXT_DETECTION")
data class CloudVisionResponse(val responses: List<AnnotateResponse>)
data class AnnotateResponse(val fullTextAnnotation: FullText?)
data class FullText(val text: String)
