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
    }

    fun processImage(bitmap: Bitmap, listener: OnResultListener) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
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

        // 1. 모든 개별 단어/기호 수집
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim().replace(" ", "")
                val timeMatch = timeRegex.find(lineText)
                if (timeMatch != null && timeStr == null) {
                    timeStr = timeMatch.value.replace(".", ":")
                }

                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    // 디지털 숫자 특유의 오인식 문자들을 숫자로 미리 변환
                    val cleaned = element.text.trim()
                        .replace("I", "1").replace("l", "1").replace("|", "1")
                        .replace("S", "5").replace("B", "8").replace("O", "0")
                        .replace("g", "9").replace("q", "9").replace("A", "4")
                    
                    val digits = cleaned.filter { it.isDigit() }
                    if (digits.isNotEmpty()) {
                        allElements.add(TextElementInfo(digits, box))
                    }
                }
            }
        }

        // 2. 가로로 인접하고 높이가 비슷한 숫자 조각들을 하나로 합치기
        val mergedLines = mutableListOf<TextLineInfo>()
        // 위에서 아래로 정렬하여 처리
        val sortedElements = allElements.sortedBy { it.rect.top }

        for (element in sortedElements) {
            var merged = false
            for (line in mergedLines) {
                // Y축 중앙값이 비슷하고 (같은 줄), 가로로 너무 멀지 않은 경우 합침
                val yDiff = abs(line.rect.centerY() - element.rect.centerY())
                val xDiff = element.rect.left - line.rect.right
                
                if (yDiff < line.rect.height() * 0.7 && xDiff < line.rect.height() * 1.5) {
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

        // 3. 합쳐진 결과 중 '진짜 숫자' 후보군 필터링 (2~3자리 숫자)
        val finalCandidates = mergedLines
            .filter { 
                val d = it.text.filter { c -> c.isDigit() }
                d.length in 2..3 && d.toInt() > 10 // 10 이하는 노이즈로 간주
            }
            .sortedByDescending { it.rect.height() } // 글자 크기(높이)가 큰 순서대로
            .take(3) // 가장 큰 3개 뭉치 선택
            .sortedBy { it.rect.top } // 다시 위에서 아래 순서로 배치 (SYS-DIA-PULSE)

        Log.d("OCRProcessor", "Final 3: ${finalCandidates.joinToString { "${it.text}(h:${it.rect.height()})" }}")

        if (finalCandidates.size >= 3) {
            listener.onSuccess(
                finalCandidates[0].text.toInt(),
                finalCandidates[1].text.toInt(),
                finalCandidates[2].text.toInt(),
                timeStr
            )
        } else if (finalCandidates.size == 2) {
            // 맥박을 못 찾은 경우
            listener.onSuccess(finalCandidates[0].text.toInt(), finalCandidates[1].text.toInt(), 0, timeStr)
        } else {
            val debug = mergedLines.joinToString { "${it.text}(h:${it.rect.height()})" }
            listener.onFailure(Exception("데이터 인식 실패 (후보: $debug)"))
        }
    }

    data class TextElementInfo(val text: String, val rect: Rect)
    class TextLineInfo(var text: String, val rect: Rect)
}
