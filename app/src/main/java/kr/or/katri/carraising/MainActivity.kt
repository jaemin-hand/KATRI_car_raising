package kr.or.katri.carraising

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.location.Location
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class Screen { Home, Preparation, PowertrainSelection, Track }

private data class PrepItem(
    val title: String,
    val detail: String,
    val skippable: Boolean = false,
    val required: Boolean = true
)

class MainActivity : Activity() {
    private val permissionRequestCode = 1001
    private val notificationPermissionRequestCode = 1002
    private var trackingService: DrivingTrackingService? = null
    private var isTrackingServiceBound = false
    private var latestTrackingSnapshot: TrackingSnapshot? = null
    private var lastTrackingNoticeSequence = 0L
    private var openTrackWhenServiceConnect = false

    private var currentScreen = Screen.Home
    private var sessionState = SessionState.Idle

    private var currentLocation: Location? = null

    private var totalDistanceM = 0.0
    private var currentSpeedKmh = 0.0
    private var isGpsSignalStale = false
    private var insideTrackArea = true
    private var brakeLineLocation: Location? = null
    private var drivingActionState = DrivingActionState.CRUISING
    private var speedGuidance: SpeedGuidance? = null
    private var odoConfirmedAtSessionDistanceM = 0.0

    private val currentRoutePoints = ArrayList<GeoPoint>()
    private val referenceRoutePoints = ArrayList<GeoPoint>()
    private var hasReferenceRoute = false

    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingNotificationPermissionAction: (() -> Unit)? = null

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
    private var tvCurrentOdo: TextView? = null
    private var tvScenarioSection: TextView? = null
    private var tvScenarioProgress: TextView? = null
    private var tvScenarioTarget: TextView? = null
    private var tvScenarioInstruction: TextView? = null
    private var trackStatusBar: View? = null
    private var alertFlashOverlay: View? = null

    private var btnSetBrakeLine: Button? = null
    private var btnClearBrakeLine: Button? = null
    private var btnStartPause: Button? = null
    private var btnStop: Button? = null

    private var selectedPowertrain: PowertrainType? = null
    private var selectedTestMode = "고주로 주행"
    private var selectedStartOdo = ""
    private var selectedTrackOdo = ""

    private var isAlertFlashLoopActive = false

    private val alertFlashLoopRunnable = object : Runnable {
        override fun run() {
            runAlertFlashPulse()
        }
    }

    private val trackingListener = TrackingListener { snapshot ->
        runOnUiThread {
            applyTrackingSnapshot(snapshot)
        }
    }

