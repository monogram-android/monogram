package org.monogram.mtproto.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.Difference_2f53482c4e
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetDifference
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.GetState
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.State_ddba9d7af9
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject

class MtProtoUpdateRecoveryTest {
    @Test
    fun `initializes and applies full difference with exact cursor`() = runBlocking {
        val calls = mutableListOf<TlMethod<*>>()
        val state = State_ddba9d7af9(10, 20, 30, 40, 0)
        val finalState = State_ddba9d7af9(11, 21, 31, 41, 0)
        val applied = mutableListOf<MtProtoUpdateDifferenceBatch>()
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { method ->
                calls += method
                when (method) {
                    GetState -> state
                    is GetDifference -> {
                        assertEquals(10, method.pts)
                        assertEquals(20, method.qts)
                        assertEquals(30, method.date)
                        Difference_2f53482c4e(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), finalState)
                    }
                    else -> error("unexpected method")
                }
            },
            applyBatch = { applied += it },
        )

        assertEquals(MtProtoUpdateCursor(10, 20, 30, 40), recovery.initialize())
        assertEquals(
            MtProtoUpdateRecoveryResult.Completed(MtProtoUpdateCursor(11, 21, 31, 41)),
            recovery.handle(UpdatesTooLong),
        )
        assertEquals(listOf(GetState, GetDifference(10, null, null, 30, 20, null)), calls)
        assertEquals(2, applied.size)
        assertEquals(MtProtoUpdateCursor(11, 21, 31, 41), applied.last().cursor)
    }

    @Test
    fun `advances through slices and empty without changing pts or qts`() = runBlocking {
        val calls = mutableListOf<TlMethod<*>>()
        val results = ArrayDeque<TlObject>().apply {
            add(State_ddba9d7af9(1, 2, 3, 4, 0))
            add(DifferenceSlice(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), State_ddba9d7af9(5, 6, 7, 8, 0)))
            add(DifferenceEmpty(9, 10))
        }
        val applied = mutableListOf<MtProtoUpdateDifferenceBatch>()
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor {
                calls += it
                results.removeFirst()
            },
            applyBatch = { applied += it },
        )

        recovery.initialize()
        assertEquals(
            MtProtoUpdateRecoveryResult.Completed(MtProtoUpdateCursor(5, 6, 9, 10)),
            recovery.recover(),
        )
        assertEquals(
            listOf(
                GetState,
                GetDifference(1, null, null, 3, 2, null),
                GetDifference(5, null, null, 7, 6, null),
            ),
            calls,
        )
        assertEquals(
            listOf(
                MtProtoUpdateCursor(1, 2, 3, 4),
                MtProtoUpdateCursor(5, 6, 7, 8),
                MtProtoUpdateCursor(5, 6, 9, 10),
            ),
            applied.map { it.cursor },
        )
    }

    @Test
    fun `bounds a server that never finishes difference slices`() = runBlocking {
        var differenceCalls = 0
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor {
                differenceCalls++
                DifferenceSlice(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    State_ddba9d7af9(differenceCalls, 2, 3, 4, 0),
                )
            },
            applyBatch = {},
            initialCursor = MtProtoUpdateCursor(1, 2, 3, 4),
            maxDifferenceBatches = 2,
        )

        assertEquals(MtProtoUpdateRecoveryResult.ResyncRequired, recovery.recover())
        assertEquals(2, differenceCalls)
        assertEquals(MtProtoUpdateCursor(2, 2, 3, 4), recovery.currentCursor())
    }

    @Test
    fun `surfaces too-long without advancing cursor or applying`() = runBlocking {
        val results = ArrayDeque<TlObject>().apply {
            add(State_ddba9d7af9(1, 2, 3, 4, 0))
            add(DifferenceTooLong(99))
        }
        var applied = 0
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { results.removeFirst() },
            applyBatch = { applied++ },
        )

        recovery.initialize()
        assertEquals(MtProtoUpdateRecoveryResult.ResyncRequired, recovery.recover())
        assertEquals(MtProtoUpdateCursor(1, 2, 3, 4), recovery.currentCursor())
        assertEquals(1, applied)
    }

    @Test
    fun `keeps cursor unchanged when applying a difference fails`() = runBlocking {
        val results = ArrayDeque<TlObject>().apply {
            add(State_ddba9d7af9(1, 2, 3, 4, 0))
            add(Difference_2f53482c4e(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), State_ddba9d7af9(5, 6, 7, 8, 0)))
        }
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor { results.removeFirst() },
            applyBatch = {
                if (it.cursor.pts == 5) error("database apply failed")
            },
        )

        recovery.initialize()
        assertThrows(IllegalStateException::class.java) { runBlocking { recovery.recover() } }
        assertEquals(MtProtoUpdateCursor(1, 2, 3, 4), recovery.currentCursor())
    }

    @Test
    fun `restored cursor skips getState and initialize is idempotent`() = runBlocking {
        val calls = mutableListOf<TlMethod<*>>()
        val restored = MtProtoUpdateCursor(10, 20, 30, 40)
        val recovery = MtProtoUpdateRecovery(
            executor = MtProtoUpdateRecoveryExecutor {
                calls += it
                error("getState must not be called")
            },
            applyBatch = {},
            initialCursor = restored,
        )

        assertEquals(restored, recovery.initialize())
        assertEquals(restored, recovery.initialize())
        assertTrue(calls.isEmpty())
    }
}
