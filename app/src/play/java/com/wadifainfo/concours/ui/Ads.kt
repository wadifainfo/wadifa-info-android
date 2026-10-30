package com.wadifainfo.concours.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.wadifainfo.concours.BuildConfig
import com.wadifainfo.concours.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob, kept subordinate to the recruitment features:
 *  - consent first (Google UMP); the SDK is initialised only once ads may be requested;
 *  - debug builds always use Google's public TEST units; release uses ids from %USERPROFILE%/.wadifa-app/admob.properties
 *    (never committed), else the test units again — so a misconfigured build can never serve live ads by accident;
 *  - banners only (no interstitial / app-open), only on Home (end) and between search results, never on the offer,
 *    PDF, QCM or profile screens; always labelled, height reserved before the ad arrives (no layout shift).
 * Ad failures are silent and isolated: the slot just stays empty.
 */
object Ads {
    val ready = mutableStateOf(false)
    private val initStarted = AtomicBoolean(false)
    private var consent: ConsentInformation? = null

    fun start(activity: Activity) {
        val ci = UserMessagingPlatform.getConsentInformation(activity).also { consent = it }
        ci.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initIfAllowed(activity, ci) } },
            { initIfAllowed(activity, ci) })
        initIfAllowed(activity, ci)   // consent given in an earlier session: no need to wait for the update
    }

    /** Users must be able to change their ad consent later (shown in Profile only when the form applies to them). */
    fun privacyOptionsRequired(): Boolean =
        consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) = UserMessagingPlatform.showPrivacyOptionsForm(activity) { }

    private fun initIfAllowed(activity: Activity, ci: ConsentInformation) {
        if (!ci.canRequestAds() || !initStarted.compareAndSet(false, true)) return
        val app = activity.applicationContext
        Thread { MobileAds.initialize(app) { activity.runOnUiThread { ready.value = true } } }.start()   // off the main thread
    }
}

/**
 * Space is reserved while the ad loads (no shift when it arrives); the "Ad" label appears only with a real ad; a failed
 * request collapses the slot completely, so users never see an empty labelled box.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier, slot: String = "home") {
    if (!Ads.ready.value) return
    val ctx = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtMost(720) - 32
    val size = remember(widthDp) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp) }
    var state by remember(slot) { mutableStateOf(0) }   // 0 loading, 1 loaded, 2 failed
    // ponytail: one AdView per composition; a slot scrolled far away is destroyed and re-requested when it comes back.
    val view = remember(slot, size) {
        AdView(ctx).apply {
            setAdSize(size); adUnitId = BuildConfig.ADMOB_BANNER
            adListener = object : AdListener() {
                override fun onAdLoaded() { state = 1 }
                override fun onAdFailedToLoad(e: LoadAdError) { state = 2 }
            }
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    if (state == 2) return
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (state == 1) stringResource(R.string.ad_label) else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Box(Modifier.fillMaxWidth().height(size.height.dp), contentAlignment = Alignment.Center) { AndroidView({ view }) }
    }
}
