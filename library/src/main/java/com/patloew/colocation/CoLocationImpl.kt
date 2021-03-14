package com.patloew.colocation

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first

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
internal class CoLocationImpl(private val context: Context) : CoLocation {

    private val locationProvider: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private val settings: SettingsClient by lazy { LocationServices.getSettingsClient(context) }

    override suspend fun flushLocations() {
        locationProvider.flushLocations().await()
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun isLocationAvailable(): Boolean =
        locationProvider.locationAvailability.await().isLocationAvailable

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun getCurrentLocation(priority: Int): Location? {
        val cancellationTokenSource = CancellationTokenSource()

        return try {
            locationProvider.getCurrentLocation(priority, cancellationTokenSource.token).await()
        } catch (cancellationException: CancellationException) {
            cancellationTokenSource.cancel()
            throw cancellationException
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun getLastLocation(): Location? = locationProvider.lastLocation.await()

    @ExperimentalCoroutinesApi
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun getLocationUpdate(locationRequest: LocationRequest): Location =
        getLocationUpdates(locationRequest, Channel.CONFLATED).first()

    @ExperimentalCoroutinesApi
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override fun getLocationUpdates(locationRequest: LocationRequest, capacity: Int): Flow<Location> =
        callbackFlow<Location> {
            val callback = object : LocationCallback() {
                private var counter: Int = 0
                override fun onLocationResult(result: LocationResult) {
                    trySend(result.lastLocation)
                    if (locationRequest.numUpdates == ++counter) close()
                }
            }.let(::ClearableLocationCallback) // Needed since we would have memory leaks otherwise

            try {
                locationProvider.requestLocationUpdates(
                    locationRequest,
                    callback,
                    Looper.getMainLooper()
                ).await()
            } catch (cancellationException: CancellationException) {
                cancel(cancellationException)
            } catch (throwable: Throwable) {
                close(throwable)
            }

            awaitClose {
                locationProvider.removeLocationUpdates(callback)
                callback.clear()
            }
        }.buffer(capacity)

    override suspend fun checkLocationSettings(locationSettingsRequest: LocationSettingsRequest): CoLocation.SettingsResult =
        try {
            settings.checkLocationSettings(locationSettingsRequest).await()
            CoLocation.SettingsResult.Satisfied
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (resolvableApiException: ResolvableApiException) {
            CoLocation.SettingsResult.Resolvable(resolvableApiException)
        } catch (exception: Exception) {
            CoLocation.SettingsResult.NotResolvable(exception)
        }

    override suspend fun checkLocationSettings(locationRequest: LocationRequest): CoLocation.SettingsResult =
        checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build())

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun setMockLocation(location: Location) {
        locationProvider.setMockLocation(location).await()
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    override suspend fun setMockMode(isMockMode: Boolean) {
        locationProvider.setMockMode(isMockMode).await()
    }

}

/** Wraps [callback] so that the reference can be cleared */
private class ClearableLocationCallback(callback: LocationCallback) : LocationCallback() {

    private var callback: LocationCallback? = callback

    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
        callback?.onLocationAvailability(locationAvailability)
    }

    override fun onLocationResult(locationResult: LocationResult) {
        callback?.onLocationResult(locationResult)
    }

    fun clear() {
        callback = null
    }

}
