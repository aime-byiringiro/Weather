package edu.tcu.aimebyiringiro.weather


import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.icu.util.TimeZone
import android.location.Location
import android.os.Bundle
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
import edu.tcu.aimebyiringiro.weather.model.WeatherResponse
import edu.tcu.aimebyiringiro.weather.databinding.ActivityMainBinding
import edu.tcu.aimebyiringiro.weather.m.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit


/**
 * The main activity of the application.
 *
 * This activity displays the weather for the current location. It also updates
 * the weather every 10 seconds.
 *
 * @author Aime Byiringiro
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var progressBar: View
    private lateinit var view: View
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var weatherService: WeatherService
    private lateinit var geoService: GeoService
    private lateinit var weatherResponse: WeatherResponse
    private lateinit var geoResponse: List<Place>

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                updateLocationAndWeatherRepeatedly()
                binding.connectionTv.text = getString(R.string.location_permission_granted)
            } else {
                binding.connectionTv.text = getString(R.string.location_permission_denied)
                Snackbar.make(
                    findViewById(android.R.id.content),
                    getString(R.string.location_permission_denied),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }

    private var lastUpdateTimeMillis: Long = 0L // Tracks the last update time



    private var cancellationTokenSource: CancellationTokenSource? = null

    private var weatherServiceCall: Call<WeatherResponse>? = null

    private var geoServiceCall: Call<List<Place>>? = null

    private var updateJob: Job? = null
    private var delayJob: Job? = null
    /**
     * Requests the location permission and updates the location and weather
     * every 10 seconds. It also shows the permission status on the UI.
     */
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
        requestLocationPermission()
    }
    /**
     * Called when the activity is being destroyed.
     * This function cancels any ongoing location and weather update requests
     * to release resources and prevent memory leaks.
     * It also cancels any active delay jobs and calls the superclass's onDestroy method.
     */
    override fun onDestroy() {
        cancelRequest()
        delayJob?.cancel()
        super.onDestroy()
    }
    /**
     * Requests the location permission, updates the location and weather
     * every 10 seconds. It also shows the permission status on the UI.
     * The permission is requested using the registerForActivityResult API,
     * which allows the permission status to be saved and restored across
     * configuration changes. The location and weather is updated every 10
     * seconds using a foreground service. The permission status is
     * displayed on the UI using the connectionTv TextView.
     */

    private fun requestLocationPermission() {

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                //The permission is already granted.
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
               // Generate teh Snack bar to explain
                        // If Ok is clicked, show the the prompt/launcher

                        //this is not done yet, only when the user clicks ok
            }
            else -> {
                requestPermissionLauncher.launch(
                    Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

    }
    /**
     * Cancels the permission request and any active network requests.
     * This is called in onDestroy to release resources and prevent memory leaks.
     */
    private fun cancelRequest() {
       cancellationTokenSource?.cancel()
        weatherServiceCall?.cancel()
        geoServiceCall?.cancel()
        updateJob?.cancel()
    }
/**
 * Continuously updates the location and weather information every 10 seconds.
 * It shows a non-cancelable progress dialog while updating and updates the
 * connection status text view on the UI.
 */




    /**
     * Launches a repeating job to update the location and weather information.
     * It shows a non-cancelable progress dialog while updating and updates the
     * connection status text view on the UI.
     */
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


    /**
     * Starts a repeating job to update the location and weather information in the background.
     * It will cancel the previous job before starting a new one.
     */
    private fun updateWeather(location: Location) {
        weatherServiceCall = weatherService.getWeather(
            lat = location.latitude,
            lon = location.longitude,
            appid = getString(R.string.appid),
            units = "imperial"
        )
        weatherServiceCall?.enqueue(
            object : Callback<WeatherResponse> {
                override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                    val weatherResponseNullable = response.body()
                    if (weatherResponseNullable != null) {
                        weatherResponse = weatherResponseNullable
                        updatePlace(location)
                        displayWeather()

                    }
                }

                override fun onFailure(p0: Call<WeatherResponse>, p1: Throwable) {
                    displayUpdateFailed()
                }

            }
        )
        lastUpdateTimeMillis = System.currentTimeMillis()
    }
    /**
     * Retrieves the weather information using the latitude and longitude of the location.
     * It launches a network request using the Retrofit API and saves the response to the
     * weatherResponse variable. It also calls the updatePlace function to update the place
     * information and displays the weather information using the displayWeather function.
     */

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
                weatherResponse.snow?.one_h ?: 0.0  // Snow volume for last 1 hour

            )
        } else if (rainVolume >0 ) {
            binding.precipitationDataTv.text = getString(
                R.string.rain_data,
                weatherResponse.rain?.one_h ?: 0.0
            )


        }
        else {
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

    /**
     * Updates the user interface with the given [WeatherResponse].
     */
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
                        geoResponse = responseList // Save the result for use in displayPlace()
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
        showLastUpdatedMessage()
        println("failed")
    }

    /**
    * Updates the display with the name of the place.
    * This function retrieves the first place from the geoResponse list
    * and sets the text of the placeTv TextView with the location name.
    * If the state is available, it is displayed; otherwise, the country is used.
    * If no place is found, it calls displayUpdateFailed to handle the failure case.
    */
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


    /**
    * Returns the drawable resource id corresponding to the given weather code.
    * The weather code represents different weather conditions, such as clear sky,
    * clouds, rain, snow, etc., and is used to select the appropriate weather icon.
    *
    * @param weatherCode The weather code string representing the current weather condition.
    * @return The drawable resource id for the corresponding weather icon.
    */

    private fun  weatherCondition(weatherCode: String): Int {
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

    /**
     * Continuously updates the location and weather information every 10 seconds.
     * If the app is offline, it will display how long ago the last update occurred.
     */
    private fun updateLocationAndWeatherRepeatedly() {
        val dialog = Dialog(this).apply {
            setContentView(R.layout.in_progress)
            setCancelable(false)
        }

        delayJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                try {
                    dialog.show()
                    binding.connectionTv.text = getString(R.string.updating)

                    // Call the existing updateLocationAndWeather function
                    updateLocationAndWeather()

                    // Success listener in the existing function will handle updates
                    // Use a delay to wait for it to complete and check the result
                    delay(2000) // Wait for the update process to potentially finish

                    // If successful, the last update time should now be updated
                    if (lastUpdateTimeMillis > 0) {
                        binding.connectionTv.text = getString(R.string.updated_just_now)
                    } else {
                        // Handle no update
                        showLastUpdatedMessage()
                    }
                } catch (e: Exception) {
                    showLastUpdatedMessage() // Show "Updated X minutes ago" on failure
                } finally {
                    dialog.dismiss()
                }

                delay(15000) // Wait 15 seconds for the next update
            }
        }
    }

    /**
    * Displays the "Updated X minutes ago" message based on the last update time.
    */
    private fun showLastUpdatedMessage() {
        if (lastUpdateTimeMillis > 0) {
            val elapsedTimeMillis = System.currentTimeMillis() - lastUpdateTimeMillis
            val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsedTimeMillis)

            binding.connectionTv.text = when {
                elapsedMinutes < 1 -> getString(R.string.updated_just_now)
                elapsedMinutes == 1L -> getString(R.string.updated_one_minute_ago)
                else -> getString(R.string.updated_minutes_ago, elapsedMinutes)
            }
        } else {
            binding.connectionTv.text = getString(R.string.update_failed_no_data)
        }
    }





}