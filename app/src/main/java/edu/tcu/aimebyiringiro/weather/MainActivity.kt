package edu.tcu.aimebyiringiro.weather

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.icu.util.TimeZone
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import edu.tcu.aimebyiringiro.weather.databinding.ActivityMainBinding
import edu.tcu.aimebyiringiro.weather.m.Place
import edu.tcu.aimebyiringiro.weather.model.WeatherResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var view: View
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var weatherService: WeatherService
    private lateinit var geoService: GeoService
    private lateinit var weatherResponse: WeatherResponse
    private var geoResponse: List<Place> = emptyList()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                updateLocationAndWeatherRepeatedly()
                binding.connectionTv.text = getString(R.string.connecting)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.location_permission_granted),
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                binding.connectionTv.text = getString(R.string.connecting)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.location_permission_denied),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

    private var lastUpdateTimeMillis: Long = 0L
    private var cancellationTokenSource: CancellationTokenSource? = null
    private var weatherServiceCall: Call<WeatherResponse>? = null
    private var geoServiceCall: Call<List<Place>>? = null
    private var delayJob: Job? = null
    private var savedWeatherData: WeatherResponse? = null
    private var offlineMinutes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        view = binding.root
        setContentView(view)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val retrofit = Retrofit.Builder()
            .baseUrl(getString(R.string.base_url))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        weatherService = retrofit.create(WeatherService::class.java)
        geoService = retrofit.create(GeoService::class.java)

        // Load previously saved weather data, place and last update time
        lastUpdateTimeMillis = loadLastUpdateTime()
        savedWeatherData = loadWeatherDataFromPrefs()
        val savedPlaces = loadPlaceFromPrefs()
        if (savedPlaces != null) {
            geoResponse = savedPlaces
        }

        if (savedWeatherData != null) {
            weatherResponse = savedWeatherData!!
            displayWeather()
            // If we have place data, display it too
            if (geoResponse.isNotEmpty()) {
                displayPlace()
            }

            if (lastUpdateTimeMillis == 0L) {
                binding.connectionTv.text = getString(R.string.updated_just_now)
            } else {
                val elapsedTimeMillis = System.currentTimeMillis() - lastUpdateTimeMillis
                val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis)
                binding.connectionTv.text = getString(R.string.updated_minutes_ago, elapsedMinutes)
            }
        }

        requestLocationPermission()
    }

    override fun onDestroy() {
        cancelRequest()
        delayJob?.cancel()
        super.onDestroy()
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                updateLocationAndWeatherRepeatedly()
                binding.connectionTv.text = getString(R.string.location_permission_granted)
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                binding.connectionTv.text = getString(R.string.location_permission_denied)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.location_permission_required),
                    Snackbar.LENGTH_INDEFINITE
                ).setAction("OK") {
                    requestPermissionLauncher.launch(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                }.show()
            }

            else -> {
                requestPermissionLauncher.launch(
                    Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    private fun cancelRequest() {
        cancellationTokenSource?.cancel()
        weatherServiceCall?.cancel()
        geoServiceCall?.cancel()
    }

    private fun updateLocationAndWeather() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) -> {
                cancellationTokenSource = CancellationTokenSource()
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource!!.token
                ).addOnSuccessListener {
                    if (it != null) {
                        updateWeather(it)
                    } else {
                        displayUpdateFailed()
                    }
                }
            }
        }
    }

    private fun updateWeather(location: Location) {
        weatherServiceCall = weatherService.getWeather(
            lat = location.latitude,
            lon = location.longitude,
            appid = getString(R.string.appid),
            units = "imperial"
        )
        weatherServiceCall?.enqueue(object : Callback<WeatherResponse> {
            override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                val weatherResponseNullable = response.body()
                if (weatherResponseNullable != null) {
                    weatherResponse = weatherResponseNullable
                    savedWeatherData = weatherResponseNullable
                    saveWeatherDataToPrefs(weatherResponseNullable)

                    lastUpdateTimeMillis = System.currentTimeMillis()
                    saveLastUpdateTime(lastUpdateTimeMillis)

                    updatePlace(location)
                    displayWeather()
                }
            }

            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                displayUpdateFailed()
            }
        })
    }

    private fun displayWeather() {
        val weatherCode = weatherResponse.weather[0].icon
        val resId = weatherCondition(weatherCode)
        binding.conditionIv.setImageResource(resId)

        val description = weatherResponse.weather[0].description
            .split(" ")
            .joinToString(" ") {
                it.replaceFirstChar { char -> char.uppercaseChar() }
            }
        binding.descriptionTv.text = getString(
            R.string.description,
            description,
            weatherResponse.main.temp_max,
            weatherResponse.main.temp_min
        )

        val sunriseUtcInMs =
            (weatherResponse.sys.sunrise + weatherResponse.timezone) * 1000L - TimeZone.getDefault().rawOffset
        val sunrise = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sunriseUtcInMs))

        val sunsetUtcInMs =
            (weatherResponse.sys.sunset + weatherResponse.timezone) * 1000L - TimeZone.getDefault().rawOffset
        val sunset = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sunsetUtcInMs))

        binding.temperatureTv.text = getString(
            R.string.temperature,
            weatherResponse.main.temp
        )
        binding.sunTitleTv.text = getString(R.string.sun_title)

        binding.sunDataTv.text = getString(
            R.string.sun_data,
            sunrise,
            sunset
        )

        binding.windTitleTv.text = getString(R.string.wind_title)

        binding.windDataTv.text = getString(
            R.string.wind_data,
            weatherResponse.wind.speed,
            weatherResponse.wind.deg,
            weatherResponse.wind.gust
        )

        binding.precipitationTitleTv.text = getString(R.string.precipitation_title)

        val rainVolume = weatherResponse.rain?.one_h ?: weatherResponse.rain?.three_h ?: 0.0
        val snowVolume = weatherResponse.snow?.one_h ?: weatherResponse.snow?.three_h ?: 0.0

        if (snowVolume > 0) {
            binding.precipitationDataTv.text = getString(
                R.string.snow_data,
                weatherResponse.snow?.one_h ?: 0.0
            )
        } else if (rainVolume > 0) {
            binding.precipitationDataTv.text = getString(
                R.string.rain_data,
                weatherResponse.rain?.one_h ?: 0.0
            )
        } else {
            binding.precipitationDataTv.text = getString(
                R.string.precipitation_data,
                weatherResponse.main.humidity,
                weatherResponse.clouds.all,
            )
        }

        binding.otherTitleTv.text = getString(R.string.other_title)
        val visibilityMiles = weatherResponse.visibility / 1609.34 // Convert meters to miles
        val pressureInHg = weatherResponse.main.pressure / 33.864 // Convert hPa to inHg
        binding.otherDataTv.text = getString(
            R.string.other_data,
            weatherResponse.main.feels_like,
            visibilityMiles,
            pressureInHg
        )
    }

    private fun updatePlace(location: Location) {
        geoServiceCall = geoService.getPlace(
            location.latitude,
            location.longitude,
            getString(R.string.appid)
        )
        geoServiceCall?.enqueue(object : Callback<List<Place>> {
            override fun onResponse(call: Call<List<Place>>, response: Response<List<Place>>) {
                if (response.isSuccessful) {
                    val responseList = response.body()
                    if (!responseList.isNullOrEmpty()) {
                        geoResponse = responseList
                        savePlaceToPrefs(geoResponse) // Save the place info
                        displayPlace()
                    } else {
                        displayUpdateFailed()
                    }
                } else {
                    displayUpdateFailed()
                }
            }

            override fun onFailure(call: Call<List<Place>>, t: Throwable) {
                displayUpdateFailed()
            }
        })
    }

    private fun displayUpdateFailed() {
        println("failed")
    }

    private fun displayPlace() {
        val place = geoResponse.firstOrNull()
        if (place != null) {
            val locationName = if (place.state.isNullOrEmpty()) {
                getString(R.string.place, place.name, place.country)
            } else {
                getString(R.string.place, place.name, place.state)
            }
            binding.placeTv.text = locationName
        } else {
            displayUpdateFailed()
        }
    }

    private fun weatherCondition(weatherCode: String): Int {
        return when (weatherCode) {
            "01d" -> R.drawable.ic_01d
            "01n" -> R.drawable.ic_01n
            "02n" -> R.drawable.ic_02n
            "03d", "03n" -> R.drawable.ic_03
            "04d", "04n" -> R.drawable.ic_04
            "09d", "09n" -> R.drawable.ic_09
            "10d" -> R.drawable.ic_10d
            "10n" -> R.drawable.ic_10n
            "11d", "11n" -> R.drawable.ic_11
            "13d", "13n" -> R.drawable.ic_13
            "50d", "50n" -> R.drawable.ic_50
            else -> R.drawable.ic_01d
        }
    }

    private fun updateLocationAndWeatherRepeatedly() {
        val dialog = Dialog(this).apply {
            setContentView(R.layout.in_progress)
            setCancelable(false)
        }

        delayJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                dialog.show()
                delay(2000)
                dialog.dismiss()

                var wasOffline = false
                try {
                    if (!isAirplaneModeOn(this@MainActivity)) {
                        // Reset because we are now online
                        offlineMinutes = 0
                        wasOffline = false

                        binding.connectionTv.text = getString(R.string.updating)
                        updateLocationAndWeather()
                        binding.connectionTv.text = getString(R.string.updated_just_now)

                    } else {
                        wasOffline = true
                        if (savedWeatherData != null) {
                            weatherResponse = savedWeatherData!!
                            displayWeather()

                            // Load saved places if any
                            val savedPlaces = loadPlaceFromPrefs()
                            if (savedPlaces != null) {
                                geoResponse = savedPlaces
                                displayPlace()
                            }

                            // Do NOT increment offlineMinutes here.
                            // Show just now on the first offline cycle (when offlineMinutes == 0).
                            if (lastUpdateTimeMillis == 0L) {
                                binding.connectionTv.text = getString(R.string.updated_just_now)
                            } else {
                                if (offlineMinutes == 0) {
                                    binding.connectionTv.text = getString(R.string.updated_just_now)
                                } else {
                                    binding.connectionTv.text = getString(R.string.updated_minutes_ago, offlineMinutes)
                                }
                            }

                        } else {
                            binding.connectionTv.text = getString(R.string.update_failed_no_data)
                        }
                    }

                } catch (e: Exception) {
                     binding.connectionTv.text = getString(R.string.failed_to_connect)
//                    // In case of an exception, handle it similarly to offline mode
//                    var offlineScenario = true
//                    if (savedWeatherData != null) {
//                        weatherResponse = savedWeatherData!!
//                        displayWeather()
//
//                        val savedPlaces = loadPlaceFromPrefs()
//                        if (savedPlaces != null) {
//                            geoResponse = savedPlaces
//                            displayPlace()
//                        }
//
//                        // Do NOT increment offlineMinutes here.
//                        // Still show just now if offlineMinutes == 0
//                        if (lastUpdateTimeMillis == 0L) {
//                            binding.connectionTv.text = getString(R.string.updated_just_now)
//                        } else {
//                            if (offlineMinutes == 0) {
//                                binding.connectionTv.text = getString(R.string.updated_just_now)
//                            } else {
//                                binding.connectionTv.text = getString(R.string.updated_minutes_ago, offlineMinutes)
//                            }
//                        }
//
//                    } else {
//                        binding.connectionTv.text = getString(R.string.update_failed_no_data)
//                        offlineScenario = false
//                    }
//                    wasOffline = offlineScenario
                } finally {
                    dialog.dismiss()
                }

                // Wait 15 seconds before the next update cycle
                delay(15000)

                // After the delay, if we were offline during this cycle, increment offlineMinutes
                // If we were online, reset it to 0.
                if (wasOffline) {
                    offlineMinutes += 1
                } else {
                    offlineMinutes = 0
                }
            }
        }
    }


    private fun isAirplaneModeOn(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }

    // Utility methods for saving/loading WeatherResponse, Place list, and last update time to/from SharedPreferences

    private fun saveWeatherDataToPrefs(weather: WeatherResponse) {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val weatherJson = Gson().toJson(weather)
        editor.putString("last_weather", weatherJson)
        editor.apply()
    }

    private fun loadWeatherDataFromPrefs(): WeatherResponse? {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val weatherJson = prefs.getString("last_weather", null) ?: return null
        return Gson().fromJson(weatherJson, object : TypeToken<WeatherResponse>() {}.type)
    }

    private fun savePlaceToPrefs(placeList: List<Place>) {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val placeJson = Gson().toJson(placeList)
        editor.putString("last_place", placeJson)
        editor.apply()
    }

    private fun loadPlaceFromPrefs(): List<Place>? {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        val placeJson = prefs.getString("last_place", null) ?: return null
        return Gson().fromJson(placeJson, object : TypeToken<List<Place>>() {}.type)
    }

    private fun saveLastUpdateTime(time: Long) {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_update_time", time).apply()
    }

    private fun loadLastUpdateTime(): Long {
        val prefs = getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("last_update_time", 0L)
    }
}
