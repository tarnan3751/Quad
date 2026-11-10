package com.campusmarket.app

import android.app.Application
import com.campusmarket.app.platform.initCampusMarket

class CampusMarketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initCampusMarket(this)
    }
}
