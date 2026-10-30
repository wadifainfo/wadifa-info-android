package com.wadifainfo.concours.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** F-Droid build: no ads and no proprietary SDK. Same surface as the play flavour's Ads.kt, so shared code stays untouched. */
object Ads {
    fun start(activity: Activity) = Unit
    fun privacyOptionsRequired(): Boolean = false
    fun showPrivacyOptions(activity: Activity) = Unit
}

@Composable
fun AdBanner(modifier: Modifier = Modifier, slot: String = "home") = Unit
