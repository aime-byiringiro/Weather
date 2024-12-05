package edu.tcu.aimebyiringiro.weather


import android.Manifest
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
import edu.tcu.aimebyiringiro.weather.model.WeatherResponse
import edu.tcu.aimebyiringiro.weather.databinding.ActivityMainBinding
import edu.tcu.aimebyiringiro.weather.m.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.DateFormat
import java.util.Date


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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
            } else {

            }
        }

    private var cancellationTokenSource: CancellationTokenSource? = null

    private var weatherServiceCall: Call<WeatherResponse>? = null

    private var geoServiceCall: Call<List<Place>>? = null

    private var updateJob: Job? = null
    private var delayJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        view = binding.root // or view = binding.main
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
    override fun onDestroy() {
        cancelRequest()
        delayJob?.cancel()
        super.onDestroy()
//        cancellationTokenSource?.cancel()
    }

    private fun requestLocationPermission() {

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                //The permission is already granted.
                updateLocationAndWeatherRepeatedly()

            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                //Generate teh Snack bar to explain
                    // If Ok is clicked, show the the prompt/launcher

                    //this is not done yet, only when the user clicks ok
                    requestPermissionLauncher.launch(
                        Manifest.permission.ACCESS_COARSE_LOCATION)
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
        updateJob?.cancel()
    }
    private fun updateLocationAndWeatherRepeatedly() {

      delayJob =  lifecycleScope.launch(Dispatchers.IO) {


            while (true) {
//                withContext(Dispatchers.Main) { updateLocationAndWeather()}
                updateJob= launch(Dispatchers.Main) { updateLocationAndWeather() }
                delay(15000)
                cancelRequest()
            }

        }
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
    }

    private fun displayWeather() {


        val weatherCode = weatherResponse.weather[0].icon
        val resId = weatherCondition(weatherCode) // This should return the resource ID (e.g., R.drawable.ic_01d)
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

        // Convert sunrise time to local
        val sunriseUtcInMs =
            (weatherResponse.sys.sunrise + weatherResponse.timezone) * 1000L - TimeZone.getDefault().rawOffset
        val sunrise = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sunriseUtcInMs))

        // Convert sunset time to local
        val sunsetUtcInMs =
            (weatherResponse.sys.sunset + weatherResponse.timezone) * 1000L - TimeZone.getDefault().rawOffset
        val sunset = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sunsetUtcInMs))


        // Display temperature
        binding.temperatureTv.text = getString(
            R.string.temperature,
            weatherResponse.main.temp
        )

        // Display sun title
        binding.sunTitleTv.text = getString(R.string.sun_title)

        // Display sun data (sunrise and sunset times)
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

        // Display precipitation title
        binding.precipitationTitleTv.text = getString(R.string.precipitation_title)

        // Display precipitation data (humidity and cloudiness)
        binding.precipitationDataTv.text = getString(
            R.string.precipitation_data,
            weatherResponse.main.humidity,
            weatherResponse.clouds.all
        )

        // Display other title
        binding.otherTitleTv.text = getString(R.string.other_title)

        // Display other data (feels like, visibility, and pressure)
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
        println("failed")
    }

    private fun displayPlace() {
        geoResponse?.let { places ->
            val place = places[0] // Always use the first place from the response

            // Format the location string based on availability of state
            val locationName = if (place.state.isNullOrEmpty()) {
                getString(R.string.place, place.name, place.country) // Use city and country
            } else {
                getString(R.string.place, place.name, place.state) // Use city and state
            }

            // Set the formatted string to the TextView
            binding.placeTv.text = locationName
        } ?: run {
            // If geoResponse is null, show a fallback message
            displayUpdateFailed()
        }
    }

    private fun  weatherCondition(weatherCode: String): Int {
        return when (weatherCode) {
            "01d" -> R.drawable.ic_01d     // Clear sky (day)
            "01n" -> R.drawable.ic_01n    // Clear sky (night)
            "02n" -> R.drawable.ic_02n  // Few clouds (day)
            "03d", "03n" -> R.drawable.ic_03// Scattered clouds
            "04d", "04n" -> R.drawable.ic_04   // Broken clouds
            "09d", "09n" -> R.drawable.ic_09     // Shower rain
            "10d" -> R.drawable.ic_10d              // Rain (day)
            "10n" -> R.drawable.ic_10n             // Rain (night)
            "11d", "11n" -> R.drawable.ic_11   // Thunderstorm
            "13d", "13n" -> R.drawable.ic_13          // Snow
            "50d", "50n" -> R.drawable.ic_50         // Mist
            else -> R.drawable.ic_01d                // Default or unknown code
        }
    }



}