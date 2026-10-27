package site.chirudev.pdfutils.core.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import site.chirudev.pdfutils.BuildConfig
import site.chirudev.pdfutils.core.data.AppVisibility
import site.chirudev.pdfutils.core.data.ContentFileSource
import site.chirudev.pdfutils.core.data.FileSource
import site.chirudev.pdfutils.core.data.Foreground
import site.chirudev.pdfutils.core.data.KeystoreSecureStore
import site.chirudev.pdfutils.core.data.SecureStore
import site.chirudev.pdfutils.core.network.ApiService
import site.chirudev.pdfutils.core.network.AuthInterceptor
import site.chirudev.pdfutils.core.network.AuthRefreshService
import site.chirudev.pdfutils.core.network.TokenAuthenticator
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier annotation class BaseClient
@Qualifier annotation class DownloadClient

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun secureStore(impl: KeystoreSecureStore): SecureStore
    @Binds abstract fun fileSource(impl: ContentFileSource): FileSource
    @Binds abstract fun foreground(impl: AppVisibility): Foreground
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** Shared connection pool and dispatcher; the other clients derive from it. */
    @Provides @Singleton @BaseClient
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Pre-signed download links carry their own auth, so no identity headers are added. */
    @Provides @Singleton @DownloadClient
    fun downloadClient(@BaseClient base: OkHttpClient): OkHttpClient = base

    @Provides @Singleton
    fun refreshService(@BaseClient base: OkHttpClient, auth: Provider<AuthInterceptor>): AuthRefreshService =
        retrofit(base.newBuilder().addInterceptor { auth.get().intercept(it) }.build()).create(AuthRefreshService::class.java)

    @Provides @Singleton
    fun apiService(@BaseClient base: OkHttpClient, auth: AuthInterceptor, authenticator: TokenAuthenticator): ApiService =
        retrofit(base.newBuilder().addInterceptor(auth).authenticator(authenticator).build()).create(ApiService::class.java)

    fun retrofit(client: OkHttpClient, baseUrl: String = BuildConfig.API_BASE_URL): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
}
