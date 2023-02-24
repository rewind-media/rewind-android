package io.media.rewind

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.media.rewind.model.EpisodeInfo
import com.media.rewind.model.LoginRequest
import com.media.rewind.model.StreamStatus
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import io.ktor.util.date.*
import io.media.rewind.MainViewModel.Companion.MainViewModelFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ServerWrapper()
        }
    }

    companion object
}

@Composable
fun ServerWrapper() {
    var mutableServerUrl by remember {
        mutableStateOf<ServerUrl?>(null)
    }
    val serverUrl = mutableServerUrl
    if (serverUrl == null) {
        Server(onSubmit = { mutableServerUrl = it })
    } else {
        LoginWrapper(serverUrl = serverUrl)
    }
}

@Composable
fun LoginWrapper(
    serverUrl: ServerUrl,
    mainViewModel: MainViewModel = viewModel(factory = MainViewModelFactory(serverUrl))
) {
    val loginState by mainViewModel.loginState.observeAsState()
    when (loginState!!) {
        is LoginState.LoggedIn -> PrivateRoot(mainViewModel)
        is LoginState.LoggedOut -> Login(mainViewModel)
        is LoginState.PendingLogin -> CircularProgressIndicator()
    }
}

@Composable
fun PrivateRoot(
    mainViewModel: MainViewModel
) {
    val nullableView by mainViewModel.viewState.observeAsState()
    when (val view = nullableView!!) { // TODO deal with nullable
        is ViewState.Browser -> Browser(mainViewModel)
        is ViewState.EpisodePlayer -> EpisodePlayer(mainViewModel, view.episodeInfo)
    }
    Browser(mainViewModel)
}

