package edu.tcu.aimebyiringiro.weather

import edu.tcu.aimebyiringiro.weather.m.Place
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface GeoService {

    @GET("geo/1.0/reverse")
    fun getPlace(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("limit") limit: Int = 1, // Limit to 1 result
        @Query("appid") appid: String
    ): Call<List<Place>>
}
