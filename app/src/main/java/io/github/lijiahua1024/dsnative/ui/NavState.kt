package io.github.lijiahua1024.dsnative.ui

sealed class NavState {
    object Chat : NavState()
    object Settings : NavState()
}
