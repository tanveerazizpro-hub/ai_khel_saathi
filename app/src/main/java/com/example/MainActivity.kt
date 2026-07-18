package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.theme.MyApplicationTheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.acos
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    lateinit var cameraExecutor: ExecutorService
    private var poseLandmarker: PoseLandmarker? = null
    var tts: TextToSpeech? = null
    
    val poseState = mutableStateOf(PoseState())
    val hasCameraPermission = mutableStateOf(false)

    private var lastSpeechTime = 0L
    private val speechDebounceMs = 2000L

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            window.decorView.postDelayed({
                hasCameraPermission.value = true
                setupCameraAndMediaPipe()
            }, 500)
        }
    }

    lateinit var workoutViewModel: WorkoutViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = androidx.room.Room.databaseBuilder(applicationContext, WorkoutDatabase::class.java, "workout-db").build()
        val repository = WorkoutRepository(db.workoutDao())
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return WorkoutViewModel(repository) as T
            }
        }
        workoutViewModel = androidx.lifecycle.ViewModelProvider(this, factory)[WorkoutViewModel::class.java]

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            window.decorView.postDelayed({
                hasCameraPermission.value = true
                setupCameraAndMediaPipe()
            }, 500)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val workoutHistory = workoutViewModel.sessions.collectAsState(initial = emptyList()).value
            val navController = rememberNavController()
            
            MyApplicationTheme {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            history = workoutHistory,
                            onNavigateToTracker = { mode ->
                                poseState.value = poseState.value.copy(
                                    currentMode = mode, 
                                    repCount = 0, 
                                    caloriesBurnt = 0f, 
                                    sessionStartTime = System.currentTimeMillis()
                                )
                                navController.navigate("tracker/$mode")
                            }
                        )
                    }
                    composable(
                        "tracker/{mode}",
                        arguments = listOf(navArgument("mode") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val mode = backStackEntry.arguments?.getString("mode") ?: "Push-ups"
                        
                        TrackerScreen(
                            mode = mode,
                            poseState = poseState.value,
                            hasCameraPermission = hasCameraPermission.value,
                            onCalibrate = {
                                poseState.value = poseState.value.copy(baselineAngle = poseState.value.lastRawElbowAngle)
                            },
                            onLanguageChange = { lang ->
                                poseState.value = poseState.value.copy(currentLanguage = lang)
                                val locale = when (lang) {
                                    "Hindi" -> Locale("hi", "IN")
                                    "Bengali" -> Locale("bn", "IN")
                                    else -> Locale.US
                                }
                                tts?.language = locale
                            },
                            onSpeak = { msg ->
                                tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                            },
                            onPlayChime = {
                                val toneG = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                                toneG.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                            },
                            onFinishSet = {
                                val sessionDuration = (System.currentTimeMillis() - poseState.value.sessionStartTime) / 1000
                                val newSession = WorkoutSessionEntity(
                                    mode = poseState.value.currentMode,
                                    reps = poseState.value.repCount,
                                    calories = poseState.value.caloriesBurnt,
                                    durationSeconds = sessionDuration
                                )
                                workoutViewModel.insert(newSession)
                                poseState.value = poseState.value.copy(
                                    repCount = 0,
                                    caloriesBurnt = 0f,
                                    sessionStartTime = System.currentTimeMillis()
                                )
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("KhelSaathi", "TTS Language not supported or missing data")
            } else {
                Log.i("KhelSaathi", "TTS initialized successfully")
            }
        } else {
            Log.e("KhelSaathi", "TTS Initialization failed")
        }
    }

    private fun setupCameraAndMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onPoseResult)
                .setErrorListener { error -> Log.e("KhelSaathi", "MediaPipe Error: ${error.message}") }
                .build()
            
            poseLandmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            Log.e("KhelSaathi", "Error setting up mediapipe", e)
        }
    }

    private fun onPoseResult(result: PoseLandmarkerResult, inputImage: com.google.mediapipe.framework.image.MPImage) {
        if (result.worldLandmarks().isEmpty() || result.landmarks().isEmpty()) return
        
        val worldLandmarks = result.worldLandmarks()[0]
        val normalizedLandmarks = result.landmarks()[0]
        val mode = poseState.value.currentMode
        
        val imageWidth = inputImage.width
        val imageHeight = inputImage.height
        
        var status = ""
        var currentAngleStr = ""
        var newRepCount = poseState.value.repCount
        
        val isJointVisible: (Int) -> Boolean = { index ->
            val lm = normalizedLandmarks.getOrNull(index)
            lm != null && lm.visibility().isPresent && lm.visibility().get() > 0.6f
        }

        var allRequiredVisible = false
        
        when (mode) {
            "Push-ups" -> {
                val req = listOf(12, 14, 16, 24, 28)
                allRequiredVisible = req.all { isJointVisible(it) }
                if (allRequiredVisible) {
                    val shoulder = worldLandmarks[12]
                    val elbow = worldLandmarks[14]
                    val wrist = worldLandmarks[16]
                    val hip = worldLandmarks[24]
                    val ankle = worldLandmarks[28]

                    val coreAngle = getAngle3D(shoulder, hip, ankle)
                    val elbowAngle = getAngle3D(shoulder, elbow, wrist)
                    
                    currentAngleStr = "Elbow: ${elbowAngle.toInt()}° | Core: ${coreAngle.toInt()}°"
                    
                    if (coreAngle < 160) {
                        status = "Core collapsed!"
                        speak("Keep your core tight. Lift your hips.")
                    } else {
                        if (elbowAngle < 90) {
                            if (!poseState.value.isInRep) {
                                poseState.value = poseState.value.copy(isInRep = true)
                            }
                            status = "Push up..."
                        } else if (elbowAngle > 160 && poseState.value.isInRep) {
                            poseState.value = poseState.value.copy(isInRep = false)
                            newRepCount++
                            status = "Great rep."
                            speak("Great rep.")
                        }
                    }
                }
            }
            "Cricket (Bowling)" -> {
                val req = listOf(12, 14, 16)
                allRequiredVisible = req.all { isJointVisible(it) }
                if (allRequiredVisible) {
                    val shoulder = worldLandmarks[12]
                    val elbow = worldLandmarks[14]
                    val wrist = worldLandmarks[16]
                    
                    val elbowAngle = getAngle3D(shoulder, elbow, wrist)
                    val bend = 180 - elbowAngle
                    
                    currentAngleStr = "Elbow Bend: ${bend.toInt()}°"
                    
                    if (bend > 15) {
                        status = "Illegal action!"
                        speak("Illegal action. Keep your bowling arm straight.")
                    } else {
                        status = "Good action"
                    }
                }
            }
            "Squats" -> {
                val req = listOf(24, 26, 28)
                allRequiredVisible = req.all { isJointVisible(it) }
                if (allRequiredVisible) {
                    val hip = worldLandmarks[24]
                    val knee = worldLandmarks[26]
                    val ankle = worldLandmarks[28]
                    
                    val kneeAngle = getAngle3D(hip, knee, ankle)
                    currentAngleStr = "Knee: ${kneeAngle.toInt()}°"
                    
                    if (kneeAngle < 100) {
                        if (!poseState.value.isInRep) {
                            poseState.value = poseState.value.copy(isInRep = true)
                        }
                        status = "Go up!"
                    } else if (kneeAngle > 160 && poseState.value.isInRep) {
                        poseState.value = poseState.value.copy(isInRep = false)
                        newRepCount++
                        status = "Great rep."
                        speak("Great rep.")
                    } else if (kneeAngle in 100f..120f && !poseState.value.isInRep) {
                        status = "Go lower!"
                        speak("Go lower.")
                    }
                }
            }
            "Bicep Curls" -> {
                val req = listOf(12, 14, 16)
                allRequiredVisible = req.all { isJointVisible(it) }
                if (allRequiredVisible) {
                    val shoulder = worldLandmarks[12]
                    val elbow = worldLandmarks[14]
                    val wrist = worldLandmarks[16]
                    
                    val rawAngle = getAngle3D(shoulder, elbow, wrist)
                    var calibratedAngle = rawAngle + (180f - poseState.value.baselineAngle)
                    if (calibratedAngle > 180f) calibratedAngle = 180f
                    
                    currentAngleStr = "Elbow: ${calibratedAngle.toInt()}°"
                    
                    if (calibratedAngle < 45) {
                        if (!poseState.value.isInRep) {
                            poseState.value = poseState.value.copy(isInRep = true, lastRawElbowAngle = rawAngle)
                        }
                        status = "Good curl"
                    } else if (calibratedAngle > 150 && poseState.value.isInRep) {
                        poseState.value = poseState.value.copy(isInRep = false, lastRawElbowAngle = rawAngle)
                        newRepCount++
                        status = "Great rep."
                        speak("Great rep.")
                    } else {
                        poseState.value = poseState.value.copy(lastRawElbowAngle = rawAngle)
                    }
                }
            }
        }
        
        if (!allRequiredVisible) {
            currentAngleStr = "--"
            status = "Adjust Position"
        }
        
        val calPerRep = when (mode) {
            "Push-ups" -> 0.3f
            "Squats" -> 0.4f
            "Bicep Curls" -> 0.1f
            "Cricket (Bowling)" -> 0.2f
            else -> 0f
        }
        val addedReps = newRepCount - poseState.value.repCount
        val addedCalories = addedReps * calPerRep
        val newCalories = poseState.value.caloriesBurnt + addedCalories
        
        poseState.value = poseState.value.copy(
            repCount = newRepCount,
            caloriesBurnt = newCalories,
            statusText = status,
            currentAngle = currentAngleStr,
            landmarks = normalizedLandmarks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            lastRawElbowAngle = poseState.value.lastRawElbowAngle
        )
    }

    private fun speak(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSpeechTime > speechDebounceMs) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpeechTime = now
        }
    }
    
    private fun getAngle3D(
        p1: com.google.mediapipe.tasks.components.containers.Landmark, 
        p2: com.google.mediapipe.tasks.components.containers.Landmark, 
        p3: com.google.mediapipe.tasks.components.containers.Landmark
    ): Float {
        val v1x = p1.x() - p2.x()
        val v1y = p1.y() - p2.y()
        val v1z = p1.z() - p2.z()
        
        val v2x = p3.x() - p2.x()
        val v2y = p3.y() - p2.y()
        val v2z = p3.z() - p2.z()
        
        val dotProduct = (v1x * v2x) + (v1y * v2y) + (v1z * v2z)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y) + (v1z * v1z))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y) + (v2z * v2z))
        
        if (mag1 * mag2 == 0f) return 0f
        
        var cosTheta = dotProduct / (mag1 * mag2)
        cosTheta = cosTheta.coerceIn(-1f, 1f)
        
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    fun analyzeImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            
            poseLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.e("KhelSaathi", "Error analyzing image", e)
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        poseLandmarker?.close()
        cameraExecutor.shutdown()
    }
}

