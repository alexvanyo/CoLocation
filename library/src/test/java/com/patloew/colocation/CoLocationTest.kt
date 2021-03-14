package com.patloew.colocation

import android.content.Context
import android.location.Location
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

/* Copyright 2020 Patrick Löwenstein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. */
@Timeout(5, unit = TimeUnit.SECONDS)
class CoLocationTest {

    private val locationProvider: FusedLocationProviderClient = mockk()
    private val settings: SettingsClient = mockk()
    private val context: Context = mockk()
    private val testCoroutineDispatcher = TestCoroutineDispatcher()
    private val testCoroutineScope = TestCoroutineScope(testCoroutineDispatcher)

    private val coLocation = CoLocationImpl(context)

    @BeforeEach
    fun before() {
        Dispatchers.setMain(testCoroutineDispatcher)

        val field = TaskExecutors::class.java.getDeclaredField("MAIN_THREAD")
        val modifierFields = Field::class.java.getDeclaredField("modifiers")
        modifierFields.isAccessible = true
        modifierFields.setInt(field, field.modifiers and Modifier.FINAL.inv())
        field.set(null, Dispatchers.Main.asExecutor())

        mockkStatic(LocationServices::class)
        every { LocationServices.getFusedLocationProviderClient(context) } returns locationProvider
        every { LocationServices.getSettingsClient(context) } returns settings
    }

