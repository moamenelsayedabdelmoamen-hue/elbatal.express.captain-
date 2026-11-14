package com.example.ui.navigation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.OrderStatus
import com.example.ui.CaptainViewModel
import com.example.ui.UiEvent
import com.example.ui.components.LocalStorageRepository
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.EmailVerificationScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.OrdersScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ShiftsScreen
import com.example.ui.screens.SignUpScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ExpressRed

sealed class ScreenRoute(val route: String) {
    object Splash : ScreenRoute("splash")
    object Login : ScreenRoute("login")
    object SignUp : ScreenRoute("signup")
    object VerifyEmail : ScreenRoute("verify_email")
    object Main : ScreenRoute("main")
}

data class BottomNavItem(
    val title: String,
    @DrawableRes val iconRes: Int,
    val index: Int,
    val testTag: String
)

val bottomNavItems = listOf(
    BottomNavItem("الرئيسية", R.drawable.ic_nav_home, 0, "bottom_nav_home"),
    BottomNavItem("سجل الطلبات", R.drawable.ic_nav_clipboard_list, 1, "bottom_nav_orders_history"),
    BottomNavItem("الشفتات", R.drawable.ic_nav_calendar_clock, 2, "bottom_nav_shifts"),
    BottomNavItem("المحفظة", R.drawable.ic_nav_wallet, 3, "bottom_nav_wallet"),
    BottomNavItem("حسابي", R.drawable.ic_nav_user_round, 4, "bottom_nav_profile")
)

@Composable
fun CaptainBottomNavigationBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.testTag("captain_bottom_navigation_bar"),
        containerColor = DarkSurface,
        contentColor = Color.White
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = selectedTabIndex == item.index
            val activeTint = if (isSelected) ExpressRed else Color.Gray
            NavigationBarItem(
                modifier = Modifier.testTag(item.testTag),
                selected = isSelected,
                onClick = { onTabSelected(item.index) },
                icon = {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = item.title,
                        tint = activeTint,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = item.title,
                        color = activeTint,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ExpressRed,
                    selectedTextColor = ExpressRed,
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = ExpressRed.copy(alpha = 0.14f)
                )
            )
        }
    }
}

