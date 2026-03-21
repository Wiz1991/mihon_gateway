package eu.kanade.tachiyomi

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

fun createAppModule(app: Application): Module {
    return module {
        single { app }

        single { NetworkHelper(app) }

        single { JavaScriptEngine(app) }

        single {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}
