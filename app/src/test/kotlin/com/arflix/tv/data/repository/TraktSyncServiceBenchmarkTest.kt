package com.arflix.tv.data.repository

import com.arflix.tv.data.api.SupabaseApi
import com.arflix.tv.data.api.WatchHistoryRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID

class TraktSyncServiceBenchmarkTest {

    @Test
    fun benchmarkStalePlaybackDeletion() = runBlocking {
        val supabaseApi = mockk<SupabaseApi>()

        // Simulate 100ms network latency
        val networkDelay = 100L

        coEvery { supabaseApi.deleteWatchHistory(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(networkDelay)
        }

        coEvery { supabaseApi.deleteWatchHistoryByIds(any(), any(), any()) } coAnswers {
            delay(networkDelay)
        }

        // Generate 200 stale records
        val staleRecords = (1..200).map {
            WatchHistoryRecord(
                id = UUID.randomUUID().toString(),
                userId = "testUser",
                mediaType = "movie",
                progress = 0.5f,
                positionSeconds = 1000L,
                durationSeconds = 2000L
            )
        }

        // 1. Measure Old N+1 Method
        val timeOldMethod = measureTimeMillis {
            val semaphore = Semaphore(5)
            coroutineScope {
                staleRecords.map { record ->
                    async {
                        semaphore.withPermit {
                            supabaseApi.deleteWatchHistory(
                                auth = "testAuth",
                                userId = "eq.testUser"
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        // 2. Measure New Batching Method
        val timeNewMethod = measureTimeMillis {
            val semaphore = Semaphore(5)
            val staleIds = staleRecords.mapNotNull { it.id }
            coroutineScope {
                staleIds.chunked(50).map { chunk ->
                    async {
                        semaphore.withPermit {
                            supabaseApi.deleteWatchHistoryByIds(
                                auth = "testAuth",
                                idIn = "in.(${chunk.joinToString(",")})"
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        println("--------------------------------------------------")
        println("Benchmark: Deleting ${staleRecords.size} Stale Records")
        println("Simulated Network Latency: ${networkDelay}ms per call")
        println("Old N+1 Method Time: ${timeOldMethod}ms")
        println("New Batching Method Time: ${timeNewMethod}ms")
        val speedup = if (timeNewMethod > 0) "${timeOldMethod / timeNewMethod.toFloat()}x" else "∞"
        println("Speedup: $speedup")
        println("--------------------------------------------------")

        // Verify that the new method actually made the right number of calls
        coVerify(exactly = 4) { supabaseApi.deleteWatchHistoryByIds(any(), any(), any()) }
    }
}