class MyCookieJar(private val cookiesStorage: CookiesStorage) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = runBlocking {
        cookiesStorage.get(Url(url.toUri())).map {
            with(Cookie.Builder()) {
                name(it.name)
                value(it.value)
                if (it.httpOnly) httpOnly()
                it.path?.also(this::path)
                it.expires?.timestamp?.apply(this::expiresAt)
                if (it.maxAge > 0) this.expiresAt((System.currentTimeMillis() / 1000) + it.maxAge)
                if (it.secure) secure()
                it.domain?.also(this::domain)
                // TODO it.encoding?
                // TODO it.extensions?
                this
            }.build()
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = runBlocking {
        cookies.forEach {
            cookiesStorage.addCookie(
                Url(url.toUri()), io.ktor.http.Cookie(
                    name = it.name,
                    value = it.value,
                    encoding = CookieEncoding.RAW, // Hopefully this plays nice...
                    maxAge = 0,
                    expires = GMTDate(it.expiresAt),
                    domain = it.domain,
                    path = it.path,
                    secure = it.secure,
                    httpOnly = it.httpOnly,
                )
            )
        }
    }

}

@Composable
fun EpisodePlayer(
    mainViewModel: MainViewModel,

    episodeInfo: EpisodeInfo
) {
    val streamProps by mainViewModel.streamProps.observeAsState()
    val streamStatus by mainViewModel.streamStatus.observeAsState()

    streamProps?.let {
        if (streamStatus == StreamStatus.available) {
            val mContext = LocalContext.current
            val mExoPlayer = remember(mContext) {
                ExoPlayer.Builder(mContext).build().apply {
                    val logging = HttpLoggingInterceptor()
                    logging.setLevel(HttpLoggingInterceptor.Level.BODY)
                    val httpClient: OkHttpClient = OkHttpClient.Builder()
                        .cookieJar(MyCookieJar(mainViewModel.cookiesStorage))
                        .addInterceptor(logging)
                        .build()
                    val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
                    val source = HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(
                            MediaItem.fromUri(
                                Uri.parse(
                                    // TODO construct this uri the right way
                                    mainViewModel.client.baseUrl + it.url
                                )
                            )
                        )

                    setMediaSource(source)
                    prepare()
                }
            }

            // Implementing ExoPlayer
            AndroidView(factory = { context ->
                StyledPlayerView(context).apply {
                    player = mExoPlayer
                }
            })
        }
    }
}


@Composable
fun Browser(
    mainViewModel: MainViewModel
) {
    Log.i("Browser", "State: ${mainViewModel.browserState.value}")
    val nullableState by mainViewModel.browserState.observeAsState()
    when (val state = nullableState!!) { // TODO deal with nullable
        is BrowserState.HomeState -> Home(mainViewModel)
        is BrowserState.LibraryState -> Library(mainViewModel)
        is BrowserState.ShowState -> Show(mainViewModel)
        is BrowserState.SeasonState -> Season(mainViewModel)
        is BrowserState.EpisodeState -> Episode(mainViewModel, state.episodeInfo)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Home(
    mainViewModel: MainViewModel
) {
    val libraries by mainViewModel.libraries.observeAsState()
    libraries!!.forEach {
        Card(onClick = {
            mainViewModel.setBrowserState(BrowserState.LibraryState(it))
        }) {
            Text(text = it.name)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Library(
    mainViewModel: MainViewModel
) {
    val shows by mainViewModel.shows.observeAsState()
    shows!!.forEach {
        Card(onClick = {
            mainViewModel.setBrowserState(BrowserState.ShowState(it))
        }) {
            Text(text = it.title)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Show(
    mainViewModel: MainViewModel
) {
    val seasons by mainViewModel.seasons.observeAsState()
    seasons!!.forEach {
        Card(onClick = {
            mainViewModel.setBrowserState(BrowserState.SeasonState(it))
        }) {
            Text(text = "Season ${it.seasonNumber}")
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Season(
    mainViewModel: MainViewModel
) {
    val episodes by mainViewModel.episodes.observeAsState()
    episodes!!.forEach {
        Card(onClick = {
            mainViewModel.setBrowserState(BrowserState.EpisodeState(it))
        }) {
            Text(text = it.title)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Episode(
    mainViewModel: MainViewModel,
    episodeInfo: EpisodeInfo
) {
    Card(onClick = {
        mainViewModel.setViewState(ViewState.EpisodePlayer(episodeInfo))
    }) {
        Text(text = episodeInfo.title)
    }
}

@Composable
fun Login(
    mainViewModel: MainViewModel
) = ConstraintLayout {
    val (usernameRef, passwordRef, buttonRef) = createRefs()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    TextField(
        label = { Text(text = "username")},
        value = username,
        onValueChange = { username = it },
        modifier = Modifier.constrainAs(usernameRef) {
            top.linkTo(parent.top)
            bottom.linkTo(passwordRef.top)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        })
    TextField(
        label = { Text(text = "password")},
        value = password,
        onValueChange = { password = it },
        modifier = Modifier.constrainAs(passwordRef) {
            top.linkTo(usernameRef.bottom)
            bottom.linkTo(buttonRef.top)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        })
    Button({
        mainViewModel.login(LoginRequest(username = username, password = password))
    }, content = {
        Text("Connect")
    }, modifier = Modifier.constrainAs(buttonRef) {
        top.linkTo(passwordRef.bottom)
        bottom.linkTo(parent.bottom)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
    })
}

@Serializable
data class ConnectionInfo(val username: String, val password: String, val server: String)

@JvmInline
value class ServerUrl(val value: String)

@Composable
fun Server(onSubmit: (ServerUrl) -> Unit) = ConstraintLayout {
    val (serverRef, buttonRef) = createRefs()
    var server by remember { mutableStateOf("https://") }

    TextField(
        label = { Text(text = "Server URL")},
        value = server,
        onValueChange = { server = it },
        modifier = Modifier.constrainAs(serverRef) {
            top.linkTo(parent.top)
            bottom.linkTo(buttonRef.top)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        })
    Button({
        onSubmit(ServerUrl(server))
    }, content = {
        Text("Connect")
    }, modifier = Modifier.constrainAs(buttonRef) {
        top.linkTo(serverRef.bottom)
        bottom.linkTo(parent.bottom)
        start.linkTo(parent.start)
        end.linkTo(parent.end)
    })
}
