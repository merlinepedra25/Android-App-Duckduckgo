/*
 * Copyright (c) 2020 DuckDuckGo
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

package com.duckduckgo.mobile.android.vpn.di

import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.di.scopes.VpnScope
import com.duckduckgo.mobile.android.vpn.service.TrackerBlockingVpnService
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Binds
import dagger.Module
import dagger.SingleInstanceIn
import dagger.Subcomponent
import dagger.android.AndroidInjector
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@SingleInstanceIn(VpnScope::class)
@MergeSubcomponent(
    scope = VpnScope::class,
)
interface TrackerBlockingVpnServiceComponent : AndroidInjector<TrackerBlockingVpnService> {
    @Subcomponent.Factory interface Factory : AndroidInjector.Factory<TrackerBlockingVpnService>
}

@ContributesTo(AppScope::class)
interface TrackerBlockingVpnServiceComponentProvider {
    fun trackerBlockingVpnServiceComponentFactory(): TrackerBlockingVpnServiceComponent.Factory
}

@Module
@ContributesTo(AppScope::class)
abstract class TrackerBlockingVpnServiceComponentBindingModule {
    @Binds
    @IntoMap
    @ClassKey(TrackerBlockingVpnService::class)
    abstract fun TrackerBlockingVpnServiceComponent.Factory.bind(): AndroidInjector.Factory<*>
}
