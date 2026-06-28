package com.amngaze

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmnGazeLogicTest {

    private lateinit var context: Context
    private lateinit var viewModel: AmnGazeViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear preferences before each test
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        
        viewModel = AmnGazeViewModel(context as Application)
    }

    @Test
    fun testDefaultSettings() = runBlocking {
        val state = viewModel.state.first()
        assertFalse(state.isSetupComplete)
        assertEquals(AppTheme.SUNSET_GLOW, state.currentTheme)
        assertEquals(BlurTargetMode.BODY_REGION, state.amnGazeBlurMode)
        assertEquals(BlurCensorStyle.MOSAIC, state.amnGazeCensorStyle)
        assertEquals(0.5f, state.amnGazeBlurSensitivity, 0.001f)
        assertTrue(state.amnGazeShowBadge)
        assertTrue(state.amnGazeShowBorder)
        assertTrue(state.amnGazeSkinHeuristic)
        assertEquals(ModerationModelType.DEFAULT_NSFW, state.amnGazeModelType)
    }

    @Test
    fun testBlurTargetModePersistence() = runBlocking {
        // Update blur target mode
        viewModel.updateBlurMode(BlurTargetMode.FULL_SCREEN)
        
        // Check in-memory state
        var state = viewModel.state.first()
        assertEquals(BlurTargetMode.FULL_SCREEN, state.amnGazeBlurMode)
        
        // Re-create ViewModel and verify state is loaded from SharedPreferences
        val nextViewModel = AmnGazeViewModel(context as Application)
        val nextState = nextViewModel.state.first()
        assertEquals(BlurTargetMode.FULL_SCREEN, nextState.amnGazeBlurMode)
    }

    @Test
    fun testModelTypePersistence() = runBlocking {
        // Update model type
        viewModel.updateModelType(ModerationModelType.QUANTIZED_NSFW)
        
        // Check in-memory state
        var state = viewModel.state.first()
        assertEquals(ModerationModelType.QUANTIZED_NSFW, state.amnGazeModelType)
        
        // Re-create ViewModel and verify state is loaded from SharedPreferences
        val nextViewModel = AmnGazeViewModel(context as Application)
        val nextState = nextViewModel.state.first()
        assertEquals(ModerationModelType.QUANTIZED_NSFW, nextState.amnGazeModelType)
    }

}
