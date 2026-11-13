package io.github.auxen.playback

import io.github.auxen.dsp.BalanceProcessor
import io.github.auxen.dsp.BassBoostProcessor
import io.github.auxen.dsp.EncodingRestorerProcessor
import io.github.auxen.dsp.LimiterProcessor
import io.github.auxen.dsp.ParametricEqProcessor
import io.github.auxen.dsp.ReplayGainProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the DSP chain's processor order as a PRODUCTION regression test.
 * `ProcessorChainTest`'s test (in the `dsp` package) builds its own
 * hardcoded processor list, so it would keep passing even if
 * [buildDspProcessorChain] itself silently swapped two stages -- this test
 * calls the exact function [EqRenderersFactory.buildAudioSink] uses (fix
 * round, review of commit dd1bd55, Important #2).
 *
 * Order is LAW -- see [ParametricEqProcessor]'s KDoc: ReplayGain -> AutoEq
 * -> GraphicEq -> BassBoost -> Balance -> Limiter -> EncodingRestorer
 * (updated by the AutoEq split, Task 1: AutoEq correction now sits before
 * the user's graphic taste, both being LTI stages with their own preamp).
 * AutoEq and the graphic EQ are the SAME class ([ParametricEqProcessor]),
 * so a type-list assertion alone can't tell them apart or catch them being
 * swapped -- [chainWiresTheAutoEqAndGraphicInstancesToTheCorrectSlots]
 * asserts reference identity to prove indices 1 and 2 are specifically the
 * autoEq and eq instances passed in, not just "two ParametricEqProcessors
 * in some order."
 */
class ProcessorChainOrderTest {

    @Test
    fun buildDspProcessorChainReturnsStagesInLawOrder() {
        val chain = buildDspProcessorChain(
            replayGainProcessor = ReplayGainProcessor(),
            autoEqProcessor = ParametricEqProcessor(),
            eqProcessor = ParametricEqProcessor(),
            bassBoostProcessor = BassBoostProcessor(),
            balanceProcessor = BalanceProcessor(),
            limiterProcessor = LimiterProcessor(),
            encodingRestorerProcessor = EncodingRestorerProcessor(),
        )
        val expectedTypes = listOf(
            ReplayGainProcessor::class.java,
            ParametricEqProcessor::class.java,
            ParametricEqProcessor::class.java,
            BassBoostProcessor::class.java,
            BalanceProcessor::class.java,
            LimiterProcessor::class.java,
            EncodingRestorerProcessor::class.java,
        )
        assertEquals(expectedTypes, chain.map { it::class.java })
    }

    @Test
    fun chainWiresTheAutoEqAndGraphicInstancesToTheCorrectSlots() {
        val autoEq = ParametricEqProcessor()
        val graphicEq = ParametricEqProcessor()
        val chain = buildDspProcessorChain(
            replayGainProcessor = ReplayGainProcessor(),
            autoEqProcessor = autoEq,
            eqProcessor = graphicEq,
            bassBoostProcessor = BassBoostProcessor(),
            balanceProcessor = BalanceProcessor(),
            limiterProcessor = LimiterProcessor(),
            encodingRestorerProcessor = EncodingRestorerProcessor(),
        )
        assertSame("index 1 must be the AutoEq instance, right after ReplayGain", autoEq, chain[1])
        assertSame("index 2 must be the graphic EQ instance, before BassBoost", graphicEq, chain[2])
    }
}
