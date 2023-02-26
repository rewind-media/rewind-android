package io.media.rewind

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.media.rewind.model.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

sealed interface StreamState {
    object Canceled : StreamState
    object Pending : StreamState
    object Available : StreamState
}

sealed interface ViewState {
    object Browser : ViewState
    class EpisodePlayer(val episodeInfo: EpisodeInfo) : ViewState
}

sealed interface BrowserState {
    class EpisodeState(val episodeInfo: EpisodeInfo) : BrowserState
    class SeasonState(val seasonInfo: SeasonInfo) : BrowserState
    class ShowState(val showInfo: ShowInfo) : BrowserState
    class LibraryState(val library: Library) : BrowserState
    object HomeState : BrowserState
}

sealed interface LoginState {
    object LoggedOut : LoginState
    object PendingLogin : LoginState
    object LoggedIn : LoginState
}

class MainViewModel(
    private val serverUrl: ServerUrl,
    val cookiesStorage: CookiesStorage = AcceptAllCookiesStorage(),
    val client: RewindClient = RewindClient(serverUrl.value, httpClientConfig = {
        it.install(ContentNegotiation) {
            json()
        }
        it.install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.v("RewindClient", message)
                }
            }
            level = LogLevel.ALL
        }
        it.install(HttpCookies) {
            this.storage = cookiesStorage
        }
    })
) : ViewModel() {

    val loginState: MutableLiveData<LoginState> = MutableLiveData(LoginState.LoggedOut)

    fun login(req: LoginRequest) {
        loginState.value = LoginState.PendingLogin
        Log.i("Login", "Logging in")
        this.viewModelScope.launch {
            loginState.value = when (client.login(req).status) {
                HttpStatusCode.OK.value -> {
                    Log.i("Login", "Logged In!")
                    setBrowserState(BrowserState.HomeState)
                    LoginState.LoggedIn
                }
                else -> LoginState.LoggedOut
            }
        }
    }

    val browserState: MutableLiveData<BrowserState> = MutableLiveData(BrowserState.HomeState)
    fun setBrowserState(browserState: BrowserState) {
        this.browserState.value = browserState
        when (browserState) {
            is BrowserState.HomeState -> loadLibraries()
            is BrowserState.LibraryState -> loadShows(browserState.library.name)
            is BrowserState.ShowState -> loadSeasons(browserState.showInfo.id)
            is BrowserState.SeasonState -> loadEpisodes(browserState.seasonInfo.id)
            is BrowserState.EpisodeState -> null
        }
    }

    val viewState: MutableLiveData<ViewState> = MutableLiveData(ViewState.Browser)
    fun setViewState(viewState: ViewState) {
        this.viewState.value = viewState
        when (viewState) {
            is ViewState.EpisodePlayer -> createStream(viewState.episodeInfo)
            is ViewState.Browser -> setBrowserState(browserState.value ?: BrowserState.HomeState)
        }
    }

    val streamStatus: MutableLiveData<StreamStatus> = MutableLiveData(StreamStatus.canceled)
    val streamProps: MutableLiveData<HlsStreamProps> = MutableLiveData()
    fun createStream(episodeInfo: EpisodeInfo) {
        this.viewModelScope.launch {
            val progress = client.getUserProgress(episodeInfo.id).body()
            streamProps.value = client.createStream(
                CreateStreamRequest(
                    episodeInfo.libraryId,
                    episodeInfo.id,
                    startOffset = progress.duration
                )
            ).body()!! // TODO deal with nullability?
            do {
                streamProps.value?.let {
                    streamStatus.value =
                        client.heartbeatStream(streamId = it.id).body()!! // TODO deal with nullability?
                    delay(
                        when (streamStatus.value) {
                            StreamStatus.available -> 15.seconds
                            StreamStatus.pending -> 500.milliseconds
                            else -> 0.seconds
                        }.toJavaDuration()
                    )
                }
            } while ((streamStatus.value == StreamStatus.available || streamStatus.value == StreamStatus.pending) &&
                viewState.value?.let {
                    when (it) {
                        is ViewState.EpisodePlayer -> it.episodeInfo == episodeInfo
                        else -> false
                    }
                } == true
            )
        }
    }


    val libraries = MutableLiveData<List<Library>>(emptyList<Library>())
    fun loadLibraries() {
        Log.i("LibraryLoader", "Lodaing Libs")
        this.viewModelScope.launch {
            libraries.value = client.listLibraries().body()!! // TODO deal with nullability?
            Log.i("LibraryLoader", "Loaded ${libraries.value}")

        }
    }

    val shows = MutableLiveData<List<ShowInfo>>(emptyList<ShowInfo>())
    fun loadShows(libraryName: String) {
        this.viewModelScope.launch {
            shows.value = client.listShows(libraryName).body()!! // TODO deal with nullability?
        }
    }

    val seasons = MutableLiveData<List<SeasonInfo>>(emptyList<SeasonInfo>())
    fun loadSeasons(showId: String) {
        this.viewModelScope.launch {
            seasons.value = client.listSeasons(showId).body()!! // TODO deal with nullability?
        }
    }

    val episodes = MutableLiveData<List<EpisodeInfo>>(emptyList<EpisodeInfo>())
    fun loadEpisodes(seasonId: String) {
        this.viewModelScope.launch {
            episodes.value = client.listEpisodes(seasonId).body()!! // TODO deal with nullability?
        }
    }

    companion object {
        class MainViewModelFactory(private val serverUrl: ServerUrl) :
            ViewModelProvider.NewInstanceFactory() {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(serverUrl) as T
        }
    }
}