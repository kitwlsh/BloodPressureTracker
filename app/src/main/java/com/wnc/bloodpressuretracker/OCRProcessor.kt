package com.wnc.bloodpressuretracker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OCRProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    interface OnResultListener {
        fun onSuccess(systolic: Int, diastolic: Int, pulse: Int, timeStr: String?)
        fun onFailure(e: Exception)
        fun onDebugText(fullText: String) // 추가: 인식된 전체 텍스트 확인용
    }

    fun processImage(bitmap: Bitmap, listener: OnResultListener) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // 인식된 모든 텍스트를 디버그용으로 먼저 전달
                listener.onDebugText(visionText.text)
                
                try {
                    analyzeLayout(visionText, listener)
                } catch (e: Exception) {
                    listener.onFailure(e)
                }
            }
            .addOnFailureListener { e ->
                listener.onFailure(e)
            }
    }

    private fun analyzeLayout(visionText: Text, listener: OnResultListener) {
        val allElements = mutableListOf<TextElementInfo>()
        val timeRegex = Regex("([01]?[0-9]|2[0-3])[:.]([0-5][0-9])")
        var timeStr: String? = null

        // 1. 모든 개별 단어/기호 수집 및 세그먼트 오인식 문자 변환
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim().replace(" ", "")
                val timeMatch = timeRegex.find(lineText)
                if (timeMatch != null && timeStr == null) {
                    timeStr = timeMatch.value.replace(".", ":")
                }

                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    
                    // 7세그먼트 디지털 숫자 특유의 오인식 문자들을 숫자로 매핑 강화
                    val cleaned = element.text.trim()
                        .replace("I", "1").replace("l", "1").replace("|", "1").replace("]", "1").replace("[", "1")
                        .replace("S", "5").replace("s", "5")
                        .replace("B", "8").replace("E", "8")
                        .replace("O", "0").replace("D", "0").replace("Q", "0").replace("U", "0")
                        .replace("g", "9").replace("q", "9")
                        .replace("A", "4").replace("H", "4")
                        .replace("G", "6").replace("b", "6")
                        .replace("Z", "2").replace("z", "2")
                        .replace("T", "7")
                    
                    val digits = cleaned.filter { it.isDigit() }
                    if (digits.isNotEmpty()) {
                        allElements.add(TextElementInfo(digits, box))
                    }
                }
            }
        }

        // 2. 가로로 인접하고 높이가 비슷한 숫자 조각들을 하나로 합치기
        val mergedLines = mutableListOf<TextLineInfo>()
        val sortedElements = allElements.sortedBy { it.rect.top }

        for (element in sortedElements) {
            var merged = false
            for (line in mergedLines) {
                // Y축 중앙값이 비슷하고 (같은 줄), 가로로 너무 멀지 않은 경우 합침
                val yDiff = abs(line.rect.centerY() - element.rect.centerY())
                val xDiff = element.rect.left - line.rect.right
                
                // 세그먼트 숫자는 조각나서 인식될 가능성이 크므로 가로 허용 오차를 조금 더 넓힘
                if (yDiff < line.rect.height() * 0.8 && xDiff < line.rect.height() * 1.8) {
                    line.text += element.text
                    line.rect.union(element.rect)
                    merged = true
                    break
                }
            }
            if (!merged) {
                mergedLines.add(TextLineInfo(element.text, Rect(element.rect)))
            }
        }

        // 3. 합쳐진 결과 중 '진짜 숫자' 후보군 필터링 (혈압 범위 체크 추가)
        val finalCandidates = mergedLines
            .filter { 
                val d = it.text.filter { c -> c.isDigit() }
                // 혈압 데이터는 보통 2~3자리이며 30~250 사이임
                d.length in 2..3 && d.toIntOrNull()?.let { v -> v in 30..250 } ?: false
            }
            .sortedByDescending { it.rect.height() } // 글자 크기가 큰 순서대로 (혈압 수치가 보통 가장 큼)
            .take(3) 
            .sortedBy { it.rect.top } // 위(SYS) -> 중간(DIA) -> 아래(PULSE) 순서

        Log.d("OCRProcessor", "Final Candidates: ${finalCandidates.joinToString { "${it.text}(h:${it.rect.height()})" }}")

        if (finalCandidates.size >= 2) {
            val sys = finalCandidates[0].text.toInt()
            val dia = finalCandidates[1].text.toInt()
            val pulse = if (finalCandidates.size >= 3) finalCandidates[2].text.toInt() else 0
            
            listener.onSuccess(sys, dia, pulse, timeStr)
        } else {
            val debug = mergedLines.joinToString { "${it.text}(h:${it.rect.height()})" }
            listener.onFailure(Exception("숫자를 충분히 찾지 못했습니다. (후보: $debug)"))
        }
    }

    data class TextElementInfo(val text: String, val rect: Rect)
    class TextLineInfo(var text: String, val rect: Rect)
}