    private val trackingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? DrivingTrackingService.LocalBinder)?.service() ?: return
            trackingService = service
            isTrackingServiceBound = true
            service.registerListener(trackingListener)
            if (hasLocationPermission()) service.startPreviewTracking()
            if (service.needsForegroundRestore()) {
                DrivingTrackingService.restoreSession(this@MainActivity)
            }
            selectedPowertrain?.let {
                if (latestTrackingSnapshot?.isSessionActive != true) {
                    service.configureSession(it, selectedStartOdo, selectedTrackOdo)
                }
            }
            openRequestedTrackIfReady()
            updateControlButtons()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService?.unregisterListener(trackingListener)
            trackingService = null
            isTrackingServiceBound = false
            updateControlButtons()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        openTrackWhenServiceConnect =
            intent?.getBooleanExtra(DrivingTrackingService.EXTRA_OPEN_TRACK, false) == true
        showHome()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, DrivingTrackingService::class.java),
            trackingServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        updateSystemBarsForCurrentScreen()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.post(uiUpdateRunnable)
        updateTrackUi()
        syncAlertFlashFromTrackingState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentScreen == Screen.Track) {
            setTrackImmersiveMode(true)
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        stopAlertFlashLoop()
    }

    override fun onStop() {
        if (isTrackingServiceBound) {
            trackingService?.unregisterListener(trackingListener)
            unbindService(trackingServiceConnection)
            isTrackingServiceBound = false
            trackingService = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopAlertFlashLoop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(DrivingTrackingService.EXTRA_OPEN_TRACK, false) == true) {
            openTrackWhenServiceConnect = true
            openRequestedTrackIfReady()
        }
    }

    override fun onBackPressed() {
        if (currentScreen == Screen.Home) super.onBackPressed() else showHome()
    }

    private fun applyTrackingSnapshot(snapshot: TrackingSnapshot) {
        latestTrackingSnapshot = snapshot
        sessionState = snapshot.sessionState
        if (snapshot.isSessionActive || selectedPowertrain == null) {
            snapshot.selectedPowertrain?.let { selectedPowertrain = it }
        }
        if (snapshot.isSessionActive || selectedStartOdo.isEmpty()) {
            selectedStartOdo = snapshot.selectedStartOdo
        }
        if (snapshot.isSessionActive || selectedTrackOdo.isEmpty()) {
            selectedTrackOdo = snapshot.selectedTrackOdo
        }
        currentLocation = snapshot.currentLocation?.let { point ->
            Location(snapshot.currentLocationProvider ?: "tracking_service").apply {
                latitude = point.latitude
                longitude = point.longitude
                snapshot.currentLocationAccuracyM?.let {
                    accuracy = it
                }
                snapshot.currentLocationBearingDegrees?.let {
                    bearing = it
                }
            }
        }
        totalDistanceM = snapshot.totalDistanceM
        currentSpeedKmh = snapshot.currentSpeedKmh
        isGpsSignalStale = snapshot.isGpsSignalStale
        insideTrackArea = snapshot.insideTrackArea
        brakeLineLocation = snapshot.brakeLineLocation?.let { point ->
            Location("tracking_service").apply {
                latitude = point.latitude
                longitude = point.longitude
            }
        }
        drivingActionState = snapshot.drivingActionState
        speedGuidance = snapshot.speedGuidance
        odoConfirmedAtSessionDistanceM = snapshot.odoConfirmedAtSessionDistanceM
        currentRoutePoints.clear()
        currentRoutePoints.addAll(snapshot.currentRoutePoints)
        referenceRoutePoints.clear()
        referenceRoutePoints.addAll(snapshot.referenceRoutePoints)
        hasReferenceRoute = snapshot.hasReferenceRoute

        previewView?.setReferenceRoute(referenceRoutePoints)
        previewView?.setCurrentRoute(currentRoutePoints)
        previewView?.setBrakeLine(
            brakeLineLocation?.latitude,
            brakeLineLocation?.longitude
        )

        if (
            snapshot.noticeSequence > lastTrackingNoticeSequence &&
            !snapshot.noticeMessage.isNullOrBlank()
        ) {
            lastTrackingNoticeSequence = snapshot.noticeSequence
            Toast.makeText(this, snapshot.noticeMessage, Toast.LENGTH_LONG).show()
        }

        if (currentScreen == Screen.Track) {
            updateTrackUi()
        }
        syncAlertFlashFromTrackingState()
        updateControlButtons()
        openRequestedTrackIfReady()
    }

    private fun openRequestedTrackIfReady() {
        if (!openTrackWhenServiceConnect) return
        val snapshot = latestTrackingSnapshot ?: return
        val powertrain = snapshot.selectedPowertrain ?: return
        if (!snapshot.isSessionActive && snapshot.sessionState != SessionState.Finished) return

        openTrackWhenServiceConnect = false
        selectedPowertrain = powertrain
        if (currentScreen != Screen.Track) showTrack()
    }

    private fun syncAlertFlashFromTrackingState() {
        val flashColor = when {
            currentScreen != Screen.Track || sessionState != SessionState.Running -> null
            drivingActionState == DrivingActionState.DECELERATING ->
                Color.argb(170, 220, 38, 38)
            speedGuidance != null -> Color.argb(170, 245, 158, 11)
            else -> null
        }
        if (flashColor == null) {
            stopAlertFlashLoop()
            return
        }

        alertFlashOverlay?.setBackgroundColor(flashColor)
        startAlertFlashLoop()
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
        when (requestCode) {
            permissionRequestCode -> {
                val action = pendingPermissionAction
                pendingPermissionAction = null
                if (hasLocationPermission()) {
                    trackingService?.startPreviewTracking()
                    action?.invoke()
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }
            notificationPermissionRequestCode -> {
                val action = pendingNotificationPermissionAction
                pendingNotificationPermissionAction = null
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this,
                        "알림 권한이 없어 주행 상태 알림이 알림창에 표시되지 않을 수 있습니다.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                action?.invoke()
            }
        }
    }

    private fun requestNotificationPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingNotificationPermissionAction = action
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            notificationPermissionRequestCode
        )
    }

    private fun startAlertFlashLoop() {
        if (alertFlashOverlay == null) return
        if (isAlertFlashLoopActive) return
        isAlertFlashLoopActive = true
        uiHandler.removeCallbacks(alertFlashLoopRunnable)
        alertFlashLoopRunnable.run()
    }

    private fun stopAlertFlashLoop() {
        isAlertFlashLoopActive = false
        uiHandler.removeCallbacks(alertFlashLoopRunnable)
        alertFlashOverlay?.animate()?.cancel()
        alertFlashOverlay?.alpha = 0f
        alertFlashOverlay?.visibility = View.GONE
    }

    private fun runAlertFlashPulse() {
        if (!isAlertFlashLoopActive) return
        val overlay = alertFlashOverlay ?: run {
            isAlertFlashLoopActive = false
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
                        if (isAlertFlashLoopActive) {
                            uiHandler.postDelayed(alertFlashLoopRunnable, 180L)
                        }
                    }
                    .start()
            }
            .start()
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

    private fun scenarioInstruction(step: DrivingScenarioStep?): String {
        if (step == null) return "시험이 완료되었거나 적용할 시나리오가 없습니다."
        if (step.driveMode == ScenarioDriveMode.CONSTANT) {
            return "${step.targetSpeedKmh}km/h 정속 유지 · 감속 없음"
        }

        val decelTarget = step.decelTargetKmh ?: 0
        return when (drivingActionState) {
            DrivingActionState.WAITING_FOR_BRAKE_LINE ->
                "제동 시작점 통과 후\n${decelTarget}km/h까지 일반감속"
            DrivingActionState.DECELERATING ->
                "일반감속 중\n${decelTarget}km/h까지 감속"
            DrivingActionState.ACCELERATING ->
                "${step.accelerationMethod.label} · ${step.targetSpeedKmh}km/h까지 재가속"
            DrivingActionState.CRUISING ->
                "제동 시작점 통과 대기"
        }
    }

    private fun showHome() {
        stopAlertFlashLoop()
        currentScreen = Screen.Home
        setContentView(createHomeView())
        updateSystemBarsForCurrentScreen()
    }

    private fun showPreparation() {
        currentScreen = Screen.Preparation
        setContentView(createPreparationView())
        updateSystemBarsForCurrentScreen()
    }

    private fun showPowertrainSelection() {
        stopAlertFlashLoop()
        currentScreen = Screen.PowertrainSelection
        setContentView(createPowertrainSelectionView())
        updateSystemBarsForCurrentScreen()
    }

    private fun showTrack() {
        if (selectedPowertrain == null) {
            showPowertrainSelection()
            return
        }
        currentScreen = Screen.Track
        setContentView(createTrackView())
        updateSystemBarsForCurrentScreen()
        if (hasLocationPermission()) {
            trackingService?.startPreviewTracking()
        } else {
            requestLocationPermission { trackingService?.startPreviewTracking() }
        }
        updateTrackUi()
        syncAlertFlashFromTrackingState()
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
            primaryButton("고주로 주행") {
                val activeSnapshot = latestTrackingSnapshot
                if (activeSnapshot?.isSessionActive == true && activeSnapshot.selectedPowertrain != null) {
                    selectedPowertrain = activeSnapshot.selectedPowertrain
                    showTrack()
                } else {
                    showPowertrainSelection()
                }
            },
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

        val appCredits = TextView(this).apply {
            text = "version ${BuildConfig.VERSION_NAME}\nmade by jmson@infomagix.com"
            textSize = 10f
            setTextColor(ScreenColors.MutedText)
            gravity = Gravity.END
        }
        root.addView(
            appCredits,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(20)
                bottomMargin = dp(20)
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
            primaryButton("하이브리드") {
                selectPowertrainAndShowTrack(PowertrainType.HYBRID)
            },
            largeButton()
        )
        content.addView(
            primaryButton("전기") {
                selectPowertrainAndShowTrack(PowertrainType.ELECTRIC)
            },
            largeButton()
        )
        root.addView(content, weightedContent())
        return root
    }

    private fun selectPowertrainAndShowTrack(powertrainType: PowertrainType) {
        val activeSnapshot = latestTrackingSnapshot
        if (activeSnapshot?.isSessionActive == true) {
            selectedPowertrain = activeSnapshot.selectedPowertrain
            Toast.makeText(this, "진행 중인 주행 시험을 다시 엽니다.", Toast.LENGTH_SHORT).show()
            showTrack()
            return
        }
        selectedPowertrain = powertrainType
        drivingActionState = DrivingActionState.CRUISING
        trackingService?.configureSession(
            powertrainType,
            selectedStartOdo,
            selectedTrackOdo
        )
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
            spinnerOf("시험 방식", "고주로 주행", "단거리 주행", "사용자 설정") {
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
        root.addView(
            toolbar(
                "고주로 주행",
                "${selectedPowertrain?.label} / ${activeTestModeLabel()}"
            )
        )
        trackStatusBar = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(
            trackStatusBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5)
            )
        )

        val body = FrameLayout(this)
        val trackOverlay = TrackPreviewView(this).also { view ->
            view.setReferenceRoute(referenceRoutePoints)
            view.setCurrentRoute(currentRoutePoints)
            view.setBrakeLine(brakeLineLocation?.latitude, brakeLineLocation?.longitude)
            view.setBackgroundPresentation(true)
            view.isClickable = false
            view.isFocusable = false
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewView = trackOverlay

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(22))
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Leave the upper route preview open and place primary driving metrics below it.
        content.addView(space(dp(104)))
        content.addView(sectionTitle("측정"))

        tvSpeed = metricTextView("0.0 km/h").apply {
            textSize = 24f
        }
        val trackOdoInput = numberInput("ODO (km)").apply {
            textSize = 14f
            setPadding(dp(10), 0, dp(10), 0)
        }
        val initialTrackOdo = selectedTrackOdo.ifEmpty { selectedStartOdo }
        tvCurrentOdo = metricTextView(currentOdoValueLabel()).apply {
            textSize = 21f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val trackOdoSavedText = tvCurrentOdo!!
        if (initialTrackOdo.isNotEmpty()) {
            trackOdoInput.setText(initialTrackOdo)
            trackOdoInput.setSelection(trackOdoInput.text.length)
        }
        var isTrackOdoEditing = initialTrackOdo.isEmpty()
        val trackOdoActionButton = secondaryButton("") {}.apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
        }
        val updateTrackOdoMode = {
            trackOdoInput.visibility = if (isTrackOdoEditing) View.VISIBLE else View.GONE
            trackOdoSavedText.visibility = if (isTrackOdoEditing) View.GONE else View.VISIBLE
            trackOdoActionButton.text = if (isTrackOdoEditing) "확인" else "편집"
        }
        trackOdoActionButton.setOnClickListener {
            if (!isTrackOdoEditing) {
                isTrackOdoEditing = true
                val currentOdo = latestTrackingSnapshot?.currentOdoKm
                    ?: currentOdoKmOrNull()
                val savedOdo = OdometerRules.editableOdoText(currentOdo)
                    ?: selectedTrackOdo.ifEmpty { selectedStartOdo }
                trackOdoInput.setText(savedOdo)
                trackOdoInput.setSelection(trackOdoInput.text.length)
                updateTrackOdoMode()
                trackOdoInput.post {
                    trackOdoInput.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(trackOdoInput, InputMethodManager.SHOW_IMPLICIT)
                }
                return@setOnClickListener
            }

            val enteredText = trackOdoInput.text.toString().trim()
            val service = trackingService
            if (service == null) {
                Toast.makeText(
                    this@MainActivity,
                    "주행 서비스에 연결 중입니다.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val result = service.confirmOdo(enteredText, selectedStartOdo)
            if (!result.success) {
                Toast.makeText(
                    this@MainActivity,
                    result.message ?: "ODO를 저장할 수 없습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            selectedTrackOdo = enteredText
            trackOdoSavedText.text = currentOdoValueLabel()
            isTrackOdoEditing = false
            trackOdoInput.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(trackOdoInput.windowToken, 0)
            updateTrackOdoMode()
            updateTrackUi()
            Toast.makeText(
                this@MainActivity,
                result.message ?: "ODO가 저장되었습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }

        val odoCard = cardContainer().apply {
            orientation = LinearLayout.VERTICAL
            background = trackSurfaceBackground()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "ODO"
                            textSize = 13f
                            setTextColor(ScreenColors.MutedText)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )
                    addView(
                        trackOdoActionButton,
                        LinearLayout.LayoutParams(dp(60), dp(32))
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(34)
                )
            )
            addView(
                trackOdoInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply {
                    topMargin = dp(4)
                }
            )
            addView(
                trackOdoSavedText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply {
                    topMargin = dp(4)
                }
            )
        }
        updateTrackOdoMode()

        content.addView(
            metricRow(
                metricCard("속도", tvSpeed!!).apply {
                    background = trackSurfaceBackground()
                },
                odoCard
            )
        )

        content.addView(sectionTitle("현재 시나리오"))
        tvScenarioSection = metricTextView("A-1 · 정속")
        tvScenarioProgress = summaryText("0.0~100.0km · 전환까지 100.0km")
        tvScenarioTarget = metricTextView("규정속도 60km/h")
        tvScenarioInstruction = metricTextView("60km/h 정속 유지 · 감속 없음")
        content.addView(
            cardContainer().apply {
                orientation = LinearLayout.VERTICAL
                background = trackSurfaceBackground()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(tvScenarioSection)
                addView(tvScenarioProgress)
                addView(tvScenarioTarget)
                addView(tvScenarioInstruction)
            }
        )

        btnSetBrakeLine = secondaryButton("시작선 수동 설정") {
            setBrakeLine()
        }.apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
        }
        btnClearBrakeLine = secondaryButton("브레이크 초기화") {
            confirmClearBrakeLine()
        }.apply {
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(12), 0, dp(12), 0)
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

        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnStartPause, LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(8) })
                addView(btnStop, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
            },
            compactFullWidth()
        )
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    btnSetBrakeLine,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        rightMargin = dp(4)
                    }
                )
                addView(
                    btnClearBrakeLine,
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        leftMargin = dp(4)
                    }
                )
            },
            fullWidth()
        )

        updateControlButtons()
        scroll.addView(content)
        body.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        body.addView(
            trackOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(body, weightedContent())
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        alertFlashOverlay = View(this).apply {
            setBackgroundColor(Color.argb(170, 220, 38, 38))
            alpha = 0f
            visibility = View.GONE
            isClickable = false
        }
        container.addView(
            alertFlashOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        applyTrackBottomInsets(container, content)
        return container
    }

    @Suppress("DEPRECATION")
    private fun applyTrackBottomInsets(container: View, content: View) {
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom

        container.setOnApplyWindowInsetsListener { _, insets ->
            val safeBottom = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val navigationBar = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    val mandatoryGesture =
                        insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
                    max(navigationBar, mandatoryGesture)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    max(
                        insets.systemWindowInsetBottom,
                        insets.mandatorySystemGestureInsets.bottom
                    )
                }
                else -> insets.systemWindowInsetBottom
            }
            content.setPadding(
                baseLeft,
                baseTop,
                baseRight,
                max(baseBottom, safeBottom + dp(8))
            )
            insets
        }
        container.post { container.requestApplyInsets() }
    }

    private fun updateSystemBarsForCurrentScreen() {
        setTrackImmersiveMode(currentScreen == Screen.Track)
    }

    @Suppress("DEPRECATION")
    private fun setTrackImmersiveMode(enabled: Boolean) {
        val decorView = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.apply {
                if (enabled) {
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                } else {
                    show(WindowInsets.Type.systemBars())
                }
            }
            return
        }

        decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
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
        if (!hasLocationPermission()) {
            requestLocationPermission {
                startSession()
            }
            return
        }
        val powertrain = selectedPowertrain ?: return
        val service = trackingService
        if (service == null) {
            Toast.makeText(this, "주행 서비스에 연결 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val readinessIssue = service.startReadinessIssue()
        if (readinessIssue != null) {
            Toast.makeText(this, readinessIssue, Toast.LENGTH_SHORT).show()
            return
        }

        val brakeLineWasSetAutomatically = service.snapshot().brakeLineLocation == null
        if (brakeLineWasSetAutomatically) {
            val result = service.setBrakeLineAtCurrentLocation()
            if (!result.success) {
                Toast.makeText(
                    this,
                    result.message ?: "브레이크 시작선을 설정할 수 없습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        requestNotificationPermission {
            DrivingTrackingService.startSession(
                this,
                powertrain,
                selectedStartOdo,
                selectedTrackOdo
            )
            if (brakeLineWasSetAutomatically) {
                Toast.makeText(
                    this,
                    "현재 위치를 브레이크 시작선으로 설정하고 주행을 시작했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun pauseSession() {
        trackingService?.pauseSession()
    }

    private fun resumeSession() {
        trackingService?.resumeSession()
    }

    private fun stopSession() {
        trackingService?.stopSession()
        Toast.makeText(this, "주행 시험이 종료되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun resetToReady() {
        trackingService?.resetToReady()
    }

    private fun setBrakeLine() {
        requestLocationPermission {
            val service = trackingService
            if (service == null) {
                Toast.makeText(this, "주행 서비스에 연결 중입니다.", Toast.LENGTH_SHORT).show()
                return@requestLocationPermission
            }
            val result = service.setBrakeLineAtCurrentLocation()
            Toast.makeText(
                this,
                result.message ?: if (result.success) {
                    "브레이크 시작선이 설정되었습니다."
                } else {
                    "브레이크 시작선을 설정할 수 없습니다."
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmClearBrakeLine() {
        if (brakeLineLocation == null) return
        AlertDialog.Builder(this)
            .setTitle("브레이크 시작선 초기화")
            .setMessage("저장된 브레이크 시작선을 삭제합니다. 다음 주행 시작 시 현재 위치가 새 시작선으로 설정됩니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("초기화") { _, _ ->
                val result = trackingService?.clearBrakeLine()
                    ?: TrackingCommandResult(false, "주행 서비스에 연결 중입니다.")
                Toast.makeText(
                    this,
                    result.message ?: if (result.success) {
                        "브레이크 시작선이 초기화되었습니다."
                    } else {
                        "브레이크 시작선을 초기화할 수 없습니다."
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun updateControlButtons() {
        val startLabel = when (sessionState) {
            SessionState.Idle, SessionState.Ready, SessionState.Finished -> "주행 시작"
            SessionState.Running -> "일시정지"
            SessionState.Paused -> "재개"
        }
        btnStartPause?.text = startLabel
        btnStartPause?.isEnabled = trackingService != null
        btnStop?.text = if (sessionState == SessionState.Finished) "초기화" else "정지"
        btnStop?.isEnabled = trackingService != null &&
            sessionState != SessionState.Idle &&
            sessionState != SessionState.Ready
        btnSetBrakeLine?.isEnabled =
            trackingService != null
        btnClearBrakeLine?.isEnabled =
            trackingService != null &&
            brakeLineLocation != null &&
            sessionState != SessionState.Running && sessionState != SessionState.Paused
        btnSetBrakeLine?.text = if (brakeLineLocation == null) {
            "시작선 수동 설정"
        } else {
            "브레이크 재설정"
        }
    }

    private fun updateTrackUi() {
        updateTrackStatusBar()
        if (sessionState != SessionState.Running) {
            stopAlertFlashLoop()
        }

        val location = currentLocation
        val progressDistanceKm =
            latestTrackingSnapshot?.testProgressDistanceKm ?: testProgressDistanceKm()
        val targetDistanceKm = selectedPowertrain
            ?.let(KatriDrivingScenario::targetDistanceKm)
            ?: KatriDrivingScenario.hybridTestTargetKm
        val scenarioStep =
            latestTrackingSnapshot?.scenarioStep ?: currentScenarioStep()
        val hasReachedTarget =
            selectedPowertrain != null && progressDistanceKm >= targetDistanceKm
        val returnInstruction = selectedPowertrain
            ?.let(KatriDrivingScenario::returnInstruction)

        tvSpeed?.text = String.format(Locale.US, "%.1f km/h", currentSpeedKmh)
        tvCurrentOdo?.text = currentOdoValueLabel()
        if (scenarioStep == null) {
            tvScenarioSection?.text = if (hasReachedTarget) {
                "시험 목표거리 도달"
            } else {
                "시나리오 미설정"
            }
            tvScenarioProgress?.text = String.format(
                Locale.US,
                "현재 %.1fkm · 시험 범위 0~%.0fkm",
                progressDistanceKm,
                targetDistanceKm
            )
            tvScenarioTarget?.text = if (hasReachedTarget) {
                String.format(Locale.US, "반납 기준 %.0fkm", targetDistanceKm)
            } else {
                "규정속도 -"
            }
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
        tvScenarioInstruction?.text = speedGuidance
            ?.let(TargetSpeedGuidanceRules::displayMessage)
            ?: if (hasReachedTarget && returnInstruction != null) {
                returnInstruction
            } else {
                scenarioInstruction(scenarioStep)
            }
        tvScenarioInstruction?.setTextColor(
            when {
                speedGuidance != null -> Color.rgb(194, 65, 12)
                drivingActionState == DrivingActionState.DECELERATING ->
                    Color.rgb(185, 28, 28)
                drivingActionState == DrivingActionState.ACCELERATING ->
                    ScreenColors.Primary
                else -> ScreenColors.Text
            }
        )

        if (location != null) {
            previewView?.setState(
                isInTrack = insideTrackArea,
                isRunning = sessionState == SessionState.Running,
                latitude = location.latitude,
                longitude = location.longitude,
                bearingDegrees = location.bearing.takeIf {
                    location.hasBearing() && currentSpeedKmh >= 5.0
                }
            )
        }
    }

    private fun updateTrackStatusBar() {
        if (currentScreen != Screen.Track) return
        val state = TrackStatusBarRules.resolve(
            sessionState = sessionState,
            actionState = drivingActionState,
            hasSpeedGuidance = speedGuidance != null,
            isGpsSignalStale = isGpsSignalStale
        )
        val color = when (state) {
            TrackStatusBarState.INACTIVE -> ScreenColors.StatusInactive
            TrackStatusBarState.NORMAL -> ScreenColors.StatusNormal
            TrackStatusBarState.SPEED_WARNING -> ScreenColors.StatusSpeedWarning
            TrackStatusBarState.BRAKING -> ScreenColors.StatusBraking
            TrackStatusBarState.GPS_ISSUE -> ScreenColors.StatusGpsIssue
            TrackStatusBarState.PAUSED -> ScreenColors.StatusPaused
        }
        val description = when (state) {
            TrackStatusBarState.INACTIVE -> "주행 대기"
            TrackStatusBarState.NORMAL -> "정상 주행"
            TrackStatusBarState.SPEED_WARNING -> "규정속도 이탈"
            TrackStatusBarState.BRAKING -> "감속 진행 중"
            TrackStatusBarState.GPS_ISSUE -> "GPS 신호 이상"
            TrackStatusBarState.PAUSED -> "주행 일시정지"
        }
        trackStatusBar?.setBackgroundColor(color)
        trackStatusBar?.contentDescription = description
    }

    private fun activeTestModeLabel(): String {
        if (selectedTestMode != "고주로 주행") return selectedTestMode
        val powertrain = selectedPowertrain ?: return selectedTestMode
        val targetDistanceKm = KatriDrivingScenario.targetDistanceKm(powertrain)
        return String.format(Locale.US, "%.0fkm 고주로 주행", targetDistanceKm)
    }

    private fun currentOdoKmOrNull(): Double? {
        val confirmedOdo = selectedTrackOdo.ifEmpty { selectedStartOdo }.toDoubleOrNull()
        return OdometerRules.currentOdoKm(
            confirmedOdoKm = confirmedOdo,
            trackedDistanceM = totalDistanceM,
            confirmationDistanceM = odoConfirmedAtSessionDistanceM
        )
    }

    private fun currentOdoValueLabel(): String {
        val currentOdo = currentOdoKmOrNull() ?: return "미입력"
        return String.format(Locale.US, "%.2f km", currentOdo)
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
        addView(left, LinearLayout.LayoutParams(0, dp(104), 1f).apply { rightMargin = dp(6) })
        addView(right, LinearLayout.LayoutParams(0, dp(104), 1f).apply { leftMargin = dp(6) })
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

    private fun trackSurfaceBackground(): android.graphics.drawable.GradientDrawable {
        return rounded(
            Color.argb(238, 255, 255, 255),
            dp(8),
            Color.argb(190, 203, 213, 225)
        )
    }
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
    private val navigationMarkerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(12).toFloat()
        color = Color.rgb(15, 23, 42)
    }
    private val navigationMarkerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(7).toFloat()
        color = Color.WHITE
    }
    private val navigationMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(37, 99, 235)
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
    private val minimumHeadingSegmentM = 3.0
    private val brakeLineHalfLengthM = 60f
    private var brakeLine: GeoPoint? = null
    private var currentMarkerLocation: GeoPoint? = null
    private var navigationHeadingDegrees = 0f
    private var hasNavigationHeading = false
    private var inTrack = true
    private var running = false
    private var backgroundPresentation = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    fun setBackgroundPresentation(enabled: Boolean) {
        backgroundPresentation = enabled
        invalidate()
    }

    fun setState(
        isInTrack: Boolean,
        isRunning: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        bearingDegrees: Float? = null
    ) {
        inTrack = isInTrack
        running = isRunning
        if (latitude != null && longitude != null) {
            val location = GeoPoint(latitude, longitude)
            currentMarkerLocation = location
            bearingDegrees
                ?.takeIf { it.isFinite() }
                ?.let(::updateNavigationHeading)
            if (isRunning) addRoutePoint(location)
        }
        invalidate()
    }

    fun setReferenceRoute(points: List<GeoPoint>) {
        referenceRoute.clear()
        referenceRoute.addAll(points)
        invalidate()
    }

    fun setCurrentRoute(points: List<GeoPoint>) {
        val visiblePoints = points.takeLast(maxRouteSize)
        updateNavigationHeadingFromRoute(visiblePoints)
        currentRoute.clear()
        currentRoute.addAll(visiblePoints)
        if (currentMarkerLocation == null) {
            currentMarkerLocation = currentRoute.lastOrNull()
        }
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
        applyPresentationAlphas()

        val baseRoute = if (referenceRoute.isNotEmpty()) referenceRoute else currentRoute
        if (baseRoute.isEmpty()) {
            if (brakeLine != null) {
                val centerY = height * 0.5f
                val startX = width * 0.25f
                val endX = width * 0.75f
                canvas.drawLine(startX, centerY, endX, centerY, brakeLineOutlinePaint)
                canvas.drawLine(startX, centerY, endX, centerY, brakeLinePaint)
                if (!backgroundPresentation) {
                    canvas.drawText("브레이크 시작선", width * 0.5f, centerY - dp(8), brakeLabelPaint)
                }
            }
            if (!backgroundPresentation) {
                canvas.drawText("첫 랩 GPS 경로 수집 대기", dp(16).toFloat(), dp(20).toFloat(), labelPaint)
            }
            if (currentMarkerLocation != null) {
                drawNavigationMarker(canvas, PointF(width * 0.5f, height * 0.5f))
            }
            return
        }

        val projection = Projection(baseRoute.first().latitude, baseRoute.first().longitude)
        val projectedReference = referenceRoute.map { project(it, projection) }
        val projectedCurrent = currentRoute.map { project(it, projection) }
        val projectedBrakeLine = brakeLine?.let { project(it, projection) }
        val projectedCurrentLocation = currentMarkerLocation
            ?.let { project(it, projection) }
            ?: projectedCurrent.lastOrNull()
        val boundsPoints = if (projectedReference.isNotEmpty()) {
            projectedReference + listOfNotNull(projectedBrakeLine)
        } else {
            projectedCurrent + listOfNotNull(projectedBrakeLine)
        }
        val transform = viewTransform(boundsPoints)

        if (projectedReference.size >= 2) {
            drawRoute(canvas, projectedReference, transform, referencePaint, closePath = true)
            if (!backgroundPresentation) {
                canvas.drawText("첫 랩 기준 경로", dp(12).toFloat(), dp(18).toFloat(), labelPaint)
            }
        } else if (!backgroundPresentation) {
            canvas.drawText("첫 랩 기준 경로 수집 중", dp(12).toFloat(), dp(18).toFloat(), labelPaint)
        }

        if (projectedCurrent.size >= 2) {
            routePaint.color = if (inTrack) ScreenColors.Primary else Color.rgb(239, 68, 68)
            routePaint.alpha = if (backgroundPresentation) 78 else 255
            drawRoute(canvas, projectedCurrent, transform, routePaint, closePath = false)
        }

        if (projectedBrakeLine != null) {
            drawBrakeLine(canvas, projectedBrakeLine, projectedReference, projectedCurrent, transform)
        }

        projectedCurrentLocation?.let { current ->
            val mapped = mapToView(current, transform)
            drawNavigationMarker(canvas, mapped)
        }
    }

    private fun applyPresentationAlphas() {
        val backgroundAlpha = backgroundPresentation
        referencePaint.alpha = if (backgroundAlpha) 52 else 255
        routePaint.alpha = if (backgroundAlpha) 78 else 255
        brakeLineOutlinePaint.alpha = if (backgroundAlpha) 190 else 255
        brakeLinePaint.alpha = if (backgroundAlpha) 170 else 255
        navigationMarkerShadowPaint.alpha = if (backgroundAlpha) 45 else 70
        navigationMarkerOutlinePaint.alpha = if (backgroundAlpha) 235 else 255
        navigationMarkerPaint.alpha = if (backgroundAlpha) 220 else 255
        labelPaint.alpha = if (backgroundAlpha) 0 else 255
        brakeLabelPaint.alpha = if (backgroundAlpha) 0 else 255
    }

    private fun drawNavigationMarker(canvas: Canvas, center: PointF) {
        val markerPath = Path().apply {
            moveTo(0f, -dp(43).toFloat())
            lineTo(dp(27).toFloat(), dp(31).toFloat())
            lineTo(0f, dp(15).toFloat())
            lineTo(-dp(27).toFloat(), dp(31).toFloat())
            close()
        }

        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.rotate(navigationHeadingDegrees)
        canvas.save()
        canvas.translate(0f, dp(2).toFloat())
        canvas.drawPath(markerPath, navigationMarkerShadowPaint)
        canvas.restore()
        canvas.drawPath(markerPath, navigationMarkerOutlinePaint)
        canvas.drawPath(markerPath, navigationMarkerPaint)
        canvas.restore()
    }

    private fun updateNavigationHeadingFromRoute(points: List<GeoPoint>) {
        val current = points.lastOrNull() ?: return
        for (index in points.lastIndex - 1 downTo 0) {
            val previous = points[index]
            if (distanceMeters(previous, current) < minimumHeadingSegmentM) continue

            val baseLatitude = (previous.latitude + current.latitude) * 0.5
            val eastM = (current.longitude - previous.longitude) *
                111_320.0 * cos(Math.toRadians(baseLatitude))
            val northM = (current.latitude - previous.latitude) * 110_540.0
            updateNavigationHeading(
                Math.toDegrees(atan2(eastM, northM)).toFloat()
            )
            return
        }
    }

    private fun updateNavigationHeading(nextHeadingDegrees: Float) {
        val normalizedNext = normalizeHeading(nextHeadingDegrees)
        if (!hasNavigationHeading) {
            navigationHeadingDegrees = normalizedNext
            hasNavigationHeading = true
            return
        }

        val shortestDelta =
            (normalizedNext - navigationHeadingDegrees + 540f) % 360f - 180f
        navigationHeadingDegrees = normalizeHeading(
            navigationHeadingDegrees + shortestDelta * 0.35f
        )
    }

    private fun normalizeHeading(headingDegrees: Float): Float {
        val normalized = headingDegrees % 360f
        return if (normalized < 0f) normalized + 360f else normalized
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

        if (!backgroundPresentation) {
            val mappedCenter = mapToView(center, transform)
            canvas.drawText("브레이크 시작선", mappedCenter.x, mappedCenter.y - dp(8), brakeLabelPaint)
        }
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
    val StatusInactive = Color.rgb(203, 213, 225)
    val StatusNormal = Color.rgb(22, 163, 74)
    val StatusSpeedWarning = Color.rgb(234, 88, 12)
    val StatusBraking = Color.rgb(220, 38, 38)
    val StatusGpsIssue = Color.rgb(234, 179, 8)
    val StatusPaused = Color.rgb(100, 116, 139)
}
