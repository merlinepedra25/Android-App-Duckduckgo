/*
 * Copyright (c) 2017 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.privacy.ui

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.brokensite.BrokenSiteData
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.model.Site
import com.duckduckgo.app.pixels.AppPixelName.PRIVACY_DASHBOARD_OPENED
import com.duckduckgo.app.privacy.db.NetworkLeaderboardDao
import com.duckduckgo.app.privacy.db.NetworkLeaderboardEntry
import com.duckduckgo.app.privacy.db.UserWhitelistDao
import com.duckduckgo.app.privacy.ui.PrivacyDashboardHybridViewModel.Command.LaunchReportBrokenSite
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.trackerdetection.model.TdsEntity
import com.duckduckgo.app.trackerdetection.model.TrackingEvent
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.privacy.config.api.ContentBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@ContributesViewModel(ActivityScope::class)
class PrivacyDashboardHybridViewModel @Inject constructor(
    private val userWhitelistDao: UserWhitelistDao,
    private val contentBlocking: ContentBlocking,
    networkLeaderboardDao: NetworkLeaderboardDao,
    private val pixel: Pixel,
    private val dispatcher: DispatcherProvider,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val command = Channel<Command>(1, DROP_OLDEST)

    sealed class Command {
        class LaunchReportBrokenSite(val data: BrokenSiteData) : Command()
    }

    data class ViewState(
        val url: String,
        val status: String = "complete",
        val upgradedHttps: Boolean,
        val parentEntity: EntityViewState,
        val site: SiteViewState,
        val trackers: Map<String, TrackerViewState>,
        val trackersBlocked: Map<String, TrackerViewState>,
        val certificate: CertificateViewState? = null
    )

    data class CertificateViewState(
        val commonName: String,
        val publicKey: PublicKeyViewState,
        val emails: List<String> = emptyList(),
        val summary: String
    )

    data class PublicKeyViewState(
        val blockSize: Int,
        val canEncrypt: Boolean,
        val bitSize: Int,
        val canSign: Boolean,
        val canDerive: Boolean,
        val canUnwrap: Boolean,
        val canWrap: Boolean,
        val canDecrypt: Boolean,
        val effectiveSize: Int,
        val isPermanent: Boolean,
        val type: String,
        val externalRepresentation: String,
        val canVerify: Boolean,
        val keyId: String
    )

    data class EntityViewState(
        val displayName: String,
        val prevalence: Double
    )

    data class SiteViewState(
        val url: String,
        val domain: String,
        val trackersUrls: Set<String>
    )

    data class TrackerViewState(
        val displayName: String,
        val prevalence: Double,
        val urls: Map<String, TrackerEventViewState>,
        val count: Int,
        val type: String = ""
    )

    data class TrackerEventViewState(
        val isBlocked: Boolean,
        val reason: String,
        val category: Set<String> = emptySet()
    )

    val viewState: MutableLiveData<ViewState> = MutableLiveData()
    private var site: Site? = null

    private val sitesVisited: LiveData<Int> = networkLeaderboardDao.sitesVisited()
    private val sitesVisitedObserver = Observer<Int> { onSitesVisitedChanged(it) }
    private val trackerNetworkLeaderboard: LiveData<List<NetworkLeaderboardEntry>> = networkLeaderboardDao.trackerNetworkLeaderboard()
    private val trackerNetworkActivityObserver = Observer<List<NetworkLeaderboardEntry>> { onTrackerNetworkEntriesChanged(it) }

    init {
        pixel.fire(PRIVACY_DASHBOARD_OPENED)
        resetViewState()
        sitesVisited.observeForever(sitesVisitedObserver)
        trackerNetworkLeaderboard.observeForever(trackerNetworkActivityObserver)
    }

    fun commands(): Flow<Command> {
        return command.receiveAsFlow()
    }

    fun onReportBrokenSiteSelected() {
        viewModelScope.launch(dispatcher.io()) {
            command.send(LaunchReportBrokenSite(BrokenSiteData.fromSite(site)))
        }
    }

    @VisibleForTesting
    public override fun onCleared() {
        super.onCleared()
        sitesVisited.removeObserver(sitesVisitedObserver)
        trackerNetworkLeaderboard.removeObserver(trackerNetworkActivityObserver)
    }

    fun onSitesVisitedChanged(count: Int?) {
    }

    fun onTrackerNetworkEntriesChanged(networkLeaderboardEntries: List<NetworkLeaderboardEntry>?) {
    }

    private fun showTrackerNetworkLeaderboard(
        siteVisitedCount: Int,
        networkCount: Int
    ): Boolean {
        return siteVisitedCount > LEADERBOARD_MIN_DOMAINS_EXCLUSIVE && networkCount >= LEADERBOARD_MIN_NETWORKS
    }

    fun onSiteChanged(site: Site?) {
        Timber.i("PDHy: $site")
        this.site = site
        if (site == null) {
            resetViewState()
        } else {
            viewModelScope.launch { updateSite(site) }
        }
    }

    private fun resetViewState() {
    }

    private fun createFakeEntity(
        event: TrackingEvent,
        string: String
    ): TrackingEvent {
        event.entity!!

        val newEntity = TdsEntity(
            name = event.entity.name + string,
            displayName = event.entity.name + string,
            prevalence = event.entity.prevalence
        )
        return event.copy(
            entity = newEntity
        )
    }

    private suspend fun updateSite(site: Site) {
        Timber.i("PDHy: will generate viewstate for $site")
        withContext(dispatchers.main()) {
            val certificateViewState = site.certificate?.let {
                it
            }

            val trackingEvents: MutableMap<String, TrackerViewState> = mutableMapOf()

            val trackinEventsFake = site.trackingEvents.flatMap {
                if (it.entity == null) return@flatMap listOf(it)
                listOf(
                    it,
                    createFakeEntity(it, "1"),
                    createFakeEntity(it, "2"),
                    createFakeEntity(it, "3"),
                    createFakeEntity(it, "4"),
                    createFakeEntity(it, "5"),
                    createFakeEntity(it, "6"),
                    createFakeEntity(it, "7")
                )
            }

            trackinEventsFake.forEach {
                if (it.entity == null) return@forEach

                val trackerViewState: TrackerViewState = trackingEvents[it.entity.displayName]?.let { trackerViewState ->
                    val urls = trackerViewState.urls + Pair(
                        it.trackerUrl,
                        TrackerEventViewState(
                            isBlocked = it.blocked,
                            reason = "first party",
                            category = it.categories?.toSet() ?: emptySet()
                        )
                    )
                    trackerViewState.copy(
                        urls = urls,
                        count = trackerViewState.count + 1
                    )
                } ?: TrackerViewState(
                    displayName = it.entity.name,
                    prevalence = it.entity.prevalence,
                    urls = mutableMapOf(
                        it.trackerUrl to TrackerEventViewState(
                            isBlocked = it.blocked,
                            reason = "first party",
                            category = it.categories?.toSet() ?: emptySet()
                        )
                    ),
                    count = 1,
                    type = "here goes type" // TODO: ????
                )

                trackingEvents[it.entity.displayName] = trackerViewState
            }

            viewState.value = ViewState(
                url = site.url,
                upgradedHttps = true,
                parentEntity = EntityViewState(
                    displayName = "entity display name",
                    prevalence = site.entity?.prevalence ?: 0.toDouble()
                ),
                site = SiteViewState(
                    url = "www.site.url",
                    domain = "site.domain",
                    trackersUrls = emptySet()
                ),
                trackers = trackingEvents,
                trackersBlocked = trackingEvents
            )
        }
    }

    private companion object {
        private const val LEADERBOARD_MIN_NETWORKS = 3
        private const val LEADERBOARD_MIN_DOMAINS_EXCLUSIVE = 30
    }
}