package kr.or.katri.carraising

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class Screen { Home, Preparation, PowertrainSelection, Track }
private enum class SessionState { Idle, Ready, Running, Paused, Finished }
private enum class DrivingActionState { CRUISING, WAITING_FOR_BRAKE_LINE, DECELERATING, ACCELERATING }

private data class PrepItem(
    val title: String,
    val detail: String,
    val skippable: Boolean = false,
    val required: Boolean = true
)

private data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

private data class BrakeLineFrame(
    val tangentEast: Double,
    val tangentNorth: Double
)

private data class SpeedSample(
    val speedKmh: Double,
    val isValidForControl: Boolean
)

class MainActivity : Activity() {
    private val permissionRequestCode = 1001
    private val locationManager: LocationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private var locationListener: LocationListener? = null
    private var isLocationListening = false

    private var currentScreen = Screen.Home
    private var sessionState = SessionState.Idle

    private var currentLocation: Location? = null
    private var previousLocation: Location? = null
    private var previousLocationUpdateMs = 0L

    private var totalDistanceM = 0.0
    private var lastGateDistanceM = 0.0
    private var wasInBrakeLineGate = false
    private var currentSpeedKmh = 0.0
    private var latestRawSpeedKmh: Double? = null
    private var insideTrackArea = true
    private var brakeLineLocation: Location? = null
    private var activeScenarioStepId: String? = null
    private var drivingActionState = DrivingActionState.CRUISING
    private var odoConfirmedAtSessionDistanceM = 0.0

    private val currentRoutePoints = ArrayList<GeoPoint>()
    private val referenceRoutePoints = ArrayList<GeoPoint>()
    private var hasReferenceRoute = false