@Composable
fun AppNavigation(viewModel: CaptainViewModel) {
    val context = LocalContext.current
    val captain by viewModel.captainState.collectAsStateWithLifecycle()
    val activeOrder by viewModel.activeOrder.collectAsStateWithLifecycle()
    val availableOrders by viewModel.availableOrders.collectAsStateWithLifecycle()
    val pendingOrder by viewModel.pendingIncomingOrder.collectAsStateWithLifecycle()
    val allOrders by viewModel.allOrders.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val totalEarnings by viewModel.totalEarnings.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()

    val shifts by viewModel.shifts.collectAsStateWithLifecycle()
    val shiftStatus by viewModel.shiftStatus.collectAsStateWithLifecycle()
    val currentCairoTime by viewModel.currentCairoTime.collectAsStateWithLifecycle()
    val currentCairoDate by viewModel.currentCairoDate.collectAsStateWithLifecycle()
    val dailyWorkHours by viewModel.dailyWorkHours.collectAsStateWithLifecycle()
    val weeklyWorkHours by viewModel.weeklyWorkHours.collectAsStateWithLifecycle()
    val isShiftNetworkConnected by viewModel.isShiftNetworkConnected.collectAsStateWithLifecycle()

    val currentOperationalDayKey by viewModel.currentOperationalDayKey.collectAsStateWithLifecycle()
    val todayCalendarDateKey by viewModel.todayCalendarDateKey.collectAsStateWithLifecycle()
    val selectedHistoryDateKey by viewModel.selectedHistoryDateKey.collectAsStateWithLifecycle()
    val selectedHistoryTab by viewModel.selectedHistoryTab.collectAsStateWithLifecycle()
    val archivedOrdersForSelectedDate by viewModel.archivedOrdersForSelectedDate.collectAsStateWithLifecycle()
    val captainHistoryOrders by viewModel.captainHistoryOrders.collectAsStateWithLifecycle()
    val currentDayWalletBalance by viewModel.currentDayWalletBalance.collectAsStateWithLifecycle()
    val currentDayCompletedCount by viewModel.currentDayCompletedCount.collectAsStateWithLifecycle()
    val previousDayWalletBalance by viewModel.previousDayWalletBalance.collectAsStateWithLifecycle()
    val dailyWalletRecords by viewModel.dailyWalletRecords.collectAsStateWithLifecycle()
    val isSyncingOrdersHistory by viewModel.isSyncingOrdersHistory.collectAsStateWithLifecycle()
    val historySyncError by viewModel.historySyncError.collectAsStateWithLifecycle()

    val profileUploadState by viewModel.profileUploadState.collectAsStateWithLifecycle()
    val idFrontUploadState by viewModel.idFrontUploadState.collectAsStateWithLifecycle()
    val idBackUploadState by viewModel.idBackUploadState.collectAsStateWithLifecycle()
    val receiptUploadState by viewModel.receiptUploadState.collectAsStateWithLifecycle()
    val orderActionError by viewModel.orderActionError.collectAsStateWithLifecycle()

    var currentRoute by remember { mutableStateOf<String>(ScreenRoute.Splash.route) }
    var selectedBottomTab by remember { mutableIntStateOf(0) }
    var unverifiedEmail by remember { mutableStateOf("") }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.onLocationPermissionGranted()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    val requestLocationPermissionsAction = {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        locationPermissionLauncher.launch(perms)
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                UiEvent.OrderAccepted -> {
                    selectedBottomTab = 0
                }
                UiEvent.OrderCompleted -> {
                    selectedBottomTab = 0
                }
                UiEvent.RequestLocationPermission -> {
                    requestLocationPermissionsAction()
                }
                UiEvent.RequestGpsEnable -> {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    context.startActivity(intent)
                }
            }
        }
    }

    CompositionLocalProvider(LocalStorageRepository provides viewModel.repository.storageRepository) {
    when (currentRoute) {
        ScreenRoute.Splash.route -> {
            SplashScreen(
                isLoggedIn = captain?.isLoggedIn == true,
                onNavigateNext = {
                    currentRoute = if (captain?.isLoggedIn == true) ScreenRoute.Main.route else ScreenRoute.Login.route
                }
            )
        }
        ScreenRoute.Login.route -> {
            LoginScreen(
                isLoading = isLoading,
                onLoginClick = { phoneOrEmail, pass, onNeedsEmailVerification, onError ->
                    viewModel.login(
                        phoneOrEmail = phoneOrEmail,
                        pass = pass,
                        onSuccess = { currentRoute = ScreenRoute.Main.route },
                        onNeedsEmailVerification = { email ->
                            unverifiedEmail = email
                            onNeedsEmailVerification(email)
                        },
                        onError = onError
                    )
                },
                onNavigateSignUp = { currentRoute = ScreenRoute.SignUp.route },
                onNavigateVerifyEmail = { email ->
                    unverifiedEmail = email
                    currentRoute = ScreenRoute.VerifyEmail.route
                },
                onForgotPasswordClick = { phoneOrEmail, onSuccess, onError ->
                    viewModel.sendPasswordReset(phoneOrEmail, onSuccess, onError)
                }
            )
        }
        ScreenRoute.SignUp.route -> {
            BackHandler {
                currentRoute = ScreenRoute.Login.route
            }
            SignUpScreen(
                isLoading = isLoading,
                onSignUpClick = { fullName, phone, email, pass, confirmPass, avatarUri, avatarIndex, vehicleType, nationalIdUri, idBackUri, licenseUri, onError ->
                    viewModel.signUp(
                        fullName = fullName,
                        phone = phone,
                        email = email,
                        pass = pass,
                        confirmPass = confirmPass,
                        avatarUri = avatarUri,
                        avatarPresetIndex = avatarIndex,
                        vehicleType = vehicleType,
                        nationalIdUri = nationalIdUri,
                        idBackUri = idBackUri,
                        licenseUri = licenseUri,
                        onEmailSent = { sentEmail ->
                            unverifiedEmail = sentEmail
                            currentRoute = ScreenRoute.VerifyEmail.route
                        },
                        onError = onError
                    )
                },
                onNavigateLogin = { currentRoute = ScreenRoute.Login.route }
            )
        }
        ScreenRoute.VerifyEmail.route -> {
            BackHandler {
                currentRoute = ScreenRoute.Login.route
            }
            EmailVerificationScreen(
                email = unverifiedEmail.ifBlank { captain?.email ?: "" },
                onCheckVerification = { onVerified, onNotVerified ->
                    viewModel.checkEmailVerification(
                        onVerified = onVerified,
                        onNotVerified = onNotVerified
                    )
                },
                onResendEmail = { onResult ->
                    viewModel.resendVerificationEmail(onResult)
                },
                onNavigateLogin = {
                    currentRoute = ScreenRoute.Login.route
                },
                onVerifiedSuccess = {
                    currentRoute = ScreenRoute.Main.route
                }
            )
        }
        ScreenRoute.Main.route -> {
            BackHandler(enabled = selectedBottomTab != 0) {
                selectedBottomTab = 0
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    CaptainBottomNavigationBar(
                        selectedTabIndex = selectedBottomTab,
                        onTabSelected = { targetIndex ->
                            if (targetIndex == 1 && selectedBottomTab != 1) {
                                viewModel.openOrdersHistoryToday()
                            }
                            selectedBottomTab = targetIndex
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (selectedBottomTab) {
                        0 -> {
                            DashboardScreen(
                                captain = captain,
                                activeOrder = activeOrder,
                                availableOrders = availableOrders,
                                pendingIncomingOrder = pendingOrder,
                                completedCount = currentDayCompletedCount,
                                totalEarnings = currentDayWalletBalance,
                                isLoading = isLoading,
                                gpsStatus = gpsStatus,
                                currentLocation = currentLocation,
                                onRequestLocationPermission = requestLocationPermissionsAction,
                                onToggleOnline = { viewModel.toggleOnlineStatus() },
                                onAcceptOrder = { viewModel.acceptIncomingOrder(it) },
                                onTimeoutOrder = { viewModel.expireIncomingOrder(it) },
                                onMarkArrivedAtMerchant = { viewModel.markArrivedAtStore(it) },
                                onConfirmPickup = { viewModel.confirmPickup(it) },
                                onSaveOrderCustomerData = { orderId, name, phone, notes, uri, onSuccess, onError ->
                                    viewModel.saveOrderCustomerData(orderId, name, phone, notes, uri, onSuccess, onError)
                                },
                                receiptUploadState = receiptUploadState,
                                onResetReceiptUploadState = { viewModel.resetReceiptUploadState() },
                                orderActionError = orderActionError,
                                onDismissOrderActionError = { viewModel.dismissOrderActionError() },
                                onAdvanceOrderStatus = { viewModel.advanceActiveOrderStatus(it) },
                                onSimulateMerchantConfirm = null,
                                onRefreshOrders = { viewModel.refreshOrders() },
                                shiftStatus = shiftStatus,
                                shifts = shifts,
                                currentCairoTime = currentCairoTime,
                                currentCairoDate = currentCairoDate,
                                dailyWorkHours = dailyWorkHours,
                                weeklyWorkHours = weeklyWorkHours,
                                isShiftNetworkConnected = isShiftNetworkConnected,
                                onRefreshShifts = { viewModel.refreshShifts() },
                                onNavigateToShifts = { selectedBottomTab = 2 }
                            )
                        }
                        1 -> {
                            OrdersScreen(
                                orders = archivedOrdersForSelectedDate,
                                captainUid = captain?.uid.orEmpty(),
                                captainPhone = captain?.phoneNumber.orEmpty(),
                                selectedDateKey = selectedHistoryDateKey,
                                todayDateKey = todayCalendarDateKey,
                                selectedTab = selectedHistoryTab,
                                isSyncing = isSyncingOrdersHistory,
                                syncError = historySyncError,
                                onSelectDateKey = { viewModel.selectHistoryDate(it) },
                                onReturnToToday = { viewModel.resetHistoryDateToToday() },
                                onSelectTab = { viewModel.selectHistoryTab(it) },
                                onSyncHistory = { viewModel.syncOrdersHistory(silentSuccess = false) }
                            )
                        }
                        2 -> {
                            ShiftsScreen(
                                shifts = shifts,
                                shiftStatus = shiftStatus,
                                currentCairoTime = currentCairoTime,
                                currentCairoDate = currentCairoDate,
                                dailyWorkHours = dailyWorkHours,
                                weeklyWorkHours = weeklyWorkHours,
                                isShiftNetworkConnected = isShiftNetworkConnected,
                                isLoading = isLoading,
                                onRefreshShifts = { viewModel.refreshShifts() }
                            )
                        }
                        3 -> {
                            WalletScreen(
                                totalEarnings = totalEarnings,
                                completedCount = completedCount,
                                completedOrders = captainHistoryOrders.filter { it.status == OrderStatus.DELIVERED },
                                currentDayWalletBalance = currentDayWalletBalance,
                                currentDayCompletedCount = currentDayCompletedCount,
                                previousDayWalletBalance = previousDayWalletBalance,
                                currentOperationalDayKey = currentOperationalDayKey,
                                dailyWalletRecords = dailyWalletRecords,
                                onOpenDayInOrders = { dayKey ->
                                    viewModel.selectHistoryDay(dayKey)
                                    selectedBottomTab = 1
                                }
                            )
                        }
                        4 -> {
                            ProfileScreen(
                                captain = captain,
                                profileUploadState = profileUploadState,
                                idFrontUploadState = idFrontUploadState,
                                idBackUploadState = idBackUploadState,
                                onUploadProfilePhoto = { uri, onDone ->
                                    viewModel.uploadCaptainProfilePhoto(uri, onDone)
                                },
                                onUploadIdFront = { uri, onDone ->
                                    viewModel.uploadCaptainIdFront(uri, onDone)
                                },
                                onUploadIdBack = { uri, onDone ->
                                    viewModel.uploadCaptainIdBack(uri, onDone)
                                },
                                onResetProfileUploadState = { viewModel.resetProfileUploadState() },
                                onResetIdFrontUploadState = { viewModel.resetIdFrontUploadState() },
                                onResetIdBackUploadState = { viewModel.resetIdBackUploadState() },
                                onSaveProfile = { name, phone, email, vehicle, plate, avatarUri ->
                                    viewModel.updateProfileInfo(name, phone, email, vehicle, plate, avatarUri)
                                },
                                onLogout = {
                                    viewModel.logout {
                                        currentRoute = ScreenRoute.Login.route
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
