package kr.or.katri.carraising

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class Screen { Home, Preparation, Track }
private enum class SessionState { Idle, Ready, Running, Paused, Finished }

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

    private var sessionStartRealtimeMs = 0L
    private var sessionPausedAccumMs = 0L
    private var sessionPausedAtMs = 0L

    private var totalDistanceM = 0.0
    private var lapStartDistanceM = 0.0
    private var lapCount = 0
    private var lastGateDistanceM = 0.0
    private var wasInStartGate = false
    private var currentSpeedKmh = 0.0
    private var insideTrackArea = true
    private var startLineLocation: Location? = null

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
    private var tvLap: TextView? = null
    private var tvLapProgress: TextView? = null
    private var tvRemain: TextView? = null
    private var tvElapsed: TextView? = null
    private var tvStatus: TextView? = null
    private var tvBoundary: TextView? = null
    private var tvStartLine: TextView? = null
    private var redFlashOverlay: View? = null

    private var btnSetStartLine: Button? = null
    private var btnStartPause: Button? = null
    private var btnStop: Button? = null

    private var selectedVehicle = "차량 A"
    private var selectedTestMode = "9000km 고주로 주행"
    private var selectedStartOdo = ""
    private var selectedTrackOdo = ""

    private val targetDistanceKm = 9000.0
    private val lapDistanceM = 5_000.0
    private val gpsGateMinDistanceM = 4_000.0
    private val redFlashIntervalM = 5_000.0
    private val startGateRadiusM = 45.0
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
    private var lastRedFlashDistanceBucket = 0
    private var isRedFlashLoopActive = false

    private val redFlashLoopRunnable = object : Runnable {
        override fun run() {
            runRedFlashPulse()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadReferenceRoute()
        initLocationListener()
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
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        stopRedFlashLoop()
        stopLocationUpdates()
    }

    override fun onBackPressed() {
        if (currentScreen == Screen.Home) super.onBackPressed() else showHome()
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

        locationManager.requestLocationUpdates(provider, 1_000L, 1f, listener, mainLooper)
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
            currentSpeedKmh = smoothSpeedKmh(calculateRawSpeedKmh(prev, location, dtSec, deltaM))
            val acceptedDeltaM = if (shouldAcceptMovement(location, deltaM, currentSpeedKmh)) deltaM else 0.0

            if (sessionState == SessionState.Running && acceptedDeltaM > 0.0) {
                totalDistanceM += acceptedDeltaM
                triggerRedFlashIfNeeded()
                appendCurrentRoutePoint(location)
                evaluateLap(location)
                updateTrackAreaState(location)
                previewView?.setState(
                    hasStartLine = startLineLocation != null,
                    isInTrack = insideTrackArea,
                    isRunning = sessionState == SessionState.Running,
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
            previousLocationUpdateMs = nowMs
        } else {
            previousLocationUpdateMs = nowMs
            currentSpeedKmh = smoothSpeedKmh(calculateRawSpeedKmh(null, location, 0.0, 0.0))
        }

        previousLocation = prev ?: location
        currentLocation = location
        if (startLineLocation != null && sessionState != SessionState.Idle) {
            updateTrackAreaState(location)
        }
        if (sessionState != SessionState.Running || currentSpeedKmh <= 0.0) {
            stopRedFlashLoop()
        }
        updateTrackUi()
    }

    private fun calculateRawSpeedKmh(prev: Location?, location: Location, dtSec: Double, deltaM: Double): Double {
        if (!isUsableLocation(location)) return 0.0

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
        if (!hasTrustedGpsSpeed && prev != null && deltaM < stationaryDistanceM && rawSpeedKmh < lowSpeedGpsJitterKmh) return 0.0
        return if (rawSpeedKmh < minimumMovingSpeedKmh) 0.0 else rawSpeedKmh
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

    private fun triggerRedFlashIfNeeded() {
        val bucket = (totalDistanceM / redFlashIntervalM).toInt()
        if (bucket <= 0 || bucket <= lastRedFlashDistanceBucket) return
        lastRedFlashDistanceBucket = bucket
        startRedFlashLoop()
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
        val inGate = isInStartGate(location)
        val traveledFromLastGate = totalDistanceM - lastGateDistanceM
        if (!wasInStartGate && inGate && traveledFromLastGate >= gpsGateMinDistanceM) {
            lapCount += 1
            lapStartDistanceM = totalDistanceM
            lastGateDistanceM = totalDistanceM
            wasInStartGate = true
            saveReferenceRouteIfReady()
        } else if (wasInStartGate && !inGate) {
            wasInStartGate = false
        }
    }

    private fun isInStartGate(location: Location): Boolean {
        val start = startLineLocation ?: return false
        return location.distanceTo(start) <= startGateRadiusM
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
        referenceRoutePoints.addAll(currentRoutePoints)
        hasReferenceRoute = true
        saveReferenceRoute()
        Toast.makeText(this, "기준 주행 경로가 저장되었습니다.", Toast.LENGTH_SHORT).show()
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

    private fun targetDistanceKmForCurrentMode(): Double {
        return when (selectedTestMode) {
            "단거리 주행" -> 5.0
            else -> targetDistanceKm
        }
    }

    private fun elapsedSessionSeconds(): Long {
        val now = SystemClock.elapsedRealtime()
        return when (sessionState) {
            SessionState.Running -> (now - sessionStartRealtimeMs - sessionPausedAccumMs) / 1000L
            SessionState.Paused -> (sessionPausedAtMs - sessionStartRealtimeMs - sessionPausedAccumMs) / 1000L
            else -> 0L
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

    private fun showTrack() {
        currentScreen = Screen.Track
        setContentView(createTrackView())
        if (hasLocationPermission()) startLocationUpdates() else requestLocationPermission { startLocationUpdates() }
        updateTrackUi()
    }

    private fun createHomeView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ScreenColors.Background)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            layoutParams = matchParent()
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(ScreenColors.Text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "거리 / 속도 / 랩 카운트"
            textSize = 15f
            setTextColor(ScreenColors.MutedText)
            gravity = Gravity.CENTER
        }
        root.addView(title, fullWidth())
        root.addView(subtitle, fullWidth())
        root.addView(space(dp(42)))
        root.addView(
            primaryButton("시험 준비") { showPreparation() },
            largeButton()
        )
        root.addView(
            primaryButton("고주로 주행") { showTrack() },
            largeButton()
        )
        return root
    }

    private fun createPreparationView(): LinearLayout {
        val root = screenRoot()
        root.addView(toolbar("시험 준비", "차량 및 시험 방식"))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        content.addView(sectionTitle("차량 / 시험"))
        content.addView(formLabel("차량"))
        content.addView(spinnerOf("차량", "차량 A", "차량 B", "차량 C") { selectedVehicle = it })
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
                showTrack()
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
        root.addView(toolbar("고주로 주행", "$selectedVehicle / $selectedTestMode"))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(22))
        }

        previewView = TrackPreviewView(this).also { view ->
            content.addView(view, fullWidthHeight(dp(230)))
        }
        content.addView(sectionTitle("측정"))

        tvSpeed = metricTextView("0.0 km/h")
        tvDistance = metricTextView("0.00 km")
        tvLap = metricTextView("0")
        tvLapProgress = metricTextView("0.00 / 5.0 km")
        tvRemain = metricTextView("${targetDistanceKmForCurrentMode()} / 0.0 km")
        tvElapsed = metricTextView("00:00:00")
        tvStatus = metricTextView(currentStatusLabel())
        tvBoundary = metricTextView("고주로: 확인 중")
        tvStartLine = metricTextView("스타트라인: 미설정")

        content.addView(metricRow(metricCard("속도", tvSpeed!!), metricCard("총 주행거리", tvDistance!!)))
        content.addView(metricRow(metricCard("랩 수", tvLap!!), metricCard("랩 거리", tvLapProgress!!)))
        content.addView(metricRow(metricCard("경과시간", tvElapsed!!), metricCard("남은 거리", tvRemain!!)))
        content.addView(tvStatus)
        content.addView(space(dp(6)))
        content.addView(tvBoundary)
        content.addView(tvStartLine)
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
                selectedTrackOdo = trackOdoInput.text.toString().trim()
                trackOdoSavedText.text = savedOdoLabel(selectedTrackOdo)
                Toast.makeText(this@MainActivity, "ODO가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(dp(112), dp(52)))
        }
        content.addView(trackOdoRow)
        content.addView(trackOdoSavedText)
        content.addView(space(dp(10)))

        btnSetStartLine = secondaryButton("현재 위치를 스타트라인으로 설정") {
            requestLocationPermission { setStartLine() }
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

        content.addView(btnSetStartLine, compactFullWidth())
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
        if (startLineLocation == null) {
            Toast.makeText(this, "스타트라인을 먼저 설정하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionState == SessionState.Idle || sessionState == SessionState.Finished) {
            stopRedFlashLoop()
            totalDistanceM = 0.0
            lapCount = 0
            lapStartDistanceM = 0.0
            lastGateDistanceM = 0.0
            lastRedFlashDistanceBucket = 0
            currentSpeedKmh = 0.0
            sessionStartRealtimeMs = SystemClock.elapsedRealtime()
            sessionPausedAccumMs = 0L
            sessionPausedAtMs = 0L
            previewView?.clearPath()
            currentRoutePoints.clear()
            currentLocation?.let { appendCurrentRoutePoint(it, force = true) }
            wasInStartGate = true
            previousLocation = currentLocation
            previousLocationUpdateMs = SystemClock.elapsedRealtime()
        } else if (sessionState == SessionState.Paused) {
            sessionPausedAccumMs += max(1L, SystemClock.elapsedRealtime() - sessionPausedAtMs)
            sessionPausedAtMs = 0L
        }

        wasInStartGate = isInStartGate(currentLocation ?: startLineLocation!!)
        sessionState = SessionState.Running
        updateControlButtons()
        updateTrackUi()
    }

    private fun pauseSession() {
        if (sessionState != SessionState.Running) return
        sessionState = SessionState.Paused
        sessionPausedAtMs = SystemClock.elapsedRealtime()
        currentSpeedKmh = 0.0
        stopRedFlashLoop()
        updateControlButtons()
        tvStatus?.text = currentStatusLabel()
        updateTrackUi()
    }

    private fun resumeSession() {
        if (sessionState != SessionState.Paused) return
        sessionPausedAccumMs += max(1L, SystemClock.elapsedRealtime() - sessionPausedAtMs)
        sessionPausedAtMs = 0L
        sessionState = SessionState.Running
        updateControlButtons()
        tvStatus?.text = currentStatusLabel()
        updateTrackUi()
    }

    private fun stopSession() {
        if (sessionState == SessionState.Idle || sessionState == SessionState.Ready) return
        saveReferenceRouteIfReady()
        sessionState = SessionState.Finished
        currentSpeedKmh = 0.0
        stopRedFlashLoop()
        tvStatus?.text = currentStatusLabel()
        updateControlButtons()
        updateTrackUi()
        Toast.makeText(this, "주행 시험이 종료되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun resetToReady() {
        totalDistanceM = 0.0
        lapCount = 0
        lapStartDistanceM = 0.0
        lastGateDistanceM = 0.0
        lastRedFlashDistanceBucket = 0
        wasInStartGate = false
        currentSpeedKmh = 0.0
        stopRedFlashLoop()
        currentRoutePoints.clear()
        sessionState = SessionState.Ready
        updateControlButtons()
        updateTrackUi()
        previewView?.clearPath()
    }

    private fun setStartLine() {
        requestLocationPermission {
            val now = currentLocation
            if (now == null) {
                Toast.makeText(this, "현재 위치를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@requestLocationPermission
            }
            startLineLocation = Location(now)
            sessionState = SessionState.Ready
            totalDistanceM = 0.0
            lapCount = 0
            lapStartDistanceM = 0.0
            lastGateDistanceM = 0.0
            lastRedFlashDistanceBucket = 0
            wasInStartGate = true
            currentSpeedKmh = 0.0
            stopRedFlashLoop()
            sessionStartRealtimeMs = 0L
            sessionPausedAccumMs = 0L
            sessionPausedAtMs = 0L
            currentRoutePoints.clear()
            previewView?.clearPath()
            previewView?.setState(
                hasStartLine = true,
                isInTrack = true,
                isRunning = false
            )
            tvStartLine?.text = "스타트라인: 설정됨"
            updateControlButtons()
            updateTrackUi()
            Toast.makeText(this, "스타트라인이 설정되었습니다.", Toast.LENGTH_SHORT).show()
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
        btnSetStartLine?.isEnabled = sessionState != SessionState.Running
        btnSetStartLine?.text = if (startLineLocation == null) "현재 위치를 스타트라인으로 설정" else "스타트라인 재설정"
    }

    private fun updateTrackUi() {
        if (
            sessionState == SessionState.Running &&
            previousLocationUpdateMs > 0L &&
            SystemClock.elapsedRealtime() - previousLocationUpdateMs > staleSpeedTimeoutMs
        ) {
            currentSpeedKmh = 0.0
        }
        if (sessionState != SessionState.Running || currentSpeedKmh <= 0.0) {
            stopRedFlashLoop()
        }

        val location = currentLocation
        val totalDistanceKm = totalDistanceM / 1000.0
        val lapDistanceKm = max(0.0, totalDistanceM - lapStartDistanceM) / 1000.0
        val targetKm = targetDistanceKmForCurrentMode()
        val remainKm = if (targetKm > 0.0) max(0.0, targetKm - totalDistanceKm) else 0.0

        tvSpeed?.text = String.format(Locale.US, "%.1f km/h", currentSpeedKmh)
        tvDistance?.text = String.format(Locale.US, "%.2f km", totalDistanceKm)
        tvLap?.text = lapCount.toString()
        tvLapProgress?.text = String.format(Locale.US, "%.2f / %.1f km", lapDistanceKm, lapDistanceM / 1000.0)
        tvRemain?.text = String.format(Locale.US, "%.1f / %.1f km", totalDistanceKm, remainKm)
        tvElapsed?.text = String.format(Locale.US, "%02d:%02d:%02d", elapsedSessionSeconds() / 3600, (elapsedSessionSeconds() % 3600) / 60, elapsedSessionSeconds() % 60)
        tvStatus?.text = currentStatusLabel()
        tvBoundary?.text = trackAreaLabel()
        tvStartLine?.text = if (startLineLocation == null) "스타트라인: 미설정" else "스타트라인: 설정됨"

        if (location != null) {
            previewView?.setState(
                hasStartLine = startLineLocation != null,
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
    private val guideRoadOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(28).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(203, 213, 225)
    }
    private val guideRoadInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(18).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(248, 250, 252)
    }
    private val guideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = dp(11).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        color = Color.rgb(71, 85, 105)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(30, 64, 175)
    }
    private val route = ArrayList<PointF>()
    private val maxRouteSize = 600
    private val stadiumLongSideM = 2_000f
    private val stadiumShortSideM = 725f
    private var inTrack = true
    private var running = false
    private var hasStartLine = false

    private var originLat = 0.0
    private var originLon = 0.0
    private var hasOrigin = false
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f

    fun setState(
        hasStartLine: Boolean,
        isInTrack: Boolean,
        isRunning: Boolean,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        this.hasStartLine = hasStartLine
        this.inTrack = isInTrack
        this.running = isRunning
        if (isRunning && latitude != null && longitude != null) {
            addRoutePoint(latitude, longitude)
        }
        invalidate()
    }

    fun clearPath() {
        route.clear()
        hasOrigin = false
        invalidate()
    }

    private fun addRoutePoint(latitude: Double, longitude: Double) {
        val point = if (!hasOrigin) {
            originLat = latitude
            originLon = longitude
            hasOrigin = true
            minX = 0f; maxX = 0f; minY = 0f; maxY = 0f
            PointF(0f, 0f)
        } else {
            val cosLat = cos(Math.toRadians(originLat)).toFloat()
            PointF(
                ((longitude - originLon) * 111_320.0 * cosLat).toFloat(),
                ((latitude - originLat) * 110_540.0).toFloat()
            )
        }

        if (route.isNotEmpty()) {
            val last = route[route.size - 1]
            val dx = last.x - point.x
            val dy = last.y - point.y
            if (sqrt(dx * dx + dy * dy) < 0.8f) return
        }

        route.add(point)
        minX = if (route.size == 1) point.x else min(minX, point.x)
        maxX = if (route.size == 1) point.x else max(maxX, point.x)
        minY = if (route.size == 1) point.y else min(minY, point.y)
        maxY = if (route.size == 1) point.y else max(maxY, point.y)
        if (route.size > maxRouteSize) route.removeAt(0)
        if (route.isNotEmpty()) {
            minX = Float.POSITIVE_INFINITY
            maxX = Float.NEGATIVE_INFINITY
            minY = Float.POSITIVE_INFINITY
            maxY = Float.NEGATIVE_INFINITY
            route.forEach {
                minX = min(minX, it.x); maxX = max(maxX, it.x)
                minY = min(minY, it.y); maxY = max(maxY, it.y)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val left = dp(16).toFloat()
        val right = width.toFloat() - dp(16)
        val top = dp(10).toFloat()
        val bottom = height.toFloat() - dp(20)

        drawStadiumGuide(canvas, left, right, top, bottom)

        if (route.size >= 2) {
            routePaint.color = if (inTrack) Color.rgb(30, 64, 175) else Color.rgb(239, 68, 68)
            val path = Path()
            route.forEachIndexed { index, p ->
                val m = mapToView(p, left, right, top, bottom)
                if (index == 0) path.moveTo(m.x, m.y) else path.lineTo(m.x, m.y)
            }
            canvas.drawPath(path, routePaint)
        }
    }

    private fun drawStadiumGuide(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val guide = stadiumGuideRect(left, right, top, bottom)
        val radius = min(guide.width(), guide.height()) * 0.5f
        canvas.drawRoundRect(guide, radius, radius, guideRoadOuterPaint)
        canvas.drawRoundRect(guide, radius, radius, guideRoadInnerPaint)

        canvas.drawText("2000m", guide.centerX(), max(top + dp(12), guide.top - dp(8)), guideLabelPaint)
        canvas.drawText("725m", min(right - dp(22), guide.right + dp(18)), guide.centerY() + dp(4), guideLabelPaint)
    }

    private fun stadiumGuideRect(left: Float, right: Float, top: Float, bottom: Float): RectF {
        val availableWidth = right - left
        val availableHeight = bottom - top
        val horizontalScale = min(availableWidth / stadiumLongSideM, availableHeight / stadiumShortSideM)
        val verticalScale = min(availableWidth / stadiumShortSideM, availableHeight / stadiumLongSideM)

        val horizontal = horizontalScale >= verticalScale
        val guideWidth = if (horizontal) stadiumLongSideM * horizontalScale else stadiumShortSideM * verticalScale
        val guideHeight = if (horizontal) stadiumShortSideM * horizontalScale else stadiumLongSideM * verticalScale
        val centerX = (left + right) * 0.5f
        val centerY = (top + bottom) * 0.5f

        return RectF(
            centerX - guideWidth * 0.5f,
            centerY - guideHeight * 0.5f,
            centerX + guideWidth * 0.5f,
            centerY + guideHeight * 0.5f
        )
    }

    private fun mapToView(point: PointF, left: Float, right: Float, top: Float, bottom: Float): PointF {
        val spanX = max(1f, maxX - minX)
        val spanY = max(1f, maxY - minY)
        val drawableWidth = right - left
        val drawableHeight = bottom - top
        val scale = 0.9f * min(drawableWidth / spanX, drawableHeight / spanY)
        val usedWidth = spanX * scale
        val usedHeight = spanY * scale
        val offsetX = left + (drawableWidth - usedWidth) * 0.5f
        val offsetY = top + (drawableHeight + usedHeight) * 0.5f
        val x = offsetX + (point.x - minX) * scale
        val y = offsetY - (point.y - minY) * scale
        return PointF(x, y)
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