    private var pendingPermissionAction: (() -> Unit)? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            if (currentScreen == Screen.Track) {
                updateTrackUi()
            }
            if (!isFinishing) {
                uiHandler.postDelayed(this, 1000L)
            }
        }
    }

    private var previewView: TrackPreviewView? = null
    private var tvSpeed: TextView? = null
    private var tvDistance: TextView? = null
    private var tvStatus: TextView? = null
    private var tvBoundary: TextView? = null
    private var tvBrakeLine: TextView? = null
    private var tvScenarioSection: TextView? = null
    private var tvScenarioProgress: TextView? = null
    private var tvScenarioTarget: TextView? = null
    private var tvScenarioInstruction: TextView? = null
    private var redFlashOverlay: View? = null
    private var lapAlarmTone: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false
    private var isTextToSpeechInitializationFinished = false
    private var isBrakeVoiceActive = false
    private var brakeVoiceTargetKmh: Int? = null
    private var brakeVoiceUtteranceSequence = 0L
    private var currentBrakeVoiceUtteranceId: String? = null
    private var hasShownVoiceUnavailableToast = false

    private var btnSetBrakeLine: Button? = null
    private var btnStartPause: Button? = null
    private var btnStop: Button? = null

    private var selectedPowertrain: PowertrainType? = null
    private var selectedTestMode = "9000km 고주로 주행"
    private var selectedStartOdo = ""
    private var selectedTrackOdo = ""

    private val gpsGateMinDistanceM = 4_000.0
    private val brakeLineFallbackRadiusM = 45.0
    private val brakeLineDetectionHalfWidthM = 25.0
    private val brakeLineHalfLengthM = 60.0
    private val speedTargetToleranceKmh = 3.0
    private val locationUpdateIntervalMs = 500L
    private val locationUpdateMinDistanceM = 1f
    private val brakeVoiceRepeatDelayMs = 1_000L
    private val routePointMinSpacingM = 3.0
    private val stationaryDistanceM = 4.0
    private val minimumMovingSpeedKmh = 0.8
    private val lowSpeedGpsJitterKmh = 12.0
    private val minSpeedSampleSeconds = 0.5
    private val maxLocationAccuracyM = 25f
    private val maxSpeedAccuracyMps = 1.5f
    private val speedSmoothingAlpha = 0.35
    private val staleSpeedTimeoutMs = 3_500L
    private val referenceRouteToleranceM = 120.0
    private val referenceRouteMinPoints = 80
    private val referenceRouteMinDistanceM = 4_000.0
    private val maxCurrentRoutePoints = 5_000
    private val referenceRoutePrefsKey = "reference_route_points"
    private val brakeLineLatitudePrefsKey = "brake_line_latitude"
    private val brakeLineLongitudePrefsKey = "brake_line_longitude"
    private var isRedFlashLoopActive = false

    private val redFlashLoopRunnable = object : Runnable {
        override fun run() {
            runRedFlashPulse()
        }
    }

    private val brakeVoiceRepeatRunnable = Runnable {
        speakBrakeInstructionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadReferenceRoute()
        loadBrakeLine()
        initLocationListener()
        initTextToSpeech()
        showHome()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.post(uiUpdateRunnable)
        if (hasLocationPermission()) {
            startLocationUpdates()
        }
        updateTrackUi()
        resumeBrakeAlertIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        stopRedFlashLoop()
        stopLocationUpdates()
    }

    override fun onDestroy() {
        stopRedFlashLoop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTextToSpeechReady = false
        lapAlarmTone?.release()
        lapAlarmTone = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (currentScreen == Screen.Home) super.onBackPressed() else showHome()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            isTextToSpeechInitializationFinished = true
            val engine = textToSpeech
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val languageResult = engine.setLanguage(Locale.KOREAN)
                isTextToSpeechReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (isTextToSpeechReady) {
                    engine.setSpeechRate(0.95f)
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            onBrakeVoiceUtteranceFinished(utteranceId, failed = false)
                        }

                        @Deprecated("Deprecated in Android")
                        override fun onError(utteranceId: String?) {
                            onBrakeVoiceUtteranceFinished(utteranceId, failed = true)
                        }
                    })
                }
            } else {
                isTextToSpeechReady = false
            }

            uiHandler.post {
                if (isBrakeVoiceActive) speakBrakeInstructionIfNeeded()
            }
        }
    }

    private fun startBrakeVoicePrompt(step: DrivingScenarioStep) {
        val targetKmh = step.decelTargetKmh ?: return
        stopBrakeVoicePrompt()
        brakeVoiceTargetKmh = targetKmh
        isBrakeVoiceActive = true
        speakBrakeInstructionIfNeeded()
    }

    private fun stopBrakeVoicePrompt() {
        uiHandler.removeCallbacks(brakeVoiceRepeatRunnable)
        if (!isBrakeVoiceActive && currentBrakeVoiceUtteranceId == null) return

        isBrakeVoiceActive = false
        brakeVoiceTargetKmh = null
        currentBrakeVoiceUtteranceId = null
        textToSpeech?.stop()
    }

    private fun speakBrakeInstructionIfNeeded() {
        uiHandler.removeCallbacks(brakeVoiceRepeatRunnable)
        val targetKmh = brakeVoiceTargetKmh ?: return
        val step = currentScenarioStep()
        if (
            !isBrakeVoiceActive ||
            sessionState != SessionState.Running ||
            drivingActionState != DrivingActionState.DECELERATING ||
            step?.decelTargetKmh != targetKmh ||
            hasReachedDecelerationTarget(targetKmh)
        ) {
            stopBrakeVoicePrompt()
            return
        }

        val engine = textToSpeech
        if (!isTextToSpeechReady || engine == null) {
            playFallbackAlarmTone()
            showVoiceUnavailableMessageIfNeeded()
            uiHandler.postDelayed(brakeVoiceRepeatRunnable, brakeVoiceRepeatDelayMs)
            return
        }

        brakeVoiceUtteranceSequence += 1L
        val utteranceId = "brake-guidance-$brakeVoiceUtteranceSequence"
        currentBrakeVoiceUtteranceId = utteranceId
        val result = engine.speak(
            brakeVoiceMessage(targetKmh),
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            currentBrakeVoiceUtteranceId = null
            playFallbackAlarmTone()
            uiHandler.postDelayed(brakeVoiceRepeatRunnable, brakeVoiceRepeatDelayMs)
        }
    }

    private fun onBrakeVoiceUtteranceFinished(utteranceId: String?, failed: Boolean) {
        uiHandler.post {
            if (!isBrakeVoiceActive || utteranceId != currentBrakeVoiceUtteranceId) return@post
            currentBrakeVoiceUtteranceId = null
            if (failed) playFallbackAlarmTone()
            uiHandler.removeCallbacks(brakeVoiceRepeatRunnable)
            uiHandler.postDelayed(brakeVoiceRepeatRunnable, brakeVoiceRepeatDelayMs)
        }
    }

    private fun brakeVoiceMessage(targetKmh: Int): String {
        return if (targetKmh <= 0) {
            "완전히 정차하세요."
        } else {
            "시속 ${targetKmh}킬로미터까지 감속하세요."
        }
    }

    private fun showVoiceUnavailableMessageIfNeeded() {
        if (!isTextToSpeechInitializationFinished || hasShownVoiceUnavailableToast) return
        hasShownVoiceUnavailableToast = true
        Toast.makeText(this, "한국어 음성 안내를 사용할 수 없어 경고음으로 알립니다.", Toast.LENGTH_LONG).show()
    }

    private fun playFallbackAlarmTone() {
        if (lapAlarmTone == null) {
            lapAlarmTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        }
        lapAlarmTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
    }

    private fun resumeBrakeAlertIfNeeded() {
        if (
            currentScreen != Screen.Track ||
            sessionState != SessionState.Running ||
            drivingActionState != DrivingActionState.DECELERATING
        ) return

        val step = currentScenarioStep() ?: return
        val targetKmh = step.decelTargetKmh ?: return
        if (hasReachedDecelerationTarget(targetKmh)) return
        startRedFlashLoop()
        if (!isBrakeVoiceActive) startBrakeVoicePrompt(step)
    }

    private fun initLocationListener() {
        if (locationListener != null) return
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocationUpdated(location)
            }

            @Deprecated("Deprecated in Android")
            override fun onProviderDisabled(provider: String) {
                // no-op
            }

            @Deprecated("Deprecated in Android")
            override fun onProviderEnabled(provider: String) {
                // no-op
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                // no-op
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission(action: () -> Unit) {
        if (hasLocationPermission()) {
            action()
            return
        }
        pendingPermissionAction = action
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            permissionRequestCode
        )
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != permissionRequestCode) return
        val allowed = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (allowed) {
            action?.invoke()
            startLocationUpdates()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        if (isLocationListening || !hasLocationPermission()) return
        val listener = locationListener ?: return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> ""
        }
        if (provider.isEmpty()) {
            Toast.makeText(this, "GPS 또는 네트워크 위치가 꺼져 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        locationManager.requestLocationUpdates(
            provider,
            locationUpdateIntervalMs,
            locationUpdateMinDistanceM,
            listener,
            mainLooper
        )
        isLocationListening = true
        locationManager.getLastKnownLocation(provider)?.let { onLocationUpdated(it) }
    }

    private fun stopLocationUpdates() {
        if (!isLocationListening) return
        locationListener?.let { locationManager.removeUpdates(it) }
        isLocationListening = false
    }

    private fun onLocationUpdated(location: Location) {
        val prev = currentLocation
        val nowMs = SystemClock.elapsedRealtime()

        if (prev != null) {
            val dtMs = max(1L, nowMs - previousLocationUpdateMs)
            val deltaM = prev.distanceTo(location).coerceAtLeast(0f).toDouble()
            val dtSec = dtMs / 1000.0
            val speedSample = calculateSpeedSample(prev, location, dtSec, deltaM)
            latestRawSpeedKmh = speedSample.speedKmh.takeIf { speedSample.isValidForControl }
            currentSpeedKmh = smoothSpeedKmh(speedSample.speedKmh)
            val acceptedDeltaM = if (shouldAcceptMovement(location, deltaM, currentSpeedKmh)) deltaM else 0.0

            if (sessionState == SessionState.Running && acceptedDeltaM > 0.0) {
                totalDistanceM += acceptedDeltaM
                syncScenarioState()
                appendCurrentRoutePoint(location)
                evaluateLap(location)
                updateDrivingActionState()
                updateTrackAreaState(location)
                previewView?.setState(
                    isInTrack = insideTrackArea,
                    isRunning = sessionState == SessionState.Running,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            previousLocationUpdateMs = nowMs
        } else {
            previousLocationUpdateMs = nowMs
            val speedSample = calculateSpeedSample(null, location, 0.0, 0.0)
            latestRawSpeedKmh = speedSample.speedKmh.takeIf { speedSample.isValidForControl }
            currentSpeedKmh = smoothSpeedKmh(speedSample.speedKmh)
        }

        previousLocation = prev ?: location
        currentLocation = location
        if (brakeLineLocation != null && sessionState != SessionState.Idle) {
            updateTrackAreaState(location)
        }
        if (sessionState != SessionState.Running) {
            stopRedFlashLoop()
        }
        updateTrackUi()
    }

    private fun calculateSpeedSample(
        prev: Location?,
        location: Location,
        dtSec: Double,
        deltaM: Double
    ): SpeedSample {
        if (!isUsableLocation(location)) return SpeedSample(0.0, isValidForControl = false)

        val hasTrustedGpsSpeed = isTrustedGpsSpeed(location)
        val gpsSpeedKmh = if (hasTrustedGpsSpeed) {
            location.speed.toDouble() * 3.6
        } else {
            null
        }
        val distanceSpeedKmh = if (
            prev != null &&
            isUsableLocation(prev) &&
            dtSec >= minSpeedSampleSeconds &&
            deltaM >= stationaryDistanceM
        ) {
            deltaM / dtSec * 3.6
        } else {
            null
        }

        val rawSpeedKmh = gpsSpeedKmh ?: distanceSpeedKmh ?: 0.0
        val hasStationaryEvidence =
            prev != null &&
                isUsableLocation(prev) &&
                dtSec >= minSpeedSampleSeconds &&
                deltaM < stationaryDistanceM
        val normalizedSpeedKmh = if (
            !hasTrustedGpsSpeed &&
            prev != null &&
            deltaM < stationaryDistanceM &&
            rawSpeedKmh < lowSpeedGpsJitterKmh
        ) {
            0.0
        } else if (rawSpeedKmh < minimumMovingSpeedKmh) {
            0.0
        } else {
            rawSpeedKmh
        }
        return SpeedSample(
            speedKmh = normalizedSpeedKmh,
            isValidForControl = gpsSpeedKmh != null || distanceSpeedKmh != null || hasStationaryEvidence
        )
    }

    private fun smoothSpeedKmh(rawSpeedKmh: Double): Double {
        if (rawSpeedKmh <= 0.0) return 0.0
        if (currentSpeedKmh <= 0.0) return rawSpeedKmh
        return currentSpeedKmh * (1.0 - speedSmoothingAlpha) + rawSpeedKmh * speedSmoothingAlpha
    }

    private fun shouldAcceptMovement(location: Location, deltaM: Double, speedKmh: Double): Boolean {
        val trustedGpsMovement = isTrustedGpsSpeed(location) && deltaM >= 0.5
        return isUsableLocation(location) &&
            speedKmh >= minimumMovingSpeedKmh &&
            (deltaM >= stationaryDistanceM || trustedGpsMovement)
    }

    private fun isUsableLocation(location: Location): Boolean {
        return !location.hasAccuracy() || location.accuracy <= maxLocationAccuracyM
    }

    private fun isTrustedGpsSpeed(location: Location): Boolean {
        if (location.provider != LocationManager.GPS_PROVIDER) return false
        if (!location.hasSpeed()) return false
        if (!isUsableLocation(location)) return false
        return !location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= maxSpeedAccuracyMps
    }

    private fun startRedFlashLoop() {
        if (redFlashOverlay == null) return
        if (isRedFlashLoopActive) return
        isRedFlashLoopActive = true
        uiHandler.removeCallbacks(redFlashLoopRunnable)
        redFlashLoopRunnable.run()
    }

    private fun stopRedFlashLoop() {
        isRedFlashLoopActive = false
        uiHandler.removeCallbacks(redFlashLoopRunnable)
        redFlashOverlay?.animate()?.cancel()
        redFlashOverlay?.alpha = 0f
        redFlashOverlay?.visibility = View.GONE
        stopBrakeVoicePrompt()
    }

    private fun runRedFlashPulse() {
        if (!isRedFlashLoopActive) return
        val overlay = redFlashOverlay ?: run {
            isRedFlashLoopActive = false
            return
        }
        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate()
            .alpha(0.55f)
            .setDuration(120L)
            .withEndAction {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(520L)
                    .withEndAction {
                        overlay.visibility = View.GONE
                        if (isRedFlashLoopActive) uiHandler.postDelayed(redFlashLoopRunnable, 180L)
                    }
                    .start()
            }
            .start()
    }

    private fun evaluateLap(location: Location) {
        val inGate = isInBrakeLineGate(location)
        val traveledFromLastGate = totalDistanceM - lastGateDistanceM
        if (!wasInBrakeLineGate && inGate && traveledFromLastGate >= gpsGateMinDistanceM) {
            lastGateDistanceM = totalDistanceM
            wasInBrakeLineGate = true
            saveReferenceRouteIfReady()
            startNewLapRoute(location)

            val step = currentScenarioStep()
            if (step?.driveMode == ScenarioDriveMode.ACCEL_DECEL) {
                drivingActionState = DrivingActionState.DECELERATING
                triggerLapAlarm(step)
            } else {
                drivingActionState = DrivingActionState.CRUISING
            }
        } else if (wasInBrakeLineGate && !inGate) {
            wasInBrakeLineGate = false
        }
    }

    private fun triggerLapAlarm(step: DrivingScenarioStep) {
        startRedFlashLoop()
        startBrakeVoicePrompt(step)
    }

    private fun isInBrakeLineGate(location: Location): Boolean {
        val center = brakeLineLocation ?: return false
        val frame = brakeLineFrame()
            ?: return location.distanceTo(center) <= brakeLineFallbackRadiusM

        val eastM = longitudeDeltaMeters(location.longitude - center.longitude, center.latitude)
        val northM = latitudeDeltaMeters(location.latitude - center.latitude)
        val alongTrackM = eastM * frame.tangentEast + northM * frame.tangentNorth
        val acrossTrackM = -eastM * frame.tangentNorth + northM * frame.tangentEast
        return abs(alongTrackM) <= brakeLineDetectionHalfWidthM &&
            abs(acrossTrackM) <= brakeLineHalfLengthM
    }

    private fun brakeLineFrame(): BrakeLineFrame? {
        val center = brakeLineLocation ?: return null
        val useClosedReference = hasReferenceRoute && referenceRoutePoints.size >= 4
        val route = if (useClosedReference) referenceRoutePoints else currentRoutePoints
        if (route.size < 2) return null

        var nearestIndex = 0
        var nearestDistanceM = Double.POSITIVE_INFINITY
        route.forEachIndexed { index, point ->
            val distanceM = distanceMeters(center.latitude, center.longitude, point.latitude, point.longitude)
            if (distanceM < nearestDistanceM) {
                nearestDistanceM = distanceM
                nearestIndex = index
            }
        }

        val sampleOffset = min(4, max(1, route.size / 8))
        val beforeIndex: Int
        val afterIndex: Int
        if (useClosedReference) {
            beforeIndex = (nearestIndex - sampleOffset + route.size) % route.size
            afterIndex = (nearestIndex + sampleOffset) % route.size
        } else {
            beforeIndex = max(0, nearestIndex - sampleOffset)
            afterIndex = min(route.lastIndex, nearestIndex + sampleOffset)
        }
        if (beforeIndex == afterIndex) return null

        val before = route[beforeIndex]
        val after = route[afterIndex]
        val eastM = longitudeDeltaMeters(after.longitude - before.longitude, center.latitude)
        val northM = latitudeDeltaMeters(after.latitude - before.latitude)
        val lengthM = sqrt(eastM * eastM + northM * northM)
        if (lengthM < 1.0) return null
        return BrakeLineFrame(eastM / lengthM, northM / lengthM)
    }

    private fun updateTrackAreaState(location: Location) {
        if (!hasReferenceRoute || referenceRoutePoints.size < 2) {
            insideTrackArea = true
            return
        }

        val dist = distanceToReferenceRouteM(location.latitude, location.longitude)
        insideTrackArea = dist <= referenceRouteToleranceM
    }

    private fun appendCurrentRoutePoint(location: Location, force: Boolean = false) {
        val point = GeoPoint(location.latitude, location.longitude)
        val last = currentRoutePoints.lastOrNull()
        val shouldAppend = force ||
            last == null ||
            distanceMeters(last.latitude, last.longitude, point.latitude, point.longitude) >= routePointMinSpacingM
        if (!shouldAppend) return

        currentRoutePoints.add(point)
        if (currentRoutePoints.size > maxCurrentRoutePoints) {
            currentRoutePoints.removeAt(0)
        }
    }

    private fun saveReferenceRouteIfReady() {
        if (hasReferenceRoute) return
        if (currentRoutePoints.size < referenceRouteMinPoints) return
        if (totalDistanceM < referenceRouteMinDistanceM) return

        referenceRoutePoints.clear()
        referenceRoutePoints.addAll(smoothRoute(currentRoutePoints))
        hasReferenceRoute = true
        saveReferenceRoute()
        previewView?.setReferenceRoute(referenceRoutePoints)
        Toast.makeText(this, "기준 주행 경로가 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun smoothRoute(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 5) return points.toList()
        val radius = 2
        return points.indices.map { index ->
            val from = max(0, index - radius)
            val to = min(points.lastIndex, index + radius)
            var latitudeSum = 0.0
            var longitudeSum = 0.0
            for (sampleIndex in from..to) {
                latitudeSum += points[sampleIndex].latitude
                longitudeSum += points[sampleIndex].longitude
            }
            val sampleCount = (to - from + 1).toDouble()
            GeoPoint(latitudeSum / sampleCount, longitudeSum / sampleCount)
        }
    }

    private fun startNewLapRoute(location: Location) {
        currentRoutePoints.clear()
        appendCurrentRoutePoint(location, force = true)
        previewView?.beginLap(location.latitude, location.longitude)
    }

    private fun loadReferenceRoute() {
        val encoded = getPreferences(MODE_PRIVATE).getString(referenceRoutePrefsKey, null) ?: return
        referenceRoutePoints.clear()
        encoded.split(";").forEach { token ->
            val parts = token.split(",")
            if (parts.size == 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) referenceRoutePoints.add(GeoPoint(lat, lon))
            }
        }
        hasReferenceRoute = referenceRoutePoints.size >= referenceRouteMinPoints
    }

    private fun loadBrakeLine() {
        val prefs = getPreferences(MODE_PRIVATE)
        val latitude = prefs.getString(brakeLineLatitudePrefsKey, null)?.toDoubleOrNull() ?: return
        val longitude = prefs.getString(brakeLineLongitudePrefsKey, null)?.toDoubleOrNull() ?: return
        brakeLineLocation = Location("saved_brake_line").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
    }

    private fun saveBrakeLine() {
        val line = brakeLineLocation ?: return
        getPreferences(MODE_PRIVATE).edit()
            .putString(brakeLineLatitudePrefsKey, line.latitude.toString())
            .putString(brakeLineLongitudePrefsKey, line.longitude.toString())
            .apply()
    }

    private fun saveReferenceRoute() {
        val encoded = referenceRoutePoints.joinToString(";") { "${it.latitude},${it.longitude}" }
        getPreferences(MODE_PRIVATE).edit().putString(referenceRoutePrefsKey, encoded).apply()
    }

    private fun clearReferenceRoute() {
        referenceRoutePoints.clear()
        hasReferenceRoute = false
        getPreferences(MODE_PRIVATE).edit().remove(referenceRoutePrefsKey).apply()
    }

    private fun distanceToReferenceRouteM(latitude: Double, longitude: Double): Double {
        var best = Double.POSITIVE_INFINITY
        for (index in 0 until referenceRoutePoints.size - 1) {
            best = min(best, distanceToSegmentM(latitude, longitude, referenceRoutePoints[index], referenceRoutePoints[index + 1]))
        }
        if (referenceRoutePoints.size > 2) {
            best = min(best, distanceToSegmentM(latitude, longitude, referenceRoutePoints.last(), referenceRoutePoints.first()))
        }
        return best
    }

    private fun distanceToSegmentM(latitude: Double, longitude: Double, start: GeoPoint, end: GeoPoint): Double {
        val ax = longitudeDeltaMeters(start.longitude - longitude, latitude)
        val ay = latitudeDeltaMeters(start.latitude - latitude)
        val bx = longitudeDeltaMeters(end.longitude - longitude, latitude)
        val by = latitudeDeltaMeters(end.latitude - latitude)
        val vx = bx - ax
        val vy = by - ay
        val lengthSquared = vx * vx + vy * vy
        if (lengthSquared <= 0.001) return sqrt(ax * ax + ay * ay)

        val t = (-(ax * vx + ay * vy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + t * vx
        val py = ay + t * vy
        return sqrt(px * px + py * py)
    }

    private fun latitudeDeltaMeters(deltaLatitude: Double): Double = deltaLatitude * 110_540.0

    private fun longitudeDeltaMeters(deltaLongitude: Double, baseLatitude: Double): Double {
        return deltaLongitude * 111_320.0 * cos(Math.toRadians(baseLatitude))
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    private fun testProgressDistanceKm(): Double {
        val gpsDistanceSinceOdoConfirmationKm =
            max(0.0, totalDistanceM - odoConfirmedAtSessionDistanceM) / 1000.0
        return confirmedOdoProgressKmOrNull()?.plus(gpsDistanceSinceOdoConfirmationKm)
            ?: (totalDistanceM / 1000.0)
    }

    private fun currentScenarioStep(): DrivingScenarioStep? {
        val powertrain = selectedPowertrain ?: return null
        return KatriDrivingScenario.stepAt(testProgressDistanceKm(), powertrain)
    }

    private fun syncScenarioState() {
        val step = currentScenarioStep()
        if (step?.id == activeScenarioStepId) return

        activeScenarioStepId = step?.id
        drivingActionState = when (step?.driveMode) {
            ScenarioDriveMode.ACCEL_DECEL -> DrivingActionState.WAITING_FOR_BRAKE_LINE
            else -> DrivingActionState.CRUISING
        }
        stopRedFlashLoop()
    }

    private fun hasReachedDecelerationTarget(targetKmh: Int): Boolean {
        return DrivingSpeedRules.hasReachedDecelerationTarget(
            targetKmh = targetKmh,
            displayedSpeedKmh = currentSpeedKmh,
            latestRawSpeedKmh = latestRawSpeedKmh,
            toleranceKmh = speedTargetToleranceKmh
        )
    }

    private fun updateDrivingActionState() {
        if (sessionState != SessionState.Running) return
        val step = currentScenarioStep() ?: return
        if (step.driveMode != ScenarioDriveMode.ACCEL_DECEL) {
            drivingActionState = DrivingActionState.CRUISING
            stopRedFlashLoop()
            return
        }

        when (drivingActionState) {
            DrivingActionState.DECELERATING -> {
                val target = step.decelTargetKmh ?: return
                if (hasReachedDecelerationTarget(target)) {
                    drivingActionState = DrivingActionState.ACCELERATING
                    stopRedFlashLoop()
                }
            }
            DrivingActionState.ACCELERATING -> {
                if (currentSpeedKmh >= step.targetSpeedKmh - speedTargetToleranceKmh) {
                    drivingActionState = DrivingActionState.WAITING_FOR_BRAKE_LINE
                }
            }
            else -> Unit
        }
    }

    private fun scenarioInstruction(step: DrivingScenarioStep?): String {
        if (step == null) return "9000km 시험이 완료되었거나 적용할 시나리오가 없습니다."
        if (step.driveMode == ScenarioDriveMode.CONSTANT) {
            return "${step.targetSpeedKmh}km/h 정속 유지 · 감속 없음"
        }

        val decelTarget = step.decelTargetKmh ?: 0
        return when (drivingActionState) {
            DrivingActionState.WAITING_FOR_BRAKE_LINE ->
                "브레이크 시작선 통과 시 ${decelTarget}km/h까지 완감속"
            DrivingActionState.DECELERATING ->
                "완감속 중 · ${decelTarget}km/h까지 감속"
            DrivingActionState.ACCELERATING ->
                "${step.accelerationMethod.label} · ${step.targetSpeedKmh}km/h까지 재가속"
            DrivingActionState.CRUISING ->
                "브레이크 시작선 통과 대기"
        }
    }

    private fun currentStatusLabel(): String = when (sessionState) {
        SessionState.Idle -> "상태: 대기"
        SessionState.Ready -> "상태: 대기"
        SessionState.Running -> "상태: 주행 중"
        SessionState.Paused -> "상태: 일시정지"
        SessionState.Finished -> "상태: 종료"
    }

    private fun showHome() {
        stopRedFlashLoop()
        currentScreen = Screen.Home
        setContentView(createHomeView())
    }

    private fun showPreparation() {
        currentScreen = Screen.Preparation
        setContentView(createPreparationView())
    }

    private fun showPowertrainSelection() {
        stopRedFlashLoop()
        currentScreen = Screen.PowertrainSelection
        setContentView(createPowertrainSelectionView())
    }

    private fun showTrack() {
        if (selectedPowertrain == null) {
            showPowertrainSelection()
            return
        }
        currentScreen = Screen.Track
        setContentView(createTrackView())
        if (hasLocationPermission()) startLocationUpdates() else requestLocationPermission { startLocationUpdates() }
        updateTrackUi()
    }

    private fun createHomeView(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(ScreenColors.Background)
            layoutParams = matchParent()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(ScreenColors.Text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "GPS 주행 / 시나리오 안내"
            textSize = 15f
            setTextColor(ScreenColors.MutedText)
            gravity = Gravity.CENTER
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.app_name)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.WHITE, dp(8), ScreenColors.Border)
        }
        content.addView(
            logo,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(168)).apply {
                bottomMargin = dp(18)
            }
        )
        content.addView(title, fullWidth())
        content.addView(subtitle, fullWidth())
        content.addView(space(dp(42)))
        content.addView(
            primaryButton("고주로 주행") { showPowertrainSelection() },
            largeButton()
        )
        root.addView(content, matchParent())

        val preparationGuideButton = secondaryButton("시험 준비 가이드") {
            showPreparation()
        }.apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), 0, dp(12), 0)
        }
        root.addView(
            preparationGuideButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(20)
                rightMargin = dp(20)
            }
        )
        return root
    }

    private fun createPowertrainSelectionView(): LinearLayout {
        val root = screenRoot()
        root.addView(toolbar("차량 유형 선택", "고주로 주행 시나리오"))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(
            primaryButton("내연기관") {
                selectPowertrainAndShowTrack(PowertrainType.COMBUSTION)
            },
            largeButton()
        )
        content.addView(
            primaryButton("하이브리드 차량") {
                selectPowertrainAndShowTrack(PowertrainType.HYBRID)
            },
            largeButton()
        )
        root.addView(content, weightedContent())
        return root
    }

    private fun selectPowertrainAndShowTrack(powertrainType: PowertrainType) {
        selectedPowertrain = powertrainType
        activeScenarioStepId = null
        drivingActionState = DrivingActionState.CRUISING
        showTrack()
    }

    private fun createPreparationView(): LinearLayout {
        val root = screenRoot()
        root.addView(toolbar("시험 준비 가이드", "시험 방식 및 체크리스트"))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        content.addView(sectionTitle("시험"))
        content.addView(formLabel("시험 방식"))
        content.addView(
            spinnerOf("시험 방식", "9000km 고주로 주행", "단거리 주행", "사용자 설정") {
                selectedTestMode = it
            }
        )
        content.addView(formLabel("시작 ODO"))
        val startOdoInput = numberInput("ODO (km)")
        if (selectedStartOdo.isNotEmpty()) startOdoInput.setText(selectedStartOdo)
        startOdoInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) selectedStartOdo = startOdoInput.text.toString().trim()
        }
        content.addView(startOdoInput)
        content.addView(formLabel("체크리스트"))

        val summary = summaryText("완료: 0/${preparationItems().count { it.required }} 필수")
        content.addView(summary)
        val checks = mutableListOf<Pair<CheckBox, PrepItem>>()
        val updateSummary = {
            val required = checks.filter { it.second.required }
            val done = required.count { it.first.isChecked }
            summary.text = "완료: $done/${required.size} 필수"
        }
        preparationItems().forEach { item ->
            val row = preparationItemRow(item, updateSummary)
            checks.add(row.first to item)
            content.addView(row.second)
        }

        content.addView(sectionTitle("이동"))
        content.addView(
            primaryButton("고주로 주행 화면으로 이동") {
                selectedStartOdo = startOdoInput.text.toString().trim()
                if (selectedTrackOdo.isEmpty()) selectedTrackOdo = selectedStartOdo
                val remaining = checks.count { it.second.required && !it.first.isChecked }
                if (remaining > 0) {
                    Toast.makeText(this, "필수 체크리스트를 먼저 완료하세요. (${remaining}개 남음)", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                showPowertrainSelection()
            },
            compactFullWidth()
        )

        scroll.addView(content)
        root.addView(scroll, weightedContent())
        return root
    }

    private fun createTrackView(): View {
        val container = FrameLayout(this).apply {
            layoutParams = matchParent()
        }
        val root = screenRoot()
        root.addView(toolbar("고주로 주행", "${selectedPowertrain?.label} / $selectedTestMode"))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(22))
        }

        previewView = TrackPreviewView(this).also { view ->
            view.setReferenceRoute(referenceRoutePoints)
            view.setBrakeLine(brakeLineLocation?.latitude, brakeLineLocation?.longitude)
            content.addView(view, fullWidthHeight(dp(230)))
        }

        content.addView(sectionTitle("현재 시나리오"))
        tvScenarioSection = metricTextView("A-1 · 정속")
        tvScenarioProgress = summaryText("0.0~100.0km · 전환까지 100.0km")
        tvScenarioTarget = metricTextView("규정속도 60km/h")
        tvScenarioInstruction = metricTextView("60km/h 정속 유지 · 감속 없음")
        content.addView(
            cardContainer().apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(tvScenarioSection)
                addView(tvScenarioProgress)
                addView(tvScenarioTarget)
                addView(tvScenarioInstruction)
            }
        )
        content.addView(sectionTitle("측정"))

        tvSpeed = metricTextView("0.0 km/h")
        tvDistance = metricTextView("0.00 km")
        tvStatus = metricTextView(currentStatusLabel())
        tvBoundary = metricTextView("고주로: 확인 중")
        tvBrakeLine = metricTextView("브레이크 시작선: 미설정")

        content.addView(metricRow(metricCard("속도", tvSpeed!!), metricCard("시험 누적거리", tvDistance!!)))
        content.addView(tvStatus)
        content.addView(space(dp(6)))
        content.addView(tvBoundary)
        content.addView(tvBrakeLine)
        content.addView(space(dp(10)))

        content.addView(sectionTitle("ODO"))
        content.addView(formLabel("현재 ODO"))
        val trackOdoInput = numberInput("ODO (km)")
        val initialTrackOdo = selectedTrackOdo.ifEmpty { selectedStartOdo }
        val trackOdoSavedText = summaryText(savedOdoLabel(initialTrackOdo))
        if (initialTrackOdo.isNotEmpty()) {
            trackOdoInput.setText(initialTrackOdo)
            trackOdoInput.setSelection(trackOdoInput.text.length)
        }
        val trackOdoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(trackOdoInput, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })
            addView(secondaryButton("확인") {
                val enteredText = trackOdoInput.text.toString().trim()
                val enteredOdo = enteredText.toDoubleOrNull()
                if (enteredOdo == null || enteredOdo < 0.0) {
                    Toast.makeText(this@MainActivity, "올바른 ODO 값을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@secondaryButton
                }
                val startOdo = selectedStartOdo.toDoubleOrNull()
                if (startOdo != null && enteredOdo < startOdo) {
                    Toast.makeText(this@MainActivity, "현재 ODO는 시작 ODO보다 작을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@secondaryButton
                }
                selectedTrackOdo = enteredText
                odoConfirmedAtSessionDistanceM = totalDistanceM
                activeScenarioStepId = null
                syncScenarioState()
                trackOdoSavedText.text = savedOdoLabel(selectedTrackOdo)
                updateTrackUi()
                Toast.makeText(this@MainActivity, "ODO가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(dp(112), dp(52)))
        }
        content.addView(trackOdoRow)
        content.addView(trackOdoSavedText)
        content.addView(space(dp(10)))

        btnSetBrakeLine = secondaryButton("현재 위치를 브레이크 시작선으로 설정") {
            requestLocationPermission { setBrakeLine() }
        }
        btnStartPause = primaryButton("주행 시작") {
            when (sessionState) {
                SessionState.Idle, SessionState.Ready, SessionState.Finished -> startSession()
                SessionState.Running -> pauseSession()
                SessionState.Paused -> resumeSession()
            }
        }
        btnStop = secondaryButton("시험 종료") {
            if (sessionState == SessionState.Finished) {
                resetToReady()
            } else {
                stopSession()
            }
        }

        content.addView(btnSetBrakeLine, compactFullWidth())
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnStartPause, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(8) })
                addView(btnStop, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
            },
            compactFullWidth()
        )

        updateControlButtons()
        scroll.addView(content)
        root.addView(scroll, weightedContent())
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        redFlashOverlay = View(this).apply {
            setBackgroundColor(Color.argb(170, 220, 38, 38))
            alpha = 0f
            visibility = View.GONE
            isClickable = false
        }
        container.addView(redFlashOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun preparationItems(): List<PrepItem> = listOf(
        PrepItem("전면 사진", "차량 전면 촬영"),
        PrepItem("후면 사진", "차량 후면 촬영"),
        PrepItem("좌측면 사진", "차량 좌측면 촬영"),
        PrepItem("우측면 사진", "차량 우측면 촬영"),
        PrepItem("타이어 공기압", "타이어 공기압 입력", skippable = true),
        PrepItem("트레드 깊이", "타이어 트레드 깊이 측정"),
        PrepItem("시작 ODO", "시작 ODO 입력"),
        PrepItem("차량 제원 라벨", "차량 제원 라벨 촬영"),
        PrepItem("타이어 정보", "타이어 제조사 및 제원 촬영", skippable = true),
        PrepItem("엔진룸 1", "엔진룸 사진 촬영"),
        PrepItem("엔진룸 2", "엔진룸 사진 촬영"),
        PrepItem("VBOX/GPS 장착", "VBOX/GPS 장착 상태 확인", skippable = true),
        PrepItem("하부 전단", "차량 하부 전단 촬영"),
        PrepItem("하부 후단", "차량 하부 후단 촬영"),
        PrepItem("FR LH 로어 컨트롤 암", "전륜 좌측 로어 컨트롤 암 촬영", required = false, skippable = true),
        PrepItem("FR RH 로어 컨트롤 암", "전륜 우측 로어 컨트롤 암 촬영", required = false, skippable = true),
        PrepItem("RR LH 로어 컨트롤 암", "후륜 좌측 로어 컨트롤 암 촬영", required = false, skippable = true),
        PrepItem("RR RH 로어 컨트롤 암", "후륜 우측 로어 컨트롤 암 촬영", required = false, skippable = true),
        PrepItem("타이어 위치교환", "편마모 확인용 타이어 위치교환 기록", required = false, skippable = true),
        PrepItem("종료 ODO", "종료 ODO 입력", required = false),
        PrepItem("종료 하부 확인", "하부 및 타이어 원위치 촬영", required = false),
        PrepItem("이벤트 사진", "이벤트 발생 시 추가 사진 촬영", required = false, skippable = true)
    )

    private fun preparationItemRow(item: PrepItem, updateSummary: () -> Unit): Pair<CheckBox, LinearLayout> {
        val check = CheckBox(this)
        val row = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        check.setOnCheckedChangeListener { _, _ -> updateSummary() }
        header.addView(check)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = item.title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ScreenColors.Text)
        })
        texts.addView(TextView(this).apply {
            text = item.detail
            textSize = 13f
            setTextColor(ScreenColors.MutedText)
        })
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(statusBadge(if (item.required) "필수" else "선택"))
        actions.addView(compactButton("완료") {
            check.isChecked = true
            Toast.makeText(this@MainActivity, "${item.title} 완료", Toast.LENGTH_SHORT).show()
        })
        if (item.skippable) {
            actions.addView(compactButton("스킵") {
                check.isChecked = true
                Toast.makeText(this@MainActivity, "${item.title} 스킵", Toast.LENGTH_SHORT).show()
            })
        }
        row.addView(actions)
        return check to row
    }

    private fun startSession() {
        if (brakeLineLocation == null) {
            Toast.makeText(this, "브레이크 시작선을 먼저 설정하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionState == SessionState.Idle || sessionState == SessionState.Ready || sessionState == SessionState.Finished) {
            stopRedFlashLoop()
            totalDistanceM = 0.0
            lastGateDistanceM = 0.0
            currentSpeedKmh = 0.0
            latestRawSpeedKmh = null
            odoConfirmedAtSessionDistanceM = 0.0
            activeScenarioStepId = null
            drivingActionState = DrivingActionState.CRUISING
            previewView?.clearPath()
            currentRoutePoints.clear()
            currentLocation?.let { appendCurrentRoutePoint(it, force = true) }
            wasInBrakeLineGate = true
            previousLocation = currentLocation
            previousLocationUpdateMs = SystemClock.elapsedRealtime()
        }

        wasInBrakeLineGate = isInBrakeLineGate(currentLocation ?: brakeLineLocation!!)
        sessionState = SessionState.Running
        syncScenarioState()
        updateControlButtons()
        updateTrackUi()
    }

    private fun pauseSession() {
        if (sessionState != SessionState.Running) return
        sessionState = SessionState.Paused
        currentSpeedKmh = 0.0
        stopRedFlashLoop()
        updateControlButtons()
        tvStatus?.text = currentStatusLabel()
        updateTrackUi()
    }

    private fun resumeSession() {
        if (sessionState != SessionState.Paused) return
        sessionState = SessionState.Running
        updateControlButtons()
        tvStatus?.text = currentStatusLabel()
        updateTrackUi()
        resumeBrakeAlertIfNeeded()
    }

    private fun stopSession() {
        if (sessionState == SessionState.Idle || sessionState == SessionState.Ready) return
        sessionState = SessionState.Finished
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        stopRedFlashLoop()
        tvStatus?.text = currentStatusLabel()
        updateControlButtons()
        updateTrackUi()
        Toast.makeText(this, "주행 시험이 종료되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun resetToReady() {
        totalDistanceM = 0.0
        lastGateDistanceM = 0.0
        wasInBrakeLineGate = false
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        odoConfirmedAtSessionDistanceM = 0.0
        activeScenarioStepId = null
        drivingActionState = DrivingActionState.CRUISING
        stopRedFlashLoop()
        currentRoutePoints.clear()
        sessionState = SessionState.Ready
        updateControlButtons()
        updateTrackUi()
        previewView?.clearPath()
    }

    private fun setBrakeLine() {
        requestLocationPermission {
            val now = currentLocation
            if (now == null) {
                Toast.makeText(this, "현재 위치를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@requestLocationPermission
            }
            brakeLineLocation = Location(now)
            saveBrakeLine()
            sessionState = SessionState.Ready
            totalDistanceM = 0.0
            lastGateDistanceM = 0.0
            wasInBrakeLineGate = true
            currentSpeedKmh = 0.0
            latestRawSpeedKmh = null
            odoConfirmedAtSessionDistanceM = 0.0
            activeScenarioStepId = null
            drivingActionState = DrivingActionState.CRUISING
            stopRedFlashLoop()
            currentRoutePoints.clear()
            previewView?.clearPath()
            previewView?.setBrakeLine(now.latitude, now.longitude)
            previewView?.setState(isInTrack = true, isRunning = false)
            tvBrakeLine?.text = "브레이크 시작선: 설정됨"
            updateControlButtons()
            updateTrackUi()
            Toast.makeText(this, "브레이크 시작선이 설정되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateControlButtons() {
        val startLabel = when (sessionState) {
            SessionState.Idle, SessionState.Ready, SessionState.Finished -> "주행 시작"
            SessionState.Running -> "일시정지"
            SessionState.Paused -> "재개"
        }
        btnStartPause?.text = startLabel
        btnStop?.text = if (sessionState == SessionState.Finished) "초기화" else "정지"
        btnSetBrakeLine?.isEnabled = sessionState != SessionState.Running
        btnSetBrakeLine?.text = if (brakeLineLocation == null) {
            "현재 위치를 브레이크 시작선으로 설정"
        } else {
            "브레이크 시작선 재설정"
        }
    }

    private fun updateTrackUi() {
        if (
            sessionState == SessionState.Running &&
            previousLocationUpdateMs > 0L &&
            SystemClock.elapsedRealtime() - previousLocationUpdateMs > staleSpeedTimeoutMs
        ) {
            currentSpeedKmh = 0.0
        }
        if (sessionState != SessionState.Running) {
            stopRedFlashLoop()
        }

        val location = currentLocation
        val progressDistanceKm = testProgressDistanceKm()
        syncScenarioState()
        updateDrivingActionState()
        val scenarioStep = currentScenarioStep()

        tvSpeed?.text = String.format(Locale.US, "%.1f km/h", currentSpeedKmh)
        tvDistance?.text = String.format(Locale.US, "%.2f km", progressDistanceKm)
        tvStatus?.text = currentStatusLabel()
        tvBoundary?.text = trackAreaLabel()
        tvBrakeLine?.text = brakeLineStatusLabel(scenarioStep)
        if (scenarioStep == null) {
            tvScenarioSection?.text = "시나리오 미설정"
            tvScenarioProgress?.text = String.format(Locale.US, "현재 %.1fkm · 시험 범위 0~9000km", progressDistanceKm)
            tvScenarioTarget?.text = "규정속도 -"
        } else {
            val nextTransitionKm = max(0.0, scenarioStep.endKm - progressDistanceKm)
            tvScenarioSection?.text = "${scenarioStep.id} · ${scenarioStep.driveMode.label}"
            tvScenarioProgress?.text = String.format(
                Locale.US,
                "%.0f~%.0fkm · 전환까지 %.1fkm",
                scenarioStep.startKm,
                scenarioStep.endKm,
                nextTransitionKm
            )
            tvScenarioTarget?.text = "규정속도 ${scenarioStep.targetSpeedKmh}km/h"
        }
        tvScenarioInstruction?.text = scenarioInstruction(scenarioStep)
        tvScenarioInstruction?.setTextColor(
            when (drivingActionState) {
                DrivingActionState.DECELERATING -> Color.rgb(185, 28, 28)
                DrivingActionState.ACCELERATING -> ScreenColors.Primary
                else -> ScreenColors.Text
            }
        )

        if (location != null) {
            previewView?.setState(
                isInTrack = insideTrackArea,
                isRunning = sessionState == SessionState.Running,
                latitude = location.latitude,
                longitude = location.longitude
            )
        }
    }

    private fun trackAreaLabel(): String {
        return when {
            !hasReferenceRoute -> "고주로: 기준 경로 학습 중"
            insideTrackArea -> "고주로: 기준 경로 내"
            else -> "고주로: 기준 경로 이탈"
        }
    }

    private fun savedOdoLabel(odo: String): String {
        return if (odo.isBlank()) "저장 ODO: 미입력" else "저장 ODO: $odo km"
    }

    private fun brakeLineStatusLabel(step: DrivingScenarioStep?): String {
        if (brakeLineLocation == null) return "브레이크 시작선: 미설정"
        if (step?.driveMode != ScenarioDriveMode.ACCEL_DECEL) {
            return "브레이크 시작선: 설정됨 · 현재 구간 감속 경보 없음"
        }
        return when (drivingActionState) {
            DrivingActionState.WAITING_FOR_BRAKE_LINE -> "브레이크 시작선: 통과 대기"
            DrivingActionState.DECELERATING -> "브레이크 시작선: 통과 · 완감속 중"
            DrivingActionState.ACCELERATING -> "브레이크 시작선: 통과 · 재가속 중"
            DrivingActionState.CRUISING -> "브레이크 시작선: 설정됨"
        }
    }

    private fun confirmedOdoProgressKmOrNull(): Double? {
        val currentOdo = selectedTrackOdo.toDoubleOrNull() ?: return null
        val startOdo = selectedStartOdo.toDoubleOrNull()
        if (startOdo != null && currentOdo < startOdo) return null
        return if (startOdo != null) currentOdo - startOdo else max(0.0, currentOdo)
    }

    private fun spinnerOf(label: String, vararg items: String, onSelected: ((String) -> Unit)? = null): Spinner {
        val opts = items.toList()
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, opts).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            background = inputBackground()
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = label
            if (opts.isNotEmpty()) onSelected?.invoke(opts.first())
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in opts.indices) onSelected?.invoke(opts[position])
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // no-op
                }
            }
        }
    }

    private fun primaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isAllCaps = false
        gravity = Gravity.CENTER
        background = rounded(ScreenColors.Primary, dp(10))
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ScreenColors.Text)
        isAllCaps = false
        gravity = Gravity.CENTER
        background = rounded(Color.WHITE, dp(8), ScreenColors.Border)
        setOnClickListener { onClick() }
    }

    private fun compactButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = rounded(ScreenColors.Primary, dp(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(84), dp(40)).apply { leftMargin = dp(8) }
    }

    private fun toolbar(title: String, subtitle: String): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        bar.addView(secondaryButton("뒤로") { showHome() }, LinearLayout.LayoutParams(dp(80), dp(46)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ScreenColors.Text)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ScreenColors.MutedText)
        })
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ScreenColors.Text)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun formLabel(textLabel: String): TextView = TextView(this).apply {
        this.text = textLabel
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ScreenColors.Text)
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun summaryText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ScreenColors.MutedText)
        setPadding(0, 0, 0, dp(8))
    }

    private fun metricTextView(initial: String): TextView = TextView(this).apply {
        text = initial
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ScreenColors.Text)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun metricCard(label: String, value: TextView): LinearLayout = cardContainer().apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 13f
            setTextColor(ScreenColors.MutedText)
        })
        addView(value)
    }

    private fun metricRow(left: View, right: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(6) })
    }

    private fun numberInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        textSize = 16f
        setPadding(dp(14), 0, dp(14), 0)
        background = inputBackground()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    }

    private fun statusBadge(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(ScreenColors.Text)
        background = rounded(Color.rgb(226, 232, 240), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(34)).apply { rightMargin = dp(8) }
    }

    private fun screenRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ScreenColors.Background)
        layoutParams = matchParent()
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(color)
            strokeColor?.let { setStroke(dp(1), it) }
        }
    }

    private fun inputBackground(): android.graphics.drawable.GradientDrawable = rounded(Color.WHITE, dp(8), ScreenColors.Border)
    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        background = rounded(Color.WHITE, dp(8), ScreenColors.Border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
    }

    private fun fullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun compactFullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
        topMargin = dp(8); bottomMargin = dp(8)
    }
    private fun largeButton(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)).apply {
        topMargin = dp(16)
    }
    private fun fullWidthHeight(height: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
        bottomMargin = dp(8)
    }
    private fun weightedContent(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    private fun matchParent(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun space(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class TrackPreviewView(context: android.content.Context) : View(context) {
    private data class Projection(val originLatitude: Double, val originLongitude: Double)

    private data class ViewTransform(
        val centerMetersX: Float,
        val centerMetersY: Float,
        val centerViewX: Float,
        val centerViewY: Float,
        val scale: Float
    )

    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(7).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(148, 163, 184)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ScreenColors.Primary
    }
    private val brakeLineOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(7).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val brakeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(220, 38, 38)
    }
    private val currentLocationOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val currentLocationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(22, 163, 74)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = dp(11).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        color = ScreenColors.MutedText
    }
    private val brakeLabelPaint = Paint(labelPaint).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(185, 28, 28)
    }

    private val currentRoute = ArrayList<GeoPoint>()
    private val referenceRoute = ArrayList<GeoPoint>()
    private val maxRouteSize = 1_200
    private val minimumPointSpacingM = 0.8
    private val brakeLineHalfLengthM = 60f
    private var brakeLine: GeoPoint? = null
    private var inTrack = true
    private var running = false

    fun setState(
        isInTrack: Boolean,
        isRunning: Boolean,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        inTrack = isInTrack
        running = isRunning
        if (isRunning && latitude != null && longitude != null) {
            addRoutePoint(GeoPoint(latitude, longitude))
        }
        invalidate()
    }

    fun setReferenceRoute(points: List<GeoPoint>) {
        referenceRoute.clear()
        referenceRoute.addAll(points)
        invalidate()
    }

    fun setBrakeLine(latitude: Double?, longitude: Double?) {
        brakeLine = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
        invalidate()
    }

    fun beginLap(latitude: Double, longitude: Double) {
        currentRoute.clear()
        addRoutePoint(GeoPoint(latitude, longitude))
        invalidate()
    }

    fun clearPath() {
        currentRoute.clear()
        invalidate()
    }

    private fun addRoutePoint(point: GeoPoint) {
        val last = currentRoute.lastOrNull()
        if (last != null && distanceMeters(last, point) < minimumPointSpacingM) return
        currentRoute.add(point)
        if (currentRoute.size > maxRouteSize) currentRoute.removeAt(0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val baseRoute = if (referenceRoute.isNotEmpty()) referenceRoute else currentRoute
        if (baseRoute.isEmpty()) {
            if (brakeLine != null) {
                val centerY = height * 0.5f
                val startX = width * 0.25f
                val endX = width * 0.75f
                canvas.drawLine(startX, centerY, endX, centerY, brakeLineOutlinePaint)
                canvas.drawLine(startX, centerY, endX, centerY, brakeLinePaint)
                canvas.drawText("브레이크 시작선", width * 0.5f, centerY - dp(8), brakeLabelPaint)
            }
            canvas.drawText("첫 랩 GPS 경로 수집 대기", dp(16).toFloat(), dp(20).toFloat(), labelPaint)
            return
        }

        val projection = Projection(baseRoute.first().latitude, baseRoute.first().longitude)
        val projectedReference = referenceRoute.map { project(it, projection) }
        val projectedCurrent = currentRoute.map { project(it, projection) }
        val projectedBrakeLine = brakeLine?.let { project(it, projection) }
        val boundsPoints = if (projectedReference.isNotEmpty()) {
            projectedReference + listOfNotNull(projectedBrakeLine)
        } else {
            projectedCurrent + listOfNotNull(projectedBrakeLine)
        }
        val transform = viewTransform(boundsPoints)

        if (projectedReference.size >= 2) {
            drawRoute(canvas, projectedReference, transform, referencePaint, closePath = true)
            canvas.drawText("첫 랩 기준 경로", dp(12).toFloat(), dp(18).toFloat(), labelPaint)
        } else {
            canvas.drawText("첫 랩 기준 경로 수집 중", dp(12).toFloat(), dp(18).toFloat(), labelPaint)
        }

        if (projectedCurrent.size >= 2) {
            routePaint.color = if (inTrack) ScreenColors.Primary else Color.rgb(239, 68, 68)
            drawRoute(canvas, projectedCurrent, transform, routePaint, closePath = false)
        }

        if (projectedBrakeLine != null) {
            drawBrakeLine(canvas, projectedBrakeLine, projectedReference, projectedCurrent, transform)
        }

        projectedCurrent.lastOrNull()?.let { current ->
            val mapped = mapToView(current, transform)
            canvas.drawCircle(mapped.x, mapped.y, dp(7).toFloat(), currentLocationOutlinePaint)
            canvas.drawCircle(mapped.x, mapped.y, dp(4).toFloat(), currentLocationPaint)
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        points: List<PointF>,
        transform: ViewTransform,
        paint: Paint,
        closePath: Boolean
    ) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val mapped = mapToView(point, transform)
            if (index == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
        }
        if (closePath) path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawBrakeLine(
        canvas: Canvas,
        center: PointF,
        projectedReference: List<PointF>,
        projectedCurrent: List<PointF>,
        transform: ViewTransform
    ) {
        val route = if (projectedReference.size >= 2) projectedReference else projectedCurrent
        var tangentX = 0f
        var tangentY = 1f
        if (route.size >= 2) {
            val nearestIndex = route.indices.minByOrNull { index ->
                val dx = route[index].x - center.x
                val dy = route[index].y - center.y
                dx * dx + dy * dy
            } ?: 0
            val sampleOffset = min(4, max(1, route.size / 8))
            val closed = projectedReference.size >= 4
            val beforeIndex = if (closed) {
                (nearestIndex - sampleOffset + route.size) % route.size
            } else {
                max(0, nearestIndex - sampleOffset)
            }
            val afterIndex = if (closed) {
                (nearestIndex + sampleOffset) % route.size
            } else {
                min(route.lastIndex, nearestIndex + sampleOffset)
            }
            val dx = route[afterIndex].x - route[beforeIndex].x
            val dy = route[afterIndex].y - route[beforeIndex].y
            val length = sqrt(dx * dx + dy * dy)
            if (length >= 1f) {
                tangentX = dx / length
                tangentY = dy / length
            }
        }

        val normalX = -tangentY
        val normalY = tangentX
        val start = PointF(center.x - normalX * brakeLineHalfLengthM, center.y - normalY * brakeLineHalfLengthM)
        val end = PointF(center.x + normalX * brakeLineHalfLengthM, center.y + normalY * brakeLineHalfLengthM)
        val mappedStart = mapToView(start, transform)
        val mappedEnd = mapToView(end, transform)
        canvas.drawLine(mappedStart.x, mappedStart.y, mappedEnd.x, mappedEnd.y, brakeLineOutlinePaint)
        canvas.drawLine(mappedStart.x, mappedStart.y, mappedEnd.x, mappedEnd.y, brakeLinePaint)

        val mappedCenter = mapToView(center, transform)
        canvas.drawText("브레이크 시작선", mappedCenter.x, mappedCenter.y - dp(8), brakeLabelPaint)
    }

    private fun viewTransform(points: List<PointF>): ViewTransform {
        var minX = points.minOf { it.x }
        var maxX = points.maxOf { it.x }
        var minY = points.minOf { it.y }
        var maxY = points.maxOf { it.y }
        val minimumSpanM = 100f
        if (maxX - minX < minimumSpanM) {
            val center = (minX + maxX) * 0.5f
            minX = center - minimumSpanM * 0.5f
            maxX = center + minimumSpanM * 0.5f
        }
        if (maxY - minY < minimumSpanM) {
            val center = (minY + maxY) * 0.5f
            minY = center - minimumSpanM * 0.5f
            maxY = center + minimumSpanM * 0.5f
        }

        val horizontalPadding = dp(22).toFloat()
        val topPadding = dp(30).toFloat()
        val bottomPadding = dp(18).toFloat()
        val availableWidth = max(1f, width - horizontalPadding * 2f)
        val availableHeight = max(1f, height - topPadding - bottomPadding)
        val scale = min(availableWidth / (maxX - minX), availableHeight / (maxY - minY))
        return ViewTransform(
            centerMetersX = (minX + maxX) * 0.5f,
            centerMetersY = (minY + maxY) * 0.5f,
            centerViewX = width * 0.5f,
            centerViewY = topPadding + availableHeight * 0.5f,
            scale = scale
        )
    }

    private fun mapToView(point: PointF, transform: ViewTransform): PointF {
        return PointF(
            transform.centerViewX + (point.x - transform.centerMetersX) * transform.scale,
            transform.centerViewY - (point.y - transform.centerMetersY) * transform.scale
        )
    }

    private fun project(point: GeoPoint, projection: Projection): PointF {
        val eastM = (point.longitude - projection.originLongitude) *
            111_320.0 * cos(Math.toRadians(projection.originLatitude))
        val northM = (point.latitude - projection.originLatitude) * 110_540.0
        return PointF(eastM.toFloat(), northM.toFloat())
    }

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val baseLatitude = (first.latitude + second.latitude) * 0.5
        val eastM = (second.longitude - first.longitude) * 111_320.0 * cos(Math.toRadians(baseLatitude))
        val northM = (second.latitude - first.latitude) * 110_540.0
        return sqrt(eastM * eastM + northM * northM)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private object ScreenColors {
    val Background = Color.rgb(246, 248, 251)
    val Text = Color.rgb(30, 41, 59)
    val MutedText = Color.rgb(71, 85, 105)
    val Primary = Color.rgb(30, 64, 175)
    val Border = Color.rgb(203, 213, 225)
}
