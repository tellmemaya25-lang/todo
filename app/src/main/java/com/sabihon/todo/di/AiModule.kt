package com.sabihon.todo.di

import com.google.firebase.functions.FirebaseFunctions
import com.sabihon.todo.data.ai.AiAssistantRepositoryImpl
import com.sabihon.todo.data.ai.AiConfig
import com.sabihon.todo.data.ai.AiFeatureFlags
import com.sabihon.todo.data.ai.AiGateway
import com.sabihon.todo.data.ai.CallableAiGateway
import com.sabihon.todo.data.ai.CircuitBreaker
import com.sabihon.todo.data.ai.RuleBasedTaskParser
import com.sabihon.todo.domain.repository.AiAssistantRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Wires the AI assistant: Tier-0 parser, Cloud Functions gateway, flags and breaker.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindAiGateway(impl: CallableAiGateway): AiGateway

    @Binds
    @Singleton
    abstract fun bindAiAssistantRepository(impl: AiAssistantRepositoryImpl): AiAssistantRepository

    companion object {

        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemDefaultZone()

        @Provides
        @Singleton
        fun provideFirebaseFunctions(): FirebaseFunctions = FirebaseFunctions.getInstance(AiConfig.REGION)

        @Provides
        @Singleton
        fun provideRuleBasedTaskParser(): RuleBasedTaskParser = RuleBasedTaskParser(monthFirstNumericDates = true)

        /** Swap for a Remote Config-backed implementation in Phase 2. */
        @Provides
        @Singleton
        fun provideAiFeatureFlags(): AiFeatureFlags = AiFeatureFlags.Default

        @Provides
        @Singleton
        fun provideCircuitBreaker(): CircuitBreaker = CircuitBreaker()
    }
}
