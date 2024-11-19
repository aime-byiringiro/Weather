package edu.tcu.aimebyiringiro.weather


import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.CancellationSignal
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
import edu.tcu.aimebyiringiro.weather.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
Update location
Launch google maps
Clear the cache
 */

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var view: View
    private lateinit var fusedLocationClient: FusedLocationProviderClient

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

    private fun displayUpdateFailed() {
        TODO("Not yet implemented")
    }

    private fun updateWeather(location: Location) {

    }

}