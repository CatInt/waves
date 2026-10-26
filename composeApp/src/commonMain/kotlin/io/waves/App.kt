package io.waves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.waves.ui.AppDependencies
import io.waves.ui.AppState
import io.waves.ui.WavesApp

@Composable
fun App(deps: AppDependencies, state: AppState = remember(deps) { AppState(deps, deps.appScope) }) {
    WavesApp(deps, state)
}
