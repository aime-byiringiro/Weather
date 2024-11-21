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
import kotlinx.coroutines.Dispatchers
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

/*
Update location
Launch google maps
Clear the cache
 */

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var view: View
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var weatherService: WeatherService
    private lateinit var weatherResponse: WeatherResponse
    // Register the permissions callback, which handles the user's response to the
// system permissions dialog. Save the return value, an instance of
// ActivityResultLauncher. You can use either a val, as shown in this snippet,
// or a lateinit var in your onAttach() or onCreate() method.
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
            .build();

        weatherService = retrofit.create(WeatherService::class.java)

        requestLocationPermission()
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

    /*

    If I use withContext, I am going to have updatateLocationwehter and I have to wai and
    and I can continue with the delay

    If use launch, I am going to have updateLocationAndWeatherRepeatedly and I can continue with the delay
    I have a cocurrent routine, I don't have to wait and I can continue with the delay. updateLocationAndWeather and statt with teh dealy

     */
    private fun cancelRequest() {
       cancellationTokenSource?.cancel()
    }
    private fun updateLocationAndWeatherRepeatedly() {

        lifecycleScope.launch(Dispatchers.IO) {

            while (true) {
                withContext(Dispatchers.Main) { updateLocationAndWeather()}
                // launch(Dispatchers.Main) { updateLocationAndWeather() }
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
            appid = getString(R.string.api_key),
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

                    TODO("Not yet implemented")
                }

                override fun onFailure(p0: Call<WeatherResponse>, p1: Throwable) {
                    displayUpdateFailed()
                    TODO("Not yet implemented")
                }

            }
        )
    }

    private fun displayWeather() {
        val description = weatherResponse.weather[0].
        description.split(" ").
        joinToString("") {
            it.replaceFirstChar { char -> char.uppercase() }
        }
        binding.descriptionTv.text =
            getString(R.string.description,
                description,
                weatherResponse.main.temp_max,
                weatherResponse.main.temp_min)

        val utcInMs =(weatherResponse.sys.sunrise + weatherResponse.timezone) * 1000L - TimeZone.getDefault().rawOffset
        val sunrise = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utcInMs))

        /*
        you need to convert in units , read the apis
        Loook at visibility
         */


    }

    private fun updatePlace(location: Location) {
        TODO("Not yet implemented")
    }

    private fun displayUpdateFailed() {
        TODO("Not yet implemented")
    }

    private fun displayPlace() {
       binding.placeTv.text =
           getString(R.string.place, geoResponse[0].name, geoResponse[0].state)
    }

}