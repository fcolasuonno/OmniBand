package com.omniband.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    val deviceType: String,
    val authKey: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
    val isActive: Boolean = true,
)

@Entity(tableName = "heart_rate")
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val bpm: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "steps")
data class StepsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val count: Int,
    val calories: Int,
    val distanceMeters: Float,
    val date: String,  // yyyy-MM-dd
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val startTime: Long,
    val endTime: Long? = null,
    val source: String = "omniband"  // "omniband" or "sleep_as_android"
)

@Entity(tableName = "sleep_stages")
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val stage: String,  // AWAKE, LIGHT, DEEP, REM
    val timestamp: Long
)

@Entity(tableName = "spo2")
data class SpO2Entity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val percent: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "battery_history")
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val percent: Int,
    val charging: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
