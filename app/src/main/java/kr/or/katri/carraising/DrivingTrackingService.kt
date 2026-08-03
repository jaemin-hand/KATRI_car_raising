package kr.or.katri.carraising

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DrivingTrackingService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): DrivingTrackingService = this@DrivingTrackingService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<TrackingListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val preferences by lazy { getSharedPreferences(LEGACY_PREFERENCES_NAME, MODE_PRIVATE) }

    private var hasBoundClient = false
    private var isLocationListening = false
    private var activeLocationProvider: String? = null
    private var isForeground = false
    private var lastNotificationUpdateMs = 0L
    private var lastStatePersistMs = 0L
    private var isGpsSignalStale = false
    private var lastGpsSignalAlertMs = 0L

    private var sessionState = SessionState.Idle
    private var selectedPowertrain: PowertrainType? = null
    private var selectedStartOdo = ""
    private var selectedTrackOdo = ""

    private var currentLocation: Location? = null
    private var previousLocationUpdateMs = 0L
    private var totalDistanceM = 0.0
    private var lastGateDistanceM = 0.0
    private var wasInBrakeLineGate = false
    private var currentSpeedKmh = 0.0
    private var latestRawSpeedKmh: Double? = null
    private var hasStationaryEvidenceForControl = false
    private var insideTrackArea = true
    private var brakeLineLocation: Location? = null
    private var activeScenarioStepId: String? = null
    private var drivingActionState = DrivingActionState.CRUISING
    private val speedGuidanceMonitor = TargetSpeedMonitor(SPEED_TARGET_TOLERANCE_KMH)
    private val brakeApproachMonitor = BrakeApproachMonitor()
    private var odoConfirmedAtSessionDistanceM = 0.0

    private val currentRoutePoints = ArrayList<GeoPoint>()
    private val referenceRoutePoints = ArrayList<GeoPoint>()
    private var hasReferenceRoute = false

    private var noticeSequence = 0L
    private var noticeMessage: String? = null

    private var alarmTone: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechInitializationComplete = false
    private var isTextToSpeechReady = false
    private var activeApproachGuidance: BrakeApproachGuidance? = null
    private var isApproachGuidanceVoiceActive = false
    private var isApproachToneSequenceActive = false
    private var approachGuidanceVoiceUtteranceSequence = 0L
    private var currentApproachGuidanceVoiceUtteranceId: String? = null
    private var isBrakeVoiceActive = false
    private var brakeVoiceTargetKmh: Int? = null
    private var brakeVoiceUtteranceSequence = 0L
    private var currentBrakeVoiceUtteranceId: String? = null
    private var isSpeedGuidanceVoiceActive = false
    private var speedGuidanceVoiceUtteranceSequence = 0L
    private var currentSpeedGuidanceVoiceUtteranceId: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocationUpdated(location)
        }

        @Deprecated("Deprecated in Android")
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Android")
        override fun onProviderEnabled(provider: String) = Unit

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshLocationProviderIfNeeded()
            val nowMs = SystemClock.elapsedRealtime()
            val hasStaleLocation =
                previousLocationUpdateMs > 0L &&
                    nowMs - previousLocationUpdateMs > STALE_SPEED_TIMEOUT_MS
            var shouldNotifyListeners = false
            val previousSpeedGuidance = speedGuidanceMonitor.guidance
            if (hasStaleLocation) {
                if (currentSpeedKmh != 0.0) {
                    currentSpeedKmh = 0.0
                    shouldNotifyListeners = true
                }
                if (sessionState == SessionState.Running && !isGpsSignalStale) {
                    isGpsSignalStale = true
                    if (
                        lastGpsSignalAlertMs == 0L ||
                        nowMs - lastGpsSignalAlertMs >= GPS_SIGNAL_ALERT_COOLDOWN_MS
                    ) {
                        lastGpsSignalAlertMs = nowMs
                        showDrivingAlert(
                            title = "GPS 신호 확인",
                            message = "위치 정보가 3.5초 이상 갱신되지 않았습니다.",
                            includeCurrentSpeed = false
                        )
                    }
                } else if (sessionState != SessionState.Running) {
                    isGpsSignalStale = false
                }
            } else {
                isGpsSignalStale = false
            }
            updateSpeedGuidanceState()
            if (speedGuidanceMonitor.guidance != previousSpeedGuidance) {
                shouldNotifyListeners = true
            }
            if (shouldNotifyListeners) notifyListeners()
            updateForegroundNotification()
            if (hasBoundClient || isLocationListening || isSessionActive()) {
                mainHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val brakeVoiceRepeatRunnable = Runnable {
        speakBrakeInstructionIfNeeded()
    }

    private val speedGuidanceVoiceRepeatRunnable = Runnable {
        speakSpeedGuidanceIfNeeded()
    }

    private val approachGuidanceVoiceRunnable = Runnable {
        isApproachToneSequenceActive = false
        speakApproachGuidanceIfNeeded()
    }

    private val approachGuidanceSecondToneRunnable = Runnable {
        val guidance = activeApproachGuidance
        if (
            !isApproachGuidanceVoiceActive ||
            guidance?.playsPreparationTone != true
        ) {
            return@Runnable
        }
        playPreparationAlertTone()
        mainHandler.postDelayed(
            approachGuidanceVoiceRunnable,
            APPROACH_TONE_TO_VOICE_DELAY_MS
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadReferenceRoute()
        loadBrakeLine()
        loadSessionState()
        initTextToSpeech()
    }

    override fun onBind(intent: Intent?): IBinder {
        hasBoundClient = true
        startPreviewTracking()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        hasBoundClient = true
        startPreviewTracking()
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasBoundClient = false
        if (!isSessionActive()) {
            stopLocationUpdates()
            stopSelf()
        }
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> handleStartSession(intent)
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_STOP_SESSION -> stopSession()
            ACTION_RESTORE_SESSION, null -> restoreActiveSession()
        }
        return if (isSessionActive()) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistSessionState(force = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistSessionState(force = true)
        stopLocationUpdates()
        stopApproachGuidanceVoice()
        stopBrakeVoicePrompt()
        stopSpeedGuidanceVoice()
        textToSpeech?.shutdown()
        textToSpeech = null
        alarmTone?.release()
        alarmTone = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun registerListener(listener: TrackingListener) {
        listeners.add(listener)
        listener.onTrackingSnapshot(snapshot())
    }

    fun unregisterListener(listener: TrackingListener) {
        listeners.remove(listener)
    }

    fun snapshot(): TrackingSnapshot {
        val location = currentLocation
        return TrackingSnapshot(
            sessionState = sessionState,
            selectedPowertrain = selectedPowertrain,
            selectedStartOdo = selectedStartOdo,
            selectedTrackOdo = selectedTrackOdo,
            currentLocation = location?.let { GeoPoint(it.latitude, it.longitude) },
            currentLocationProvider = location?.provider,
            currentLocationAccuracyM = location?.accuracy?.takeIf { location.hasAccuracy() },
            currentLocationBearingDegrees = location
                ?.bearing
                ?.takeIf { location.hasBearing() && it.isFinite() },
            currentSpeedKmh = currentSpeedKmh,
            latestRawSpeedKmh = latestRawSpeedKmh,
            totalDistanceM = totalDistanceM,
            testProgressDistanceKm = testProgressDistanceKm(),
            currentOdoKm = currentOdoKmOrNull(),
            odoConfirmedAtSessionDistanceM = odoConfirmedAtSessionDistanceM,
            insideTrackArea = insideTrackArea,
            brakeLineLocation = brakeLineLocation?.let { GeoPoint(it.latitude, it.longitude) },
            activeScenarioStepId = activeScenarioStepId,
            drivingActionState = drivingActionState,
            speedGuidance = speedGuidanceMonitor.guidance,
            currentRoutePoints = currentRoutePoints.toList(),
            referenceRoutePoints = referenceRoutePoints.toList(),
            hasReferenceRoute = hasReferenceRoute,
            noticeSequence = noticeSequence,
            noticeMessage = noticeMessage
        )
    }

    fun startPreviewTracking() {
        if (!hasLocationPermission()) return
        scheduleStatusRefresh()
        startLocationUpdates(scheduleRefresh = false)
    }

    fun configureSession(
        powertrain: PowertrainType,
        startOdo: String,
        trackOdo: String
    ) {
        if (isSessionActive() && selectedPowertrain != powertrain) return
        selectedPowertrain = powertrain
        selectedStartOdo = startOdo
        selectedTrackOdo = trackOdo
        brakeApproachMonitor.reset()
        syncScenarioState()
        persistSessionState(force = true)
        notifyListeners()
    }

    fun startReadinessIssue(): String? {
        if (!hasLocationPermission()) return "위치 권한이 필요합니다."
        val now = currentLocation ?: return "GPS 위치를 수신 중입니다. 잠시 후 다시 시작하세요."
        return brakeLineLocationIssue(now)
    }

    fun setBrakeLineAtCurrentLocation(): TrackingCommandResult {
        val now = currentLocation
            ?: return TrackingCommandResult(false, "현재 위치를 사용할 수 없습니다.")
        val issue = brakeLineLocationIssue(now)
        if (issue != null) return TrackingCommandResult(false, issue)

        val isReset = brakeLineLocation != null
        applyBrakeLine(now)
        return TrackingCommandResult(
            true,
            if (isReset) {
                "현재 위치로 브레이크 시작선이 재설정되었습니다."
            } else {
                "브레이크 시작선이 설정되었습니다."
            }
        )
    }

    fun clearBrakeLine(): TrackingCommandResult {
        if (isSessionActive()) {
            return TrackingCommandResult(false, "주행 중에는 브레이크 시작선을 초기화할 수 없습니다.")
        }
        if (brakeLineLocation == null) {
            return TrackingCommandResult(false, "초기화할 브레이크 시작선이 없습니다.")
        }

        brakeLineLocation = null
        lastGateDistanceM = totalDistanceM
        wasInBrakeLineGate = false
        brakeApproachMonitor.reset()
        stopApproachGuidanceVoice()
        if (sessionState == SessionState.Ready) {
            sessionState = SessionState.Idle
        }
        preferences.edit()
            .remove(BRAKE_LINE_LATITUDE_PREFS_KEY)
            .remove(BRAKE_LINE_LONGITUDE_PREFS_KEY)
            .apply()
        persistSessionState(force = true)
        notifyListeners()
        return TrackingCommandResult(true, "브레이크 시작선이 초기화되었습니다.")
    }

    fun confirmOdo(currentOdoText: String, startOdoText: String): TrackingCommandResult {
        val enteredOdo = currentOdoText.toDoubleOrNull()
        if (enteredOdo == null || enteredOdo < 0.0) {
            return TrackingCommandResult(false, "올바른 ODO 값을 입력하세요.")
        }
        val startOdo = startOdoText.toDoubleOrNull()
        if (startOdo != null && enteredOdo < startOdo) {
            return TrackingCommandResult(false, "현재 ODO는 시작 ODO보다 작을 수 없습니다.")
        }

        selectedStartOdo = startOdoText
        selectedTrackOdo = currentOdoText
        odoConfirmedAtSessionDistanceM = totalDistanceM
        activeScenarioStepId = null
        syncScenarioState()
        persistSessionState(force = true)
        notifyListeners()
        updateForegroundNotification(force = true)
        return TrackingCommandResult(true, "ODO가 저장되었습니다.")
    }

    fun pauseSession() {
        if (sessionState != SessionState.Running) return
        sessionState = SessionState.Paused
        currentSpeedKmh = 0.0
        stopApproachGuidanceVoice()
        stopBrakeVoicePrompt()
        clearSpeedGuidance(resetArming = true)
        persistSessionState(force = true)
        notifyListeners()
        updateForegroundNotification(force = true)
    }

    fun resumeSession() {
        if (sessionState != SessionState.Paused) return
        sessionState = SessionState.Running
        previousLocationUpdateMs = SystemClock.elapsedRealtime()
        syncScenarioState()
        resumeBrakeVoiceIfNeeded()
        updateSpeedGuidanceState()
        persistSessionState(force = true)
        notifyListeners()
        updateForegroundNotification(force = true)
    }

    fun stopSession() {
        if (sessionState == SessionState.Idle || sessionState == SessionState.Ready) return
        sessionState = SessionState.Finished
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        stopApproachGuidanceVoice()
        brakeApproachMonitor.reset()
        stopBrakeVoicePrompt()
        clearSpeedGuidance(resetArming = true)
        persistSessionState(force = true)
        notifyListeners()
        leaveForeground()
        if (!hasBoundClient) {
            stopLocationUpdates()
            stopSelf()
        }
    }

    fun resetToReady() {
        totalDistanceM = 0.0
        lastGateDistanceM = 0.0
        wasInBrakeLineGate = false
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        odoConfirmedAtSessionDistanceM = 0.0
        activeScenarioStepId = null
        drivingActionState = DrivingActionState.CRUISING
        brakeApproachMonitor.reset()
        currentRoutePoints.clear()
        sessionState = SessionState.Ready
        stopApproachGuidanceVoice()
        stopBrakeVoicePrompt()
        clearSpeedGuidance(resetArming = true)
        persistSessionState(force = true)
        notifyListeners()
    }

    fun needsForegroundRestore(): Boolean = isSessionActive() && !isForeground

    private fun handleStartSession(intent: Intent) {
        if (!enterForeground()) {
            sessionState = if (brakeLineLocation == null) SessionState.Idle else SessionState.Ready
            persistSessionState(force = true)
            notifyListeners()
            stopSelf()
            return
        }

        val powertrain = intent.getStringExtra(EXTRA_POWERTRAIN)
            ?.let { runCatching { PowertrainType.valueOf(it) }.getOrNull() }
        if (powertrain == null) {
            failSessionStart("차량 유형을 확인할 수 없습니다.")
            return
        }

        selectedPowertrain = powertrain
        selectedStartOdo = intent.getStringExtra(EXTRA_START_ODO).orEmpty()
        selectedTrackOdo = intent.getStringExtra(EXTRA_TRACK_ODO).orEmpty()

        val result = startSessionInternal()
        if (!result.success) {
            failSessionStart(result.message ?: "주행을 시작할 수 없습니다.")
        }
    }

    private fun startSessionInternal(): TrackingCommandResult {
        if (sessionState == SessionState.Running || sessionState == SessionState.Paused) {
            return TrackingCommandResult(true)
        }
        if (!hasLocationPermission()) {
            return TrackingCommandResult(false, "위치 권한이 필요합니다.")
        }
        startLocationUpdates()

        val now = currentLocation
            ?: return TrackingCommandResult(false, "GPS 위치를 수신 중입니다. 잠시 후 다시 시작하세요.")
        val locationIssue = brakeLineLocationIssue(now)
        if (locationIssue != null) return TrackingCommandResult(false, locationIssue)
        if (brakeLineLocation == null) applyBrakeLine(now)

        totalDistanceM = 0.0
        lastGateDistanceM = 0.0
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        odoConfirmedAtSessionDistanceM = 0.0
        activeScenarioStepId = null
        drivingActionState = DrivingActionState.CRUISING
        brakeApproachMonitor.reset()
        stopApproachGuidanceVoice()
        clearSpeedGuidance(resetArming = true)
        currentRoutePoints.clear()
        appendCurrentRoutePoint(now, force = true)
        wasInBrakeLineGate = isInBrakeLineGate(now)
        previousLocationUpdateMs = SystemClock.elapsedRealtime()
        sessionState = SessionState.Running
        syncScenarioState()
        persistSessionState(force = true)
        notifyListeners()
        updateForegroundNotification(force = true)
        return TrackingCommandResult(true)
    }

    private fun failSessionStart(message: String) {
        publishNotice(message)
        sessionState = if (brakeLineLocation == null) SessionState.Idle else SessionState.Ready
        brakeApproachMonitor.reset()
        stopApproachGuidanceVoice()
        clearSpeedGuidance(resetArming = true)
        persistSessionState(force = true)
        notifyListeners()
        leaveForeground()
        if (!hasBoundClient) stopSelf()
    }

    private fun togglePause() {
        when (sessionState) {
            SessionState.Running -> pauseSession()
            SessionState.Paused -> resumeSession()
            else -> Unit
        }
    }

    private fun restoreActiveSession() {
        if (!isSessionActive()) {
            if (!hasBoundClient) stopSelf()
            return
        }
        if (!enterForeground()) {
            sessionState = SessionState.Paused
            persistSessionState(force = true)
            notifyListeners()
            stopSelf()
            return
        }
        if (sessionState == SessionState.Running && previousLocationUpdateMs == 0L) {
            previousLocationUpdateMs = SystemClock.elapsedRealtime()
        }
        startLocationUpdates()
        notifyListeners()
    }

    private fun isSessionActive(): Boolean {
        return sessionState == SessionState.Running || sessionState == SessionState.Paused
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates(scheduleRefresh: Boolean = true) {
        if (!hasLocationPermission()) return
        if (scheduleRefresh) scheduleStatusRefresh()
        if (isLocationListening) return
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }

        try {
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_INTERVAL_MS,
                LOCATION_UPDATE_MIN_DISTANCE_M,
                locationListener,
                mainLooper
            )
            isLocationListening = true
            activeLocationProvider = provider
            locationManager.getLastKnownLocation(provider)?.let { onLocationUpdated(it) }
        } catch (_: SecurityException) {
            publishNotice("위치 권한을 확인하세요.")
        }
    }

    private fun stopLocationUpdates() {
        if (!isLocationListening) return
        locationManager.removeUpdates(locationListener)
        isLocationListening = false
        activeLocationProvider = null
        if (!isSessionActive()) mainHandler.removeCallbacks(statusRefreshRunnable)
    }

    private fun scheduleStatusRefresh() {
        mainHandler.removeCallbacks(statusRefreshRunnable)
        mainHandler.post(statusRefreshRunnable)
    }

    private fun refreshLocationProviderIfNeeded() {
        if (!hasLocationPermission()) return
        val preferredProvider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (preferredProvider == activeLocationProvider) return

        stopLocationUpdates()
        if (preferredProvider != null && (hasBoundClient || isSessionActive())) {
            startLocationUpdates(scheduleRefresh = false)
        }
    }

    private fun onLocationUpdated(location: Location) {
        val previous = currentLocation
        val nowMs = SystemClock.elapsedRealtime()
        isGpsSignalStale = false

        if (previous != null) {
            val dtMs = max(1L, nowMs - previousLocationUpdateMs)
            val deltaM = previous.distanceTo(location).coerceAtLeast(0f).toDouble()
            val dtSec = dtMs / 1000.0
            val speedSample = calculateSpeedSample(previous, location, dtSec, deltaM)
            latestRawSpeedKmh = speedSample.speedKmh.takeIf { speedSample.isValidForControl }
            hasStationaryEvidenceForControl =
                speedSample.isValidForControl && speedSample.hasStationaryEvidence
            currentSpeedKmh = smoothSpeedKmh(speedSample.speedKmh)
            val acceptedDeltaM = if (shouldAcceptMovement(location, deltaM, currentSpeedKmh)) {
                deltaM
            } else {
                0.0
            }

            if (isSessionActive() && acceptedDeltaM > 0.0) {
                totalDistanceM += acceptedDeltaM
            }
            if (sessionState == SessionState.Running) {
                if (acceptedDeltaM > 0.0) {
                    appendCurrentRoutePoint(location)
                    updateTrackAreaState(location)
                    syncScenarioState()
                    evaluateLap(location)
                }
                // A complete-stop sample adds no distance but must still end deceleration.
                updateDrivingActionState()
            }
            if (sessionState == SessionState.Paused) {
                updatePausedBrakeLineState(location)
            }
        } else {
            val speedSample = calculateSpeedSample(null, location, 0.0, 0.0)
            latestRawSpeedKmh = speedSample.speedKmh.takeIf { speedSample.isValidForControl }
            hasStationaryEvidenceForControl = false
            currentSpeedKmh = smoothSpeedKmh(speedSample.speedKmh)
        }

        previousLocationUpdateMs = nowMs
        currentLocation = Location(location)
        if (brakeLineLocation != null && sessionState != SessionState.Idle) {
            updateTrackAreaState(location)
        }
        if (sessionState == SessionState.Running) {
            updateBrakeApproachGuidance(location)
        } else {
            stopApproachGuidanceVoice()
            stopBrakeVoicePrompt()
        }
        updateSpeedGuidanceState()

        persistSessionState()
        notifyListeners()
        updateForegroundNotification()
    }

    private fun calculateSpeedSample(
        previous: Location?,
        location: Location,
        dtSec: Double,
        deltaM: Double
    ): SpeedSample {
        if (!isUsableLocation(location)) {
            return SpeedSample(
                speedKmh = 0.0,
                isValidForControl = false,
                hasStationaryEvidence = false
            )
        }

        val hasTrustedGpsSpeed = isTrustedGpsSpeed(location)
        val gpsSpeedKmh = if (hasTrustedGpsSpeed) location.speed.toDouble() * 3.6 else null
        val distanceSpeedKmh = if (
            previous != null &&
            isUsableLocation(previous) &&
            dtSec >= MIN_SPEED_SAMPLE_SECONDS &&
            deltaM >= STATIONARY_DISTANCE_M
        ) {
            deltaM / dtSec * 3.6
        } else {
            null
        }

        val rawSpeedKmh = gpsSpeedKmh ?: distanceSpeedKmh ?: 0.0
        val positionSpeedKmh = if (
            previous != null &&
            isUsableLocation(previous) &&
            dtSec >= MIN_SPEED_SAMPLE_SECONDS
        ) {
            deltaM / dtSec * 3.6
        } else {
            null
        }
        val hasStationaryEvidence =
            positionSpeedKmh != null &&
                positionSpeedKmh <= COMPLETE_STOP_MAX_POSITION_SPEED_KMH
        val normalizedSpeedKmh = if (
            !hasTrustedGpsSpeed &&
            previous != null &&
            deltaM < STATIONARY_DISTANCE_M &&
            rawSpeedKmh < LOW_SPEED_GPS_JITTER_KMH
        ) {
            0.0
        } else if (rawSpeedKmh < MINIMUM_MOVING_SPEED_KMH) {
            0.0
        } else {
            rawSpeedKmh
        }
        return SpeedSample(
            speedKmh = normalizedSpeedKmh,
            isValidForControl =
                gpsSpeedKmh != null || distanceSpeedKmh != null || hasStationaryEvidence,
            hasStationaryEvidence = hasStationaryEvidence
        )
    }

    private fun smoothSpeedKmh(rawSpeedKmh: Double): Double {
        if (rawSpeedKmh <= 0.0) return 0.0
        if (currentSpeedKmh <= 0.0) return rawSpeedKmh
        return currentSpeedKmh * (1.0 - SPEED_SMOOTHING_ALPHA) +
            rawSpeedKmh * SPEED_SMOOTHING_ALPHA
    }

    private fun shouldAcceptMovement(location: Location, deltaM: Double, speedKmh: Double): Boolean {
        val trustedGpsMovement = isTrustedGpsSpeed(location) && deltaM >= 0.5
        return isUsableLocation(location) &&
            speedKmh >= MINIMUM_MOVING_SPEED_KMH &&
            (deltaM >= STATIONARY_DISTANCE_M || trustedGpsMovement)
    }

    private fun isUsableLocation(location: Location): Boolean {
        return !location.hasAccuracy() || location.accuracy <= MAX_LOCATION_ACCURACY_M
    }

    private fun isTrustedGpsSpeed(location: Location): Boolean {
        if (location.provider != LocationManager.GPS_PROVIDER) return false
        if (!location.hasSpeed() || !isUsableLocation(location)) return false
        return !location.hasSpeedAccuracy() ||
            location.speedAccuracyMetersPerSecond <= MAX_SPEED_ACCURACY_MPS
    }

    private fun updateBrakeApproachGuidance(location: Location) {
        val brakeLine = brakeLineLocation ?: return
        val step = currentScenarioStep() ?: return
        val remainingRouteDistanceM = if (
            hasReferenceRoute &&
            referenceRoutePoints.size >= REFERENCE_ROUTE_MIN_POINTS
        ) {
            BrakeApproachGuidanceRules.remainingRouteDistanceM(
                route = referenceRoutePoints,
                currentLocation = GeoPoint(location.latitude, location.longitude),
                brakeLineLocation = GeoPoint(brakeLine.latitude, brakeLine.longitude)
            )
        } else {
            null
        }
        val guidance = brakeApproachMonitor.update(
            sessionState = sessionState,
            step = step,
            actionState = drivingActionState,
            currentSpeedKmh = currentSpeedKmh,
            hasValidSpeedSample = latestRawSpeedKmh != null,
            isGpsSignalStale = isGpsSignalStale,
            traveledSinceBrakeLineM = max(0.0, totalDistanceM - lastGateDistanceM),
            remainingRouteDistanceM = remainingRouteDistanceM,
            straightLineDistanceM = location.distanceTo(brakeLine).toDouble()
        ) ?: return

        startApproachGuidanceVoice(guidance)
    }

    private fun evaluateLap(location: Location) {
        val inGate = isInBrakeLineGate(location)
        val traveledFromLastGate = totalDistanceM - lastGateDistanceM
        if (!wasInBrakeLineGate && inGate && traveledFromLastGate >= GPS_GATE_MIN_DISTANCE_M) {
            lastGateDistanceM = totalDistanceM
            wasInBrakeLineGate = true
            brakeApproachMonitor.resetForNextLap()
            stopApproachGuidanceVoice()
            saveReferenceRouteIfReady()
            startNewLapRoute(location)

            val step = currentScenarioStep()
            if (step?.driveMode == ScenarioDriveMode.ACCEL_DECEL) {
                drivingActionState = DrivingActionState.DECELERATING
                clearSpeedGuidance(resetArming = false)
                showDrivingAlert(
                    title = "브레이크 시작 · ${step.id}",
                    message = DrivingNotificationContent.brakeAlert(step)
                )
                startBrakeVoicePrompt(step)
            } else {
                drivingActionState = DrivingActionState.CRUISING
            }
        } else if (wasInBrakeLineGate && !inGate) {
            wasInBrakeLineGate = false
        }
    }

    private fun updatePausedBrakeLineState(location: Location) {
        val inGate = isInBrakeLineGate(location)
        if (!wasInBrakeLineGate && inGate) {
            lastGateDistanceM = totalDistanceM
        }
        wasInBrakeLineGate = inGate
    }

    private fun isInBrakeLineGate(location: Location): Boolean {
        val center = brakeLineLocation ?: return false
        val frame = brakeLineFrame()
            ?: return location.distanceTo(center) <= BRAKE_LINE_FALLBACK_RADIUS_M

        val eastM = longitudeDeltaMeters(location.longitude - center.longitude, center.latitude)
        val northM = latitudeDeltaMeters(location.latitude - center.latitude)
        val alongTrackM = eastM * frame.tangentEast + northM * frame.tangentNorth
        val acrossTrackM = -eastM * frame.tangentNorth + northM * frame.tangentEast
        return abs(alongTrackM) <= BRAKE_LINE_DETECTION_HALF_WIDTH_M &&
            abs(acrossTrackM) <= BRAKE_LINE_HALF_LENGTH_M
    }

    private fun brakeLineFrame(): BrakeLineFrame? {
        val center = brakeLineLocation ?: return null
        val useClosedReference = hasReferenceRoute && referenceRoutePoints.size >= 4
        val route = if (useClosedReference) referenceRoutePoints else currentRoutePoints
        if (route.size < 2) return null

        var nearestIndex = 0
        var nearestDistanceM = Double.POSITIVE_INFINITY
        route.forEachIndexed { index, point ->
            val distanceM = distanceMeters(
                center.latitude,
                center.longitude,
                point.latitude,
                point.longitude
            )
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
        insideTrackArea =
            distanceToReferenceRouteM(location.latitude, location.longitude) <= REFERENCE_ROUTE_TOLERANCE_M
    }

    private fun appendCurrentRoutePoint(location: Location, force: Boolean = false) {
        val point = GeoPoint(location.latitude, location.longitude)
        val last = currentRoutePoints.lastOrNull()
        val shouldAppend = force ||
            last == null ||
            distanceMeters(last.latitude, last.longitude, point.latitude, point.longitude) >=
            ROUTE_POINT_MIN_SPACING_M
        if (!shouldAppend) return

        currentRoutePoints.add(point)
        if (currentRoutePoints.size > MAX_CURRENT_ROUTE_POINTS) {
            currentRoutePoints.removeAt(0)
        }
    }

    private fun saveReferenceRouteIfReady() {
        if (hasReferenceRoute) return
        if (currentRoutePoints.size < REFERENCE_ROUTE_MIN_POINTS) return
        if (totalDistanceM < REFERENCE_ROUTE_MIN_DISTANCE_M) return

        referenceRoutePoints.clear()
        referenceRoutePoints.addAll(smoothRoute(currentRoutePoints))
        hasReferenceRoute = true
        saveReferenceRoute()
        publishNotice("기준 주행 경로가 저장되었습니다.")
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
    }

    private fun loadReferenceRoute() {
        val encoded = preferences.getString(REFERENCE_ROUTE_PREFS_KEY, null) ?: return
        referenceRoutePoints.clear()
        encoded.split(";").forEach { token ->
            val parts = token.split(",")
            if (parts.size == 2) {
                val latitude = parts[0].toDoubleOrNull()
                val longitude = parts[1].toDoubleOrNull()
                if (latitude != null && longitude != null) {
                    referenceRoutePoints.add(GeoPoint(latitude, longitude))
                }
            }
        }
        hasReferenceRoute = referenceRoutePoints.size >= REFERENCE_ROUTE_MIN_POINTS
    }

    private fun saveReferenceRoute() {
        val encoded = referenceRoutePoints.joinToString(";") {
            "${it.latitude},${it.longitude}"
        }
        preferences.edit().putString(REFERENCE_ROUTE_PREFS_KEY, encoded).apply()
    }

    private fun loadBrakeLine() {
        val latitude = preferences.getString(BRAKE_LINE_LATITUDE_PREFS_KEY, null)
            ?.toDoubleOrNull()
            ?: return
        val longitude = preferences.getString(BRAKE_LINE_LONGITUDE_PREFS_KEY, null)
            ?.toDoubleOrNull()
            ?: return
        brakeLineLocation = Location("saved_brake_line").apply {
            this.latitude = latitude
            this.longitude = longitude
        }
    }

    private fun saveBrakeLine() {
        val line = brakeLineLocation ?: return
        preferences.edit()
            .putString(BRAKE_LINE_LATITUDE_PREFS_KEY, line.latitude.toString())
            .putString(BRAKE_LINE_LONGITUDE_PREFS_KEY, line.longitude.toString())
            .apply()
    }

    private fun applyBrakeLine(location: Location) {
        val activeSession = isSessionActive()
        brakeLineLocation = Location(location)
        saveBrakeLine()
        lastGateDistanceM = totalDistanceM
        wasInBrakeLineGate = true
        brakeApproachMonitor.resetForNextLap()
        stopApproachGuidanceVoice()
        stopBrakeVoicePrompt()

        if (activeSession) {
            drivingActionState = when (currentScenarioStep()?.driveMode) {
                ScenarioDriveMode.ACCEL_DECEL -> DrivingActionState.WAITING_FOR_BRAKE_LINE
                else -> DrivingActionState.CRUISING
            }
            persistSessionState(force = true)
            notifyListeners()
            updateForegroundNotification(force = true)
            return
        }

        sessionState = SessionState.Ready
        totalDistanceM = 0.0
        lastGateDistanceM = 0.0
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
        odoConfirmedAtSessionDistanceM = 0.0
        activeScenarioStepId = null
        drivingActionState = DrivingActionState.CRUISING
        currentRoutePoints.clear()
        persistSessionState(force = true)
        notifyListeners()
    }

    private fun brakeLineLocationIssue(location: Location): String? {
        if (location.provider != LocationManager.GPS_PROVIDER) {
            return "브레이크 시작선 설정을 위해 GPS 신호가 필요합니다."
        }
        if (!location.hasAccuracy() || location.accuracy > MAX_LOCATION_ACCURACY_M) {
            return "GPS 정확도가 낮습니다. 신호가 안정된 후 다시 시작하세요."
        }
        val locationAgeMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos)
                .coerceAtLeast(0L) / 1_000_000L
        } else {
            0L
        }
        if (locationAgeMs > STALE_SPEED_TIMEOUT_MS) {
            return "최신 GPS 위치를 기다리는 중입니다. 잠시 후 다시 시작하세요."
        }
        return null
    }

    private fun distanceToReferenceRouteM(latitude: Double, longitude: Double): Double {
        var best = Double.POSITIVE_INFINITY
        for (index in 0 until referenceRoutePoints.size - 1) {
            best = min(
                best,
                distanceToSegmentM(
                    latitude,
                    longitude,
                    referenceRoutePoints[index],
                    referenceRoutePoints[index + 1]
                )
            )
        }
        if (referenceRoutePoints.size > 2) {
            best = min(
                best,
                distanceToSegmentM(
                    latitude,
                    longitude,
                    referenceRoutePoints.last(),
                    referenceRoutePoints.first()
                )
            )
        }
        return best
    }

    private fun distanceToSegmentM(
        latitude: Double,
        longitude: Double,
        start: GeoPoint,
        end: GeoPoint
    ): Double {
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

    private fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val output = FloatArray(1)
        Location.distanceBetween(latitude1, longitude1, latitude2, longitude2, output)
        return output[0].toDouble()
    }

    private fun testProgressDistanceKm(): Double {
        val gpsDistanceSinceOdoConfirmationKm =
            max(0.0, totalDistanceM - odoConfirmedAtSessionDistanceM) / 1000.0
        return confirmedOdoProgressKmOrNull()?.plus(gpsDistanceSinceOdoConfirmationKm)
            ?: (totalDistanceM / 1000.0)
    }

    private fun currentOdoKmOrNull(): Double? {
        val confirmedOdo = selectedTrackOdo.ifEmpty { selectedStartOdo }.toDoubleOrNull()
        return OdometerRules.currentOdoKm(
            confirmedOdoKm = confirmedOdo,
            trackedDistanceM = totalDistanceM,
            confirmationDistanceM = odoConfirmedAtSessionDistanceM
        )
    }

    private fun confirmedOdoProgressKmOrNull(): Double? {
        val currentOdo = selectedTrackOdo.toDoubleOrNull() ?: return null
        val startOdo = selectedStartOdo.toDoubleOrNull()
        if (startOdo != null && currentOdo < startOdo) return null
        return if (startOdo != null) currentOdo - startOdo else max(0.0, currentOdo)
    }

    private fun currentScenarioStep(): DrivingScenarioStep? {
        val powertrain = selectedPowertrain ?: return null
        return KatriDrivingScenario.stepAt(testProgressDistanceKm(), powertrain)
    }

    private fun syncScenarioState() {
        val step = currentScenarioStep()
        if (step?.id == activeScenarioStepId) return

        val previousStepId = activeScenarioStepId
        activeScenarioStepId = step?.id
        drivingActionState = when (step?.driveMode) {
            ScenarioDriveMode.ACCEL_DECEL -> DrivingActionState.WAITING_FOR_BRAKE_LINE
            else -> DrivingActionState.CRUISING
        }
        brakeApproachMonitor.cancelCandidate()
        stopApproachGuidanceVoice()
        stopBrakeVoicePrompt()
        clearSpeedGuidance(resetArming = false)

        if (sessionState == SessionState.Running && previousStepId != null) {
            if (step == null) {
                showDrivingAlert(
                    title = "시험 목표거리 도달",
                    message = selectedPowertrain
                        ?.let(KatriDrivingScenario::returnInstruction)
                        ?: "설정된 주행 시나리오가 완료되었습니다."
                )
            } else {
                showDrivingAlert(
                    title = "시나리오 전환 · ${step.id}",
                    message = DrivingNotificationContent.scenarioTransition(step)
                )
            }
        }
    }

    private fun updateDrivingActionState() {
        if (sessionState != SessionState.Running) return
        val step = currentScenarioStep() ?: return
        if (step.driveMode != ScenarioDriveMode.ACCEL_DECEL) {
            drivingActionState = DrivingActionState.CRUISING
            stopBrakeVoicePrompt()
            return
        }

        when (drivingActionState) {
            DrivingActionState.DECELERATING -> {
                val target = step.decelTargetKmh ?: return
                if (hasReachedDecelerationTarget(target)) {
                    drivingActionState = DrivingActionState.ACCELERATING
                    stopBrakeVoicePrompt()
                }
            }
            DrivingActionState.ACCELERATING -> {
                if (currentSpeedKmh >= step.targetSpeedKmh - SPEED_TARGET_TOLERANCE_KMH) {
                    drivingActionState = DrivingActionState.WAITING_FOR_BRAKE_LINE
                }
            }
            else -> Unit
        }
    }

    private fun hasReachedDecelerationTarget(targetKmh: Int): Boolean {
        return DrivingSpeedRules.hasReachedDecelerationTarget(
            targetKmh = targetKmh,
            displayedSpeedKmh = currentSpeedKmh,
            latestRawSpeedKmh = latestRawSpeedKmh,
            toleranceKmh = DECELERATION_TARGET_TOLERANCE_KMH,
            completeStopResidualSpeedKmh = COMPLETE_STOP_MAX_RESIDUAL_SPEED_KMH,
            hasStationaryEvidence = hasStationaryEvidenceForControl
        )
    }

    private fun updateSpeedGuidanceState() {
        val previousGuidance = speedGuidanceMonitor.guidance
        val nextGuidance = speedGuidanceMonitor.update(
            sessionState = sessionState,
            step = currentScenarioStep(),
            actionState = drivingActionState,
            currentSpeedKmh = currentSpeedKmh,
            hasValidSpeedSample = latestRawSpeedKmh != null,
            isGpsSignalStale = isGpsSignalStale
        )

        when {
            nextGuidance == null -> stopSpeedGuidanceVoice()
            previousGuidance == null ||
                previousGuidance.direction != nextGuidance.direction ->
                startSpeedGuidanceVoice()
            !isSpeedGuidanceVoiceActive -> startSpeedGuidanceVoice()
        }
    }

    private fun clearSpeedGuidance(resetArming: Boolean) {
        if (resetArming) {
            speedGuidanceMonitor.reset()
        } else {
            speedGuidanceMonitor.suspendGuidance()
        }
        stopSpeedGuidanceVoice()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            isTextToSpeechInitializationComplete = true
            val engine = textToSpeech
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val languageResult = engine.setLanguage(Locale.KOREAN)
                isTextToSpeechReady =
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
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
                            onApproachGuidanceUtteranceFinished(utteranceId)
                            onBrakeVoiceUtteranceFinished(utteranceId, failed = false)
                            onSpeedGuidanceUtteranceFinished(utteranceId, failed = false)
                        }

                        @Deprecated("Deprecated in Android")
                        override fun onError(utteranceId: String?) {
                            onApproachGuidanceUtteranceFinished(utteranceId)
                            onBrakeVoiceUtteranceFinished(utteranceId, failed = true)
                            onSpeedGuidanceUtteranceFinished(utteranceId, failed = true)
                        }
                    })
                }
            }
            mainHandler.post {
                resumeBrakeVoiceIfNeeded()
                resumeApproachGuidanceVoiceIfNeeded()
                resumeSpeedGuidanceVoiceIfNeeded()
            }
        }
    }

    private fun startApproachGuidanceVoice(guidance: BrakeApproachGuidance) {
        stopSpeedGuidanceVoice()
        stopApproachGuidanceVoice()
        activeApproachGuidance = guidance
        isApproachGuidanceVoiceActive = true

        if (guidance.playsPreparationTone) {
            isApproachToneSequenceActive = true
            playPreparationAlertTone()
            mainHandler.postDelayed(
                approachGuidanceSecondToneRunnable,
                APPROACH_TONE_INTERVAL_MS
            )
        } else {
            speakApproachGuidanceIfNeeded()
        }
    }

    private fun resumeApproachGuidanceVoiceIfNeeded() {
        if (
            !isApproachGuidanceVoiceActive ||
            isBrakeVoiceActive ||
            sessionState != SessionState.Running ||
            isApproachToneSequenceActive ||
            currentApproachGuidanceVoiceUtteranceId != null
        ) {
            return
        }
        speakApproachGuidanceIfNeeded()
    }

    private fun stopApproachGuidanceVoice() {
        mainHandler.removeCallbacks(approachGuidanceVoiceRunnable)
        mainHandler.removeCallbacks(approachGuidanceSecondToneRunnable)
        val hadActivePrompt =
            isApproachGuidanceVoiceActive ||
                currentApproachGuidanceVoiceUtteranceId != null
        activeApproachGuidance = null
        isApproachGuidanceVoiceActive = false
        isApproachToneSequenceActive = false
        currentApproachGuidanceVoiceUtteranceId = null
        if (hadActivePrompt && !isBrakeVoiceActive && !isSpeedGuidanceVoiceActive) {
            textToSpeech?.stop()
        }
    }

    private fun speakApproachGuidanceIfNeeded() {
        val guidance = activeApproachGuidance
        if (
            !isApproachGuidanceVoiceActive ||
            isBrakeVoiceActive ||
            sessionState != SessionState.Running ||
            guidance == null
        ) {
            stopApproachGuidanceVoice()
            return
        }
        if (!isTextToSpeechInitializationComplete) return

        val engine = textToSpeech
        if (!isTextToSpeechReady || engine == null) {
            completeApproachGuidanceVoice()
            return
        }

        approachGuidanceVoiceUtteranceSequence += 1L
        val utteranceId =
            "approach-guidance-$approachGuidanceVoiceUtteranceSequence"
        currentApproachGuidanceVoiceUtteranceId = utteranceId
        val result = engine.speak(
            guidance.message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            currentApproachGuidanceVoiceUtteranceId = null
            completeApproachGuidanceVoice()
        }
    }

    private fun onApproachGuidanceUtteranceFinished(utteranceId: String?) {
        mainHandler.post {
            if (
                !isApproachGuidanceVoiceActive ||
                utteranceId != currentApproachGuidanceVoiceUtteranceId
            ) {
                return@post
            }
            completeApproachGuidanceVoice()
        }
    }

    private fun completeApproachGuidanceVoice() {
        mainHandler.removeCallbacks(approachGuidanceVoiceRunnable)
        mainHandler.removeCallbacks(approachGuidanceSecondToneRunnable)
        activeApproachGuidance = null
        isApproachGuidanceVoiceActive = false
        isApproachToneSequenceActive = false
        currentApproachGuidanceVoiceUtteranceId = null
        resumeSpeedGuidanceVoiceIfNeeded()
    }

    private fun startBrakeVoicePrompt(step: DrivingScenarioStep) {
        val targetKmh = step.decelTargetKmh ?: return
        stopApproachGuidanceVoice()
        clearSpeedGuidance(resetArming = false)
        stopBrakeVoicePrompt()
        brakeVoiceTargetKmh = targetKmh
        isBrakeVoiceActive = true
        speakBrakeInstructionIfNeeded()
    }

    private fun resumeBrakeVoiceIfNeeded() {
        if (
            sessionState != SessionState.Running ||
            drivingActionState != DrivingActionState.DECELERATING
        ) return
        val step = currentScenarioStep() ?: return
        val targetKmh = step.decelTargetKmh ?: return
        if (hasReachedDecelerationTarget(targetKmh)) return
        if (!isBrakeVoiceActive) startBrakeVoicePrompt(step)
    }

    private fun stopBrakeVoicePrompt() {
        mainHandler.removeCallbacks(brakeVoiceRepeatRunnable)
        if (!isBrakeVoiceActive && currentBrakeVoiceUtteranceId == null) return
        isBrakeVoiceActive = false
        brakeVoiceTargetKmh = null
        currentBrakeVoiceUtteranceId = null
        textToSpeech?.stop()
    }

    private fun speakBrakeInstructionIfNeeded() {
        mainHandler.removeCallbacks(brakeVoiceRepeatRunnable)
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
            mainHandler.postDelayed(brakeVoiceRepeatRunnable, BRAKE_VOICE_REPEAT_DELAY_MS)
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
            mainHandler.postDelayed(brakeVoiceRepeatRunnable, BRAKE_VOICE_REPEAT_DELAY_MS)
        }
    }

    private fun onBrakeVoiceUtteranceFinished(utteranceId: String?, failed: Boolean) {
        mainHandler.post {
            if (!isBrakeVoiceActive || utteranceId != currentBrakeVoiceUtteranceId) return@post
            currentBrakeVoiceUtteranceId = null
            if (failed) playFallbackAlarmTone()
            mainHandler.removeCallbacks(brakeVoiceRepeatRunnable)
            mainHandler.postDelayed(brakeVoiceRepeatRunnable, BRAKE_VOICE_REPEAT_DELAY_MS)
        }
    }

    private fun startSpeedGuidanceVoice() {
        if (
            isBrakeVoiceActive ||
            isApproachGuidanceVoiceActive ||
            speedGuidanceMonitor.guidance == null
        ) {
            return
        }
        stopSpeedGuidanceVoice()
        isSpeedGuidanceVoiceActive = true
        speakSpeedGuidanceIfNeeded()
    }

    private fun resumeSpeedGuidanceVoiceIfNeeded() {
        if (
            isBrakeVoiceActive ||
            isApproachGuidanceVoiceActive ||
            sessionState != SessionState.Running ||
            speedGuidanceMonitor.guidance == null
        ) return
        if (!isSpeedGuidanceVoiceActive) {
            isSpeedGuidanceVoiceActive = true
        }
        speakSpeedGuidanceIfNeeded()
    }

    private fun stopSpeedGuidanceVoice() {
        mainHandler.removeCallbacks(speedGuidanceVoiceRepeatRunnable)
        if (
            !isSpeedGuidanceVoiceActive &&
            currentSpeedGuidanceVoiceUtteranceId == null
        ) return
        val hadActivePrompt =
            isSpeedGuidanceVoiceActive || currentSpeedGuidanceVoiceUtteranceId != null
        isSpeedGuidanceVoiceActive = false
        currentSpeedGuidanceVoiceUtteranceId = null
        if (
            hadActivePrompt &&
            !isBrakeVoiceActive &&
            !isApproachGuidanceVoiceActive
        ) {
            textToSpeech?.stop()
        }
    }

    private fun speakSpeedGuidanceIfNeeded() {
        mainHandler.removeCallbacks(speedGuidanceVoiceRepeatRunnable)
        val guidance = speedGuidanceMonitor.guidance
        if (
            !isSpeedGuidanceVoiceActive ||
            isBrakeVoiceActive ||
            isApproachGuidanceVoiceActive ||
            sessionState != SessionState.Running ||
            guidance == null
        ) {
            stopSpeedGuidanceVoice()
            return
        }

        val engine = textToSpeech
        if (!isTextToSpeechReady || engine == null) {
            playFallbackAlarmTone()
            mainHandler.postDelayed(
                speedGuidanceVoiceRepeatRunnable,
                SPEED_GUIDANCE_VOICE_REPEAT_DELAY_MS
            )
            return
        }

        speedGuidanceVoiceUtteranceSequence += 1L
        val utteranceId = "speed-guidance-$speedGuidanceVoiceUtteranceSequence"
        currentSpeedGuidanceVoiceUtteranceId = utteranceId
        val result = engine.speak(
            TargetSpeedGuidanceRules.voiceMessage(guidance),
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            currentSpeedGuidanceVoiceUtteranceId = null
            playFallbackAlarmTone()
            mainHandler.postDelayed(
                speedGuidanceVoiceRepeatRunnable,
                SPEED_GUIDANCE_VOICE_REPEAT_DELAY_MS
            )
        }
    }

    private fun onSpeedGuidanceUtteranceFinished(utteranceId: String?, failed: Boolean) {
        mainHandler.post {
            if (
                !isSpeedGuidanceVoiceActive ||
                utteranceId != currentSpeedGuidanceVoiceUtteranceId
            ) return@post
            currentSpeedGuidanceVoiceUtteranceId = null
            if (failed) playFallbackAlarmTone()
            mainHandler.removeCallbacks(speedGuidanceVoiceRepeatRunnable)
            mainHandler.postDelayed(
                speedGuidanceVoiceRepeatRunnable,
                SPEED_GUIDANCE_VOICE_REPEAT_DELAY_MS
            )
        }
    }

    private fun brakeVoiceMessage(targetKmh: Int): String {
        return if (targetKmh <= 0) {
            "완전 정차하세요."
        } else {
            "시속 ${targetKmh}킬로미터까지 감속하세요."
        }
    }

    private fun playFallbackAlarmTone() {
        if (alarmTone == null) {
            alarmTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        }
        alarmTone?.startTone(ToneGenerator.TONE_PROP_BEEP, 500)
    }

    private fun playPreparationAlertTone() {
        if (alarmTone == null) {
            alarmTone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        }
        alarmTone?.startTone(ToneGenerator.TONE_PROP_ACK, APPROACH_TONE_DURATION_MS)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val trackingChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "주행 기록",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "백그라운드 GPS 주행 기록 상태"
            setSound(null, null)
            enableVibration(false)
        }
        val alertChannel = NotificationChannel(
            DRIVING_ALERT_CHANNEL_ID,
            "주행 경보",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "브레이크 시작, 시나리오 전환, GPS 이상 경보"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 250L, 150L, 250L)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(trackingChannel, alertChannel))
    }

    private fun enterForeground(): Boolean {
        if (isForeground) {
            updateForegroundNotification(force = true)
            return true
        }
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (_: SecurityException) {
            publishNotice("백그라운드 위치 서비스를 시작할 권한이 없습니다.")
            false
        }
    }

    private fun leaveForeground() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun updateForegroundNotification(force: Boolean = false) {
        if (!isForeground) return
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastNotificationUpdateMs < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationUpdateMs = nowMs
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun openTrackPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TRACK, true)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showDrivingAlert(
        title: String,
        message: String,
        includeCurrentSpeed: Boolean = true
    ) {
        if (!canPostNotifications()) return

        val scenario = currentScenarioStep()
        val expandedMessage = buildString {
            append(message)
            scenario?.let {
                append("\n현재 구간: ${it.id} · ${it.driveMode.label}")
            }
            if (includeCurrentSpeed) {
                append(String.format(Locale.US, "\n현재 속도: %.1fkm/h", currentSpeedKmh))
            }
            append(String.format(Locale.US, "\n시험 누적거리: %.2fkm", testProgressDistanceKm()))
        }
        val notification = Notification.Builder(this, DRIVING_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(expandedMessage))
            .setContentIntent(openTrackPendingIntent())
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(DRIVING_ALERT_TIMEOUT_MS)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(DRIVING_ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val contentIntent = openTrackPendingIntent()

        val toggleIntent = Intent(this, DrivingTrackingService::class.java)
            .setAction(ACTION_TOGGLE_PAUSE)
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_PAUSE,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, DrivingTrackingService::class.java)
            .setAction(ACTION_STOP_SESSION)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_SESSION,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scenario = currentScenarioStep()
        val title = when (sessionState) {
            SessionState.Paused -> "KATRI ODO 기록 중 · 주행 일시정지"
            else -> scenario?.let { "KATRI 주행 중 · ${it.id}" } ?: "KATRI 주행 기록 중"
        }
        val status = DrivingNotificationContent.status(
            sessionState = sessionState,
            speedKmh = currentSpeedKmh,
            progressDistanceKm = testProgressDistanceKm(),
            step = scenario,
            actionState = drivingActionState,
            speedGuidance = speedGuidanceMonitor.guidance
        )
        val expandedStyle = Notification.InboxStyle()
            .setBigContentTitle(title)
        status.expandedLines.forEach { expandedStyle.addLine(it) }
        status.summaryText?.let { expandedStyle.setSummaryText(it) }

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(title)
            .setContentText(status.compactText)
            .setStyle(expandedStyle)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (sessionState == SessionState.Paused) {
                            android.R.drawable.ic_media_play
                        } else {
                            android.R.drawable.ic_media_pause
                        }
                    ),
                    if (sessionState == SessionState.Paused) "재개" else "일시정지",
                    togglePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel
                    ),
                    "시험 종료",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        val snapshot = snapshot()
        listeners.forEach { listener ->
            runCatching { listener.onTrackingSnapshot(snapshot) }
        }
    }

    private fun publishNotice(message: String) {
        noticeSequence += 1L
        noticeMessage = message
        notifyListeners()
    }

    private fun loadSessionState() {
        sessionState = preferences.getString(KEY_SESSION_STATE, null)
            ?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
            ?: if (brakeLineLocation == null) SessionState.Idle else SessionState.Ready
        selectedPowertrain = preferences.getString(KEY_POWERTRAIN, null)
            ?.let { runCatching { PowertrainType.valueOf(it) }.getOrNull() }
        selectedStartOdo = preferences.getString(KEY_START_ODO, "").orEmpty()
        selectedTrackOdo = preferences.getString(KEY_TRACK_ODO, "").orEmpty()
        totalDistanceM = preferences.getString(KEY_TOTAL_DISTANCE_M, null)?.toDoubleOrNull() ?: 0.0
        lastGateDistanceM =
            preferences.getString(KEY_LAST_GATE_DISTANCE_M, null)?.toDoubleOrNull() ?: 0.0
        wasInBrakeLineGate = preferences.getBoolean(KEY_WAS_IN_BRAKE_GATE, false)
        odoConfirmedAtSessionDistanceM =
            preferences.getString(KEY_ODO_CONFIRMATION_DISTANCE_M, null)?.toDoubleOrNull() ?: 0.0
        activeScenarioStepId = preferences.getString(KEY_ACTIVE_SCENARIO_STEP_ID, null)
        drivingActionState = preferences.getString(KEY_DRIVING_ACTION_STATE, null)
            ?.let { runCatching { DrivingActionState.valueOf(it) }.getOrNull() }
            ?: DrivingActionState.CRUISING
        speedGuidanceMonitor.restoreArmedTargetSpeed(
            preferences.getString(KEY_SPEED_GUIDANCE_ARMED_TARGET_KMH, null)?.toIntOrNull()
        )
        brakeApproachMonitor.restoreAnnouncedStepId(
            preferences.getString(KEY_BRAKE_APPROACH_ANNOUNCED_STEP_ID, null)
        )

        if (isSessionActive() && selectedPowertrain == null) {
            sessionState = if (brakeLineLocation == null) SessionState.Idle else SessionState.Ready
        }
        if (!isSessionActive()) {
            speedGuidanceMonitor.reset()
            brakeApproachMonitor.reset()
        }
        currentSpeedKmh = 0.0
        latestRawSpeedKmh = null
    }

    private fun persistSessionState(force: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastStatePersistMs < STATE_PERSIST_INTERVAL_MS) return
        lastStatePersistMs = nowMs
        preferences.edit()
            .putString(KEY_SESSION_STATE, sessionState.name)
            .putString(KEY_POWERTRAIN, selectedPowertrain?.name)
            .putString(KEY_START_ODO, selectedStartOdo)
            .putString(KEY_TRACK_ODO, selectedTrackOdo)
            .putString(KEY_TOTAL_DISTANCE_M, totalDistanceM.toString())
            .putString(KEY_LAST_GATE_DISTANCE_M, lastGateDistanceM.toString())
            .putBoolean(KEY_WAS_IN_BRAKE_GATE, wasInBrakeLineGate)
            .putString(KEY_ODO_CONFIRMATION_DISTANCE_M, odoConfirmedAtSessionDistanceM.toString())
            .putString(KEY_ACTIVE_SCENARIO_STEP_ID, activeScenarioStepId)
            .putString(KEY_DRIVING_ACTION_STATE, drivingActionState.name)
            .putString(
                KEY_SPEED_GUIDANCE_ARMED_TARGET_KMH,
                speedGuidanceMonitor.armedTargetSpeedKmh?.toString()
            )
            .putString(
                KEY_BRAKE_APPROACH_ANNOUNCED_STEP_ID,
                brakeApproachMonitor.announcedStepId
            )
            .apply()
    }

    companion object {
        const val EXTRA_OPEN_TRACK = "open_track"

        private const val ACTION_START_SESSION =
            "kr.or.katri.carraising.action.START_SESSION"
        private const val ACTION_TOGGLE_PAUSE =
            "kr.or.katri.carraising.action.TOGGLE_PAUSE"
        private const val ACTION_STOP_SESSION =
            "kr.or.katri.carraising.action.STOP_SESSION"
        private const val ACTION_RESTORE_SESSION =
            "kr.or.katri.carraising.action.RESTORE_SESSION"

        private const val EXTRA_POWERTRAIN = "powertrain"
        private const val EXTRA_START_ODO = "start_odo"
        private const val EXTRA_TRACK_ODO = "track_odo"

        private const val NOTIFICATION_CHANNEL_ID = "katri_driving_tracking"
        private const val DRIVING_ALERT_CHANNEL_ID = "katri_driving_alerts"
        private const val NOTIFICATION_ID = 2401
        private const val DRIVING_ALERT_NOTIFICATION_ID = 2405
        private const val REQUEST_OPEN_APP = 2402
        private const val REQUEST_TOGGLE_PAUSE = 2403
        private const val REQUEST_STOP_SESSION = 2404

        private const val LEGACY_PREFERENCES_NAME = "MainActivity"
        private const val REFERENCE_ROUTE_PREFS_KEY = "reference_route_points"
        private const val BRAKE_LINE_LATITUDE_PREFS_KEY = "brake_line_latitude"
        private const val BRAKE_LINE_LONGITUDE_PREFS_KEY = "brake_line_longitude"

        private const val KEY_SESSION_STATE = "tracking_session_state"
        private const val KEY_POWERTRAIN = "tracking_powertrain"
        private const val KEY_START_ODO = "tracking_start_odo"
        private const val KEY_TRACK_ODO = "tracking_track_odo"
        private const val KEY_TOTAL_DISTANCE_M = "tracking_total_distance_m"
        private const val KEY_LAST_GATE_DISTANCE_M = "tracking_last_gate_distance_m"
        private const val KEY_WAS_IN_BRAKE_GATE = "tracking_was_in_brake_gate"
        private const val KEY_ODO_CONFIRMATION_DISTANCE_M =
            "tracking_odo_confirmation_distance_m"
        private const val KEY_ACTIVE_SCENARIO_STEP_ID = "tracking_active_scenario_step_id"
        private const val KEY_DRIVING_ACTION_STATE = "tracking_driving_action_state"
        private const val KEY_SPEED_GUIDANCE_ARMED_TARGET_KMH =
            "tracking_speed_guidance_armed_target_kmh"
        private const val KEY_BRAKE_APPROACH_ANNOUNCED_STEP_ID =
            "tracking_brake_approach_announced_step_id"

        private const val GPS_GATE_MIN_DISTANCE_M = 4_000.0
        private const val BRAKE_LINE_FALLBACK_RADIUS_M = 45.0
        private const val BRAKE_LINE_DETECTION_HALF_WIDTH_M = 25.0
        private const val BRAKE_LINE_HALF_LENGTH_M = 60.0
        private const val SPEED_TARGET_TOLERANCE_KMH = 3.0
        private const val DECELERATION_TARGET_TOLERANCE_KMH = 6.0
        private const val COMPLETE_STOP_MAX_RESIDUAL_SPEED_KMH = 10.0
        private const val LOCATION_UPDATE_INTERVAL_MS = 500L
        private const val LOCATION_UPDATE_MIN_DISTANCE_M = 0f
        private const val BRAKE_VOICE_REPEAT_DELAY_MS = 1_000L
        private const val SPEED_GUIDANCE_VOICE_REPEAT_DELAY_MS = 3_000L
        private const val APPROACH_TONE_DURATION_MS = 180
        private const val APPROACH_TONE_INTERVAL_MS = 320L
        private const val APPROACH_TONE_TO_VOICE_DELAY_MS = 320L
        private const val ROUTE_POINT_MIN_SPACING_M = 3.0
        private const val STATIONARY_DISTANCE_M = 4.0
        private const val MINIMUM_MOVING_SPEED_KMH = 0.8
        private const val LOW_SPEED_GPS_JITTER_KMH = 12.0
        private const val COMPLETE_STOP_MAX_POSITION_SPEED_KMH = 3.0
        private const val MIN_SPEED_SAMPLE_SECONDS = 0.5
        private const val MAX_LOCATION_ACCURACY_M = 25f
        private const val MAX_SPEED_ACCURACY_MPS = 1.5f
        private const val SPEED_SMOOTHING_ALPHA = 0.35
        private const val STALE_SPEED_TIMEOUT_MS = 3_500L
        private const val GPS_SIGNAL_ALERT_COOLDOWN_MS = 60_000L
        private const val DRIVING_ALERT_TIMEOUT_MS = 10_000L
        private const val REFERENCE_ROUTE_TOLERANCE_M = 120.0
        private const val REFERENCE_ROUTE_MIN_POINTS = 80
        private const val REFERENCE_ROUTE_MIN_DISTANCE_M = 4_000.0
        private const val MAX_CURRENT_ROUTE_POINTS = 5_000
        private const val STATUS_REFRESH_INTERVAL_MS = 1_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
        private const val STATE_PERSIST_INTERVAL_MS = 1_000L

        fun startSession(
            context: Context,
            powertrain: PowertrainType,
            startOdo: String,
            trackOdo: String
        ) {
            val intent = Intent(context, DrivingTrackingService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_POWERTRAIN, powertrain.name)
                putExtra(EXTRA_START_ODO, startOdo)
                putExtra(EXTRA_TRACK_ODO, trackOdo)
            }
            context.startForegroundService(intent)
        }

        fun restoreSession(context: Context) {
            val intent = Intent(context, DrivingTrackingService::class.java)
                .setAction(ACTION_RESTORE_SESSION)
            context.startForegroundService(intent)
        }
    }
}
