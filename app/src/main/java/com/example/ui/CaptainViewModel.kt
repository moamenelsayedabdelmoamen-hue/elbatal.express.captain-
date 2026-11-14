package com.example.ui

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CaptainEntity
import com.example.data.CaptainRepository
import com.example.data.DailyWalletEntity
import com.example.data.DailyWalletManager
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.OrderHistoryHelper
import com.example.data.OrderHistorySummaryStats
import com.example.data.OrderHistoryTab
import com.example.data.OrderStatus
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.location.CaptainLocationTracker
import com.example.data.location.GpsStatus
import com.example.data.storage.ImageUploadState
import android.util.Log
import com.example.data.extractAllShifts
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    object OrderAccepted : UiEvent()
    object OrderCompleted : UiEvent()
    object RequestLocationPermission : UiEvent()
    object RequestGpsEnable : UiEvent()
}

data class OrderActionError(
    val message: String,
    val actionLabel: String,
    val isRetryable: Boolean = true,
    val isNotFound: Boolean = false,
    val onRetry: () -> Unit,
    val onDismissOrClear: (() -> Unit)? = null
)

class CaptainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    val repository = CaptainRepository(db.captainDao(), db.orderDao(), db.shiftDao(), application.applicationContext)

    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private var shiftDocListener: ListenerRegistration? = null
    private var shiftQueryListener: ListenerRegistration? = null
    private var captainDocListener: ListenerRegistration? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    val captainState: StateFlow<CaptainEntity?> = repository.captainFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gpsStatus: StateFlow<GpsStatus> = CaptainLocationTracker.gpsStatus
    val currentLocation: StateFlow<Location?> = CaptainLocationTracker.currentLocation
    val isGpsTracking: StateFlow<Boolean> = CaptainLocationTracker.isTracking

    // Work Shift State & Management with Live Firestore Real-Time Updates
    private val _liveShifts = MutableStateFlow<List<ShiftEntity>>(emptyList())
    val shifts: StateFlow<List<ShiftEntity>> = combine(repository.shiftsFlow, _liveShifts) { roomShifts, liveShifts ->
        if (liveShifts.isNotEmpty()) liveShifts else roomShifts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentCairoTime = MutableStateFlow(ShiftManager.getCurrentCairoTimeFormatted())
    val currentCairoTime: StateFlow<String> = _currentCairoTime.asStateFlow()

    private val _currentCairoDate = MutableStateFlow(ShiftManager.getCurrentCairoDateFormatted())
    val currentCairoDate: StateFlow<String> = _currentCairoDate.asStateFlow()

    private val _shiftStatus = MutableStateFlow<ShiftStatus>(ShiftStatus.NoSchedule())
    val shiftStatus: StateFlow<ShiftStatus> = _shiftStatus.asStateFlow()

    val isShiftNetworkConnected: StateFlow<Boolean> = repository.isShiftNetworkConnected
    val isShiftSynced: StateFlow<Boolean> = repository.isShiftSynced

    val weeklyWorkHours: StateFlow<Double> = shifts.map { ShiftManager.calculateWeeklyWorkHours(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val dailyWorkHours: StateFlow<Double> = shifts.map { list ->
        val todayShift = list.find { it.dayOfWeek == ShiftManager.getCurrentCairoCalendar().get(java.util.Calendar.DAY_OF_WEEK) }
        todayShift?.let { ShiftManager.calculateShiftDurationHours(it) } ?: 0.0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val activeOrder: StateFlow<OrderEntity?> = repository.activeOrderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Orders filtered by shift working hours: captain receives available orders ONLY during active shift
    val availableOrders: StateFlow<List<OrderEntity>> = combine(
        repository.availableOrdersFlow,
        _shiftStatus
    ) { orders, status ->
        if (status.isAvailableForOrders) orders else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingIncomingOrder: StateFlow<OrderEntity?> = combine(
        repository.pendingOrderFlow,
        _shiftStatus
    ) { order, status ->
        if (status.isAvailableForOrders) order else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allOrders: StateFlow<List<OrderEntity>> = repository.allOrdersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedOrders: StateFlow<List<OrderEntity>> = repository.completedOrdersFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedCount: StateFlow<Int> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val fromOrders = orders.count {
            it.status == OrderStatus.DELIVERED &&
                DailyWalletManager.isCaptainOwnOrder(it, uid, phone)
        }
        maxOf(fromOrders, captain?.totalOrdersCount ?: 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalEarnings: StateFlow<Double> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val fromOrders = orders
            .filter {
                it.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.isCaptainOwnOrder(it, uid, phone)
            }
            .sumOf { it.deliveryFee }
        maxOf(fromOrders, captain?.walletBalance ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    // --- 1:00 PM Daily Wallet Rollover & Archived Order History Date Selection ---
    private val _currentOperationalDayKey = MutableStateFlow(DailyWalletManager.getOperationalDayKey())
    val currentOperationalDayKey: StateFlow<String> = _currentOperationalDayKey.asStateFlow()

    private val _todayCalendarDateKey = MutableStateFlow(OrderHistoryHelper.getTodayDateKey())
    val todayCalendarDateKey: StateFlow<String> = _todayCalendarDateKey.asStateFlow()

    // Selected calendar date ("yyyy-MM-dd") for Archived Order History — defaults strictly to TODAY
    private val _selectedHistoryDateKey = MutableStateFlow(OrderHistoryHelper.getTodayDateKey())
    val selectedHistoryDateKey: StateFlow<String> = _selectedHistoryDateKey.asStateFlow()

    // Legacy compatibility alias for WalletScreen day selection
    val selectedHistoryDayKey: StateFlow<String?> = _selectedHistoryDateKey.map<String, String?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _selectedHistoryDateKey.value)

    private val _selectedHistoryTab = MutableStateFlow(OrderHistoryTab.ALL)
    val selectedHistoryTab: StateFlow<OrderHistoryTab> = _selectedHistoryTab.asStateFlow()

    private val _isSyncingOrdersHistory = MutableStateFlow(false)
    val isSyncingOrdersHistory: StateFlow<Boolean> = _isSyncingOrdersHistory.asStateFlow()

    private val _historySyncError = MutableStateFlow<String?>(null)
    val historySyncError: StateFlow<String?> = _historySyncError.asStateFlow()

    val dailyWalletRecords: StateFlow<List<DailyWalletEntity>> = repository.dailyWalletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val captainHistoryOrders: StateFlow<List<OrderEntity>> = combine(
        allOrders,
        captainState
    ) { orders, captain ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        orders
            .filter { DailyWalletManager.isCaptainOwnOrder(it, uid, phone) }
            .sortedByDescending { DailyWalletManager.getOrderEffectiveTimestamp(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Archived finished orders (strictly DELIVERED and CANCELLED) for the logged-in captain
     * whose completion/cancellation timestamp falls within _selectedHistoryDateKey,
     * sorted descending (newest finished first).
     */
    val archivedOrdersForSelectedDate: StateFlow<List<OrderEntity>> = combine(
        allOrders,
        captainState,
        _selectedHistoryDateKey
    ) { orders, captain, dateKey ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orders,
            captainUid = uid,
            captainPhone = phone,
            selectedDateKey = dateKey
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHistoryOrders: StateFlow<List<OrderEntity>> = combine(
        archivedOrdersForSelectedDate,
        _selectedHistoryTab
    ) { dateOrders, tab ->
        OrderHistoryHelper.filterByTab(dateOrders, tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historySummaryStats: StateFlow<OrderHistorySummaryStats> = combine(
        archivedOrdersForSelectedDate,
        _selectedHistoryTab
    ) { dateOrders, tab ->
        OrderHistoryHelper.computeSummaryStats(dateOrders, tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrderHistorySummaryStats(0, 0, 0))

    val selectedDayOrders: StateFlow<List<OrderEntity>> = archivedOrdersForSelectedDate

    // Current day wallet balance: real delivery fees earned today (operational day or today's calendar date)
    val currentDayWalletBalance: StateFlow<Double> = combine(
        allOrders,
        captainState,
        _currentOperationalDayKey
    ) { orders, captain, currentDayKey ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val todayCalendarKey = DailyWalletManager.getCalendarDateKey(System.currentTimeMillis())

        orders
            .filter { order ->
                order.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.isCaptainOwnOrder(order, uid, phone) &&
                    (
                        DailyWalletManager.getOrderOperationalDayKey(order) == currentDayKey ||
                        DailyWalletManager.getCalendarDateKey(DailyWalletManager.getOrderEffectiveTimestamp(order)) == todayCalendarKey
                    )
            }
            .sumOf { it.deliveryFee }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val currentDayCompletedCount: StateFlow<Int> = combine(
        allOrders,
        captainState,
        _currentOperationalDayKey
    ) { orders, captain, currentDayKey ->
        val uid = auth.currentUser?.uid ?: captain?.uid ?: ""
        val phone = captain?.phoneNumber ?: ""
        val todayCalendarKey = DailyWalletManager.getCalendarDateKey(System.currentTimeMillis())

        orders.count { order ->
            order.status == OrderStatus.DELIVERED &&
                DailyWalletManager.isCaptainOwnOrder(order, uid, phone) &&
                (
                    DailyWalletManager.getOrderOperationalDayKey(order) == currentDayKey ||
                    DailyWalletManager.getCalendarDateKey(DailyWalletManager.getOrderEffectiveTimestamp(order)) == todayCalendarKey
                )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Previous operational day's archived wallet balance (recorded at 1:00 PM rollover)
    val previousDayWalletBalance: StateFlow<Double> = combine(
        captainHistoryOrders,
        dailyWalletRecords,
        _currentOperationalDayKey
    ) { orders, records, currentDayKey ->
        val prevKey = DailyWalletManager.getPreviousOperationalDayKey(currentDayKey)
        val fromOrders = orders
            .filter {
                it.status == OrderStatus.DELIVERED &&
                    DailyWalletManager.getOrderOperationalDayKey(it) == prevKey
            }
            .sumOf { it.deliveryFee }
        if (fromOrders > 0.0) {
            fromOrders
        } else {
            records.find { it.dayKey == prevKey }?.walletBalance ?: 0.0
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val selectedDayWalletBalance: StateFlow<Double> = combine(
        captainHistoryOrders,
        dailyWalletRecords,
        _selectedHistoryDateKey,
        _currentOperationalDayKey
    ) { orders, records, selectedKey, currentDayKey ->
        if (selectedKey == currentDayKey) {
            orders
                .filter {
                    it.status == OrderStatus.DELIVERED &&
                        DailyWalletManager.getOrderOperationalDayKey(it) == currentDayKey
                }
                .sumOf { it.deliveryFee }
        } else {
            val fromOrders = orders
                .filter {
                    it.status == OrderStatus.DELIVERED &&
                        DailyWalletManager.getOrderOperationalDayKey(it) == selectedKey
                }
                .sumOf { it.deliveryFee }
            if (fromOrders > 0.0) {
                fromOrders
            } else {
                records.find { it.dayKey == selectedKey }?.walletBalance ?: 0.0
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _eventFlow = MutableSharedFlow<UiEvent>()
    val eventFlow: SharedFlow<UiEvent> = _eventFlow.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSimulatingOrder = MutableStateFlow(false)
    val isSimulatingOrder: StateFlow<Boolean> = _isSimulatingOrder.asStateFlow()

    // --- Firebase Storage Upload States ---
    private val _profileUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val profileUploadState: StateFlow<ImageUploadState> = _profileUploadState.asStateFlow()

    private val _idFrontUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val idFrontUploadState: StateFlow<ImageUploadState> = _idFrontUploadState.asStateFlow()

    private val _idBackUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val idBackUploadState: StateFlow<ImageUploadState> = _idBackUploadState.asStateFlow()

    private val _receiptUploadState = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    val receiptUploadState: StateFlow<ImageUploadState> = _receiptUploadState.asStateFlow()

    private val _orderActionError = MutableStateFlow<OrderActionError?>(null)
    val orderActionError: StateFlow<OrderActionError?> = _orderActionError.asStateFlow()

    fun dismissOrderActionError() {
        _orderActionError.value = null
    }

    init {
        // Dynamic live ticker updating Cairo clock, shift status, and 1:00 PM wallet rollover every second
        viewModelScope.launch {
            while (true) {
                _currentCairoTime.value = ShiftManager.getCurrentCairoTimeFormatted()
                _currentCairoDate.value = ShiftManager.getCurrentCairoDateFormatted()
                _shiftStatus.value = ShiftManager.calculateShiftStatus(shifts.value)

                val latestCalendarToday = OrderHistoryHelper.getTodayDateKey()
                if (latestCalendarToday != _todayCalendarDateKey.value) {
                    val prevToday = _todayCalendarDateKey.value
                    _todayCalendarDateKey.value = latestCalendarToday
                    if (_selectedHistoryDateKey.value == prevToday) {
                        _selectedHistoryDateKey.value = latestCalendarToday
                    }
                }

                val latestOpDay = DailyWalletManager.getOperationalDayKey()
                if (latestOpDay != _currentOperationalDayKey.value) {
                    _currentOperationalDayKey.value = latestOpDay
                    repository.reconcileDailyWallets()
                    _eventFlow.emit(
                        UiEvent.ShowToast("الساعة 1:00 م: تم تسجيل رصيد المحفظة في اليوم السابق وبدء محفظة اليوم الجديد برصيد 0 ج.م")
                    )
                }
                delay(1000L)
            }
        }
        // Auto-sync shifts & historical captain orders on launch/initialization
        viewModelScope.launch {
            repository.syncShiftsFromFirestore()
            repository.reconcileDailyWallets()
            if (auth.currentUser != null) {
                repository.syncCaptainOrdersHistoryFromFirestore()
            }
        }
        // Set up real-time SnapshotListener on Firestore for captain shifts
        setupRealtimeShiftListener()
    }

    /**
     * Called when opening the Order History screen ("سجل الطلبات") directly so it always
     * selects today's date automatically and displays only today's finished orders.
     */
    fun openOrdersHistoryToday() {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        _selectedHistoryDateKey.value = today
        _selectedHistoryTab.value = OrderHistoryTab.ALL
        _historySyncError.value = null
        if (auth.currentUser != null) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun selectHistoryDate(dateKey: String) {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        if (OrderHistoryHelper.isFutureDateKey(dateKey, today)) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("لا يمكن اختيار تاريخ مستقبلي في سجل الطلبات"))
            }
            return
        }
        val clamped = OrderHistoryHelper.clampToMaxToday(dateKey, today)
        _selectedHistoryDateKey.value = clamped
        _historySyncError.value = null
        if (auth.currentUser != null) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun resetHistoryDateToToday() {
        val today = OrderHistoryHelper.getTodayDateKey()
        _todayCalendarDateKey.value = today
        _selectedHistoryDateKey.value = today
        _historySyncError.value = null
        if (auth.currentUser != null) {
            syncOrdersHistory(silentSuccess = true)
        }
    }

    fun selectHistoryTab(tab: OrderHistoryTab) {
        _selectedHistoryTab.value = tab
    }

    fun selectHistoryDay(dayKey: String?) {
        val target = dayKey ?: OrderHistoryHelper.getTodayDateKey()
        selectHistoryDate(target)
    }

    fun shiftSelectedHistoryDay(deltaDays: Int) {
        val today = OrderHistoryHelper.getTodayDateKey()
        val candidate = OrderHistoryHelper.shiftDateKey(_selectedHistoryDateKey.value, deltaDays)
        if (!OrderHistoryHelper.isFutureDateKey(candidate, today)) {
            selectHistoryDate(candidate)
        }
    }

    fun syncOrdersHistory(silentSuccess: Boolean = false) {
        viewModelScope.launch {
            _isSyncingOrdersHistory.value = true
            _historySyncError.value = null
            val targetDate = _selectedHistoryDateKey.value
            val res = repository.syncCaptainArchivedOrdersForDate(targetDate)
            _isSyncingOrdersHistory.value = false
            if (res.isSuccess) {
                if (!silentSuccess) {
                    _eventFlow.emit(UiEvent.ShowToast("تم تحديث سجل الطلبات المنتهية بنجاح"))
                }
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage
                    ?: "تعذر الاتصال بقاعدة البيانات لجلب سجل الطلبات. تحقق من الإنترنت وأعد المحاولة."
                _historySyncError.value = errMsg
                if (!silentSuccess) {
                    _eventFlow.emit(UiEvent.ShowToast(errMsg))
                }
            }
        }
    }

    fun toggleOnlineStatus() {
        val app = getApplication<Application>()
        val current = captainState.value?.isOnline ?: false
        val newStatus = !current

        if (newStatus) {
            // 1. Check Permissions
            if (!CaptainLocationTracker.hasLocationPermission(app)) {
                viewModelScope.launch {
                    _eventFlow.emit(UiEvent.RequestLocationPermission)
                }
                return
            }

            // 2. Check GPS Hardware Provider
            if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
                viewModelScope.launch {
                    _eventFlow.emit(UiEvent.RequestGpsEnable)
                    _eventFlow.emit(UiEvent.ShowToast("يرجى تشغيل الـ GPS من إعدادات الهاتف لتفعيل الاتصال"))
                }
                return
            }

            // 3. Start Live GPS Foreground Service
            CaptainLocationTracker.startService(app)
            viewModelScope.launch {
                repository.setOnlineStatus(true)
                _eventFlow.emit(UiEvent.ShowToast("أنت الآن متصل ومتاح لاستقبال الرحلات عبر الـ GPS ⚡"))
            }
        } else {
            // Stop GPS Tracking Service
            CaptainLocationTracker.stopService(app)
            viewModelScope.launch {
                repository.setOnlineStatus(false)
                _eventFlow.emit(UiEvent.ShowToast("أنت الآن غير متصل. تم إيقاف بث وتتبع الموقع."))
            }
        }
    }

    fun onLocationPermissionGranted() {
        val app = getApplication<Application>()
        if (!CaptainLocationTracker.isGpsProviderEnabled(app)) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.RequestGpsEnable)
                _eventFlow.emit(UiEvent.ShowToast("تم منح الإذن! يرجى تشغيل الـ GPS لتصبح متصلاً"))
            }
        } else {
            CaptainLocationTracker.startService(app)
            viewModelScope.launch {
                repository.setOnlineStatus(true)
                _eventFlow.emit(UiEvent.ShowToast("تم تفعيل الـ GPS والاتصال بنجاح! ⚡"))
            }
        }
    }

    fun onLocationPermissionDenied() {
        viewModelScope.launch {
            _eventFlow.emit(UiEvent.ShowToast("لا يمكن تفعيل وضع (متصل) واستقبال الطلبات دون إذن الموقع GPS"))
        }
    }

    fun login(
        phoneOrEmail: String,
        pass: String,
        onSuccess: () -> Unit,
        onNeedsEmailVerification: (String) -> Unit = {},
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (phoneOrEmail.isBlank() || pass.isBlank()) {
                onError("يرجى إدخال البريد الإلكتروني أو رقم الهاتف وكلمة المرور")
                return@launch
            }
            _isLoading.value = true
            try {
                when (val outcome = repository.login(phoneOrEmail, pass)) {
                    is LoginOutcome.Success -> {
                        _eventFlow.emit(UiEvent.ShowToast("أهلاً بك يا كابتن! تم تسجيل الدخول بنجاح"))
                        onSuccess()
                    }
                    is LoginOutcome.EmailNotVerified -> {
                        onNeedsEmailVerification(outcome.email)
                    }
                    is LoginOutcome.Error -> {
                        onError(outcome.message)
                    }
                }
            } catch (e: Exception) {
                onError("تعذر الاتصال: ${e.localizedMessage ?: "خطأ في الشبكة"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendPasswordReset(
        phoneOrEmail: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (phoneOrEmail.isBlank()) {
                onError("يرجى إدخال البريد الإلكتروني أو رقم الهاتف")
                return@launch
            }
            _isLoading.value = true
            try {
                val res = repository.sendPasswordReset(phoneOrEmail)
                if (res.isSuccess) {
                    val msg = res.getOrDefault("تم إرسال رابط استعادة كلمة السر بنجاح")
                    _eventFlow.emit(UiEvent.ShowToast(msg))
                    onSuccess(msg)
                } else {
                    onError(res.exceptionOrNull()?.localizedMessage ?: "تعذر إرسال رابط الاستعادة")
                }
            } catch (e: Exception) {
                onError("خطأ: ${e.localizedMessage ?: "تعذر الاتصال"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun signUp(
        fullName: String,
        phone: String,
        email: String,
        pass: String,
        confirmPass: String = "",
        avatarUri: String = "",
        avatarPresetIndex: Int = 0,
        vehicleType: String = "سكوتر / دراجة نارية",
        nationalIdUri: String = "",
        idBackUri: String = "",
        licenseUri: String = "",
        onEmailSent: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            if (fullName.isBlank()) {
                onError("يرجى إدخال اسم الكابتن الكامل")
                return@launch
            }
            if (phone.length < 8) {
                onError("يرجى إدخال رقم هاتف صحيح")
                return@launch
            }
            if (email.isBlank()) {
                onError("البريد الإلكتروني إلزامي لتأكيد الحساب وتفعيله")
                return@launch
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                onError("صيغة البريد الإلكتروني غير صحيحة (مثال: captain@example.com)")
                return@launch
            }
            if (pass.length < 6) {
                onError("كلمة المرور يجب أن تكون 6 أحرف أو أرقام على الأقل")
                return@launch
            }
            if (confirmPass.isNotBlank() && pass != confirmPass) {
                onError("كلمة المرور وتأكيد كلمة المرور غير متطابقتين")
                return@launch
            }
            if ((nationalIdUri.isNotBlank() && idBackUri.isBlank()) || (nationalIdUri.isBlank() && idBackUri.isNotBlank())) {
                onError("يرجى رفع الوجه الأمامي والوجه الخلفي للبطاقة الشخصية معاً لاكتمال المستند")
                return@launch
            }

            _isLoading.value = true
            try {
                val result = repository.registerCaptainWithEmailVerification(
                    fullName = fullName,
                    phone = phone,
                    email = email,
                    pass = pass,
                    avatarPresetIndex = avatarPresetIndex,
                    avatarUri = avatarUri,
                    vehicleType = vehicleType,
                    nationalIdUri = nationalIdUri,
                    idBackUri = idBackUri,
                    licenseUri = licenseUri
                )
                if (result.isSuccess) {
                    val sentEmail = result.getOrNull() ?: email.trim()
                    _eventFlow.emit(UiEvent.ShowToast("تم إنشاء الحساب ورفع الصور بأمان وإرسال رابط التأكيد"))
                    onEmailSent(sentEmail)
                } else {
                    onError(result.exceptionOrNull()?.localizedMessage ?: "فشل إنشاء الحساب")
                }
            } catch (e: Exception) {
                onError("خطأ: ${e.localizedMessage ?: "حدث خطأ غير متوقع"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun checkEmailVerification(
        onVerified: () -> Unit,
        onNotVerified: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val res = repository.checkEmailVerification()
                if (res.isSuccess && res.getOrNull() == true) {
                    _eventFlow.emit(UiEvent.ShowToast("تم تأكيد البريد بنجاح! مرحباً بك يا كابتن"))
                    onVerified()
                } else if (res.isSuccess) {
                    onNotVerified("لم يتم تأكيد البريد الإلكتروني بعد! يرجى فتح البريد الإلكتروني والضغط على الرابط أولاً ثم الضغط هنا مجدداً.")
                } else {
                    onNotVerified(res.exceptionOrNull()?.localizedMessage ?: "تعذر التحقق من حالة البريد")
                }
            } catch (e: Exception) {
                onNotVerified(e.localizedMessage ?: "حدث خطأ أثناء التحقق")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resendVerificationEmail(
        onResult: (isSuccess: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val res = repository.resendVerificationEmail()
                if (res.isSuccess) {
                    val msg = res.getOrNull() ?: "تمت إعادة إرسال رابط التأكيد بنجاح"
                    _eventFlow.emit(UiEvent.ShowToast(msg))
                    onResult(true, msg)
                } else {
                    val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر إعادة الإرسال حالياً"
                    onResult(false, err)
                }
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "حدث خطأ أثناء إعادة الإرسال")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun validateSession(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val isValid = repository.validateCurrentSession()
            onResult(isValid)
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        val app = getApplication<Application>()
        CaptainLocationTracker.stopService(app)
        viewModelScope.launch {
            _profileUploadState.value = ImageUploadState.Idle
            _idFrontUploadState.value = ImageUploadState.Idle
            _idBackUploadState.value = ImageUploadState.Idle
            _receiptUploadState.value = ImageUploadState.Idle
            _orderActionError.value = null
            repository.logout()
            onLoggedOut()
        }
    }

    fun acceptIncomingOrder(orderId: Long, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!_shiftStatus.value.isAvailableForOrders) {
            viewModelScope.launch {
                _eventFlow.emit(UiEvent.ShowToast("عفواً، أنت خارج ساعات العمل الرسمية ولا يمكنك قبول طلبات جديدة حالياً."))
            }
            onResult(false, "أنت خارج ساعات العمل الرسمية")
            return
        }

        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.acceptOrder(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.OrderAccepted)
                _eventFlow.emit(UiEvent.ShowToast("تم قبول الطلب بنجاح! اضغط 'تم الوصول للتاجر' عند الوصول 🚀"))
                onResult(true, "تم قبول الطلب بنجاح")
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.localizedMessage ?: "تعذر الاتصال بالخادم لقبول الطلب. يرجى إعادة المحاولة."
                _orderActionError.value = OrderActionError(
                    message = errorMsg,
                    actionLabel = "إعادة محاولة قبول الطلب",
                    isRetryable = cfEx?.isRetryable ?: true,
                    onRetry = { acceptIncomingOrder(orderId, onResult) }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
                onResult(false, errorMsg)
            }
        }
    }

    fun refreshShifts(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.syncShiftsFromFirestore()
            _isLoading.value = false
            if (res.isSuccess) {
                val count = res.getOrDefault(emptyList()).size
                val msg = if (count > 0) "تم تحديث جدول الشفتات ($count أيام مسجلة) بنجاح" else "تم التحديث: لا توجد شفتات مخصصة لك حالياً من الإدارة"
                _eventFlow.emit(UiEvent.ShowToast(msg))
                onResult(true, msg)
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر تحديث الشفتات من السيرفر"
                _eventFlow.emit(UiEvent.ShowToast(err))
                onResult(false, err)
            }
        }
    }

    fun removeStaleOrder(orderId: Long) {
        viewModelScope.launch {
            repository.removeStaleOrder(orderId)
            _orderActionError.value = null
            _eventFlow.emit(UiEvent.ShowToast("تم تحديث القائمة وإزالة الطلب غير المتاح"))
        }
    }

    fun markArrivedAtStore(orderId: Long) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.markAtStore(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم تأكيد الوصول إلى التاجر بنجاح! اضغط 'استلام الطلب' 📦"))
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتسجيل الوصول للتاجر."
                val isNotFound = cfEx?.isNotFound == true ||
                    cfEx?.httpCode in listOf(404, 410) ||
                    errorMsg.contains("الطلب غير موجود") ||
                    errorMsg.contains("لم يعد متاحاً")

                _orderActionError.value = OrderActionError(
                    message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                    actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                    isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                    isNotFound = isNotFound,
                    onRetry = {
                        if (isNotFound) {
                            removeStaleOrder(orderId)
                        } else {
                            markArrivedAtStore(orderId)
                        }
                    },
                    onDismissOrClear = {
                        removeStaleOrder(orderId)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
            }
        }
    }

    fun confirmPickup(orderId: Long) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.confirmPickup(orderId)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم استلام الطلب من التاجر! يرجى إدخال بيانات العميل وصورة الإيصال 📷"))
            } else {
                val err = res.exceptionOrNull()
                val cfEx = err as? com.example.data.remote.CloudFunctionException
                val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتأكيد استلام الطلب."
                val isNotFound = cfEx?.isNotFound == true ||
                    cfEx?.httpCode in listOf(404, 410) ||
                    errorMsg.contains("الطلب غير موجود") ||
                    errorMsg.contains("لم يعد متاحاً")

                _orderActionError.value = OrderActionError(
                    message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                    actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                    isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                    isNotFound = isNotFound,
                    onRetry = {
                        if (isNotFound) {
                            removeStaleOrder(orderId)
                        } else {
                            confirmPickup(orderId)
                        }
                    },
                    onDismissOrClear = {
                        removeStaleOrder(orderId)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(errorMsg))
            }
        }
    }

    fun saveOrderCustomerData(
        orderId: Long,
        customerName: String,
        customerPhone: String,
        notes: String,
        imageUri: Uri,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _orderActionError.value = null
            _isLoading.value = true
            val res = repository.saveOrderCustomerData(
                orderId = orderId,
                customerName = customerName,
                customerPhone = customerPhone,
                notes = notes,
                imageUri = imageUri,
                onUploadStateChange = { state -> _receiptUploadState.value = state }
            )
            _isLoading.value = false
            if (res.isSuccess) {
                _receiptUploadState.value = ImageUploadState.Idle
                _eventFlow.emit(UiEvent.ShowToast("تم رفع الإيصال وحفظ بيانات العميل وإرسالها للتاجر بنجاح ✓"))
                onSuccess()
            } else {
                val errObj = res.exceptionOrNull()
                val err = errObj?.localizedMessage ?: "تعذر حفظ بيانات العميل والإيصال على الخادم"
                _orderActionError.value = OrderActionError(
                    message = err,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = {
                        saveOrderCustomerData(orderId, customerName, customerPhone, notes, imageUri, onSuccess, onError)
                    }
                )
                _eventFlow.emit(UiEvent.ShowToast(err))
                onError(err)
            }
        }
    }

    fun resetReceiptUploadState() {
        _receiptUploadState.value = ImageUploadState.Idle
    }

    fun saveOrderDataAndImage(
        orderId: Long,
        imageUri: Uri,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        saveOrderCustomerData(orderId, "عميل البطل", "", "", imageUri, onSuccess, onError)
    }

    fun expireIncomingOrder(orderId: Long) {
        viewModelScope.launch {
            val res = repository.expireDeliveryOffer(orderId)
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("انتهت مهلة قبول الطلب وتم تحويله إلى كابتن آخر"))
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "تعذر إبلاغ الخادم بانتهاء مهلة العرض"
                _orderActionError.value = OrderActionError(
                    message = errMsg,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = { expireIncomingOrder(orderId) }
                )
                _eventFlow.emit(UiEvent.ShowToast(errMsg))
            }
        }
    }

    fun declineIncomingOrder(orderId: Long) {
        viewModelScope.launch {
            _orderActionError.value = null
            val res = repository.declineOrder(orderId)
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم رفض الطلب"))
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "تعذر الاتصال بالخادم لرفض الطلب"
                _orderActionError.value = OrderActionError(
                    message = errMsg,
                    actionLabel = "إعادة المحاولة",
                    isRetryable = true,
                    onRetry = { declineIncomingOrder(orderId) }
                )
                _eventFlow.emit(UiEvent.ShowToast(errMsg))
            }
        }
    }

    fun advanceActiveOrderStatus(order: OrderEntity) {
        viewModelScope.launch {
            when (order.status) {
                OrderStatus.ACCEPTED -> {
                    markArrivedAtStore(order.id)
                }
                OrderStatus.AT_STORE -> {
                    confirmPickup(order.id)
                }
                OrderStatus.EN_ROUTE -> {
                    _orderActionError.value = null
                    _isLoading.value = true
                    val res = repository.completeDelivery(order.id)
                    _isLoading.value = false
                    if (res.isSuccess) {
                        _eventFlow.emit(UiEvent.OrderCompleted)
                        _eventFlow.emit(UiEvent.ShowToast("تم تسليم الطلب بنجاح! تم إضافة ${res.getOrDefault(25.0).toInt()} ج.م لمحفظتك 🏁"))
                    } else {
                        val err = res.exceptionOrNull()
                        val cfEx = err as? com.example.data.remote.CloudFunctionException
                        val errorMsg = err?.message ?: "تعذر الاتصال بالخادم لتأكيد تسليم الطلب."
                        val isNotFound = cfEx?.isNotFound == true ||
                            cfEx?.httpCode in listOf(404, 410) ||
                            errorMsg.contains("الطلب غير موجود") ||
                            errorMsg.contains("لم يعد متاحاً")

                        _orderActionError.value = OrderActionError(
                            message = if (isNotFound) "الطلب غير موجود أو لم يعد متاحاً على الخادم." else errorMsg,
                            actionLabel = if (isNotFound) "تحديث الطلبات" else "إعادة المحاولة",
                            isRetryable = if (isNotFound) false else (cfEx?.isRetryable ?: true),
                            isNotFound = isNotFound,
                            onRetry = {
                                if (isNotFound) {
                                    removeStaleOrder(order.id)
                                } else {
                                    advanceActiveOrderStatus(order)
                                }
                            },
                            onDismissOrClear = {
                                removeStaleOrder(order.id)
                            }
                        )
                        _eventFlow.emit(UiEvent.ShowToast(errorMsg))
                    }
                }
                else -> {}
            }
        }
    }

    fun refreshOrders(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.syncOrdersFromFirestore()
            _isLoading.value = false
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                val msg = if (count > 0) "تم تحديث وجلب $count طلب جديد بنجاح" else "تم التحديث: لا توجد طلبات جديدة متاحة حالياً"
                _eventFlow.emit(UiEvent.ShowToast(msg))
                onResult(true, msg)
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "فشل تحديث الطلبات"
                _eventFlow.emit(UiEvent.ShowToast(err))
                onResult(false, err)
            }
        }
    }

    fun updateProfileInfo(fullName: String, phoneNumber: String, email: String, vehicleType: String, licensePlate: String, avatarUri: String = "") {
        viewModelScope.launch {
            _isLoading.value = true
            val res = repository.updateProfile(fullName, phoneNumber, email, vehicleType, licensePlate, avatarUri)
            _isLoading.value = false
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم تحديث بيانات الكابتن ورقم الهاتف بنجاح"))
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "فشل تحديث البيانات"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainProfilePhoto(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveProfilePhoto(uri) { state ->
                _profileUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم رفع وحفظ الصورة الشخصية في Firebase Storage بنجاح ✓"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الصورة الشخصية"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainIdFront(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveIdFront(uri) { state ->
                _idFrontUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم رفع وحفظ الوجه الأمامي للبطاقة الشخصية بنجاح ✓"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الوجه الأمامي للبطاقة"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun uploadCaptainIdBack(uri: Uri, onCompleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.uploadAndSaveIdBack(uri) { state ->
                _idBackUploadState.value = state
            }
            if (res.isSuccess) {
                _eventFlow.emit(UiEvent.ShowToast("تم رفع وحفظ الوجه الخلفي للبطاقة الشخصية بنجاح ✓"))
                onCompleted()
            } else {
                val err = res.exceptionOrNull()?.localizedMessage ?: "تعذر رفع الوجه الخلفي للبطاقة"
                _eventFlow.emit(UiEvent.ShowToast(err))
            }
        }
    }

    fun resetProfileUploadState() {
        _profileUploadState.value = ImageUploadState.Idle
    }

    fun resetIdFrontUploadState() {
        _idFrontUploadState.value = ImageUploadState.Idle
    }

    fun resetIdBackUploadState() {
        _idBackUploadState.value = ImageUploadState.Idle
    }

    fun setupRealtimeShiftListener() {
        val user = auth.currentUser
        if (user != null) {
            attachShiftSnapshotListeners(user.uid)
        }
        if (authStateListener == null) {
            authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                val uid = firebaseAuth.currentUser?.uid
                if (uid != null) {
                    attachShiftSnapshotListeners(uid)
                } else {
                    detachShiftSnapshotListeners()
                }
            }
            auth.addAuthStateListener(authStateListener!!)
        }
    }

    private fun attachShiftSnapshotListeners(uid: String) {
        detachShiftSnapshotListeners()
        Log.i("CaptainViewModel", "[ShiftSnapshotListener] Attaching live Firestore listener for UID: $uid (target: captain_schedules/$uid)")

        // 1. Direct document listener on captain_schedules/{uid}
        shiftDocListener = firestore.collection("captain_schedules").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("CaptainViewModel", "[ShiftSnapshotListener] captain_schedules/$uid error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val extracted = snapshot.extractAllShifts(uid)
                    if (extracted.isNotEmpty()) {
                        Log.i("CaptainViewModel", "[ShiftSnapshotListener] Live update: ${extracted.size} shifts from captain_schedules/$uid")
                        _liveShifts.value = extracted
                        _shiftStatus.value = ShiftManager.calculateShiftStatus(extracted)
                        viewModelScope.launch {
                            repository.saveShiftsLocally(uid, extracted)
                        }
                    }
                }
            }

        // 2. Query listener on captain_schedules where captainId == uid
        shiftQueryListener = firestore.collection("captain_schedules")
            .whereEqualTo("captainId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null && !snapshot.isEmpty) {
                    val allShifts = mutableListOf<ShiftEntity>()
                    for (doc in snapshot.documents) {
                        allShifts.addAll(doc.extractAllShifts(uid))
                    }
                    if (allShifts.isNotEmpty()) {
                        Log.i("CaptainViewModel", "[ShiftSnapshotListener] Live update: ${allShifts.size} shifts from query")
                        _liveShifts.value = allShifts
                        _shiftStatus.value = ShiftManager.calculateShiftStatus(allShifts)
                        viewModelScope.launch {
                            repository.saveShiftsLocally(uid, allShifts)
                        }
                    }
                }
            }

        // 3. Document listener on captains/{uid} (profile doc fallback)
        captainDocListener = firestore.collection("captains").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val embedded = snapshot.extractAllShifts(uid)
                    if (embedded.isNotEmpty()) {
                        Log.i("CaptainViewModel", "[ShiftSnapshotListener] Live update: ${embedded.size} shifts from captains/$uid")
                        _liveShifts.value = embedded
                        _shiftStatus.value = ShiftManager.calculateShiftStatus(embedded)
                        viewModelScope.launch {
                            repository.saveShiftsLocally(uid, embedded)
                        }
                    }
                }
            }
    }

    fun detachShiftSnapshotListeners() {
        shiftDocListener?.remove()
        shiftDocListener = null
        shiftQueryListener?.remove()
        shiftQueryListener = null
        captainDocListener?.remove()
        captainDocListener = null
    }

    override fun onCleared() {
        super.onCleared()
        detachShiftSnapshotListeners()
        authStateListener?.let { auth.removeAuthStateListener(it) }
    }
}
