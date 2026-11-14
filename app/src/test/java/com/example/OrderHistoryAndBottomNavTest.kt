package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import com.example.data.OrderHistoryHelper
import com.example.data.OrderHistoryTab
import com.example.data.OrderStatus
import com.example.data.ShiftStatus
import com.example.data.location.CaptainLocationService
import com.example.data.location.GpsConfig
import com.example.ui.navigation.CaptainBottomNavigationBar
import com.example.ui.navigation.bottomNavItems
import com.example.ui.screens.OrdersScreen
import com.example.ui.screens.ShiftsScreen
import com.example.ui.theme.ElBatalTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1200dp")
class OrderHistoryAndBottomNavTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDatabase
    private val loggedInCaptainUid = "captain_hero_101"
    private val otherCaptainUid = "captain_other_999"

    private val fixedTodayKey = "2026-11-10"
    private val fixedYesterdayKey = "2026-11-09"
    private val fixedEmptyPastKey = "2026-11-05"
    private val fixedFutureKey = "2026-11-11"

    private fun timestampInDate(dateKey: String, hour24: Int, minute: Int): Long {
        val (startMs, _) = OrderHistoryHelper.getCalendarDayBoundsMs(dateKey)
        return startMs + (hour24 * 3600_000L) + (minute * 60_000L)
    }

    private fun buildSampleOrder(
        id: Long,
        orderNumber: String,
        storeName: String,
        status: OrderStatus,
        captainId: String = loggedInCaptainUid,
        deliveryFee: Double = 35.0,
        completedAt: Long = 0L,
        cancelledAt: Long = 0L,
        cancellationReason: String = "",
        createdAt: Long = timestampInDate(fixedTodayKey, 10, 0),
        firestoreDocId: String = "doc_$id"
    ): OrderEntity {
        return OrderEntity(
            id = id,
            orderNumber = orderNumber,
            customerName = "أحمد محمود",
            customerPhone = "01012345678",
            storeName = storeName,
            storePhone = "01200001111",
            pickupAddress = "شارع التحرير، الدقي",
            deliveryAddress = "شارع جامعة الدول، المهندسين",
            itemsSummary = "وجبة شاورما عربي كبير",
            itemsCount = 2,
            orderTotalAmount = 220.0,
            deliveryFee = deliveryFee,
            distanceKm = 3.5,
            status = status,
            createdAt = createdAt,
            firestoreDocId = firestoreDocId,
            captainId = captainId,
            completedAt = completedAt,
            cancelledAt = cancelledAt,
            cancellationReason = cancellationReason
        )
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. فتح سجل الطلبات وظهور طلبات اليوم فقط تلقائيًا + الترتيب التنازلي الأحدث أولاً
    @Test
    fun scenario1_openingOrderHistoryShowsOnlyTodayFinishedOrdersAutomaticallySortedNewestFirst() {
        val todayOlderCompleted = buildSampleOrder(
            id = 101L,
            orderNumber = "ORD-101",
            storeName = "مطعم كرم الشام",
            status = OrderStatus.DELIVERED,
            deliveryFee = 40.0,
            completedAt = timestampInDate(fixedTodayKey, 11, 15)
        )
        val todayNewerCancelled = buildSampleOrder(
            id = 102L,
            orderNumber = "ORD-102",
            storeName = "بازوكا فرايد تشيكن",
            status = OrderStatus.CANCELLED,
            cancelledAt = timestampInDate(fixedTodayKey, 16, 45),
            cancellationReason = "العميل ألغى الطلب قبل التحرك"
        )
        val yesterdayCompleted = buildSampleOrder(
            id = 103L,
            orderNumber = "ORD-YEST-103",
            storeName = "جاد للمأكولات",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedYesterdayKey, 18, 30)
        )

        val allOrders = listOf(todayOlderCompleted, todayNewerCancelled, yesterdayCompleted)

        // Verify descending order helper: 16:45 (102) comes before 11:15 (101), and 103 (yesterday) is excluded
        val filtered = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = allOrders,
            captainUid = loggedInCaptainUid,
            selectedDateKey = fixedTodayKey
        )
        assertEquals(listOf(102L, 101L), filtered.map { it.id })

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = allOrders,
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedTodayKey,
                    todayDateKey = fixedTodayKey
                )
            }
        }

        // Today's finished orders appear
        composeTestRule.onNodeWithText("بازوكا فرايد تشيكن").assertIsDisplayed()
        composeTestRule.onNodeWithText("مطعم كرم الشام").assertIsDisplayed()
        composeTestRule.onNodeWithText("أجر الكابتن: 40 ج.م").assertIsDisplayed()
        composeTestRule.onNodeWithText("سبب الإلغاء: العميل ألغى الطلب قبل التحرك").assertIsDisplayed()

        // Yesterday's order does NOT appear by default
        composeTestRule.onNodeWithText("جاد للمأكولات").assertDoesNotExist()

        // Summary stats for today: total 2, completed 1, cancelled 1
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("2")
        composeTestRule.onNodeWithTag("stat_completed_count_value").assertTextEquals("1")
        composeTestRule.onNodeWithTag("stat_cancelled_count_value").assertTextEquals("1")
    }

    // 2. اختيار تاريخ سابق وظهور طلبات هذا التاريخ فقط + منع اختيار تاريخ مستقبلي + العودة لليوم بضغطة واحدة
    @Test
    fun scenario2_selectingPreviousDateShowsOnlyThatDateOrdersAndBlocksFutureDatesAndReturnsToToday() {
        val todayOrder = buildSampleOrder(
            id = 201L,
            orderNumber = "TODAY-201",
            storeName = "أبو شقرة اليوم",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedTodayKey, 14, 0)
        )
        val yesterdayOrder = buildSampleOrder(
            id = 202L,
            orderNumber = "YEST-202",
            storeName = "بيتزا هت أمس",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedYesterdayKey, 19, 20)
        )
        val allOrders = listOf(todayOrder, yesterdayOrder)

        // Verify future date is strictly blocked
        assertTrue(OrderHistoryHelper.isFutureDateKey(fixedFutureKey, fixedTodayKey))
        assertEquals(fixedTodayKey, OrderHistoryHelper.clampToMaxToday(fixedFutureKey, fixedTodayKey))

        var selectedDateState by mutableStateOf(fixedTodayKey)

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = allOrders,
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = selectedDateState,
                    todayDateKey = fixedTodayKey,
                    onSelectDateKey = { picked ->
                        if (!OrderHistoryHelper.isFutureDateKey(picked, fixedTodayKey)) {
                            selectedDateState = picked
                        }
                    },
                    onReturnToToday = { selectedDateState = fixedTodayKey }
                )
            }
        }

        // Initially shows Today
        composeTestRule.onNodeWithText("أبو شقرة اليوم").assertIsDisplayed()
        composeTestRule.onNodeWithText("بيتزا هت أمس").assertDoesNotExist()

        // Calendar button opens the DatePickerDialog
        composeTestRule.onNodeWithTag("open_calendar_picker_button").performClick()
        composeTestRule.onNodeWithTag("history_date_picker_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("إلغاء").performClick()

        // Switch to previous day (yesterday)
        selectedDateState = fixedYesterdayKey
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("بيتزا هت أمس").assertIsDisplayed()
        composeTestRule.onNodeWithText("أبو شقرة اليوم").assertDoesNotExist()

        // Attempt to set a future date via clamp -> blocked!
        if (!OrderHistoryHelper.isFutureDateKey(fixedFutureKey, fixedTodayKey)) {
            selectedDateState = fixedFutureKey
        }
        assertEquals(fixedYesterdayKey, selectedDateState)

        // One-tap Return to Today button restores today's orders immediately
        composeTestRule.onNodeWithTag("return_to_today_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(fixedTodayKey, selectedDateState)
        composeTestRule.onNodeWithText("أبو شقرة اليوم").assertIsDisplayed()
    }

    // 3. اختيار تاريخ لا يحتوي على طلبات وظهور رسالة فارغة واضحة
    @Test
    fun scenario3_selectingDateWithZeroOrdersDisplaysExactEmptyMessage() {
        val todayOrder = buildSampleOrder(
            id = 301L,
            orderNumber = "ORD-301",
            storeName = "مطعم الدهان",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedTodayKey, 15, 0)
        )

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = listOf(todayOrder),
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedEmptyPastKey,
                    todayDateKey = fixedTodayKey
                )
            }
        }

        composeTestRule.onNodeWithTag("orders_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("لا توجد طلبات منتهية في هذا اليوم").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("0")
    }

    // 4. التنقل بين تبويبات: الكل – مكتملة – ملغاة وتغير الأرقام الإحصائية
    @Test
    fun scenario4_switchingBetweenAllCompletedAndCancelledTabsUpdatesListAndSummaryStats() {
        val completed1 = buildSampleOrder(
            id = 401L,
            orderNumber = "COMP-401",
            storeName = "برجر كنج",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedTodayKey, 12, 0)
        )
        val completed2 = buildSampleOrder(
            id = 402L,
            orderNumber = "COMP-402",
            storeName = "ماكدونالدز",
            status = OrderStatus.DELIVERED,
            completedAt = timestampInDate(fixedTodayKey, 14, 0)
        )
        val cancelled1 = buildSampleOrder(
            id = 403L,
            orderNumber = "CANC-403",
            storeName = "كنتاكي",
            status = OrderStatus.CANCELLED,
            cancelledAt = timestampInDate(fixedTodayKey, 15, 30),
            cancellationReason = "المتجر مغلق"
        )

        var currentTab by mutableStateOf(OrderHistoryTab.ALL)

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = listOf(completed1, completed2, cancelled1),
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedTodayKey,
                    todayDateKey = fixedTodayKey,
                    selectedTab = currentTab,
                    onSelectTab = { currentTab = it }
                )
            }
        }

        // Tab: الكل (3 total: 2 completed, 1 cancelled)
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("3")
        composeTestRule.onNodeWithTag("stat_completed_count_value").assertTextEquals("2")
        composeTestRule.onNodeWithTag("stat_cancelled_count_value").assertTextEquals("1")
        composeTestRule.onNodeWithText("برجر كنج").assertIsDisplayed()
        composeTestRule.onNodeWithText("كنتاكي").assertIsDisplayed()

        // Click tab: مكتملة
        composeTestRule.onNodeWithTag("history_tab_completed").performClick()
        composeTestRule.waitForIdle()
        assertEquals(OrderHistoryTab.COMPLETED, currentTab)
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("2")
        composeTestRule.onNodeWithTag("stat_completed_count_value").assertTextEquals("2")
        composeTestRule.onNodeWithTag("stat_cancelled_count_value").assertTextEquals("0")
        composeTestRule.onNodeWithText("ماكدونالدز").assertIsDisplayed()
        composeTestRule.onNodeWithText("كنتاكي").assertDoesNotExist()

        // Click tab: ملغاة
        composeTestRule.onNodeWithTag("history_tab_cancelled").performClick()
        composeTestRule.waitForIdle()
        assertEquals(OrderHistoryTab.CANCELLED, currentTab)
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("1")
        composeTestRule.onNodeWithTag("stat_completed_count_value").assertTextEquals("0")
        composeTestRule.onNodeWithTag("stat_cancelled_count_value").assertTextEquals("1")
        composeTestRule.onNodeWithText("كنتاكي").assertIsDisplayed()
        composeTestRule.onNodeWithText("برجر كنج").assertDoesNotExist()
    }

    // 5. عدم ظهور أي طلب جارٍ أو قيد التنفيذ داخل سجل الطلبات
    @Test
    fun scenario5_noActiveOrPendingOrdersEverAppearInOrderHistory() {
        val nonArchivedStatuses = listOf(
            OrderStatus.PENDING,
            OrderStatus.ACCEPTED,
            OrderStatus.AT_STORE,
            OrderStatus.PICKED_UP,
            OrderStatus.ORDER_DATA_ENTERED,
            OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
            OrderStatus.MERCHANT_REJECTED,
            OrderStatus.EN_ROUTE,
            OrderStatus.EXPIRED
        )

        val activeOrders = nonArchivedStatuses.mapIndexed { idx, status ->
            buildSampleOrder(
                id = 500L + idx,
                orderNumber = "ACT-$idx",
                storeName = "متجر نشط $status",
                status = status,
                createdAt = timestampInDate(fixedTodayKey, 13, idx)
            )
        }

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = activeOrders,
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedTodayKey,
                    todayDateKey = fixedTodayKey
                )
            }
        }

        // Zero active/pending orders appear -> empty state shown
        composeTestRule.onNodeWithText("لا توجد طلبات منتهية في هذا اليوم").assertIsDisplayed()
        nonArchivedStatuses.forEach { st ->
            composeTestRule.onNodeWithText("متجر نشط $st").assertDoesNotExist()
        }
    }

    // 6. عدم ظهور الطلب المكتمل في السجل إلا بعد اكتماله فعليًا
    @Test
    fun scenario6_completedOrderDoesNotAppearUntilStatusBecomesDelivered() = runBlocking {
        val orderDao = db.orderDao()
        val inProgressOrder = buildSampleOrder(
            id = 601L,
            orderNumber = "LIVE-601",
            storeName = "مطعم الشيف سرحان",
            status = OrderStatus.EN_ROUTE,
            createdAt = timestampInDate(fixedTodayKey, 12, 0),
            firestoreDocId = "doc_live_601"
        )
        orderDao.upsertByFirestoreId(inProgressOrder)

        val beforeCompletion = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orderDao.getAllOrdersFlow().first(),
            captainUid = loggedInCaptainUid,
            selectedDateKey = fixedTodayKey
        )
        assertTrue("EN_ROUTE order must NOT appear in history before completion", beforeCompletion.isEmpty())

        // Now complete delivery -> status becomes DELIVERED with completedAt timestamp
        val deliveredTime = timestampInDate(fixedTodayKey, 12, 40)
        orderDao.markOrderDelivered(601L, loggedInCaptainUid, deliveredTime)

        val afterCompletion = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orderDao.getAllOrdersFlow().first(),
            captainUid = loggedInCaptainUid,
            selectedDateKey = fixedTodayKey
        )
        assertEquals(1, afterCompletion.size)
        assertEquals(OrderStatus.DELIVERED, afterCompletion.first().status)
        assertEquals(deliveredTime, afterCompletion.first().completedAt)
    }

    // 7. عدم ظهور الطلب الملغي في السجل إلا بعد إلغائه فعليًا + فتح تفاصيل الطلب كاملة
    @Test
    fun scenario7_cancelledOrderDoesNotAppearUntilStatusBecomesCancelledAndDetailsSheetWorks() = runBlocking {
        val orderDao = db.orderDao()
        val acceptedOrder = buildSampleOrder(
            id = 701L,
            orderNumber = "CANC-701",
            storeName = "حلواني العبد",
            status = OrderStatus.ACCEPTED,
            createdAt = timestampInDate(fixedTodayKey, 17, 0),
            firestoreDocId = "doc_canc_701"
        )
        orderDao.upsertByFirestoreId(acceptedOrder)

        val beforeCancel = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orderDao.getAllOrdersFlow().first(),
            captainUid = loggedInCaptainUid,
            selectedDateKey = fixedTodayKey
        )
        assertTrue("ACCEPTED order must NOT appear in history before cancellation", beforeCancel.isEmpty())

        // Mark cancelled
        val cancelTime = timestampInDate(fixedTodayKey, 17, 25)
        orderDao.markOrderCancelled(701L, cancelTime, "تأخر تجهيز الطلب")

        val afterCancel = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = orderDao.getAllOrdersFlow().first(),
            captainUid = loggedInCaptainUid,
            selectedDateKey = fixedTodayKey
        )
        assertEquals(1, afterCancel.size)
        assertEquals(OrderStatus.CANCELLED, afterCancel.first().status)

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = afterCancel,
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedTodayKey,
                    todayDateKey = fixedTodayKey
                )
            }
        }

        composeTestRule.onNodeWithText("حلواني العبد").assertIsDisplayed()
        composeTestRule.onNodeWithText("سبب الإلغاء: تأخر تجهيز الطلب").assertIsDisplayed()

        // Open full details sheet
        composeTestRule.onNodeWithTag("view_order_details_button_701").performClick()
        composeTestRule.onNodeWithTag("archived_order_details_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText("تفاصيل الطلب #CANC-701").assertIsDisplayed()
        Unit
    }

    // 8. ظهور طلبات الكابتن المسجل فقط وعدم تسريب طلبات أي كابتن آخر أو تكرار السجلات
    @Test
    fun scenario8_strictlyShowsOnlyLoggedInCaptainOrdersAndPreventsDuplicates(): Unit = runBlocking {
        val orderDao = db.orderDao()
        val myFinishedOrder = buildSampleOrder(
            id = 801L,
            orderNumber = "MY-801",
            storeName = "مشويات الحاتي الخاصة بي",
            status = OrderStatus.DELIVERED,
            captainId = loggedInCaptainUid,
            completedAt = timestampInDate(fixedTodayKey, 13, 10),
            firestoreDocId = "shared_doc_801"
        )
        // Re-upserting same firestoreDocId with a different generated id must NOT duplicate rows
        val duplicateSyncOfMyOrder = myFinishedOrder.copy(id = 9999L, deliveryFee = 45.0)

        val foreignCaptainFinishedOrder = buildSampleOrder(
            id = 802L,
            orderNumber = "OTHER-802",
            storeName = "مطعم كابتن آخر سري",
            status = OrderStatus.DELIVERED,
            captainId = otherCaptainUid,
            completedAt = timestampInDate(fixedTodayKey, 14, 10),
            firestoreDocId = "foreign_doc_802"
        )
        val unassignedCancelledOrder = buildSampleOrder(
            id = 803L,
            orderNumber = "UNASSIGNED-803",
            storeName = "طلب بدون كابتن",
            status = OrderStatus.CANCELLED,
            captainId = "",
            cancelledAt = timestampInDate(fixedTodayKey, 15, 0),
            firestoreDocId = "unassigned_doc_803"
        )

        orderDao.upsertByFirestoreId(myFinishedOrder)
        orderDao.upsertByFirestoreId(duplicateSyncOfMyOrder)
        orderDao.upsertByFirestoreId(foreignCaptainFinishedOrder)
        orderDao.upsertByFirestoreId(unassignedCancelledOrder)

        val allDbRows = orderDao.getAllOrdersFlow().first()
        assertEquals("upsertByFirestoreId must prevent duplicate records for shared_doc_801", 3, allDbRows.size)

        composeTestRule.setContent {
            ElBatalTheme {
                OrdersScreen(
                    orders = allDbRows,
                    captainUid = loggedInCaptainUid,
                    selectedDateKey = fixedTodayKey,
                    todayDateKey = fixedTodayKey
                )
            }
        }

        composeTestRule.onNodeWithText("مشويات الحاتي الخاصة بي").assertIsDisplayed()
        composeTestRule.onNodeWithText("أجر الكابتن: 45 ج.م").assertIsDisplayed()
        composeTestRule.onNodeWithText("مطعم كابتن آخر سري").assertDoesNotExist()
        composeTestRule.onNodeWithText("طلب بدون كابتن").assertDoesNotExist()
        composeTestRule.onNodeWithTag("stat_total_finished_value").assertTextEquals("1")
        Unit
    }

    // 9. عمل القائمة السفلية والتنقل بين الأقسام الخمسة بالترتيب المحدد تمامًا
    @Test
    fun scenario9_bottomNavigationBarDisplaysExactFiveSectionsInOrderAndSwitchesTabs() {
        assertEquals(5, bottomNavItems.size)
        assertEquals(
            listOf("الرئيسية", "سجل الطلبات", "الشفتات", "المحفظة", "حسابي"),
            bottomNavItems.map { it.title }
        )
        assertEquals(R.drawable.ic_nav_home, bottomNavItems[0].iconRes)
        assertEquals(R.drawable.ic_nav_clipboard_list, bottomNavItems[1].iconRes)
        assertEquals(R.drawable.ic_nav_calendar_clock, bottomNavItems[2].iconRes)
        assertEquals(R.drawable.ic_nav_wallet, bottomNavItems[3].iconRes)
        assertEquals(R.drawable.ic_nav_user_round, bottomNavItems[4].iconRes)

        var selectedTab by mutableIntStateOf(0)

        composeTestRule.setContent {
            ElBatalTheme {
                CaptainBottomNavigationBar(
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("bottom_nav_home").assertIsSelected()
        composeTestRule.onNodeWithTag("bottom_nav_orders_history").assertIsNotSelected()

        // Navigate across all 5 tabs
        val tagsAndIndices = listOf(
            "bottom_nav_orders_history" to 1,
            "bottom_nav_shifts" to 2,
            "bottom_nav_wallet" to 3,
            "bottom_nav_profile" to 4,
            "bottom_nav_home" to 0
        )
        for ((tag, expectedIdx) in tagsAndIndices) {
            composeTestRule.onNodeWithTag(tag).performClick()
            composeTestRule.waitForIdle()
            assertEquals(expectedIdx, selectedTab)
            composeTestRule.onNodeWithTag(tag).assertIsSelected()
        }
    }

    // 10. فتح صفحة الشفتات الحالية عند الضغط على أيقونة الشفتات (Calendar Clock) بدون تكرار صفحات
    @Test
    fun scenario10_clickingCalendarClockShiftsIconOpensExistingShiftsScreen() {
        composeTestRule.setContent {
            ElBatalTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        CaptainBottomNavigationBar(
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            0 -> Text("الشاشة الرئيسية", modifier = Modifier.testTag("mock_dashboard"))
                            1 -> OrdersScreen(
                                orders = emptyList(),
                                captainUid = loggedInCaptainUid,
                                selectedDateKey = fixedTodayKey,
                                todayDateKey = fixedTodayKey
                            )
                            2 -> ShiftsScreen(
                                shifts = emptyList(),
                                shiftStatus = ShiftStatus.NoSchedule(),
                                currentCairoTime = "02:30 م",
                                currentCairoDate = "الثلاثاء 10 نوفمبر",
                                dailyWorkHours = 8.0,
                                weeklyWorkHours = 48.0,
                                isShiftNetworkConnected = true
                            )
                            3 -> Text("شاشة المحفظة")
                            4 -> Text("شاشة حسابي")
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("mock_dashboard").assertIsDisplayed()

        // Tap الشفتات (Calendar Clock icon at index 2)
        composeTestRule.onNodeWithTag("bottom_nav_shifts").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("shifts_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("شفتات العمل").assertIsDisplayed()
    }

    // 11. استمرار عمل الإشعارات الخارجية Push Notifications وقنوات الإشعارات بشكل طبيعي
    @Test
    fun scenario11_pushNotificationsPermissionAndNotificationChannelsRemainIntact() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )

        val declaredPermissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "POST_NOTIFICATIONS permission must remain declared in AndroidManifest.xml",
            declaredPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)
        )

        val declaredServices = pkgInfo.services?.map { it.name }.orEmpty()
        assertTrue(
            "CaptainLocationService must remain registered",
            declaredServices.contains(CaptainLocationService::class.java.name)
        )
        assertFalse(GpsConfig.NOTIFICATION_CHANNEL_ID.isBlank())
        assertNotNull(context.getDrawable(R.drawable.ic_nav_calendar_clock))
        assertNotNull(context.getDrawable(R.drawable.ic_nav_clipboard_list))
    }

    // 12. التحقق من تنظيف رقم الطلب وعدم تكرار علامة الشباك (##ELB-9468 -> ELB-9468)
    @Test
    fun scenario12_cleanOrderNumberPreventsDuplicateHash() {
        val sample1 = buildSampleOrder(
            id = 501L,
            orderNumber = "#ELB-9468",
            storeName = "McDonald's",
            status = OrderStatus.ACCEPTED
        )
        assertEquals("ELB-9468", sample1.cleanOrderNumber)

        val sample2 = buildSampleOrder(
            id = 502L,
            orderNumber = "##ELB-9999",
            storeName = "KFC",
            status = OrderStatus.PENDING
        )
        assertEquals("ELB-9999", sample2.cleanOrderNumber)

        val sample3 = buildSampleOrder(
            id = 503L,
            orderNumber = "ELB-1234",
            storeName = "Hardee's",
            status = OrderStatus.DELIVERED
        )
        assertEquals("ELB-1234", sample3.cleanOrderNumber)
    }

    // 13. التحقق من دقة واحتساب أرباح اليوم الحقيقية فور اكتمال الطلب
    @Test
    fun scenario13_todayEarningsAndCompletedCountImmediatelyReflectDeliveredOrders() = runBlocking {
        val orderDao = db.orderDao()
        val nowMs = System.currentTimeMillis()

        val deliveredOrder1 = buildSampleOrder(
            id = 601L,
            orderNumber = "ELB-701",
            storeName = "مطعم البرنس",
            status = OrderStatus.DELIVERED,
            deliveryFee = 35.0,
            completedAt = nowMs,
            createdAt = nowMs
        )
        val deliveredOrder2 = buildSampleOrder(
            id = 602L,
            orderNumber = "ELB-702",
            storeName = "مطعم صبحي كابر",
            status = OrderStatus.DELIVERED,
            deliveryFee = 45.0,
            completedAt = nowMs,
            createdAt = nowMs
        )
        orderDao.insertOrder(deliveredOrder1)
        orderDao.insertOrder(deliveredOrder2)

        val all = orderDao.getAllOrdersFlow().first()
        val todayKey = com.example.data.DailyWalletManager.getOperationalDayKey(nowMs)
        val todayCalKey = com.example.data.DailyWalletManager.getCalendarDateKey(nowMs)

        val todayOrders = all.filter {
            it.status == OrderStatus.DELIVERED &&
                (com.example.data.DailyWalletManager.getOrderOperationalDayKey(it) == todayKey ||
                    com.example.data.DailyWalletManager.getCalendarDateKey(com.example.data.DailyWalletManager.getOrderEffectiveTimestamp(it)) == todayCalKey)
        }

        assertEquals(2, todayOrders.size)
        assertEquals(80.0, todayOrders.sumOf { it.deliveryFee }, 0.001)
    }

    // 14. التحقق من إزالة الطلب المنتهي أو غير الموجود من قاعدة البيانات المحلية وعدم تعليق الكابتن
    @Test
    fun scenario14_staleOrNotFoundOrderCanBeRemovedLocally() = runBlocking {
        val orderDao = db.orderDao()
        val ghostOrder = buildSampleOrder(
            id = 999L,
            orderNumber = "ELB-9468",
            storeName = "McDonald's",
            status = OrderStatus.ACCEPTED
        )
        orderDao.insertOrder(ghostOrder)

        val activeBefore = orderDao.getActiveOrderFlow().first()
        assertNotNull(activeBefore)
        assertEquals(999L, activeBefore?.id)

        // Simulate server returning 404 / NotFound -> removeStaleOrder removes from local DB
        orderDao.deleteOrderById(999L)

        val activeAfter = orderDao.getActiveOrderFlow().first()
        assertEquals(null, activeAfter)
    }

    // 15. التحقق من دالة sanitizeOrderId في CloudFunctionsOrderClient
    @Test
    fun scenario15_cloudFunctionsOrderClientSanitizesOrderIds() {
        val client = com.example.data.remote.CloudFunctionsOrderClient()
        assertEquals("ELB-9468", client.sanitizeOrderId("#ELB-9468"))
        assertEquals("ELB-9468", client.sanitizeOrderId("##ELB-9468"))
        assertEquals("ELB-9468", client.sanitizeOrderId("  #ELB-9468  "))
        assertEquals("ELB-9468", client.sanitizeOrderId("ELB-9468"))
    }
}
