package io.github.auxen.playback

import io.github.auxen.dsp.BalanceProcessor
import io.github.auxen.dsp.BassBoostProcessor
import io.github.auxen.dsp.EncodingRestorerProcessor
import io.github.auxen.dsp.LimiterProcessor
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.dsp.ReplayGainProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the DSP chain's processor order as a PRODUCTION regression test.
 * `ProcessorChainTest`'s six-stage test (in the `dsp` package) builds its
 * own hardcoded processor list, so it would keep passing even if
 * [buildDspProcessorChain] itself silently swapped two stages -- this test
 * calls the exact function [EqRenderersFactory.buildAudioSink] uses (fix
 * round, review of commit dd1bd55, Important #2).
 *
 * Order is LAW -- see [ParametricEqProcessor]'s KDoc: ReplayGain ->
 * ParametricEq -> BassBoost -> Balance -> Limiter -> EncodingRestorer.
 */
class ProcessorChainOrderTest {

    @Test
    fun buildDspProcessorChainReturnsStagesInLawOrder() {
        val chain = buildDspProcessorChain(
            replayGainProcessor = ReplayGainProcessor(),
            eqProcessor = ParametricEqProcessor(),
            bassBoostProcessor = BassBoostProcessor(),
            balanceProcessor = BalanceProcessor(),
            limiterProcessor = LimiterProcessor(),
            encodingRestorerProcessor = EncodingRestorerProcessor(),
        )
        val expectedTypes = listOf(
            ReplayGainProcessor::class.java,
            ParametricEqProcessor::class.java,
            BassBoostProcessor::class.java,
            BalanceProcessor::class.java,
            LimiterProcessor::class.java,
            EncodingRestorerProcessor::class.java,
        )
        assertEquals(expectedTypes, chain.map { it::class.java })
    }
}
