
package com.aiman.yolo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(
    val label: String,
    val classIndex: Int,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)

class YOLODetector(context: Context) {

    private val interpreter: Interpreter

    private val inputScale: Float
    private val inputZeroPoint: Int

    private val outputScale: Float
    private val outputZeroPoint: Int

    private val inputWidth = 640
    private val inputHeight = 640

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane",
        "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird",
        "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat",
        "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife",
        "spoon", "bowl", "banana", "apple", "sandwich",
        "orange", "broccoli", "carrot", "hot dog", "pizza",
        "donut", "cake", "chair", "couch", "potted plant",
        "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave",
        "oven", "toaster", "sink", "refrigerator", "book",
        "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    init {
        val model = loadModelFile(context)

        interpreter = Interpreter(model)

        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        inputScale = inputTensor.quantizationParams().scale
        inputZeroPoint = inputTensor.quantizationParams().zeroPoint

        outputScale = outputTensor.quantizationParams().scale
        outputZeroPoint = outputTensor.quantizationParams().zeroPoint

        Log.i("YOLO_MODEL", "Model loaded successfully")
        Log.i("YOLO_MODEL", "Input: ${inputTensor.shape().contentToString()}")
        Log.i("YOLO_MODEL", "Output: ${outputTensor.shape().contentToString()}")
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(
            "yolov8n_full_integer_quant.tflite"
        )

        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun detect(bitmap: Bitmap): List<Detection> {

        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            inputWidth,
            inputHeight,
            true
        )

        val pixels = IntArray(inputWidth * inputHeight)

        resizedBitmap.getPixels(
            pixels,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        val inputBuffer = ByteBuffer.allocateDirect(
            inputWidth * inputHeight * 3
        ).order(ByteOrder.nativeOrder())

        for (pixel in pixels) {

            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF

            inputBuffer.put(
                ((red / 255f) / inputScale + inputZeroPoint)
                    .toInt().coerceIn(-128, 127).toByte()
            )

            inputBuffer.put(
                ((green / 255f) / inputScale + inputZeroPoint)
                    .toInt().coerceIn(-128, 127).toByte()
            )

            inputBuffer.put(
                ((blue / 255f) / inputScale + inputZeroPoint)
                    .toInt().coerceIn(-128, 127).toByte()
            )
        }

        inputBuffer.rewind()

        val outputShape = interpreter.getOutputTensor(0).shape()

        val outputChannels = outputShape[1]
        val candidateCount = outputShape[2]

        val output = Array(1) {
            Array(outputChannels) {
                ByteArray(candidateCount)
            }
        }

        interpreter.run(inputBuffer, output)

        val detections = mutableListOf<Detection>()

        for (i in 0 until candidateCount) {

            var highestScore = 0f
            var bestClass = -1

            for (classIndex in 4 until outputChannels) {

                val quantizedScore =
                    output[0][classIndex][i].toInt()

                val score =
                    (quantizedScore - outputZeroPoint) * outputScale

                if (score > highestScore) {
                    highestScore = score
                    bestClass = classIndex - 4
                }
            }

            if (highestScore >= 0.50f &&
                bestClass in labels.indices
            ) {

                fun value(channel: Int): Float {
                    val quantizedValue =
                        output[0][channel][i].toInt()

                    return (quantizedValue - outputZeroPoint) *
                            outputScale
                }

                detections.add(
                    Detection(
                        label = labels[bestClass],
                        classIndex = bestClass,
                        confidence = highestScore,
                        centerX = value(0),
                        centerY = value(1),
                        width = value(2),
                        height = value(3)
                    )
                )
            }
        }

        return detections
            .sortedByDescending { it.confidence }
            .take(10)
    }

    fun close() {
        interpreter.close()
    }
}


