package com.bluetriangle.analytics.compose
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.Lifecycle.Event.ON_RESUME
import androidx.lifecycle.Lifecycle.Event.ON_START
import androidx.lifecycle.Lifecycle.Event.ON_STOP
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation3.scene.SceneState
import com.bluetriangle.analytics.Tracker
import com.bluetriangle.analytics.lifecycle.LifecycleRegistry
import com.bluetriangle.analytics.model.Screen
import com.bluetriangle.analytics.model.ScreenType
import com.bluetriangle.analytics.screenTracking.BTTScreenTracker
import com.bluetriangle.analytics.screenTracking.ScreenLifecycleTracker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
@NonRestartableComposable
fun BttTimerEffect(screenName: String) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        LifecycleRegistry.onEnterComposition(screenName)
        val screenTracker = Tracker.instance?.screenTrackMonitor
        val observer = ComposableLifecycleObserver(screenTracker, screenName)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            LifecycleRegistry.onLeaveComposition(screenName)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
@NonRestartableComposable
fun NavHostController.withBttNavigationTracker(): NavHostController {
    val currentLocationTracker = remember { mutableStateOf<BTTScreenTracker?>(null) }
    val loadTracker = ScreenLoadTracker(LocalView.current)

    DisposableEffect(this) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            val screenName = (destination.label ?: destination.route) ?: "unknown"

            Log.i("withBttNavigationTracker", "Label=${destination.label}, Route=${destination.route}, ScreenName= $screenName")
            currentLocationTracker.value?.onViewEnded()
            currentLocationTracker.value = BTTScreenTracker(screenName.toString())
            currentLocationTracker.value?.onLoadStarted()
            loadTracker.trackScreenLoad {
                currentLocationTracker.value?.onLoadEnded()
            }
        }

        this@withBttNavigationTracker.addOnDestinationChangedListener(listener)

        onDispose {
            currentLocationTracker.value?.onViewEnded()
            this@withBttNavigationTracker.removeOnDestinationChangedListener(listener)
        }
    }

    return this
}

@Composable
@NonRestartableComposable
fun <T: Any> SceneState<T>.bttTrackBackStack():SceneState<T> {
    val currentLocationTracker = remember { mutableStateOf<BTTScreenTracker?>(null) }
    val loadTracker = ScreenLoadTracker(LocalView.current)

    LaunchedEffect(this) {
        snapshotFlow {
            entries.lastOrNull()?.contentKey
        }
        .distinctUntilChanged()
        .collectLatest { key ->
            val screenName = when (key) {
                null -> "unknown"
                is String -> key
                else -> key::class.java.simpleName
            }

            currentLocationTracker.value?.onViewEnded()
            currentLocationTracker.value = BTTScreenTracker(screenName.toString())
            currentLocationTracker.value?.onLoadStarted()
            loadTracker.trackScreenLoad {
                currentLocationTracker.value?.onLoadEnded()
            }
        }
    }
    return this
}

object ExDecomposeHookEx {
    @Composable
    @NonRestartableComposable
    fun bttTrackStack(stack: Value<*>) {
        val currentLocationTracker = remember { mutableStateOf<BTTScreenTracker?>(null) }
        val loadTracker = ScreenLoadTracker(LocalView.current)

        @Suppress("UNCHECKED_CAST")
        val childStackValue = stack as? Value<ChildStack<Any, Any>> ?: return

        childStackValue.subscribe {
            val screenName = it.active.configuration.javaClass.simpleName ?: "Unknown"

            currentLocationTracker.value?.onViewEnded()
            currentLocationTracker.value = BTTScreenTracker(screenName)
            currentLocationTracker.value?.onLoadStarted()
            loadTracker.trackScreenLoad {
                currentLocationTracker.value?.onLoadEnded()
            }
        }
    }
}

object DecomposeHook {
    private var currentLocationTracker: BTTScreenTracker? = null

    @JvmStatic
    fun bttTrackStack(stack: Value<*>) {
        @Suppress("UNCHECKED_CAST")
        val childStackValue = stack as? Value<ChildStack<Any, Any>> ?: return
        childStackValue.observe {
            val screenName = it.active.configuration.javaClass.simpleName ?: "Unknown"
            currentLocationTracker?.onViewEnded()
            currentLocationTracker = BTTScreenTracker(screenName)
            currentLocationTracker?.onLoadStarted()
        }
    }
}

internal class ComposableLifecycleObserver(
    private val screenTracker: ScreenLifecycleTracker?,
    screenName: String
) : LifecycleEventObserver {

    val screen = Screen(screenName.hashCode().toString(), screenName, ScreenType.Composable)

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        screenTracker?.apply {
            when (event) {
                ON_CREATE -> onLoadStarted(screen, automated = true)
                ON_START -> onLoadEnded(screen, automated = true)
                ON_RESUME -> onViewStarted(screen, automated = true)
                ON_STOP -> onViewEnded(screen, automated = true)
                else -> {}
            }
        }
    }
}