    @AfterEach
    fun after() {
        testCoroutineDispatcher.cleanupTestCoroutines()
        testCoroutineScope.cleanupTestCoroutines()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun flushLocations()= testCoroutineScope.runBlockingTest {
        testTaskWithCancelThrows(
            createTask = { locationProvider.flushLocations() },
            taskResult = null,
            expectedResult = Unit,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.flushLocations() }
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun isLocationAvailable(locationAvailable: Boolean) = testCoroutineScope.runBlockingTest {
        val locationAvailability = mockk<LocationAvailability> {
            every { isLocationAvailable } returns locationAvailable
        }
        testTaskWithCancelThrows(
            createTask = { locationProvider.locationAvailability },
            taskResult = locationAvailability,
            expectedResult = locationAvailable,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.isLocationAvailable() }
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [
        LocationRequest.PRIORITY_HIGH_ACCURACY,
        LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
        LocationRequest.PRIORITY_LOW_POWER,
        LocationRequest.PRIORITY_NO_POWER
    ])
    fun getCurrentLocation(priority: Int) = testCoroutineScope.runBlockingTest {
        val location = mockk<Location>()
        testTaskWithCancelThrows(
            createTask = { locationProvider.getCurrentLocation(priority, any()) },
            taskResult = location,
            expectedResult = location,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.getCurrentLocation(priority) }
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [
        LocationRequest.PRIORITY_HIGH_ACCURACY,
        LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY,
        LocationRequest.PRIORITY_LOW_POWER,
        LocationRequest.PRIORITY_NO_POWER
    ])
    fun `cancelling getCurrentLocation cancels task`(priority: Int) {
        lateinit var currentLocationTaskCompletionSource: TaskCompletionSource<Location>

        every {
            locationProvider.getCurrentLocation(priority, any())
        } answers {
            currentLocationTaskCompletionSource = TaskCompletionSource(arg(1))
            currentLocationTaskCompletionSource.task
        }

        val deferred = testCoroutineScope.async {
            coLocation.getCurrentLocation(priority)
        }

        deferred.cancel()

        assertTrue(deferred.isCancelled)
        assertTrue(currentLocationTaskCompletionSource.task.isCanceled)
    }

    @Test
    fun getLastLocation()= testCoroutineScope.runBlockingTest {
        val location = mockk<Location>()
        testTaskWithCancelThrows(
            createTask = { locationProvider.lastLocation },
            taskResult = location,
            expectedResult = location,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.getLastLocation() }
        )
    }

    @Test
    fun setMockLocation() = testCoroutineScope.runBlockingTest {
        val location: Location = mockk()
        testTaskWithCancelThrows(
            createTask = { locationProvider.setMockLocation(location) },
            taskResult = null,
            expectedResult = Unit,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.setMockLocation(location) }
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun setMockMode(mockMode: Boolean) = testCoroutineScope.runBlockingTest {
        testTaskWithCancelThrows(
            createTask = { locationProvider.setMockMode(mockMode) },
            taskResult = null,
            expectedResult = Unit,
            expectedErrorException = TestException(),
            coLocationCall = { coLocation.setMockMode(mockMode) }
        )
    }

    @Test
    fun `getLocationUpdate success`() {
        val requestTaskCompletionSource = TaskCompletionSource<Void>()
        val removeTaskCompletionSource = TaskCompletionSource<Void>()

        val locationRequest: LocationRequest = mockk {
            every { numUpdates } returns Integer.MAX_VALUE
        }
        val locations: List<Location> = MutableList(5) { mockk() }
        val callbackSlot = slot<LocationCallback>()
        every {
            locationProvider.requestLocationUpdates(
                locationRequest,
                capture(callbackSlot),
                any()
            )
        } answers {
            every {
                locationProvider.removeLocationUpdates(callbackSlot.captured)
            } returns removeTaskCompletionSource.task

            requestTaskCompletionSource.task
        }
        var result: Location? = null

        val job = testCoroutineScope.launch {
            result = coLocation.getLocationUpdate(locationRequest)
        }

        testCoroutineScope.runBlockingTest {
            locations.forEach { location ->
                callbackSlot.captured.onLocationResult(LocationResult.create(listOf(location)))
                delay(10)
            }
            job.cancelAndJoin()
        }

        assertEquals(locations[0], result)
        verify { locationProvider.removeLocationUpdates(callbackSlot.captured) }
    }

    @Test
    fun `getLocationUpdate error`() {
        val requestTaskCompletionSource = TaskCompletionSource<Void>()
        val removeTaskCompletionSource = TaskCompletionSource<Void>()

        val testException = TestException()
        requestTaskCompletionSource.setException(testException)
        val locationRequest: LocationRequest = mockk()

        val callbackSlot = slot<LocationCallback>()

        every {
            locationProvider.requestLocationUpdates(locationRequest, capture(callbackSlot), any())
        } answers {
            every {
                locationProvider.removeLocationUpdates(callbackSlot.captured)
            } returns removeTaskCompletionSource.task

            requestTaskCompletionSource.task
        }

        var result: Location? = null
        var resultException: TestException? = null

        testCoroutineScope.runBlockingTest {
            try {
                result = coLocation.getLocationUpdate(locationRequest)
            } catch (e: TestException) {
                resultException = e
            }
        }

        assertNull(result)
        assertNotNull(resultException)
    }

    @Test
    fun `getLocationUpdate cancel`() {
        val cancellationTokenSource = CancellationTokenSource()

        val requestTaskCompletionSource = TaskCompletionSource<Void>(cancellationTokenSource.token)
        val removeTaskCompletionSource = TaskCompletionSource<Void>()

        cancellationTokenSource.cancel()
        val locationRequest: LocationRequest = mockk()

        val callbackSlot = slot<LocationCallback>()

        every {
            locationProvider.requestLocationUpdates(locationRequest, capture(callbackSlot), any())
        } answers {
            every {
                locationProvider.removeLocationUpdates(callbackSlot.captured)
            } returns removeTaskCompletionSource.task

            requestTaskCompletionSource.task
        }
        var result: Location? = null
        var resultException: CancellationException? = null

        testCoroutineScope.runBlockingTest {
            try {
                result = coLocation.getLocationUpdate(locationRequest)
            } catch (e: CancellationException) {
                resultException = e
            }
        }

        assertNull(result)
        assertNotNull(resultException)
    }

    @Test
    fun `getLocationUpdates success`() {
        val requestTaskCompletionSource = TaskCompletionSource<Void>()
        val removeTaskCompletionSource = TaskCompletionSource<Void>()

        val locationRequest: LocationRequest = mockk {
            every { numUpdates } returns Integer.MAX_VALUE
        }
        val locations: List<Location> = MutableList(5) { mockk() }
        val callbackSlot = slot<LocationCallback>()
        every {
            locationProvider.requestLocationUpdates(
                locationRequest,
                capture(callbackSlot),
                any()
            )
        } answers {
            every {
                locationProvider.removeLocationUpdates(callbackSlot.captured)
            } returns removeTaskCompletionSource.task

            requestTaskCompletionSource.task
        }

        val flowResults = mutableListOf<Location>()

        val job = testCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coLocation.getLocationUpdates(locationRequest).collect(flowResults::add)
        }

        testCoroutineScope.runBlockingTest {
            locations.forEach { location ->
                callbackSlot.captured.onLocationResult(LocationResult.create(listOf(location)))
                delay(10)
            }
            job.cancelAndJoin()
        }

        assertEquals(locations, flowResults)
        verify { locationProvider.removeLocationUpdates(callbackSlot.captured) }
    }

    @Test
    fun `getLocationUpdates error`() {
        val requestTaskCompletionSource = TaskCompletionSource<Void>()
        val testException = TestException()
        requestTaskCompletionSource.setException(testException)
        val locationRequest: LocationRequest = mockk()
        every {
            locationProvider.requestLocationUpdates(locationRequest, any(), any())
        } returns requestTaskCompletionSource.task
        every { locationProvider.removeLocationUpdates(any<LocationCallback>()) } returns mockk()

        testCoroutineScope.runBlockingTest {
            assertThrows<TestException> {
                coLocation.getLocationUpdates(locationRequest).collect()
            }
        }
    }

    @Test
    fun `getLocationUpdates cancel`() {
        val cancellationTokenSource = CancellationTokenSource()

        val requestTaskCompletionSource = TaskCompletionSource<Void>(cancellationTokenSource.token)
        val locationRequest: LocationRequest = mockk()
        every {
            locationProvider.requestLocationUpdates(locationRequest, any(), any())
        } returns requestTaskCompletionSource.task
        every { locationProvider.removeLocationUpdates(any<LocationCallback>()) } returns mockk()

        val deferred = testCoroutineScope.async {
            try {
                coLocation.getLocationUpdates(locationRequest).collect()
                null
            } catch (e: CancellationException) {
                e
            }
        }

        cancellationTokenSource.cancel()

        testCoroutineScope.runBlockingTest {
            val resultException = deferred.await()

            assertNotNull(resultException)
        }
    }

    @Test
    fun `checkLocationSettings satisfied`() = testCoroutineScope.runBlockingTest {
        val locationSettingsRequest: LocationSettingsRequest = mockk()
        testTaskSuccess(
            createTask = { settings.checkLocationSettings(locationSettingsRequest) },
            taskResult = mockk(),
            expectedResult = CoLocation.SettingsResult.Satisfied,
            coLocationCall = { coLocation.checkLocationSettings(locationSettingsRequest) }
        )
    }

    @Test
    fun `checkLocationSettings resolvable`() {
        val locationSettingsRequest: LocationSettingsRequest = mockk()
        val errorTaskCompletionSource = TaskCompletionSource<LocationSettingsResponse>()
        errorTaskCompletionSource.setException(mockk<ResolvableApiException>())

        every {
            settings.checkLocationSettings(locationSettingsRequest)
        } returns errorTaskCompletionSource.task
        testCoroutineScope.runBlockingTest {
            val result = coLocation.checkLocationSettings(locationSettingsRequest)
            assertTrue(result is CoLocation.SettingsResult.Resolvable)
        }
    }

    @Test
    fun `checkLocationSettings not resolvable`() {
        val locationSettingsRequest: LocationSettingsRequest = mockk()
        val errorTaskCompletionSource = TaskCompletionSource<LocationSettingsResponse>()
        errorTaskCompletionSource.setException(TestException())

        every {
            settings.checkLocationSettings(locationSettingsRequest)
        } returns errorTaskCompletionSource.task
        testCoroutineScope.runBlockingTest {
            val result = coLocation.checkLocationSettings(locationSettingsRequest)
            assertTrue(result is CoLocation.SettingsResult.NotResolvable)
        }
    }

    @Test
    fun `checkLocationSettings canceled`() = testCoroutineScope.runBlockingTest {
        val locationSettingsRequest: LocationSettingsRequest = mockk()
        assertThrows<CancellationException> {
            testTaskCancel(
                createTask = { settings.checkLocationSettings(locationSettingsRequest) },
                coLocationCall = { coLocation.checkLocationSettings(locationSettingsRequest) }
            )
        }
    }

    @Test
    fun `checkLocationSettings locationRequest success`() = testCoroutineScope.runBlockingTest {
        val locationRequest: LocationRequest = mockk()
        testTaskSuccess(
            createTask = { settings.checkLocationSettings(any()) },
            taskResult = mockk(),
            expectedResult = CoLocation.SettingsResult.Satisfied,
            coLocationCall = { coLocation.checkLocationSettings(locationRequest) }
        )
    }
}

private suspend fun <T, R> testTaskWithCancelThrows(
    createTask: MockKMatcherScope.() -> Task<T>,
    taskResult: T,
    expectedResult: R,
    expectedErrorException: Exception,
    coLocationCall: suspend () -> R
) {
    testTaskSuccess(createTask, taskResult, expectedResult, coLocationCall)
    testTaskFailure(createTask, expectedErrorException, coLocationCall)
    assertThrows<CancellationException> { testTaskCancel(createTask, coLocationCall) }
}

private suspend fun <T, R> testTaskCancel(
    createTask: MockKMatcherScope.() -> Task<T>,
    coLocationCall: suspend () -> R
): R? {
    val cancellationTokenSource = CancellationTokenSource()
    val cancelTaskCompletionSource = TaskCompletionSource<T>(cancellationTokenSource.token)
    every { createTask() } returns cancelTaskCompletionSource.task

    cancellationTokenSource.cancel()

    return coLocationCall()
}

private suspend fun <T, R> testTaskSuccess(
    createTask: MockKMatcherScope.() -> Task<T>,
    taskResult: T,
    expectedResult: R,
    coLocationCall: suspend () -> R
) {
    val successTaskCompletionSource = TaskCompletionSource<T>()
    every { createTask() } returns successTaskCompletionSource.task

    successTaskCompletionSource.setResult(taskResult)

    assertEquals(expectedResult, coLocationCall())
}

private suspend fun <T, R> testTaskFailure(
    createTask: MockKMatcherScope.() -> Task<T>,
    expectedErrorException: Exception,
    coLocationCall: suspend () -> R
) {
    val errorTaskCompletionSource = TaskCompletionSource<T>()
    every { createTask() } returns errorTaskCompletionSource.task

    errorTaskCompletionSource.setException(expectedErrorException)

    assertThrows(expectedErrorException::class.java) {
        coLocationCall()
    }
}

class TestException : Exception()

private inline fun <T : Throwable, R> assertThrows(clazz: Class<T>, block: () -> R) {
    try {
        block()
        fail("block did not throw expected exception")
    } catch (throwable: Throwable) {
        assertEquals(clazz, throwable::class.java)
    }
}

private inline fun <reified T : Throwable> assertThrows(block: () -> Any?) =
    assertThrows(T::class.java, block)
