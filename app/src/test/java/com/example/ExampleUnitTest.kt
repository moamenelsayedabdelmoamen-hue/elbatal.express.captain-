package com.example

import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.formatRawTimeToHHmm
import com.example.data.parseShiftMap
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ExampleUnitTest {

    @Test
    fun testFormatRawTimeParsesAllFormatsCorrectly() {
        // String formats
        assertEquals("09:00", formatRawTimeToHHmm("09:00"))
        assertEquals("14:30", formatRawTimeToHHmm("14:30"))
        assertEquals("14:30", formatRawTimeToHHmm("02:30 م"))
        assertEquals("09:15", formatRawTimeToHHmm("09:15 ص"))

        // Number format (minutes from midnight: 540 = 09:00)
        assertEquals("09:00", formatRawTimeToHHmm(540))
        assertEquals("14:30", formatRawTimeToHHmm(870))

        // Hour of day numbers (0..24)
        assertEquals("10:00", formatRawTimeToHHmm(10))
        assertEquals("12:00", formatRawTimeToHHmm(12))
        assertEquals("20:00", formatRawTimeToHHmm(20))
        assertEquals("09:30", formatRawTimeToHHmm(9.5))

        // Map formats (e.g. time pickers {hour: 14, minute: 30})
        assertEquals("14:30", formatRawTimeToHHmm(mapOf("hour" to 14, "minute" to 30)))
        assertEquals("11:00", formatRawTimeToHHmm(mapOf("hours" to 11, "minutes" to 0)))

        // Arabic numerals
        assertEquals("12:00", formatRawTimeToHHmm("١٢:٠٠"))
        assertEquals("14:30", formatRawTimeToHHmm("٠٢:٣٠ م"))

        // Timestamp format
        val cal = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.HOUR_OF_DAY, 16)
            set(Calendar.MINUTE, 45)
            set(Calendar.SECOND, 0)
        }
        val timestamp = Timestamp(cal.time)
        assertEquals("16:45", formatRawTimeToHHmm(timestamp))
    }

    @Test
    fun testParseShiftMapExtractsAllVariations() {
        val testUid = "captain_test_123"

        // Variation 1: Standard snake_case
        val data1 = mapOf<String, Any?>(
            "day_of_week" to 7, // Saturday
            "start_time" to "10:00",
            "end_time" to "18:00",
            "is_day_off" to false
        )
        val shift1 = parseShiftMap(data1, "shift1", testUid)
        assertNotNull(shift1)
        assertEquals(Calendar.SATURDAY, shift1?.dayOfWeek)
        assertEquals("10:00", shift1?.startTime)
        assertEquals("18:00", shift1?.endTime)
        assertFalse(shift1!!.isDayOff)

        // Variation 2: Arabic day name and string boolean off
        val data2 = mapOf<String, Any?>(
            "day" to "الجمعة",
            "start" to "00:00",
            "end" to "00:00",
            "isOff" to true,
            "notes" to "عطلة أسبوعية"
        )
        val shift2 = parseShiftMap(data2, "shift2", testUid)
        assertNotNull(shift2)
        assertEquals(Calendar.FRIDAY, shift2?.dayOfWeek)
        assertTrue(shift2!!.isDayOff)
    }

    @Test
    fun testOvernightShiftCalculation() {
        val overnightShift = ShiftEntity(
            id = "overnight_1",
            captainUid = "c1",
            dayOfWeek = Calendar.MONDAY,
            dayNameArabic = "الإثنين",
            startTime = "22:00", // 10 PM
            endTime = "06:00",   // 6 AM next day
            isDayOff = false
        )

        // Shift duration across midnight should be exactly 8 hours (2 hours before midnight + 6 hours after)
        val duration = ShiftManager.calculateShiftDurationHours(overnightShift)
        assertEquals(8.0, duration, 0.01)

        // Test at 23:30 (inside shift)
        val testCalInside = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }
        val statusInside = ShiftManager.calculateShiftStatus(listOf(overnightShift), testCalInside)
        assertTrue("Captain should be active during overnight shift", statusInside is ShiftStatus.Active)
        assertTrue(statusInside.isAvailableForOrders)

        // Test at 12:00 PM (outside shift)
        val testCalOutside = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
        }
        val statusOutside = ShiftManager.calculateShiftStatus(listOf(overnightShift), testCalOutside)
        assertTrue("Captain should be outside shift at 12 PM", statusOutside is ShiftStatus.OutsideShift)
        assertFalse(statusOutside.isAvailableForOrders)
    }

    @Test
    fun testEmptyScheduleDeniesOrderAvailability() {
        val emptyShifts = emptyList<ShiftEntity>()
        val status = ShiftManager.calculateShiftStatus(emptyShifts)
        assertTrue(status is ShiftStatus.NoSchedule)
        assertFalse("When no schedule exists, captain cannot receive orders", status.isAvailableForOrders)
    }

    @Test
    fun testWeeklyWorkHoursCalculation() {
        val shifts = listOf(
            ShiftEntity(id = "1", captainUid = "c1", dayOfWeek = Calendar.SATURDAY, startTime = "09:00", endTime = "17:00", isDayOff = false), // 8h
            ShiftEntity(id = "2", captainUid = "c1", dayOfWeek = Calendar.SUNDAY, startTime = "09:00", endTime = "17:00", isDayOff = false),   // 8h
            ShiftEntity(id = "3", captainUid = "c1", dayOfWeek = Calendar.FRIDAY, startTime = "00:00", endTime = "00:00", isDayOff = true)      // 0h
        )
        val total = ShiftManager.calculateWeeklyWorkHours(shifts)
        assertEquals(16.0, total, 0.01)
    }

    @Test
    fun testNormalizeArabicDigitsAndPhoneEquivalence() {
        val arabicPhone = "٠١٠١٢٣٤٥٦٧٨"
        val englishPhone = "01012345678"
        val intlPhone = "+201012345678"

        assertEquals(englishPhone, com.example.data.normalizeArabicDigits(arabicPhone))
        assertTrue(com.example.data.isSamePhone(arabicPhone, englishPhone))
        assertTrue(com.example.data.isSamePhone(arabicPhone, intlPhone))
        assertTrue(com.example.data.isSamePhone("010 1234 5678", intlPhone))
    }

    @Test
    fun testOrderStatusLifecycleEnum() {
        // Verify all 11 statuses exist and have distinct valid identifiers
        val statuses = com.example.data.OrderStatus.values()
        assertTrue(statuses.contains(com.example.data.OrderStatus.PENDING))
        assertTrue(statuses.contains(com.example.data.OrderStatus.ACCEPTED))
        assertTrue(statuses.contains(com.example.data.OrderStatus.AT_STORE))
        assertTrue(statuses.contains(com.example.data.OrderStatus.PICKED_UP))
        assertTrue(statuses.contains(com.example.data.OrderStatus.ORDER_DATA_ENTERED))
        assertTrue(statuses.contains(com.example.data.OrderStatus.AWAITING_MERCHANT_CONFIRMATION))
        assertTrue(statuses.contains(com.example.data.OrderStatus.MERCHANT_REJECTED))
        assertTrue(statuses.contains(com.example.data.OrderStatus.EN_ROUTE))
        assertTrue(statuses.contains(com.example.data.OrderStatus.DELIVERED))
        assertTrue(statuses.contains(com.example.data.OrderStatus.CANCELLED))
        assertTrue(statuses.contains(com.example.data.OrderStatus.EXPIRED))
    }

    @Test
    fun testOfferCountdownCalculation() {
        val now = System.currentTimeMillis()
        val offerExpiresAt = now + 45_000L // 45 seconds remaining

        val remainingMs = (offerExpiresAt - now).coerceAtLeast(0L)
        val remainingSeconds = (remainingMs / 1000).toInt()
        assertEquals(45, remainingSeconds)

        // Expired case
        val expiredTime = now - 5_000L
        val expiredRemaining = (expiredTime - now).coerceAtLeast(0L)
        assertEquals(0L, expiredRemaining)
    }

    @Test
    fun testEgyptianPhoneNumberValidation() {
        val validPhones = listOf("01012345678", "01123456789", "01234567890", "01512345678")
        val invalidPhones = listOf("0101234567", "01912345678", "12345678901", "abcdefghijk")

        val phoneRegex = Regex("^01[0125][0-9]{8}$")
        validPhones.forEach { phone ->
            assertTrue("Expected $phone to be valid", phoneRegex.matches(phone))
        }
        invalidPhones.forEach { phone ->
            assertFalse("Expected $phone to be invalid", phoneRegex.matches(phone))
        }
    }

    @Test
    fun testRootShiftTimesExpandToFullWeeklySchedule() {
        val testUid = "captain_admin_update"
        // Simulating admin control panel updating start_time and end_time at root of captain_schedules doc
        val adminUpdate = mapOf<String, Any?>(
            "start_time" to "11:00",
            "end_time" to "19:00",
            "captainId" to testUid
        )

        val fullWeekShifts = com.example.data.extractShiftsFromMap(adminUpdate, "doc1", testUid)

        // Must generate all 7 days of the week
        assertEquals(7, fullWeekShifts.size)

        // Saturday through Thursday should have 11:00 to 19:00, isDayOff = false
        val saturday = fullWeekShifts.find { it.dayOfWeek == Calendar.SATURDAY }
        assertNotNull(saturday)
        assertEquals("11:00", saturday?.startTime)
        assertEquals("19:00", saturday?.endTime)
        assertFalse(saturday!!.isDayOff)

        val thursday = fullWeekShifts.find { it.dayOfWeek == Calendar.THURSDAY }
        assertNotNull(thursday)
        assertEquals("11:00", thursday?.startTime)
        assertEquals("19:00", thursday?.endTime)
        assertFalse(thursday!!.isDayOff)

        // Friday should be day off
        val friday = fullWeekShifts.find { it.dayOfWeek == Calendar.FRIDAY }
        assertNotNull(friday)
        assertTrue(friday!!.isDayOff)

        // Total weekly hours: 6 days * 8 hours = 48 hours
        val weeklyHours = ShiftManager.calculateWeeklyWorkHours(fullWeekShifts)
        assertEquals(48.0, weeklyHours, 0.01)
    }

    @Test
    fun testParseTimeRangeStringVariants() {
        val range1 = com.example.data.parseTimeRangeString("10:00 - 18:00")
        assertNotNull(range1)
        assertEquals("10:00", range1?.first)
        assertEquals("18:00", range1?.second)

        val range2 = com.example.data.parseTimeRangeString("10:00 ص إلى 06:00 م")
        assertNotNull(range2)
        assertEquals("10:00 ص", range2?.first)
        assertEquals("06:00 م", range2?.second)

        val range3 = com.example.data.parseTimeRangeString("من 09:00 الى 17:00")
        assertNotNull(range3)
        assertEquals("09:00", range3?.first)
        assertEquals("17:00", range3?.second)
    }

    @Test
    fun testAdminControlPanelFieldVariantsMatchDatabase() {
        val testUid = "captain_admin_variants"

        // Arabic fields used in dashboard: بداية_الدوام / نهاية_الدوام
        val adminData1 = mapOf<String, Any?>(
            "بداية_الدوام" to "09:00",
            "نهاية_الدوام" to "17:00",
            "captainId" to testUid
        )
        val shifts1 = com.example.data.extractShiftsFromMap(adminData1, "doc_arabic", testUid)
        assertEquals("09:00", shifts1.first { !it.isDayOff }.startTime)
        assertEquals("17:00", shifts1.first { !it.isDayOff }.endTime)

        // Range field: shift = "10:00 - 18:00"
        val adminData2 = mapOf<String, Any?>(
            "shift" to "10:00 - 18:00",
            "captainId" to testUid
        )
        val shifts2 = com.example.data.extractShiftsFromMap(adminData2, "doc_range", testUid)
        assertEquals("10:00", shifts2.first { !it.isDayOff }.startTime)
        assertEquals("18:00", shifts2.first { !it.isDayOff }.endTime)

        // shiftFrom / shiftTo
        val adminData3 = mapOf<String, Any?>(
            "shiftFrom" to "08:00",
            "shiftTo" to "16:00",
            "captainId" to testUid
        )
        val shifts3 = com.example.data.extractShiftsFromMap(adminData3, "doc_from_to", testUid)
        assertEquals("08:00", shifts3.first { !it.isDayOff }.startTime)
        assertEquals("16:00", shifts3.first { !it.isDayOff }.endTime)
    }

    @Test
    fun testShiftHoursCalculationFromDatabaseHours() {
        val testUid = "captain_hours_test"

        // Case 1: Start time + hours
        val dataWithHours = mapOf<String, Any?>(
            "start_time" to "10:00",
            "hours" to 8,
            "captainId" to testUid
        )
        val shift1 = com.example.data.parseShiftMap(dataWithHours, "test1", testUid)
        assertNotNull(shift1)
        assertEquals("10:00", shift1?.startTime)
        assertEquals("18:00", shift1?.endTime)
        val dur1 = ShiftManager.calculateShiftDurationHours(shift1!!)
        assertEquals(8.0, dur1, 0.01)

        // Case 2: Start time + decimal hours (e.g. 7.5 hours)
        val dataWithDecimalHours = mapOf<String, Any?>(
            "startTime" to "08:30",
            "duration" to 7.5,
            "captainId" to testUid
        )
        val shift2 = com.example.data.parseShiftMap(dataWithDecimalHours, "test2", testUid)
        assertNotNull(shift2)
        assertEquals("08:30", shift2?.startTime)
        assertEquals("16:00", shift2?.endTime)
        val dur2 = ShiftManager.calculateShiftDurationHours(shift2!!)
        assertEquals(7.5, dur2, 0.01)

        // Case 3: End time + hours (derives start time)
        val dataWithEndAndHours = mapOf<String, Any?>(
            "endTime" to "22:00",
            "ساعات_العمل" to 6,
            "captainId" to testUid
        )
        val shift3 = com.example.data.parseShiftMap(dataWithEndAndHours, "test3", testUid)
        assertNotNull(shift3)
        assertEquals("16:00", shift3?.startTime)
        assertEquals("22:00", shift3?.endTime)
        val dur3 = ShiftManager.calculateShiftDurationHours(shift3!!)
        assertEquals(6.0, dur3, 0.01)
    }

    @Test
    fun testPreservePerDayHoursFromScheduleWithoutRootOverwrite() {
        val testUid = "captain_per_day_hours"

        // Schedule container specifies different hours per day
        val scheduleMap = mapOf<String, Any?>(
            "saturday" to mapOf(
                "startTime" to "12:00",
                "endTime" to "18:00" // 6 hours
            ),
            "sunday" to mapOf(
                "startTime" to "10:00",
                "endTime" to "18:00" // 8 hours
            ),
            "monday" to mapOf(
                "startTime" to "09:00",
                "endTime" to "21:00" // 12 hours
            ),
            // Root working_hours that should NOT overwrite individual schedule days
            "working_hours" to "08:00 - 16:00"
        )

        val shifts = com.example.data.extractShiftsFromMap(scheduleMap, "doc_sched", testUid)

        val sat = shifts.first { it.dayOfWeek == Calendar.SATURDAY }
        assertEquals("12:00", sat.startTime)
        assertEquals("18:00", sat.endTime)
        assertEquals(6.0, ShiftManager.calculateShiftDurationHours(sat), 0.01)

        val sun = shifts.first { it.dayOfWeek == Calendar.SUNDAY }
        assertEquals("10:00", sun.startTime)
        assertEquals("18:00", sun.endTime)
        assertEquals(8.0, ShiftManager.calculateShiftDurationHours(sun), 0.01)

        val mon = shifts.first { it.dayOfWeek == Calendar.MONDAY }
        assertEquals("09:00", mon.startTime)
        assertEquals("21:00", mon.endTime)
        assertEquals(12.0, ShiftManager.calculateShiftDurationHours(mon), 0.01)
    }

    @Test
    fun testArabicShiftDurationFormatting() {
        assertEquals("8 ساعات عمل", ShiftManager.formatShiftDurationArabic(8.0))
        assertEquals("8 ساعات و 30 دقيقة عمل", ShiftManager.formatShiftDurationArabic(8.5))
        assertEquals("7 ساعات و 30 دقيقة عمل", ShiftManager.formatShiftDurationArabic(7.5))
        assertEquals("ساعة عمل واحدة", ShiftManager.formatShiftDurationArabic(1.0))
        assertEquals("ساعتين عمل", ShiftManager.formatShiftDurationArabic(2.0))
        assertEquals("3 ساعات عمل", ShiftManager.formatShiftDurationArabic(3.0))
        assertEquals("12 ساعة عمل", ShiftManager.formatShiftDurationArabic(12.0))
        assertEquals("12 ساعة و 5 دقائق عمل", ShiftManager.formatShiftDurationArabic(12.0 + 5.0 / 60.0))
        assertEquals("22 ساعة عمل", ShiftManager.formatShiftDurationArabic(22.0))
    }

    @Test
    fun testCaptainScheduleReadsEveryNestedShiftUsingExactClockTimes() {
        val uid = "captain_shift_parse"
        val firestoreSchedule = mapOf<String, Any?>(
            "weeklySchedule" to mapOf(
                "sunday" to mapOf(
                    "day" to "sunday",
                    "dayNameAr" to "الأحد",
                    "isDayOff" to false,
                    "totalHours" to 22.0,
                    "shifts" to listOf(
                        mapOf(
                            "id" to "shift_sunday_1",
                            "name" to "وردية أساسية",
                            "startTime" to "14:00",
                            "endTime" to "12:00",
                            "durationHours" to 22.0,
                            "isOvernight" to true
                        )
                    )
                ),
                "monday" to mapOf(
                    "day" to "monday",
                    "dayNameAr" to "الاثنين",
                    "isDayOff" to false,
                    "totalHours" to 14.0,
                    "shifts" to listOf(
                        mapOf(
                            "id" to "shift_monday_1",
                            "name" to "وردية أساسية",
                            "startTime" to "10:00",
                            "endTime" to "00:00",
                            "durationHours" to 14.0,
                            "isOvernight" to true
                        )
                    )
                ),
                "friday" to mapOf(
                    "day" to "friday",
                    "dayNameAr" to "الجمعة",
                    "isDayOff" to true,
                    "totalHours" to 0,
                    "shifts" to emptyList<Map<String, Any?>>()
                )
            )
        )

        val shifts = com.example.data.extractShiftsFromMap(firestoreSchedule, "schedule", uid)
        val sunday = shifts.single { it.id == "shift_sunday_1" }
        val monday = shifts.single { it.id == "shift_monday_1" }

        assertEquals("14:00", sunday.startTime)
        assertEquals("12:00", sunday.endTime)
        assertEquals(22.0, ShiftManager.calculateShiftDurationHours(sunday), 0.01)
        assertEquals("10:00", monday.startTime)
        assertEquals("00:00", monday.endTime)
        assertEquals(14.0, ShiftManager.calculateShiftDurationHours(monday), 0.01)
        assertTrue(shifts.any { it.dayOfWeek == Calendar.FRIDAY && it.isDayOff })
    }

    @Test
    fun testMultipleShiftsOnSameDayAreNotCollapsed() {
        val uid = "captain_multi_shift"
        val firestoreSchedule = mapOf<String, Any?>(
            "weeklySchedule" to mapOf(
                "saturday" to mapOf(
                    "day" to "saturday",
                    "dayNameAr" to "السبت",
                    "isDayOff" to false,
                    "totalHours" to 14.0,
                    "shifts" to listOf(
                        mapOf("id" to "shift_saturday_1", "startTime" to "06:00", "endTime" to "12:00"),
                        mapOf("id" to "shift_saturday_2", "startTime" to "14:00", "endTime" to "22:00")
                    )
                )
            )
        )

        val shifts = com.example.data.extractShiftsFromMap(firestoreSchedule, "schedule", uid)
        assertEquals(2, shifts.size)
        assertEquals(setOf("shift_saturday_1", "shift_saturday_2"), shifts.map { it.id }.toSet())
        assertEquals(14.0, ShiftManager.calculateWeeklyWorkHours(shifts), 0.01)
    }

    @Test
    fun testLongOvernightShiftRemainsActiveAfterMidnightUntilItsActualEnd() {
        val sundayShift = ShiftEntity(
            id = "shift_sunday_1",
            captainUid = "captain_long_shift",
            dayOfWeek = Calendar.SUNDAY,
            dayNameArabic = "الأحد",
            startTime = "14:00",
            endTime = "12:00",
            isDayOff = false
        )
        val mondayElevenAm = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val status = ShiftManager.calculateShiftStatus(listOf(sundayShift), mondayElevenAm)
        assertTrue(status is ShiftStatus.Active)
        assertEquals("shift_sunday_1", (status as ShiftStatus.Active).shift.id)
    }

    @Test
    fun testEgyptianPhoneMatchingFuzzy() {
        val canonical = "01012345678"
        val arabic = "٠١٠١٢٣٤٥٦٧٨"
        val intl = "+201012345678"
        val intlNoPlus = "201012345678"
        val localSpaced = "010 1234 5678"
        val localDashed = "010-1234-5678"
        val noZero = "1012345678"

        assertTrue(com.example.data.isSamePhone(canonical, arabic))
        assertTrue(com.example.data.isSamePhone(canonical, intl))
        assertTrue(com.example.data.isSamePhone(canonical, intlNoPlus))
        assertTrue(com.example.data.isSamePhone(canonical, localSpaced))
        assertTrue(com.example.data.isSamePhone(canonical, localDashed))
        assertTrue(com.example.data.isSamePhone(canonical, noZero))
        assertTrue(com.example.data.isSamePhone(arabic, intl))
        assertTrue(com.example.data.isSamePhone(localSpaced, localDashed))

        // Different numbers must not match
        assertFalse(com.example.data.isSamePhone("01012345678", "01099999999"))
        assertFalse(com.example.data.isSamePhone("01112345678", "01212345678"))
    }

    @Test
    fun testDailyWalletRolloverAt1PmCairoTime() {
        val cairoTz = com.example.data.DailyWalletManager.CAIRO_TZ

        // 1) 2026-11-10 at 12:59:59 PM Cairo -> still belongs to "2026-11-09" cycle!
        val before1Pm = Calendar.getInstance(cairoTz).apply {
            set(2026, Calendar.NOVEMBER, 10, 12, 59, 59)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026-11-09", com.example.data.DailyWalletManager.getOperationalDayKey(before1Pm))

        // 2) 2026-11-10 at 13:00:00 (1:00 PM) Cairo -> rolls over to new operational day "2026-11-10"!
        val exact1Pm = Calendar.getInstance(cairoTz).apply {
            set(2026, Calendar.NOVEMBER, 10, 13, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("2026-11-10", com.example.data.DailyWalletManager.getOperationalDayKey(exact1Pm))

        // Two orders delivered during the 2026-11-09 cycle (one on Nov 9 at 5 PM, one on Nov 10 at 11:30 AM before 1 PM)
        val orderNov9Evening = Calendar.getInstance(cairoTz).apply {
            set(2026, Calendar.NOVEMBER, 9, 17, 0, 0)
        }.timeInMillis
        val orderNov10Morning = Calendar.getInstance(cairoTz).apply {
            set(2026, Calendar.NOVEMBER, 10, 11, 30, 0)
        }.timeInMillis

        val ordersAt1Pm = listOf(
            com.example.data.OrderEntity(
                id = 101L,
                orderNumber = "ORD-101",
                customerName = "أحمد",
                customerPhone = "01011111111",
                storeName = "مطعم أ",
                pickupAddress = "عنوان",
                deliveryAddress = "عنوان",
                itemsSummary = "وجبة",
                itemsCount = 1,
                orderTotalAmount = 200.0,
                deliveryFee = 50.0,
                distanceKm = 3.0,
                status = com.example.data.OrderStatus.DELIVERED,
                createdAt = orderNov9Evening,
                completedAt = orderNov9Evening,
                captainId = "cap_1"
            ),
            com.example.data.OrderEntity(
                id = 102L,
                orderNumber = "ORD-102",
                customerName = "محمود",
                customerPhone = "01022222222",
                storeName = "مطعم ب",
                pickupAddress = "عنوان",
                deliveryAddress = "عنوان",
                itemsSummary = "وجبة",
                itemsCount = 1,
                orderTotalAmount = 150.0,
                deliveryFee = 35.0,
                distanceKm = 2.5,
                status = com.example.data.OrderStatus.DELIVERED,
                createdAt = orderNov10Morning,
                completedAt = orderNov10Morning,
                captainId = "cap_1"
            )
        )

        // At 1:00 PM on Nov 10:
        // - Previous day ("2026-11-09") is archived (isClosed = true) with 50 + 35 = 85.0 EGP
        // - New day ("2026-11-10") starts at 0.0 EGP (isClosed = false)
        val recordsAt1Pm = com.example.data.DailyWalletManager.buildDailyRecords(
            captainUid = "cap_1",
            captainOrders = ordersAt1Pm,
            nowMs = exact1Pm
        )
        val prevDayRecord = recordsAt1Pm.first { it.dayKey == "2026-11-09" }
        val newDayRecordAtStart = recordsAt1Pm.first { it.dayKey == "2026-11-10" }

        assertTrue(prevDayRecord.isClosed)
        assertEquals(85.0, prevDayRecord.walletBalance, 0.001)
        assertEquals(2, prevDayRecord.deliveredOrdersCount)

        assertFalse(newDayRecordAtStart.isClosed)
        assertEquals(0.0, newDayRecordAtStart.walletBalance, 0.001)
        assertEquals(0, newDayRecordAtStart.deliveredOrdersCount)

        // Now deliver a new order at 2:15 PM (14:15) on Nov 10 -> added to new day's wallet!
        val orderNov10Afternoon = Calendar.getInstance(cairoTz).apply {
            set(2026, Calendar.NOVEMBER, 10, 14, 15, 0)
        }.timeInMillis
        val ordersAfterNewDelivery = ordersAt1Pm + com.example.data.OrderEntity(
            id = 103L,
            orderNumber = "ORD-103",
            customerName = "سارة",
            customerPhone = "01033333333",
            storeName = "مطعم ج",
            pickupAddress = "عنوان",
            deliveryAddress = "عنوان",
            itemsSummary = "وجبة",
            itemsCount = 1,
            orderTotalAmount = 180.0,
            deliveryFee = 40.0,
            distanceKm = 4.0,
            status = com.example.data.OrderStatus.DELIVERED,
            createdAt = orderNov10Afternoon,
            completedAt = orderNov10Afternoon,
            captainId = "cap_1"
        )

        val recordsAfterDelivery = com.example.data.DailyWalletManager.buildDailyRecords(
            captainUid = "cap_1",
            captainOrders = ordersAfterNewDelivery,
            existingRecords = recordsAt1Pm,
            nowMs = orderNov10Afternoon + 60_000L
        )
        val updatedNewDay = recordsAfterDelivery.first { it.dayKey == "2026-11-10" }
        val stillClosedPrevDay = recordsAfterDelivery.first { it.dayKey == "2026-11-09" }

        assertEquals(40.0, updatedNewDay.walletBalance, 0.001)
        assertEquals(1, updatedNewDay.deliveredOrdersCount)
        assertEquals(85.0, stillClosedPrevDay.walletBalance, 0.001)
        assertTrue(stillClosedPrevDay.isClosed)
    }

    @Test
    fun testPhoneAuthRegistryBidiAndFormatNormalization() {
        val pastedFromContacts = "\u202A+20 101 234 5678\u202C"
        val pastedArabicBidi = "\u200E٠١٠١٢٣٤٥٦٧٨\u200F"
        val intlDoubleZero = "00201012345678"
        val tenDigit = "1012345678"

        assertEquals("01012345678", com.example.data.PhoneAuthRegistry.normalizeEgyptianPhone(pastedFromContacts))
        assertEquals("01012345678", com.example.data.PhoneAuthRegistry.normalizeEgyptianPhone(pastedArabicBidi))
        assertEquals("01012345678", com.example.data.PhoneAuthRegistry.normalizeEgyptianPhone(intlDoubleZero))
        assertEquals("01012345678", com.example.data.PhoneAuthRegistry.normalizeEgyptianPhone(tenDigit))

        val variants = com.example.data.PhoneAuthRegistry.generatePhoneQueryVariants(pastedFromContacts)
        assertTrue(variants.contains("01012345678"))
        assertTrue(variants.contains("+201012345678"))
        assertTrue(variants.contains("201012345678"))
        assertTrue(variants.contains("1012345678"))
        assertTrue(variants.contains("٠١٠١٢٣٤٥٦٧٨"))
    }
}