data class PoseState(
    val currentMode: String = "Push-ups",
    val currentLanguage: String = "English",
    val repCount: Int = 0,
    val caloriesBurnt: Float = 0f,
    val sessionStartTime: Long = System.currentTimeMillis(),
    val currentAngle: String = "",
    val statusText: String = "",
    val isInRep: Boolean = false,
    val landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> = emptyList(),
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val baselineAngle: Float = 180f,
    val lastRawElbowAngle: Float = 180f,
    val isFrontCamera: Boolean = true
)

@Composable
fun HomeScreen(
    history: List<WorkoutSessionEntity>,
    onNavigateToTracker: (String) -> Unit
) {
    val activities = listOf(
        "Cricket (Bowling)" to Color(0xFF007AFF),
        "Push-ups" to Color(0xFFFF3B30),
        "Bicep Curls" to Color(0xFFFF9F0A),
        "Squats" to Color(0xFF34C759)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // One UI Header
        Text(
            text = "AI KhelSaathi",
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "Track your form. Master your game.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        // Activity Grid
        Text(
            text = "ACTIVITIES",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(max = 400.dp)
        ) {
            items(activities.size) { index ->
                val (name, color) = activities[index]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable { onNavigateToTracker(name) }
                        .padding(24.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Box(modifier = Modifier.size(16.dp).background(color, RoundedCornerShape(50)).align(Alignment.TopEnd))
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            WorkoutSummaryChart(history = history)
        }
    }
}

@Composable
fun WorkoutSummaryChart(history: List<WorkoutSessionEntity>) {
    var viewMode by remember { mutableStateOf("Daily") }
    val chartData = history.take(7).map { it.calories }.reversed()
    
    Text(
        text = "WORKOUT SUMMARY",
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 12.dp)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF1C1C1E))
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Calories Burned", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .padding(4.dp)
            ) {
                Box(modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (viewMode == "Daily") Color(0xFF1C1C1E) else Color.Transparent)
                    .clickable { viewMode = "Daily" }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Daily", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                
                Box(modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (viewMode == "Weekly") Color(0xFF1C1C1E) else Color.Transparent)
                    .clickable { viewMode = "Weekly" }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Weekly", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (chartData.isNotEmpty()) {
            val maxCal = chartData.maxOrNull()?.coerceAtLeast(10f) ?: 10f
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                val width = size.width
                val height = size.height
                
                val path = Path()
                if (chartData.size == 1) {
                    val y = height - (chartData[0] / maxCal) * height
                    drawCircle(color = Color(0xFF007AFF), radius = 8f, center = Offset(width / 2, y))
                } else {
                    val stepX = width / (chartData.size - 1)
                    var prevX = 0f
                    var prevY = height - (chartData[0] / maxCal) * height
                    
                    path.moveTo(prevX, prevY)
                    drawCircle(color = Color(0xFF007AFF), radius = 8f, center = Offset(prevX, prevY))
                    
                    for (i in 1 until chartData.size) {
                        val currentX = i * stepX
                        val currentY = height - (chartData[i] / maxCal) * height
                        
                        val cp1X = prevX + (currentX - prevX) / 2
                        val cp1Y = prevY
                        val cp2X = prevX + (currentX - prevX) / 2
                        val cp2Y = currentY
                        
                        path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, currentX, currentY)
                        drawCircle(color = Color(0xFF007AFF), radius = 8f, center = Offset(currentX, currentY))
                        
                        prevX = currentX
                        prevY = currentY
                    }
                    
                    drawPath(
                        path = path,
                        color = Color(0xFF007AFF),
                        style = Stroke(width = 8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        history.take(5).forEach { session ->
            val dateStr = SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(session.timestamp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(session.mode, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(dateStr, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${String.format("%.1f", session.calories)} kcal", color = Color(0xFFFF9F0A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${session.durationSeconds / 60}m ${session.durationSeconds % 60}s", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    mode: String,
    poseState: PoseState,
    hasCameraPermission: Boolean,
    onCalibrate: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onPlayChime: () -> Unit,
    onFinishSet: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var calibrationCountdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(calibrationCountdown) {
        if (calibrationCountdown == 5) {
            val msg = if (poseState.currentLanguage == "Bengali") "পাঁচ... চার... তিন... দুই... এক" else "Calibrating in 5... 4... 3... 2... 1."
            onSpeak(msg)
        }
        if (calibrationCountdown > 0) {
            kotlinx.coroutines.delay(1000)
            calibrationCountdown--
            if (calibrationCountdown == 0) {
                onCalibrate()
                onPlayChime()
                val successMsg = if (poseState.currentLanguage == "Bengali") "ক্যালিব্রেশন সম্পূর্ণ" else "Calibration Complete"
                onSpeak(successMsg)
            }
        }
    }

    BottomSheetScaffold(
        sheetPeekHeight = 64.dp,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        sheetContainerColor = Color(0xFF1C1C1E),
        containerColor = Color.Transparent,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "CONTROLS",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Language Selector
                val languages = listOf("English", "Hindi", "Bengali")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    languages.forEach { lang ->
                        val isSelected = poseState.currentLanguage == lang
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFF007AFF) else Color.Black)
                                .clickable { onLanguageChange(lang) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lang,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                // Stats Grid
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
                            .padding(16.dp)
                    ) {
                        Text("JOINT ANGLE", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(poseState.currentAngle.ifEmpty { "--" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black)
                            .padding(16.dp)
                    ) {
                        Text("REPS", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(String.format("%02d", poseState.repCount), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (mode == "Bicep Curls") {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { if (calibrationCountdown == 0) calibrationCountdown = 5 },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF007AFF)),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF007AFF)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("START CALIBRATION", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onFinishSet,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                    shape = RoundedCornerShape(32.dp)
                ) {
                    Text("FINISH SET", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCameraPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                
                            val mainActivity = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<MainActivity>().firstOrNull()
                            
                            if (mainActivity != null) {
                                imageAnalysis.setAnalyzer(mainActivity.cameraExecutor) { imageProxy ->
                                    mainActivity.analyzeImage(imageProxy)
                                }
                                
                                try {
                                    cameraProvider.unbindAll()
                                    val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                                    val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                                    val selector = if (hasFront) CameraSelector.DEFAULT_FRONT_CAMERA else if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else null
                                    
                                    mainActivity.poseState.value = mainActivity.poseState.value.copy(isFrontCamera = hasFront)
                                    
                                    if (selector != null) {
                                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                                    }
                                } catch (e: Exception) {
                                    Log.e("Camera", "Use case binding failed", e)
                                }
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        
                        previewView
                    }
                )
                
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    if (poseState.landmarks.isNotEmpty()) {
                        val viewWidth = size.width
                        val viewHeight = size.height
                        
                        val imgWidth = poseState.imageWidth.toFloat()
                        val imgHeight = poseState.imageHeight.toFloat()
                        
                        val scaleFactor = maxOf(viewWidth / imgWidth, viewHeight / imgHeight)
                        val scaledWidth = imgWidth * scaleFactor
                        val scaledHeight = imgHeight * scaleFactor
                        
                        val offsetX = (viewWidth - scaledWidth) / 2f
                        val offsetY = (viewHeight - scaledHeight) / 2f
                        
                        val isMirrored = poseState.isFrontCamera

                        val connections = listOf(
                            11 to 12, 12 to 24, 24 to 23, 23 to 11,
                            11 to 13, 13 to 15, 12 to 14, 14 to 16,
                            23 to 25, 25 to 27, 24 to 26, 26 to 28
                        )
                        
                        for ((start, end) in connections) {
                            val lm1 = poseState.landmarks.getOrNull(start)
                            val lm2 = poseState.landmarks.getOrNull(end)
                            
                            val vis1 = if (lm1 != null && lm1.visibility().isPresent) lm1.visibility().get() else 0f
                            val vis2 = if (lm2 != null && lm2.visibility().isPresent) lm2.visibility().get() else 0f
                            
                            if (vis1 > 0.6f && vis2 > 0.6f) {
                                val x1 = if (isMirrored) 1f - lm1!!.x() else lm1!!.x()
                                val x2 = if (isMirrored) 1f - lm2!!.x() else lm2!!.x()
                                
                                val mappedX1 = (x1 * scaledWidth) + offsetX
                                val mappedY1 = (lm1!!.y() * scaledHeight) + offsetY
                                val mappedX2 = (x2 * scaledWidth) + offsetX
                                val mappedY2 = (lm2!!.y() * scaledHeight) + offsetY
                                
                                drawLine(
                                    color = Color.White,
                                    start = Offset(mappedX1, mappedY1),
                                    end = Offset(mappedX2, mappedY2),
                                    strokeWidth = 10f
                                )
                            }
                        }
                        
                        for (i in 11..28) {
                            val lm = poseState.landmarks.getOrNull(i)
                            if (lm != null) {
                                val vis = if (lm.visibility().isPresent) lm.visibility().get() else 0f
                                if (vis > 0.6f) {
                                    val x = if (isMirrored) 1f - lm.x() else lm.x()
                                    val mappedX = (x * scaledWidth) + offsetX
                                    val mappedY = (lm.y() * scaledHeight) + offsetY
                                    
                                    drawCircle(
                                        color = Color(0xFF34C759),
                                        radius = 14f,
                                        center = Offset(mappedX, mappedY)
                                    )
                                }
                            }
                        }
                    }
                }

                // Top HUD Overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .statusBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = mode.uppercase(),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        val isError = poseState.statusText.contains("!") || poseState.statusText.contains("Adjust Position") || poseState.statusText.contains("collapsed") || poseState.statusText.contains("Illegal action")
                        val statusColor = if (isError) Color(0xFFFF3B30) else Color(0xFF34C759)
                        
                        Text(
                            text = poseState.statusText.ifEmpty { "READY" }.uppercase(),
                            color = statusColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    
                    var oldCalories by remember { mutableFloatStateOf(poseState.caloriesBurnt) }
                    val scale = remember { Animatable(1f) }
                    
                    LaunchedEffect(poseState.caloriesBurnt) {
                        if (poseState.caloriesBurnt > oldCalories) {
                            scale.animateTo(targetValue = 1.15f, animationSpec = tween(100))
                            scale.animateTo(targetValue = 1f, animationSpec = tween(100))
                            oldCalories = poseState.caloriesBurnt
                        } else if (poseState.caloriesBurnt == 0f) {
                            oldCalories = 0f
                        }
                    }
                    
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .scale(scale.value)
                            .shadow(
                                elevation = ((scale.value - 1f) * 100).dp, 
                                shape = RoundedCornerShape(16.dp), 
                                spotColor = Color(0xFFFF9F0A),
                                ambientColor = Color(0xFFFF9F0A)
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1C1C1E))
                            .padding(16.dp)
                    ) {
                        Text("CALORIES", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format("%.1f", poseState.caloriesBurnt),
                            color = Color(0xFFFF9F0A),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Camera permission required.", color = Color.White)
                }
            }

            if (calibrationCountdown > 0) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = calibrationCountdown.toString(),
                        color = Color.White,
                        fontSize = 120.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .padding(16.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
