package com.example.bt1.service

import com.example.bt1.data.TaskItem
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header

interface ApiService {
    @GET("mock/teach/tasks")
    suspend fun getTasks(
        @Header("Authorization") authHeader: String? = null
    ): List<TaskItem>

    companion object {
        // Base URL corresponding to the mock API in slide: https://share.tryapps.store/mock/teach/tasks
        private const val BASE_URL = "https://share.tryapps.store/"

        val instance: ApiService by lazy {
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
